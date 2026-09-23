package com.golddigger.app.domain

import com.golddigger.app.core.CashHolding
import com.golddigger.app.core.FormationConfig
import com.golddigger.app.domain.model.Formation
import com.golddigger.app.domain.model.FormationInput
import com.golddigger.app.domain.model.FormationInsight
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.FormationZone
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.InsightSeverity
import com.golddigger.app.domain.model.PlayerCard
import com.golddigger.app.domain.model.RiskProfile
import javax.inject.Inject
import kotlin.math.abs

/**
 * Turns holdings + their risk metrics into a [Formation]: which pitch role each
 * holding plays, how tall each zone should be, and which "gap" insights apply.
 *
 * Pure — no Android, no coroutines, no I/O — following the same pattern as
 * [PortfolioCalculator], so the whole classification is unit-testable. Role
 * assignment is computed entirely from the live metrics; nothing is keyed to a
 * specific ticker, so it behaves correctly for any stock the user adds later.
 * The one hard rule is that a user's manual override always wins.
 */
class FormationClassifier @Inject constructor() {

    fun classify(input: FormationInput): Formation {
        val priced = input.holdings.filter { (it.marketValue ?: 0.0) > 0.0 }
        val totalValue = priced.sumOf { it.marketValue ?: 0.0 }
        val nonCashValue = priced.filterNot { it.isCash() }.sumOf { it.marketValue ?: 0.0 }
        val dominantSector = dominantSectorLabel(priced, input.groupAllocations)

        val cards = priced.map { holding ->
            val risk = input.risk[holding.ticker] ?: RiskProfile(holding.ticker)
            val auto = autoRole(holding, risk)
            val override = input.overrides[holding.holdingId]
            val role = override ?: auto
            PlayerCardDraft(holding, risk, role, auto, isOverridden = override != null)
        }

        val onPitchRoles = listOf(
            FormationRole.ATTACK,
            FormationRole.MIDFIELD,
            FormationRole.DEFENSE,
            FormationRole.GOALKEEPER,
        )

        val zones = onPitchRoles.map { role ->
            val drafts = cards.filter { it.role == role }
            buildZone(role, drafts, totalValue)
        }
        val normalizedZones = normalizeHeights(zones)

        val bench = cards.filter { it.role == FormationRole.BENCH }
            .map { it.toCard(intensity = 0.0) }
            .sortedByDescending { it.marketValue }

        val insights = detectInsights(
            zones = normalizedZones,
            totalValue = totalValue,
            nonCashValue = nonCashValue,
            dominantSectorLabel = dominantSector,
        )

        return Formation(
            zones = normalizedZones,
            bench = bench,
            insights = insights,
            dominantSectorLabel = dominantSector,
            totalValue = totalValue,
            nonCashValue = nonCashValue,
        )
    }

    // --- Role assignment ----------------------------------------------------

    /**
     * The computed (pre-override) role. Order of checks matters: the most
     * aggressive classification that fits wins.
     *
     * Every comparison against a [FormationConfig] threshold goes through
     * [clears]/[under] rather than a bare `>=`/`<`, so a metric within
     * [FormationConfig.ROLE_THRESHOLD_MARGIN_FRACTION] of a cutoff doesn't
     * decide anything on its own — see that constant for why.
     */
    fun autoRole(holding: HoldingValuation, risk: RiskProfile): FormationRole {
        if (holding.isCash()) return FormationRole.GOALKEEPER
        if (!risk.hasAnySignal) return FormationRole.BENCH

        val beta = risk.beta
        val corr = risk.sectorCorrelation
        val vol = risk.realizedVolatility

        val highBeta = beta != null && clears(beta, FormationConfig.MIDFIELD_BETA_MAX)
        val marketBeta = beta != null && clears(beta, FormationConfig.DEFENSE_BETA_MAX)
        val lowBeta = beta != null && under(beta, FormationConfig.DEFENSE_BETA_MAX)

        val highCorr = corr != null && clears(corr, FormationConfig.MODERATE_CORRELATION_MAX)
        val moderateCorr = corr != null && !highCorr && clears(corr, FormationConfig.LOW_CORRELATION_MAX)
        val highVol = vol != null && clears(vol, FormationConfig.HIGH_VOLATILITY_STDDEV)

        // Attack: amplifies the market, moves with the dominant sector, or just swings hard.
        if (highBeta || highCorr || highVol) {
            return FormationRole.ATTACK
        }
        // Midfield: roughly market-like, or partly tied to the dominant sector.
        if (marketBeta || moderateCorr) {
            return FormationRole.MIDFIELD
        }
        // Defense: demonstrably low beta and not pulled around by the dominant sector.
        if (lowBeta) {
            return FormationRole.DEFENSE
        }
        // Some signal, but not enough to place confidently (e.g. only a low vol
        // reading, or a beta sitting in the no-man's-land between thresholds).
        return FormationRole.BENCH
    }

