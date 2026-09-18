package com.golddigger.app.domain

import com.golddigger.app.domain.model.OcrToken
import com.golddigger.app.domain.model.ParseConfidence
import com.golddigger.app.domain.model.ParsedHolding
import kotlin.math.abs

/**
 * Turns raw OCR words from a photo of a brokerage app / statement into a
 * best-effort list of [ParsedHolding] candidates — ticker, shares, average
 * price. Pure — no Android, no ML Kit, no I/O — so the table-reading logic is
 * unit-testable independent of the camera or the recognizer.
 *
 * Screenshots of a portfolio are never in one fixed layout, so nothing here
 * is keyed to a specific broker's format: it reads whatever grid of words the
 * recognizer returned. The approach is the same one a person uses to read an
 * unfamiliar table:
 *  1. Group words into rows by vertical position (reading order from OCR
 *     often interleaves columns, so this re-derives rows from geometry).
 *  2. If one row looks like a header ("Shares", "Avg Cost", …), use its
 *     column x-positions to attribute every other row's numbers. When a
 *     table shows shares, market value and lifetime gain/loss but no direct
 *     average-cost column, that column set still fully determines it —
 *     `avg cost = (market value − gain/loss) / shares` — so a header row
 *     naming those instead is used the same way, via exact arithmetic on
 *     numbers actually printed in the photo, not a guess.
 *  3. Otherwise, cluster the x-positions of numbers across every row to find
 *     the table's column bands and use the same left-to-right convention
 *     nearly every broker uses: Shares first, then Avg cost / Avg price.
 *  4. Whatever can't be attributed confidently is still returned (with the
 *     raw row text) so the user can fill it in by hand — this is a
 *     suggestion engine, not a silent importer; the caller always shows
 *     these for confirmation before writing anything to Room.
 */
object PortfolioOcrParser {

    private val HEADER_TICKER_WORDS = setOf("SYMBOL", "TICKER", "STOCK", "HOLDING", "SECURITY")
    private val HEADER_SHARES_WORDS = setOf("SHARES", "SHARE", "SHR", "SHRS", "QTY", "QUANTITY", "UNITS")

    // Specific to an avg-cost column, as opposed to a live/current price
    // column — see the comment in [columnAnchors] on why that distinction
    // matters. "PRICE" alone is ambiguous between the two, so it's kept in
    // [HEADER_PRICE_WORDS] (a header only labeled "Price" usually does mean
    // avg price) but never wins over one of these more specific words.
    private val HEADER_AVG_COST_WORDS = setOf("AVG", "AVERAGE", "COST", "BASIS")
    private val HEADER_PRICE_WORDS = HEADER_AVG_COST_WORDS + "PRICE"

    // Not enough on their own to place a holding, but combined with shares
    // they let avg cost be *computed* rather than guessed — see [assignNumbers].
    private val HEADER_VALUE_WORDS = setOf("VALUE", "BALANCE")
    private val HEADER_GAINLOSS_WORDS = setOf("GAIN", "LOSS", "RETURN", "P/L", "PL")

    /**
     * Common statement chrome that happens to look ticker-shaped (1-5 caps
     * letters). Deliberately does NOT include words that are themselves real,
     * actively-traded ticker symbols — ALL (Allstate), LOW (Lowe's), OPEN
     * (Opendoor), DAY (Dayforce) — even though they can also appear as
     * ordinary statement text, since [findTicker] only looks at data rows
     * (the header row is excluded upstream), where a bare word like that is
     * far more likely to be a real holding than leftover chrome.
     */
    private val NON_TICKER_WORDS = setOf(
        "USD", "CAD", "EUR", "GBP", "TOTAL", "CASH", "TODAY", "GAIN", "LOSS",
        "VALUE", "MKT", "AVG", "QTY", "SHARES", "SHARE", "SHR", "PRICE", "COST",
        "BASIS", "SYMBOL", "TICKER", "STOCK", "NAME", "ACCOUNT", "BALANCE",
        "PORTFOLIO", "HOLDINGS", "POSITIONS", "EQUITY", "CHANGE",
        "WEEK", "MONTH", "YEAR", "YTD", "PCT", "PERCENT", "OF", "AND", "THE",
        "FOR", "PER", "CURRENT", "MARKET", "CLOSE", "HIGH",
        "VOL", "VOLUME", "NAV", "APY", "APR", "ETF", "FUND", "TRUST", "GROUP",
        "HLDG", "HOLD", "CLASS", "INC", "CORP", "LTD", "LLC", "PLC", "CO",
        "IRA", "ROTH", "NEW",
    )

