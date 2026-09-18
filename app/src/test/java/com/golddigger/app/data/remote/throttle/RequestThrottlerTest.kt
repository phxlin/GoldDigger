package com.golddigger.app.data.remote.throttle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestThrottlerTest {

    @Test
    fun `first N acquisitions within the window do not wait`() = runTest {
        var virtualNow = 0L
        val slept = mutableListOf<Long>()
        val throttler = RequestThrottler(
            maxPermits = 3,
            windowMillis = 60_000,
            clock = { virtualNow },
            sleep = { slept += it },
        )

        repeat(3) { throttler.acquire() }

        assertThat(slept).isEmpty()
        assertThat(throttler.availablePermits()).isEqualTo(0)
    }

    @Test
    fun `the request over the limit waits until the window frees a permit`() = runTest {
        var virtualNow = 0L
        val slept = mutableListOf<Long>()
        val throttler = RequestThrottler(
            maxPermits = 2,
            windowMillis = 60_000,
            clock = { virtualNow },
            sleep = { waited ->
                slept += waited
                virtualNow += waited // advancing virtual time as if we slept
            },
        )

        throttler.acquire() // t=0
        throttler.acquire() // t=0
        throttler.acquire() // must wait ~60s for the first grant to age out

        assertThat(slept).hasSize(1)
        assertThat(slept.single()).isEqualTo(60_000)
    }

    @Test
    fun `permits recover as the window slides`() = runTest {
        var virtualNow = 0L
        val throttler = RequestThrottler(
            maxPermits = 5,
            windowMillis = 1_000,
            clock = { virtualNow },
            sleep = { virtualNow += it },
        )
        repeat(5) { throttler.acquire() }
        assertThat(throttler.availablePermits()).isEqualTo(0)

        virtualNow += 1_001
        assertThat(throttler.availablePermits()).isEqualTo(5)
    }
}
