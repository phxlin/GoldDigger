package com.golddigger.app.domain

import com.golddigger.app.core.CashHolding
import com.golddigger.app.domain.model.FormationInput
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.InsightSeverity
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
    fun `a mostly market-like book gets an informational note, not a warning`() {
        val f = classify(
            listOf(
                holding(1, "MID_A", 800.0),
                holding(2, "MID_B", 100.0),
                holding(3, "EDGE", 100.0),
            ),
            risk = mapOf(
                "MID_A" to RiskProfile("MID_A", beta = 1.0),
                "MID_B" to RiskProfile("MID_B", beta = 1.05),
                "EDGE" to RiskProfile("EDGE", beta = 2.0),
            ),
        )
        val note = f.insights.single { it.id == "mostly-midfield" }
        assertThat(note.severity).isEqualTo(InsightSeverity.INFO)
    }

    @Test
    fun `a book without a dominant midfield gets no mostly-market-like note`() {
        val f = classify(
            listOf(
                holding(1, "ATK", 500.0),
                holding(2, "DEF", 500.0),
            ),
            risk = mapOf(
                "ATK" to RiskProfile("ATK", beta = 2.0),
                "DEF" to RiskProfile("DEF", beta = 0.3),
            ),
        )
        assertThat(f.insights.map { it.id }).doesNotContain("mostly-midfield")
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
                "MILD" to RiskProfile("MILD", beta = 1.8), // clears MIDFIELD_BETA_MAX's margin, but barely
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
                "MILD" to RiskProfile("MILD", beta = 1.8),
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

    // --- Role-threshold margin ---------------------------------------------
    //
    // FormationConfig.ROLE_THRESHOLD_MARGIN_FRACTION (10%) means a metric has
    // to clear a threshold by more than 10% of the threshold's own value to
    // decide a role on its own. These use their own synthetic betas/
    // correlations/volatilities — not the AMZN/AVGO pair that motivated the
    // change — to check the rule generalizes rather than just fixing one pair.

    @Test
    fun `two betas straddling the attack cutoff by a hair both land in midfield`() {
        // MIDFIELD_BETA_MAX = 1.5; 1.49 and 1.52 are both within 10% of it
        // (1.35..1.65), so neither should be trusted to decide Attack alone.
        val f = classify(
            listOf(
                holding(1, "JUST_UNDER", 500.0),
                holding(2, "JUST_OVER", 500.0),
            ),
            risk = mapOf(
                "JUST_UNDER" to RiskProfile("JUST_UNDER", beta = 1.49),
                "JUST_OVER" to RiskProfile("JUST_OVER", beta = 1.52),
            ),
        )
        assertThat(roleOf(f, "JUST_UNDER")).isEqualTo(FormationRole.MIDFIELD)
        assertThat(roleOf(f, "JUST_OVER")).isEqualTo(FormationRole.MIDFIELD)
    }

    @Test
    fun `a beta that clearly clears the attack margin is still an attacker`() {
        // 1.5 * 1.10 = 1.65 — 1.9 clears it with room to spare.
        val f = classify(
            listOf(holding(1, "CLEAR", 500.0)),
            risk = mapOf("CLEAR" to RiskProfile("CLEAR", beta = 1.9)),
        )
        assertThat(roleOf(f, "CLEAR")).isEqualTo(FormationRole.ATTACK)
    }

    @Test
    fun `a beta stuck between the defense and midfield margins with no other signal benches the holding`() {
        // DEFENSE_BETA_MAX = 0.9; the no-man's-land is roughly 0.81..0.99.
        // 0.92 is inside it and there's no correlation or volatility reading
        // to break the tie, so this isn't confident enough to place.
        val f = classify(
            listOf(holding(1, "AMBIGUOUS", 500.0)),
            risk = mapOf("AMBIGUOUS" to RiskProfile("AMBIGUOUS", beta = 0.92)),
        )
        assertThat(roleOf(f, "AMBIGUOUS")).isEqualTo(FormationRole.BENCH)
    }

    @Test
    fun `a beta clearly under the defense margin is still a defender`() {
        // 0.9 * 0.90 = 0.81 — 0.6 clears it with room to spare.
        val f = classify(
            listOf(holding(1, "SAFE", 500.0)),
            risk = mapOf("SAFE" to RiskProfile("SAFE", beta = 0.6)),
        )
        assertThat(roleOf(f, "SAFE")).isEqualTo(FormationRole.DEFENSE)
    }

    @Test
    fun `a correlation just past the high-correlation cutoff does not yet force attack`() {
        // MODERATE_CORRELATION_MAX = 0.7; 0.72 is within 10% of it (up to
        // 0.77), so it should read as moderate (Midfield), not high (Attack)
        // — the tame beta alone wouldn't put it anywhere near Attack either.
        val f = classify(
            listOf(holding(1, "BARELY", 500.0)),
            risk = mapOf("BARELY" to RiskProfile("BARELY", beta = 0.5, sectorCorrelation = 0.72)),
        )
        assertThat(roleOf(f, "BARELY")).isEqualTo(FormationRole.MIDFIELD)
    }

    @Test
    fun `a correlation that clearly clears the high-correlation margin forces attack`() {
        // 0.7 * 1.10 = 0.77 — 0.95 clears it with room to spare.
        val f = classify(
            listOf(holding(1, "TIED", 500.0)),
            risk = mapOf("TIED" to RiskProfile("TIED", beta = 0.5, sectorCorrelation = 0.95)),
        )
        assertThat(roleOf(f, "TIED")).isEqualTo(FormationRole.ATTACK)
    }

    @Test
    fun `volatility just past the high-volatility cutoff does not yet force attack`() {
        // HIGH_VOLATILITY_STDDEV = 0.035; 0.036 is within 10% of it (up to
        // 0.0385), so a tame beta and no correlation reading should leave
        // this on the bench rather than confidently in Attack.
        val f = classify(
            listOf(holding(1, "TWITCHY", 500.0)),
            risk = mapOf("TWITCHY" to RiskProfile("TWITCHY", realizedVolatility = 0.036)),
        )
        assertThat(roleOf(f, "TWITCHY")).isEqualTo(FormationRole.BENCH)
    }

    @Test
    fun `volatility that clearly clears the high-volatility margin forces attack`() {
        // 0.035 * 1.10 = 0.0385 — 0.05 clears it with room to spare.
        val f = classify(
            listOf(holding(1, "WILDSWING", 500.0)),
            risk = mapOf("WILDSWING" to RiskProfile("WILDSWING", realizedVolatility = 0.05)),
        )
        assertThat(roleOf(f, "WILDSWING")).isEqualTo(FormationRole.ATTACK)
    }
}
