package com.golddigger.app.data.remote.throttle

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sliding-window rate limiter shared by every code path that hits the price
 * provider (pull-to-refresh, background WorkManager sync, ticker search). It
 * guarantees the app never issues more than [maxPermits] requests per
 * [windowMillis], no matter how many holdings the user has or how many screens
 * ask for data at once.
 *
 * [clock] and [sleep] are injectable so the behaviour is unit-testable with
 * virtual time.
 */
class RequestThrottler(
    private val maxPermits: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val grantTimes = ArrayDeque<Long>()

    /** Suspends until issuing one more request stays within the limit. */
    suspend fun acquire() {
        while (true) {
            val waitMillis = mutex.withLock {
                val now = clock()
                while (grantTimes.isNotEmpty() && now - grantTimes.first() >= windowMillis) {
                    grantTimes.removeFirst()
                }
                if (grantTimes.size < maxPermits) {
                    grantTimes.addLast(now)
                    0L
                } else {
                    windowMillis - (now - grantTimes.first())
                }
            }
            if (waitMillis <= 0L) return
            sleep(waitMillis)
        }
    }

    /** Requests available right now without waiting. Useful for diagnostics/UI. */
    suspend fun availablePermits(): Int = mutex.withLock {
        val now = clock()
        while (grantTimes.isNotEmpty() && now - grantTimes.first() >= windowMillis) {
            grantTimes.removeFirst()
        }
        (maxPermits - grantTimes.size).coerceAtLeast(0)
    }
}