    /** True once [value] clears [threshold] by more than the configured margin. */
    private fun clears(value: Double, threshold: Double): Boolean =
        value > threshold * (1.0 + FormationConfig.ROLE_THRESHOLD_MARGIN_FRACTION)

    /** True once [value] is convincingly under [threshold], by more than the configured margin. */
    private fun under(value: Double, threshold: Double): Boolean =
        value < threshold * (1.0 - FormationConfig.ROLE_THRESHOLD_MARGIN_FRACTION)

    // --- Dominant sector --------------------------------------------------

    private fun dominantSectorLabel(
        priced: List<HoldingValuation>,
        groupAllocations: List<com.golddigger.app.domain.model.GroupAllocation>,
    ): String? {
        val bySector = priced
            .filterNot { it.isCash() }
            .groupBy { it.sector?.takeIf { s -> s.isNotBlank() } ?: "Unclassified" }
            .mapValues { (_, rows) -> rows.sumOf { it.marketValue ?: 0.0 } }

        val topSector = bySector.entries.maxByOrNull { it.value }
        // "Dominant sector" can be a user-defined group, but not one that is just
        // the cash position — "a pullback in Cash" is nonsense.
        val topGroup = groupAllocations
            .filterNot { g -> g.tickers.isNotEmpty() && g.tickers.all { CashHolding.isCashTicker(it) } }
            .maxByOrNull { it.currentValue }

        return when {
            topSector == null && topGroup == null -> null
            topGroup != null && topGroup.currentValue > (topSector?.value ?: 0.0) -> topGroup.name
            topSector != null && topSector.key != "Unclassified" -> topSector.key
            topGroup != null -> topGroup.name
            else -> null
        }
    }

    // --- Zones + intensity ---------------------------------------------------

    private fun buildZone(
        role: FormationRole,
        drafts: List<PlayerCardDraft>,
        totalValue: Double,
    ): FormationZone {
        val valueSum = drafts.sumOf { it.holding.marketValue ?: 0.0 }
        val share = if (totalValue > 0.0) valueSum / totalValue * 100.0 else 0.0

        // Intensity: rank each holding by its zone-defining metric, then map to 0..1.
        val metricOf: (PlayerCardDraft) -> Double = { d -> zoneMetric(role, d.risk, d.holding) }
        val metrics = drafts.map(metricOf)
        val min = metrics.minOrNull() ?: 0.0
        val max = metrics.maxOrNull() ?: 0.0
        val span = (max - min).takeIf { it > 1e-9 }

        val players = drafts.mapIndexed { i, d ->
            val intensity = when {
                span == null -> 0.5
                else -> ((metrics[i] - min) / span).coerceIn(0.0, 1.0)
            }
            d.toCard(intensity)
        }
            // Order the line by how extreme each holding is for its role — the
            // most aggressive attacker / most defensive defender leads — then by
            // size so ties are stable. This is also the order the "+N more"
            // overflow trims from (least extreme drop off first).
            .sortedWith(compareByDescending<PlayerCard> { it.intensity }.thenByDescending { it.marketValue })

        return FormationZone(
            role = role,
            players = players,
            valueSum = valueSum,
            valueSharePct = share,
            heightWeight = 0f, // filled in by normalizeHeights
        )
    }

    /** The number that makes a holding "extreme" for its zone (higher = hotter chip). */
    private fun zoneMetric(role: FormationRole, risk: RiskProfile, holding: HoldingValuation): Double =
        when (role) {
            FormationRole.ATTACK -> maxOf(
                risk.beta ?: 0.0,
                (risk.sectorCorrelation ?: 0.0) + 1.0, // corr in [-1,1] -> shift so it competes with beta
                (risk.realizedVolatility ?: 0.0) * 20.0,
            )
            FormationRole.MIDFIELD -> risk.beta ?: (1.0 + (risk.sectorCorrelation ?: 0.0))
            // Lower beta = more defensive = hotter.
            FormationRole.DEFENSE -> 1.0 - (risk.beta ?: FormationConfig.DEFENSE_BETA_MAX)
            FormationRole.GOALKEEPER -> holding.portfolioWeightPct
            FormationRole.BENCH -> 0.0
        }

    private fun normalizeHeights(zones: List<FormationZone>): List<FormationZone> {
        val raw = zones.map { z ->
            when {
                z.players.isEmpty() -> FormationConfig.EMPTY_ZONE_HEIGHT_WEIGHT
                else -> maxOf(
                    (z.valueSharePct / 100.0).toFloat(),
                    FormationConfig.MIN_ZONE_HEIGHT_WEIGHT,
                )
            }
        }
        val sum = raw.sum().takeIf { it > 0f } ?: 1f
        return zones.mapIndexed { i, z -> z.copy(heightWeight = raw[i] / sum) }
    }