    // Case-sensitive on purpose: tickers are displayed in caps, company names
    // and units ("sh") are not, and that distinction is a strong signal.
    private val TICKER_REGEX = Regex("^\\$?[A-Z]{1,5}(\\.[A-Z]{1,2})?$")
    private val NUMBER_REGEX = Regex("^\\(?[+-]?\\$?[\\d,]+\\.?\\d*\\)?$")

    fun parse(tokens: List<OcrToken>): List<ParsedHolding> {
        if (tokens.isEmpty()) return emptyList()
        val rows = clusterIntoRows(tokens)
        val header = rows.firstOrNull { isHeaderRow(it) }
        val dataRows = if (header != null) rows.filterNot { it === header } else rows
        val headerAnchors = header?.let { columnAnchors(it) }
        val inferredColumns = inferColumnCenters(dataRows)

        return dataRows.mapNotNull { row -> parseRow(row, headerAnchors, inferredColumns) }
    }

    // --- Row detection ------------------------------------------------------

    /**
     * Groups words into visual rows by vertical center, tolerant of OCR
     * jitter. Input order doesn't matter — a photo's recognizer may return
     * words in column-major reading order, which this re-derives from
     * geometry rather than trusting.
     */
    private fun clusterIntoRows(tokens: List<OcrToken>): List<List<OcrToken>> {
        val sorted = tokens.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrToken>>()
        for (t in sorted) {
            val currentRow = rows.lastOrNull()?.takeIf { row ->
                val rowCenterY = row.sumOf { it.centerY.toDouble() } / row.size
                val avgHeight = row.sumOf { it.height.toDouble() } / row.size
                abs(t.centerY - rowCenterY) < avgHeight * 0.6
            }
            if (currentRow != null) currentRow.add(t) else rows.add(mutableListOf(t))
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    // --- Header / column detection -----------------------------------------

    private data class ColumnAnchors(
        val shares: Float?,
        val price: Float?,
        val value: Float?,
        val gainLoss: Float?,
    )

    private fun isHeaderRow(row: List<OcrToken>): Boolean {
        val words = row.map { it.text.uppercase() }
        val hasShares = words.any { it in HEADER_SHARES_WORDS }
        val hasPrice = words.any { it in HEADER_PRICE_WORDS }
        val hasTicker = words.any { it in HEADER_TICKER_WORDS }
        val hasValue = words.any { it in HEADER_VALUE_WORDS }
        val hasGainLoss = words.any { it in HEADER_GAINLOSS_WORDS }
        // Require two signals so an ordinary data row never gets mistaken for
        // the header (e.g. a row that happens to contain "cost basis" text).
        return (hasShares && hasPrice) || (hasShares && hasTicker) || (hasPrice && hasTicker) ||
            (hasShares && hasValue && hasGainLoss)
    }

    private fun columnAnchors(header: List<OcrToken>): ColumnAnchors {
        var shares: Float? = null
        var price: Float? = null
        var priceIsAvgCost = false
        var value: Float? = null
        var gainLoss: Float? = null
        for (t in header) {
            val w = t.text.uppercase()
            if (shares == null && w in HEADER_SHARES_WORDS) shares = t.centerX
            if (w in HEADER_PRICE_WORDS) {
                val isAvgCost = w in HEADER_AVG_COST_WORDS
                // A row can show both a live/current price column and a
                // separate avg-cost column ("Last Price | Average Cost
                // Basis"); only the latter is what this field means. Prefer
                // whichever header word is avg-cost-specific over a bare
                // "PRICE" instead of first-match-wins, so the OCR's read
                // order of the two headers can't flip which column gets used.
                if (price == null || (isAvgCost && !priceIsAvgCost)) {
                    price = t.centerX
                    priceIsAvgCost = isAvgCost
                }
            }
            if (value == null && w in HEADER_VALUE_WORDS) value = t.centerX
            if (gainLoss == null && w in HEADER_GAINLOSS_WORDS) gainLoss = t.centerX
        }
        return ColumnAnchors(shares, price, value, gainLoss)
    }

    /**
     * Without a header, most broker tables still line every row's numbers up
     * in consistent vertical bands. Clustering every data row's number
     * x-positions together finds those bands; the leftmost is (by the
     * near-universal broker convention) shares, the next is avg cost/price.
     * Later columns (current price, market value, gain…) are left alone.
     */
    private fun inferColumnCenters(dataRows: List<List<OcrToken>>): List<Float> {
        val xs = dataRows.flatMap { row -> row.filter { looksNumeric(it.text) }.map { it.centerX } }
        if (xs.isEmpty()) return emptyList()
        val sorted = xs.sorted()
        val span = (sorted.last() - sorted.first()).takeIf { it > 0f } ?: 1f
        val bandGap = span * 0.06f
        val clusters = mutableListOf<MutableList<Float>>()
        for (x in sorted) {
            val last = clusters.lastOrNull()
            if (last != null && x - last.last() < bandGap) last.add(x) else clusters.add(mutableListOf(x))
        }
        return clusters.map { it.average().toFloat() }
    }

    // --- Per-row extraction --------------------------------------------------

    private fun parseRow(
        row: List<OcrToken>,
        headerAnchors: ColumnAnchors?,
        inferredColumns: List<Float>,
    ): ParsedHolding? {
        val tickerToken = findTicker(row) ?: return null
        val numberTokens = row.filter { it !== tickerToken && looksNumeric(it.text) }
        val (shares, price, confidence) = assignNumbers(numberTokens, headerAnchors, inferredColumns)
        return ParsedHolding(
            ticker = normalizeTicker(tickerToken.text),
            shares = shares,
            avgPrice = price,
            confidence = confidence,
            sourceText = row.joinToString(" ") { it.text },
        )
    }

    private fun findTicker(row: List<OcrToken>): OcrToken? =
        row.firstOrNull { t ->
            TICKER_REGEX.matches(t.text) && normalizeTicker(t.text) !in NON_TICKER_WORDS
        }

    private fun looksNumeric(text: String): Boolean = NUMBER_REGEX.matches(text)

    private fun assignNumbers(
        numbers: List<OcrToken>,
        headerAnchors: ColumnAnchors?,
        inferredColumns: List<Float>,
    ): Triple<Double?, Double?, ParseConfidence> {
        if (headerAnchors != null && (headerAnchors.shares != null || headerAnchors.price != null)) {
            val claimed = mutableSetOf<OcrToken>()
            val sharesTok = nearestUnclaimed(numbers, headerAnchors.shares, claimed)?.also { claimed += it }
            val shares = sharesTok?.let { parseNumber(it.text) }
            val priceTok = nearestUnclaimed(numbers, headerAnchors.price, claimed)?.also { claimed += it }
            var price = priceTok?.let { parseNumber(it.text) }

            // No direct avg-cost column, but shares + market value + lifetime
            // gain/loss fully determine it: avg cost = (value − gain) / shares.
            if (price == null && shares != null && shares != 0.0 &&
                headerAnchors.value != null && headerAnchors.gainLoss != null
            ) {
                val valueTok = nearestUnclaimed(numbers, headerAnchors.value, claimed)?.also { claimed += it }
                val marketValue = valueTok?.let { parseNumber(it.text) }
                val gainTok = nearestUnclaimed(numbers, headerAnchors.gainLoss, claimed)
                val gainLoss = gainTok?.let { parseNumber(it.text) }
                if (marketValue != null && gainLoss != null) {
                    price = (marketValue - gainLoss) / shares
                }
            }
            if (shares != null || price != null) return finish(shares, price)
        }

        if (inferredColumns.size >= 2) {
            val sortedCols = inferredColumns.sorted()
            val byColumn = numbers.groupBy { n -> sortedCols.minByOrNull { abs(it - n.centerX) } }
            val shares = byColumn[sortedCols[0]]?.firstOrNull()?.let { parseNumber(it.text) }
            val price = byColumn[sortedCols[1]]?.firstOrNull()?.let { parseNumber(it.text) }
            if (shares != null || price != null) return finish(shares, price)
        }

        // Last resort (e.g. a single isolated row): a `$`-prefixed number is
        // almost always a price; the first remaining plain number is shares.
        val dollarToken = numbers.firstOrNull { it.text.contains('$') }
        val price = dollarToken?.let { parseNumber(it.text) }
        val shares = numbers.firstOrNull { it !== dollarToken }?.let { parseNumber(it.text) }
        return finish(shares, price)
    }

    private fun nearestUnclaimed(numbers: List<OcrToken>, anchor: Float?, claimed: Set<OcrToken>): OcrToken? {
        if (anchor == null) return null
        return numbers.filterNot { it in claimed }.minByOrNull { abs(it.centerX - anchor) }
    }

    private fun finish(shares: Double?, price: Double?): Triple<Double?, Double?, ParseConfidence> {
        val confidence = if (shares != null && price != null) ParseConfidence.HIGH else ParseConfidence.LOW
        return Triple(shares, price, confidence)
    }

    private fun parseNumber(text: String): Double? {
        val negative = text.startsWith('(') || text.startsWith('-')
        val cleaned = text.trim('(', ')', '+', '-').replace("$", "").replace(",", "")
        val value = cleaned.toDoubleOrNull() ?: return null
        return if (negative) -value else value
    }

    private fun normalizeTicker(text: String): String = text.trim('$').uppercase()
}
