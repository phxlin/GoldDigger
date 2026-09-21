package com.golddigger.app.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MarketHoursTest {

    private fun utc(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, ZoneOffset.UTC)

    private fun epochMs(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    // September 2026 is EDT (UTC-4): the 09:30 open is 13:30 UTC. The 11th is a
    // Friday, the 12th/13th the weekend, the 14th a Monday.
    private val fridayOpen = epochMs(utc(11, 13, 30))
    private val mondayOpen = epochMs(utc(14, 13, 30))

    @Test
    fun `the market is open during weekday regular hours only`() {
        assertThat(MarketHours.isMarketOpen(utc(14, 15))).isTrue()
        assertThat(MarketHours.isMarketOpen(utc(14, 13, 29))).isFalse()
        assertThat(MarketHours.isMarketOpen(utc(14, 20))).isFalse() // 16:00 close
        assertThat(MarketHours.isMarketOpen(utc(13, 15))).isFalse() // Sunday
    }

    @Test
    fun `mid-session the latest session open is today's`() {
        assertThat(MarketHours.lastSessionOpenEpochMs(epochMs(utc(14, 15)))).isEqualTo(mondayOpen)
    }

    @Test
    fun `after the close it is still today's open`() {
        assertThat(MarketHours.lastSessionOpenEpochMs(epochMs(utc(14, 23)))).isEqualTo(mondayOpen)
    }

    @Test
    fun `before the open it is the previous trading day's`() {
        assertThat(MarketHours.lastSessionOpenEpochMs(epochMs(utc(14, 11)))).isEqualTo(fridayOpen)
    }

    @Test
    fun `on a weekend it is Friday's`() {
        assertThat(MarketHours.lastSessionOpenEpochMs(epochMs(utc(12, 15)))).isEqualTo(fridayOpen)
        assertThat(MarketHours.lastSessionOpenEpochMs(epochMs(utc(13, 15)))).isEqualTo(fridayOpen)
    }
}
