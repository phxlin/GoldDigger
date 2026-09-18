package com.golddigger.app.core

/**
 * Cash is modelled as an ordinary holding with a synthetic, non-tradable ticker
 * so it flows through [com.golddigger.app.domain.PortfolioCalculator], the pie
 * chart and the group buckets with no special cases in the math. Its price is
 * pinned to 1.0 and it is never sent to the price provider.
 *
 * `shares` holds the dollar balance and `costBasis` mirrors it (so gain/loss is
 * always zero).
 */
object CashHolding {
    const val TICKER = "\$CASH"
    const val NAME = "Cash"
    const val SECTOR = "Cash"

    fun isCashTicker(ticker: String): Boolean = ticker.equals(TICKER, ignoreCase = true)
}
