package com.golddigger.app.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Every tunable that governs the Soccer Formation view — how a holding is
 * assigned to a pitch role, when the "gap" insight callouts fire, and how the
 * pitch is laid out. Nothing here is a magic number buried in a Composable or
 * the classifier: the thresholds can be re-tuned in one place without hunting
 * through UI or use-case code.
 *
 * The role model maps portfolio *risk shape* onto a soccer lineup:
 *
 *  | Role        | Rough criteria                                              | Zone   |
 *  |-------------|------------------------------------------------------------|--------|
 *  | Goalkeeper  | cash / cash-equivalent holdings                             | bottom |
 *  | Defense     | low beta AND not correlated to the dominant sector          | back   |
 *  | Midfield    | market-like beta, OR moderate dominant-sector correlation   | middle |
 *  | Attack      | high beta, OR high correlation, OR high realized volatility | front  |
 *  | Bench       | not enough data yet to classify (new holding, no history)   | —      |
 */
object FormationConfig {

    // --- Beta benchmark ---------------------------------------------------

    /**
     * Benchmark a holding's beta is measured against. SPY (the S&P 500) is the
     * textbook definition of *market* beta and the right lens for "how much does
     * this amplify a broad move". The semiconductor / tech tilt of a
     * concentrated book is captured separately by the dominant-sector
     * correlation leg below, not by swapping this to a tech index (which would
     * flatten every tech name to ~1.0 and wash out the signal).
     *
     * Configurable so a provider that returns candles can measure beta against
     * any symbol.
     */
    const val DEFAULT_BETA_BENCHMARK: String = "SPY"

    /**
     * Sector → representative ETF, used when the price provider can supply
     * historical candles for an arbitrary symbol (Finnhub's free tier cannot,
     * so today the dominant-sector correlation is estimated from the user's own
     * holdings in that sector instead — see `PortfolioRepositoryImpl`). Keys are
     * matched case-insensitively as substrings of the Finnhub industry string.
     */
    @Suppress("unused")
    val SECTOR_PROXIES: Map<String, String> = mapOf(
        "semiconductor" to "SOXX",
        "technology" to "XLK",
        "software" to "IGV",
        "financial" to "XLF",
        "bank" to "KBE",
        "health" to "XLV",
        "pharmaceutical" to "XLV",
        "biotechnology" to "XBI",
        "energy" to "XLE",
        "oil" to "XLE",
        "consumer" to "XLY",
        "retail" to "XRT",
        "industrial" to "XLI",
        "materials" to "XLB",
        "utilities" to "XLU",
        "real estate" to "XLRE",
        "communication" to "XLC",
        "media" to "XLC",
    )

    /**
     * Common bond / fixed-income ETFs, keyed uppercase. The price provider
     * reports no sector for any fund, so without this a bond ETF would land
     * in the same "Unclassified" bucket as equity ETFs and end up correlated
     * against a basket that's overwhelmingly stocks (see
     * `PortfolioRepositoryImpl`'s sector-correlation basket selection).
     */
    val FIXED_INCOME_ETFS: Set<String> = setOf(
        "BND", "BNDX", "AGG", "AGGU", "TLT", "TLH", "IEF", "IEI", "SHY", "SHV",
        "LQD", "VCIT", "VCSH", "VCLT", "HYG", "JNK", "MUB", "MBB", "BIV", "BSV",
        "BLV", "GOVT", "SCHZ", "SCHR", "SCHO", "SPTL", "SPTI", "SPTS", "TIP", "VTIP",
    )

    // --- Role thresholds ------------------------------------------------------

    /** Beta at or above this is no longer "Defense" (it moves at least with the market). */
    const val DEFENSE_BETA_MAX: Double = 0.9

    /** Beta above this is "Attack"; between [DEFENSE_BETA_MAX] and this is "Midfield". */
    const val MIDFIELD_BETA_MAX: Double = 1.5

    /** Trailing-window correlation below this counts as *uncorrelated* to the dominant sector. */
    const val LOW_CORRELATION_MAX: Double = 0.4

    /** Correlation from [LOW_CORRELATION_MAX] up to this is *moderate*; above this is *high*. */
    const val MODERATE_CORRELATION_MAX: Double = 0.7

    /**
     * Realized volatility (standard deviation of the return between consecutive
     * cached price points) at or above this pushes a holding to Attack even if
     * its beta is tame. Per-interval dispersion, not annualized — the free tier
     * gives no daily closes to annualize from.
     */
    const val HIGH_VOLATILITY_STDDEV: Double = 0.035

    /**
     * How far past a role threshold above (beta, correlation, volatility)
     * must land, as a fraction of the threshold's own value, before it's
     * trusted to push a holding into the more aggressive role — and how far
     * below [DEFENSE_BETA_MAX] beta must land to be trusted for Defense. A
     * metric inside this margin is noise-sensitive: e.g. two betas of 1.48
     * and 1.51 straddling [MIDFIELD_BETA_MAX] are economically almost
     * identical and shouldn't land in different roles just because one
     * rounds a hair past a hard cutoff. A value inside the margin resolves
     * to the more conservative role, or — if nothing else about the holding
     * clears a threshold either — Bench, same as any other "not enough
     * signal to place confidently" case.
     */
    const val ROLE_THRESHOLD_MARGIN_FRACTION: Double = 0.10

    /**
     * Fewest data points needed before an estimate is trusted: intraday
     * price points for realized volatility, but trading *days* of overlap
     * for beta/correlation (see `PortfolioRepositoryImpl.dailyClosesByTicker`)
     * — a handful of points from one session isn't enough to say two assets
     * move together.
     */
    const val MIN_POINTS_FOR_ESTIMATE: Int = 12

    /** Trailing window (in days) for the dominant-sector correlation estimate. */
    @Suppress("unused")
    const val CORRELATION_WINDOW_DAYS: Long = 90

    /** Risk metrics (beta, correlation) are re-fetched / recomputed no more often than this. */
    val METRICS_TTL: Duration = 7.days

    // --- Gap-insight thresholds --------------------------------------------

    /** Defense holding this little of the portfolio (or nothing) triggers the "no defenders" callout. */
    const val DEFENSE_LIGHT_PCT: Double = 10.0

    /** Cash (Goalkeeper) above this share of the portfolio triggers the "keeper carrying the back line" callout. */
    const val KEEPER_HEAVY_PCT: Double = 50.0

    /** Attack above this share of *non-cash* value triggers the "front-loaded" callout. */
    const val ATTACK_HEAVY_NONCASH_PCT: Double = 60.0

    /**
     * Midfield at or above this share of the *whole* portfolio triggers the
     * informational "mostly market-like" note — not a warning, since a
     * heavily market-like book (a core index-fund position, say) can be
     * entirely intentional.
     */
    const val MIDFIELD_HEAVY_PCT: Double = 70.0

    // --- Pitch layout ----------------------------------------------------

    /** A non-empty zone never renders shorter than this fraction of the pitch, however little value it holds. */
    const val MIN_ZONE_HEIGHT_WEIGHT: Float = 0.14f

    /** An empty zone still shows a thin band this tall (so a missing line is visible). */
    const val EMPTY_ZONE_HEIGHT_WEIGHT: Float = 0.08f
}
