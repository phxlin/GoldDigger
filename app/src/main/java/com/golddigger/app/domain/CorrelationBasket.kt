package com.golddigger.app.domain

import com.golddigger.app.core.CashHolding
import com.golddigger.app.domain.model.HoldingValuation

/**
 * The "dominant sector" a stock's correlation is measured against: the largest
 * sector, by value, among individual stocks that have a real sector label.
 *
 * Funds are left out on purpose. Broad index ETFs carry no sector, so they pile
 * into one unlabelled bucket that is really "the market" — measuring anything
 * against it just reports that everything moves with the market, and puts
 * diversified funds and even bond funds in Attack. Cash and unlabelled stocks
 * are left out for the same reason.
 */
object CorrelationBasket {

    /** Tickers to correlate against, or empty when no real sector has two or more names. */
    fun tickers(holdings: List<HoldingValuation>): Set<String> {
        val candidates = holdings.filter {
            !CashHolding.isCashTicker(it.ticker) && !it.isEtf && !it.sector.isNullOrBlank()
        }
        val top = candidates
            .groupBy { it.sector }
            .values
            .maxByOrNull { rows -> rows.sumOf { it.marketValue ?: 0.0 } }
        return top?.takeIf { it.size >= 2 }?.map { it.ticker }?.toSet().orEmpty()
    }
}
