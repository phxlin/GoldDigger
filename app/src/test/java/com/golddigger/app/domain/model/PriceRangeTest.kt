package com.golddigger.app.domain.model

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class PriceRangeTest {

    private val zone = ZoneId.of("UTC")

    // 2026-09-13T12:00:00Z
    private val now = ZonedDateTime.of(2026, 9, 13, 12, 0, 0, 0, zone)
        .toInstant().toEpochMilli()

    @Test
    fun `Max has no lower bound`() {
        assertThat(PriceRange.MAX.sinceEpochMs(now, zone)).isEqualTo(0L)
    }

    @Test
    fun `one day is exactly 24 hours before now`() {
        val expected = now - 24L * 60 * 60 * 1000
        assertThat(PriceRange.ONE_DAY.sinceEpochMs(now, zone)).isEqualTo(expected)
    }

    @Test
    fun `five days is exactly 5 times 24 hours before now`() {
        val expected = now - 5 * 24L * 60 * 60 * 1000
        assertThat(PriceRange.FIVE_DAYS.sinceEpochMs(now, zone)).isEqualTo(expected)
    }

    @Test
    fun `year-to-date starts at midnight Jan 1 of the current year`() {
        val expected = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertThat(PriceRange.YEAR_TO_DATE.sinceEpochMs(now, zone)).isEqualTo(expected)
    }

    @Test
    fun `one year is a calendar year before now, not a fixed 365-day offset`() {
        val expected = ZonedDateTime.of(2025, 9, 13, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertThat(PriceRange.ONE_YEAR.sinceEpochMs(now, zone)).isEqualTo(expected)
    }

    @Test
    fun `ranges are ordered from narrowest to widest`() {
        // Each range's cutoff is further back in time (a smaller epoch value)
        // than the previous one's — mixing this up would silently show the
        // wrong window's data for a range.
        val narrowestToWidest = listOf(
            PriceRange.ONE_DAY,
            PriceRange.FIVE_DAYS,
            PriceRange.ONE_MONTH,
            PriceRange.SIX_MONTHS,
            PriceRange.ONE_YEAR,
            PriceRange.FIVE_YEARS,
        )
        val cutoffs = narrowestToWidest.map { it.sinceEpochMs(now, zone) }
        for (i in 0 until cutoffs.size - 1) {
            assertThat(cutoffs[i]).isGreaterThan(cutoffs[i + 1])
        }
        assertThat(cutoffs.last()).isGreaterThan(PriceRange.MAX.sinceEpochMs(now, zone))
    }
}
