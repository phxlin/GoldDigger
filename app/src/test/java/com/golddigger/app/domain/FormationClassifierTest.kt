package com.golddigger.app.domain

import com.golddigger.app.core.CashHolding
import com.golddigger.app.domain.model.FormationInput
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.RiskProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormationClassifierTest {

    private val classifier = FormationClassifier()

    private fun holding(
        id: Long,
        ticker: String,
        marketValue: Double,
        sector: String? = "Technology",
        weightPct: Double = 0.0,
    ) = HoldingValuation(
        holdingId = id,
        ticker = ticker,
        companyName = "$ticker Inc",
        sector = sector,
        shares = 1.0,
        costBasis = marketValue,
        avgCost = marketValue,
        price = marketValue,
        dayChangePct = 0.0,
        priceUpdatedAt = 0L,
        marketValue = marketValue,
        gainLoss = 0.0,
        gainLossPct = 0.0,
        portfolioWeightPct = weightPct,
    )

    private fun classify(
        holdings: List<HoldingValuation>,
        risk: Map<String, RiskProfile> = emptyMap(),
        overrides: Map<Long, FormationRole> = emptyMap(),
    ) = classifier.classify(FormationInput(holdings, risk, overrides))

    private fun roleOf(formation: com.golddigger.app.domain.model.Formation, ticker: String): FormationRole {
        formation.zones.forEach { z -> if (z.players.any { it.ticker == ticker }) return z.role }
        if (formation.bench.any { it.ticker == ticker }) return FormationRole.BENCH
        error("$ticker not found in formation")
    }

    @Test
    fun `cash is always the goalkeeper`() {
        val f = classify(
            listOf(holding(1, CashHolding.TICKER, 500.0, sector = CashHolding.SECTOR)),
        )
        assertThat(roleOf(f, CashHolding.TICKER)).isEqualTo(FormationRole.GOALKEEPER)
    }

    @Test
    fun `a low-beta stock is a defender`() {
        val f = classify(
            listOf(holding(1, "LOWB", 1_000.0)),
            risk = mapOf("LOWB" to RiskProfile("LOWB", beta = 0.5)),
        )
        assertThat(roleOf(f, "LOWB")).isEqualTo(FormationRole.DEFENSE)
    }

    @Test
    fun `a high-beta stock is an attacker`() {
        val f = classify(
            listOf(holding(1, "HIGHB", 1_000.0)),
            risk = mapOf("HIGHB" to RiskProfile("HIGHB", beta = 2.1)),
        )
        assertThat(roleOf(f, "HIGHB")).isEqualTo(FormationRole.ATTACK)
    }

    @Test
    fun `a market-like beta is a midfielder`() {
        val f = classify(
            listOf(holding(1, "MID", 1_000.0)),
            risk = mapOf("MID" to RiskProfile("MID", beta = 1.1)),
        )
        assertThat(roleOf(f, "MID")).isEqualTo(FormationRole.MIDFIELD)
    }

    @Test
    fun `a stock with no risk data sits on the bench`() {
        val f = classify(listOf(holding(1, "NEW", 1_000.0)))
        assertThat(roleOf(f, "NEW")).isEqualTo(FormationRole.BENCH)
        assertThat(f.zones.flatMap { it.players }).isEmpty()
    }

    @Test
    fun `high dominant-sector correlation forces attack despite a tame beta`() {
        val f = classify(
            listOf(holding(1, "CORR", 1_000.0)),
            risk = mapOf("CORR" to RiskProfile("CORR", beta = 0.6, sectorCorrelation = 0.88)),
        )
        assertThat(roleOf(f, "CORR")).isEqualTo(FormationRole.ATTACK)
    }

    @Test
    fun `a manual override beats the computed role`() {
        val f = classify(
            listOf(holding(1, "OVR", 1_000.0)),
            risk = mapOf("OVR" to RiskProfile("OVR", beta = 2.5)), // would be ATTACK
            overrides = mapOf(1L to FormationRole.DEFENSE),
        )
        assertThat(roleOf(f, "OVR")).isEqualTo(FormationRole.DEFENSE)
        val card = f.zone(FormationRole.DEFENSE)!!.players.single()
        assertThat(card.isOverridden).isTrue()
        assertThat(card.autoRole).isEqualTo(FormationRole.ATTACK)
    }

    @Test
    fun `an all-attack book warns about a thin defense`() {
        val f = classify(
            listOf(
                holding(1, "A", 600.0),
                holding(2, "B", 400.0),
            ),
            risk = mapOf(
                "A" to RiskProfile("A", beta = 2.0),
                "B" to RiskProfile("B", beta = 1.8),
            ),
        )
        assertThat(f.insights.map { it.id }).contains("thin-defense")
    }

    @Test
    fun `a cash-heavy book warns that the keeper carries the back line`() {
        val f = classify(
            listOf(
                holding(1, CashHolding.TICKER, 600.0, sector = CashHolding.SECTOR),
                holding(2, "STK", 400.0),
            ),
            risk = mapOf("STK" to RiskProfile("STK", beta = 1.0)),
        )
        assertThat(f.insights.map { it.id }).contains("heavy-keeper")
    }

    @Test
    fun `an attack-dominated book warns that the formation is front-loaded`() {
        val f = classify(
            listOf(
                holding(1, "A", 500.0),
                holding(2, "B", 400.0),
                holding(3, "D", 100.0),
            ),
            risk = mapOf(
                "A" to RiskProfile("A", beta = 2.0),
                "B" to RiskProfile("B", beta = 2.2),
                "D" to RiskProfile("D", beta = 0.4),
            ),
        )
        assertThat(f.insights.map { it.id }).contains("front-loaded")
    }

    @Test
    fun `zone height weight tracks the dollars in the zone`() {
        val f = classify(
            listOf(
                holding(1, "BIG", 900.0),
                holding(2, "SMALL", 100.0),
            ),
            risk = mapOf(
                "BIG" to RiskProfile("BIG", beta = 2.0),   // attack
                "SMALL" to RiskProfile("SMALL", beta = 0.3), // defense
            ),
        )
        val attack = f.zone(FormationRole.ATTACK)!!
        val defense = f.zone(FormationRole.DEFENSE)!!
        assertThat(attack.heightWeight).isGreaterThan(defense.heightWeight)
    }

    @Test
    fun `intensity ranks holdings within their zone`() {
        val f = classify(
            listOf(
                holding(1, "HOT", 500.0),
                holding(2, "MILD", 500.0),
            ),
            risk = mapOf(
                "HOT" to RiskProfile("HOT", beta = 3.0),
                "MILD" to RiskProfile("MILD", beta = 1.6),
            ),
        )
        val players = f.zone(FormationRole.ATTACK)!!.players.associateBy { it.ticker }
        assertThat(players.getValue("HOT").intensity).isWithin(1e-9).of(1.0)
        assertThat(players.getValue("MILD").intensity).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a zone's players are ordered most-extreme first`() {
        val f = classify(
            listOf(
                holding(1, "MILD", 100.0),
                holding(2, "WILD", 100.0),
                holding(3, "MID", 100.0),
            ),
            risk = mapOf(
                "MILD" to RiskProfile("MILD", beta = 1.6),
                "WILD" to RiskProfile("WILD", beta = 3.4),
                "MID" to RiskProfile("MID", beta = 2.3),
            ),
        )
        assertThat(f.zone(FormationRole.ATTACK)!!.players.map { it.ticker })
            .containsExactly("WILD", "MID", "MILD").inOrder()
    }

    @Test
    fun `the lowest-beta defender leads the defensive line`() {
        val f = classify(
            listOf(
                holding(1, "SOLID", 100.0),
                holding(2, "ROCK", 100.0),
            ),
            risk = mapOf(
                "SOLID" to RiskProfile("SOLID", beta = 0.8),
                "ROCK" to RiskProfile("ROCK", beta = 0.3),
            ),
        )
        assertThat(f.zone(FormationRole.DEFENSE)!!.players.first().ticker).isEqualTo("ROCK")
    }

    @Test
    fun `overrides survive into the correct zone even for cash`() {
        val f = classify(
            listOf(holding(1, CashHolding.TICKER, 500.0, sector = CashHolding.SECTOR)),
            overrides = mapOf(1L to FormationRole.MIDFIELD),
        )
        assertThat(roleOf(f, CashHolding.TICKER)).isEqualTo(FormationRole.MIDFIELD)
    }
}
