package com.golddigger.app.data.repository

/**
 * Outcome of the most recent price refresh, surfaced to the UI as a calm status
 * line rather than an error dialog. Rate limiting in particular is a normal,
 * expected state on a free API tier.
 */
sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState

    /** Everything on screen is fresh as of [atEpochMs]. */
    data class UpToDate(val atEpochMs: Long) : SyncState

    /**
     * Provider refused further requests. [nextAllowedEpochMs] is our best guess
     * at when a retry will succeed (from Retry-After, or the throttle window).
     */
    data class RateLimited(
        val lastUpdatedEpochMs: Long?,
        val nextAllowedEpochMs: Long?,
    ) : SyncState

    /** Network failed; the UI keeps showing cached prices with a stale badge. */
    data class Offline(val lastUpdatedEpochMs: Long?) : SyncState

    data object NoApiKey : SyncState
}
