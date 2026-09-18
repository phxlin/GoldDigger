package com.golddigger.app.domain.model

/**
 * A pitch role. Ordered front-to-back so `entries` can drive the stacked-zone
 * layout directly. [BENCH] is the "not enough data to classify" state — a
 * holding just added, with no price history and no beta yet.
 */
enum class FormationRole {
    ATTACK,
    MIDFIELD,
    DEFENSE,
    GOALKEEPER,
    BENCH,
    ;

    val isOnPitch: Boolean get() = this != BENCH
}

/**
 * The risk inputs known for one ticker. Every field is nullable: not every
 * provider returns beta, and correlation / volatility need accumulated price
 * history the app may not have yet.
 */
data class RiskProfile(
    val ticker: String,
    val beta: Double? = null,
    /** Trailing-window correlation of this holding to the portfolio's dominant sector, [-1, 1]. */
    val sectorCorrelation: Double? = null,
    /** Std-dev of the return between consecutive cached price points (per-interval, not annualized). */
    val realizedVolatility: Double? = null,
) {
    val hasAnySignal: Boolean
        get() = beta != null || sectorCorrelation != null || realizedVolatility != null
}

/** Input to [com.golddigger.app.domain.FormationClassifier]. UI-free. */
data class FormationInput(
    val holdings: List<HoldingValuation>,
    val risk: Map<String, RiskProfile>,
    /** Manual role overrides by holding id — these win over the computed role. */
    val overrides: Map<Long, FormationRole>,
    val groupAllocations: List<GroupAllocation> = emptyList(),
)

/**
 * One holding placed on the pitch.
 *
 * @param intensity 0..1 — how extreme this holding's defining metric is *within
 *   its zone* (a borderline Attack sits near 0, the highest-beta name near 1).
 *   Drives the chip's colour intensity so a crowded zone still reads.
 */
data class PlayerCard(
    val holdingId: Long,
    val ticker: String,
    val label: String,
    val marketValue: Double,
    val portfolioWeightPct: Double,
    val role: FormationRole,
    val autoRole: FormationRole,
    val isOverridden: Boolean,
    val isCash: Boolean,
    val isEtf: Boolean = false,
    val beta: Double?,
    val sectorCorrelation: Double?,
    val realizedVolatility: Double?,
    val intensity: Double,
)

/**
 * A stacked band of the pitch.
 *
 * @param valueSharePct this zone's share of total portfolio value, 0..100.
 * @param heightWeight normalized fraction of the pitch height this zone should
 *   occupy — proportional to dollar value, floored so a small-but-present zone
 *   stays visible and an empty zone still shows a thin band.
 */
data class FormationZone(
    val role: FormationRole,
    val players: List<PlayerCard>,
    val valueSum: Double,
    val valueSharePct: Double,
    val heightWeight: Float,
)

enum class InsightSeverity { INFO, WARNING }

/** An automated "gap" callout shown beside the pitch. */
data class FormationInsight(
    val id: String,
    val headline: String,
    val body: String,
    val severity: InsightSeverity,
)

/**
 * The full computed formation. [zones] is always the four on-pitch roles in
 * [FormationRole] order (ATTACK first); [bench] holds the unclassified.
 */
data class Formation(
    val zones: List<FormationZone>,
    val bench: List<PlayerCard>,
    val insights: List<FormationInsight>,
    val dominantSectorLabel: String?,
    val totalValue: Double,
    val nonCashValue: Double,
) {
    val isEmpty: Boolean get() = zones.all { it.players.isEmpty() } && bench.isEmpty()

    fun zone(role: FormationRole): FormationZone? = zones.firstOrNull { it.role == role }
}
