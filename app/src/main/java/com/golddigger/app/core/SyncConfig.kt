package com.golddigger.app.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Central place for every tunable that governs how aggressively the app talks to
 * the price provider. Nothing here is a magic number scattered through the code:
 * if the user upgrades to a paid tier, only this file (and the persisted
 * [com.golddigger.app.data.settings.SettingsRepository] overrides) need to change.
 */
object SyncConfig {

    /** Free Finnhub tier allows 60 calls/minute. We stay comfortably under it. */
    const val MAX_REQUESTS_PER_MINUTE: Int = 50

    /** Window the throttler refills its token bucket over. */
    val THROTTLE_WINDOW: Duration = 1.minutes

    /**
     * How many symbols to request per API call. Finnhub's free `/quote` endpoint
     * is single-symbol, so the effective batch is 1 and the repository issues
     * throttled sequential calls. A provider with a multi-symbol quote endpoint
     * would raise this and [com.golddigger.app.data.remote.StockPriceApi] would
     * pack that many tickers per request.
     */
    const val DEFAULT_BATCH_SIZE: Int = 1

    /** Default gap between background price syncs. */
    val DEFAULT_REFRESH_INTERVAL: Duration = 15.minutes

    /** WorkManager's hard floor for periodic work. */
    val MIN_REFRESH_INTERVAL: Duration = 15.minutes

    val MAX_REFRESH_INTERVAL: Duration = 6.hours

    /**
     * A cached price newer than this (relative to the active refresh interval) is
     * considered fresh and is not re-fetched, even if several screens ask for it
     * at once.
     */
    fun isFresh(lastUpdatedEpochMs: Long, now: Long, refreshInterval: Duration): Boolean =
        now - lastUpdatedEpochMs < refreshInterval.inWholeMilliseconds

    /** Below this age a price is shown without a "stale" badge. */
    val STALE_AFTER: Duration = 20.minutes

    /** Company news older than this is re-fetched when the detail screen opens. */
    val NEWS_TTL: Duration = 30.minutes

    /** How far back company news is requested. */
    const val NEWS_LOOKBACK_DAYS: Long = 14

    /** Most articles kept per ticker in the cache. */
    const val NEWS_MAX_PER_TICKER: Int = 25

    /** Retrofit/OkHttp timeouts. */
    val NETWORK_TIMEOUT: Duration = 20.seconds

    const val WORK_NAME_PERIODIC_SYNC: String = "golddigger-periodic-price-sync"
}
