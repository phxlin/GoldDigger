package com.golddigger.app.core

import java.time.DayOfWeek
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
        val weekday = nyNow.dayOfWeek != DayOfWeek.SATURDAY &&
            nyNow.dayOfWeek != DayOfWeek.SUNDAY
        val t = nyNow.toLocalTime()
        return weekday && !t.isBefore(OPEN) && t.isBefore(CLOSE)
    }
}
