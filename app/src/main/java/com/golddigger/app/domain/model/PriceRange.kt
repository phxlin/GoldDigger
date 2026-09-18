package com.golddigger.app.domain.model

import java.time.Instant
import java.time.ZoneId

/**
 * A selectable window for the Holding Detail price chart. Each range is
 * resolved to a lower-bound timestamp at read time via [sinceEpochMs] rather
 * than stored — "1Y" always means "the last 365 days from now," not a fixed
 * calendar window — so the same enum value keeps meaning the same thing as
 * time passes.
 */
enum class PriceRange(val label: String) {
    ONE_DAY("1D"),
    FIVE_DAYS("5D"),
    ONE_MONTH("1M"),
    SIX_MONTHS("6M"),
    YEAR_TO_DATE("YTD"),
    ONE_YEAR("1Y"),
    FIVE_YEARS("5Y"),
    MAX("Max");

    /**
     * The oldest timestamp (epoch ms) this range should include, given the
     * current time [nowEpochMs]. [MAX] returns 0 — "everything retained,"
     * since real price-point timestamps are always well after the epoch.
     */
    fun sinceEpochMs(nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (this == MAX) return 0L
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
        val since = when (this) {
            ONE_DAY -> now.minusDays(1)
            FIVE_DAYS -> now.minusDays(5)
            ONE_MONTH -> now.minusMonths(1)
            SIX_MONTHS -> now.minusMonths(6)
            YEAR_TO_DATE -> now.withDayOfYear(1).toLocalDate().atStartOfDay(zone)
            ONE_YEAR -> now.minusYears(1)
            FIVE_YEARS -> now.minusYears(5)
            MAX -> now
        }
        return since.toInstant().toEpochMilli()
    }
}
