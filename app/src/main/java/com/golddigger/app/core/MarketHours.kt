package com.golddigger.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * US equity regular trading session (09:30–16:00 America/New_York, Mon–Fri).
 * Holidays are not modelled; the cost of an occasional wasted sync on a market
 * holiday is negligible and keeping this dependency-free keeps it testable.
 */
object MarketHours {

    private val EXCHANGE_ZONE: ZoneId = ZoneId.of("America/New_York")
    private val OPEN: LocalTime = LocalTime.of(9, 30)
    private val CLOSE: LocalTime = LocalTime.of(16, 0)

    fun isMarketOpen(now: ZonedDateTime = ZonedDateTime.now(EXCHANGE_ZONE)): Boolean {
        val nyNow = now.withZoneSameInstant(EXCHANGE_ZONE)
        val t = nyNow.toLocalTime()
        return isWeekday(nyNow.toLocalDate()) && !t.isBefore(OPEN) && t.isBefore(CLOSE)
    }

    /**
     * Epoch ms of the most recent regular-session open at or before
     * [nowEpochMs]: today's 09:30 ET once a weekday has opened, otherwise the
     * previous weekday's. The Holding Detail "1D" range starts here, so it shows
     * the latest trading session, including when it's evenings, weekends or
     * pre-market and nothing is being recorded.
     */
    fun lastSessionOpenEpochMs(nowEpochMs: Long): Long {
        val nyNow = Instant.ofEpochMilli(nowEpochMs).atZone(EXCHANGE_ZONE)
        var day = nyNow.toLocalDate()
        if (!isWeekday(day) || nyNow.toLocalTime().isBefore(OPEN)) {
            do {
                day = day.minusDays(1)
            } while (!isWeekday(day))
        }
        return day.atTime(OPEN).atZone(EXCHANGE_ZONE).toInstant().toEpochMilli()
    }

    /**
     * The exchange-local calendar date [epochMs] falls on — used to align
     * price points to trading days rather than exact sync timestamps (see
     * `PortfolioRepositoryImpl`'s beta/correlation estimates).
     */
    fun sessionDate(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(EXCHANGE_ZONE).toLocalDate()

    private fun isWeekday(day: LocalDate) =
        day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY
}