    // --- Gap insights -----------------------------------------------------

    fun detectInsights(
        zones: List<FormationZone>,
        totalValue: Double,
        nonCashValue: Double,
        dominantSectorLabel: String?,
    ): List<FormationInsight> {
        if (totalValue <= 0.0) return emptyList()
        val insights = mutableListOf<FormationInsight>()

        val attack = zones.firstOrNull { it.role == FormationRole.ATTACK }
        val midfield = zones.firstOrNull { it.role == FormationRole.MIDFIELD }
        val defense = zones.firstOrNull { it.role == FormationRole.DEFENSE }
        val keeper = zones.firstOrNull { it.role == FormationRole.GOALKEEPER }

        val defensePct = defense?.valueSharePct ?: 0.0
        val keeperPct = keeper?.valueSharePct ?: 0.0
        val frontPct = (attack?.valueSharePct ?: 0.0) + (midfield?.valueSharePct ?: 0.0)

        // 1. No real defenders.
        if (defense == null || defense.players.isEmpty() || defensePct < FormationConfig.DEFENSE_LIGHT_PCT) {
            insights += FormationInsight(
                id = "thin-defense",
                headline = "No real defenders",
                body = "Attack and Midfield are ${pct(frontPct)} of your portfolio with " +
                    "nothing structurally uncorrelated backing them up. A low-beta or " +
                    "counter-cyclical position would give the lineup a back line.",
                severity = InsightSeverity.WARNING,
            )
        }

        // 2. Keeper carrying the back line.
        if (keeperPct > FormationConfig.KEEPER_HEAVY_PCT) {
            insights += FormationInsight(
                id = "heavy-keeper",
                headline = "Your keeper is carrying the whole back line",
                body = "Cash is ${pct(keeperPct)} of the portfolio. That is real " +
                    "downside protection, but consider whether idle cash should be " +
                    "doing more work.",
                severity = InsightSeverity.INFO,
            )
        }

        // 3. Front-loaded formation.
        val attackNonCashPct =
            if (nonCashValue > 0.0) (attack?.valueSum ?: 0.0) / nonCashValue * 100.0 else 0.0
        if (attack != null && attack.players.isNotEmpty() &&
            attackNonCashPct > FormationConfig.ATTACK_HEAVY_NONCASH_PCT
        ) {
            val sector = dominantSectorLabel?.let { "a pullback in $it" } ?: "a broad pullback"
            insights += FormationInsight(
                id = "front-loaded",
                headline = "Front-loaded formation",
                body = "Attack alone is ${pct(attackNonCashPct)} of your non-cash holdings — " +
                    "$sector would hit most of your lineup at once.",
                severity = InsightSeverity.WARNING,
            )
        }

        // 4. Mostly market-like — informational, not a warning: no growth tilt
        // or downside hedge either way. That might be exactly the point (a
        // core index-fund book, say), so this is just a fact to notice, not
        // an implied verdict that something's wrong.
        val midfieldPct = midfield?.valueSharePct ?: 0.0
        if (midfieldPct >= FormationConfig.MIDFIELD_HEAVY_PCT) {
            insights += FormationInsight(
                id = "mostly-midfield",
                headline = "Mostly market-like",
                body = "${pct(midfieldPct)} of your portfolio doesn't lean toward growth or " +
                    "protection either way. That might be exactly the point — a core index " +
                    "book, say — but it's worth knowing if you expected more of a tilt.",
                severity = InsightSeverity.INFO,
            )
        }

        return insights
    }

    private fun pct(value: Double): String {
        val rounded = abs(value)
        return if (rounded >= 10.0) "${rounded.toInt()}%" else String.format(java.util.Locale.US, "%.1f%%", rounded)
    }

    // --- helpers ----------------------------------------------------------

    private fun HoldingValuation.isCash(): Boolean = CashHolding.isCashTicker(ticker)

    private data class PlayerCardDraft(
        val holding: HoldingValuation,
        val risk: RiskProfile,
        val role: FormationRole,
        val autoRole: FormationRole,
        val isOverridden: Boolean,
    ) {
        fun toCard(intensity: Double) = PlayerCard(
            holdingId = holding.holdingId,
            ticker = holding.ticker,
            label = if (CashHolding.isCashTicker(holding.ticker)) CashHolding.NAME else holding.ticker,
            marketValue = holding.marketValue ?: 0.0,
            portfolioWeightPct = holding.portfolioWeightPct,
            role = role,
            autoRole = autoRole,
            isOverridden = isOverridden,
            isCash = CashHolding.isCashTicker(holding.ticker),
            isEtf = holding.isEtf,
            beta = risk.beta,
            sectorCorrelation = risk.sectorCorrelation,
            realizedVolatility = risk.realizedVolatility,
            intensity = intensity,
        )
    }
}
