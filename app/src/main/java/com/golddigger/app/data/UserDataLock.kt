package com.golddigger.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serialises the writes that can touch what a backup import or "Delete all data" replaces: the
 * import / delete transaction itself, and the price and news writes a sync makes after its network
 * calls return. Without it a sync that fetched quotes just before a delete could write them just
 * after it, leaving price rows for holdings that no longer exist.
 *
 * Only the write phase is guarded, never a network call, so holding it is always brief.
 */
@Singleton
class UserDataLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
