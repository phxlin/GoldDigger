package com.golddigger.app.ui.common

import com.golddigger.app.core.CashHolding
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PriceRange
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** User-facing name for a holding row/slice: real tickers verbatim, cash as "Cash". */
fun HoldingValuation.displayLabel(): String =
    if (CashHolding.isCashTicker(ticker)) CashHolding.NAME else ticker

val HoldingValuation.isCash: Boolean get() = CashHolding.isCashTicker(ticker)

private val currency: NumberFormat
    get() = NumberFormat.getCurrencyInstance(Locale.US)

fun Double.asCurrency(): String = currency.format(this)

fun Double.asSignedCurrency(): String {
    val formatted = currency.format(kotlin.math.abs(this))
    return if (this >= 0) "+$formatted" else "-$formatted"
}

fun Double.asPercent(decimals: Int = 2): String =
    String.format(Locale.US, "%+.${decimals}f%%", this)

fun Double.asPlainPercent(decimals: Int = 1): String =
    String.format(Locale.US, "%.${decimals}f%%", this)

/** Compact money for tight spots (chips, dense labels): "$1.2k", "$3.4M", "$920". */
fun Double.asCompactCurrency(): String {
    val abs = kotlin.math.abs(this)
    val sign = if (this < 0) "-" else ""
    return when {
        abs >= 1_000_000 -> String.format(Locale.US, "%s$%.1fM", sign, abs / 1_000_000)
        abs >= 1_000 -> String.format(Locale.US, "%s$%.1fk", sign, abs / 1_000)
        else -> String.format(Locale.US, "%s$%.0f", sign, abs)
    }
}

fun Double.asShares(): String =
    if (this % 1.0 == 0.0) this.toLong().toString()
    else String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')

/**
 * Keeps only digits and a single decimal point, for a free-text numeric entry
 * field (shares, price, cost) — used by both manual add/edit and photo import.
 */
fun String.filterToNumericInput(): String =
    filterIndexed { i, c -> c.isDigit() || (c == '.' && !substring(0, i).contains('.')) }

/**
 * A round-trippable text representation of [this] for pre-filling an editable
 * numeric field — unlike [asShares]/[asCurrency], this never rounds, so
 * re-parsing the field gets the exact original value back.
 */
fun Double.asEditableNumber(): String =
    if (this % 1.0 == 0.0) this.toLong().toString() else this.toString()

/** "just now", "4 min ago", "2 h ago", "3 d ago". */
fun relativeTime(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMs == null || epochMs <= 0) return "never"
    val delta = (now - epochMs).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        else -> "$days d ago"
    }
}

fun clockTime(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return "—"
    val fmt = java.text.SimpleDateFormat("h:mm a", Locale.US)
    return fmt.format(java.util.Date(epochMs))
}

/**
 * Label for a point scrubbed on the price-history chart. [PriceRange.ONE_DAY]
 * shows a clock time since every point falls on the same day; every wider
 * range shows a date instead, since time-of-day stops being the interesting
 * part once points span multiple days.
 */
fun Long.asChartTimestamp(range: PriceRange): String {
    val pattern = if (range == PriceRange.ONE_DAY) "h:mm a" else "MMM d, yyyy"
    return java.text.SimpleDateFormat(pattern, Locale.US).format(java.util.Date(this))
}
