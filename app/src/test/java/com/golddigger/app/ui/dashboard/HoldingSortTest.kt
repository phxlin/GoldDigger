package com.golddigger.app.ui.dashboard

import com.golddigger.app.core.CashHolding
import com.golddigger.app.domain.model.HoldingValuation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HoldingSortTest {

    private fun holding(
        ticker: String,
        price: Double?,
        marketValue: Double?,
    ) = HoldingValuation(
        holdingId = ticker.hashCode().toLong(),
        ticker = ticker,
        companyName = "$ticker Inc",
        sector = null,
        shares = 1.0,
        costBasis = 1.0,
        avgCost = 1.0,
        price = price,
        dayChangePct = 0.0,
        priceUpdatedAt = price?.let { 1L },
        marketValue = marketValue,
        gainLoss = marketValue?.let { it - 1.0 },
        gainLossPct = 0.0,
        portfolioWeightPct = 0.0,
    )

    private val aapl = holding("AAPL", price = 200.0, marketValue = 2_000.0)
    private val tsla = holding("TSLA", price = 350.0, marketValue = 700.0)
    private val cash = holding(CashHolding.TICKER, price = 1.0, marketValue = 5_000.0)
    private val newbie = holding("NEW", price = null, marketValue = null)

    private val all = listOf(aapl, tsla, cash, newbie)

    private fun sort(key: HoldingSortKey, ascending: Boolean) =
        sortHoldings(all, HoldingSort(key, ascending)).map { it.ticker }

    @Test
    fun `value descending puts the biggest position first, unpriced last`() {
        assertThat(sort(HoldingSortKey.VALUE, ascending = false))
            .containsExactly(CashHolding.TICKER, "AAPL", "TSLA", "NEW").inOrder()
    }

    @Test
    fun `value ascending keeps unpriced at the bottom, not the top`() {
        assertThat(sort(HoldingSortKey.VALUE, ascending = true))
            .containsExactly("TSLA", "AAPL", CashHolding.TICKER, "NEW").inOrder()
    }

    @Test
    fun `price sort ignores position size and drops unpriced last`() {
        assertThat(sort(HoldingSortKey.PRICE, ascending = false))
            .containsExactly("TSLA", "AAPL", CashHolding.TICKER, "NEW").inOrder()
    }

    @Test
    fun `symbol sort is alphabetical and shows cash as "Cash"`() {
        // ascending: Cash, AAPL... -> "cash" > "aapl", so AAPL first
        assertThat(sort(HoldingSortKey.SYMBOL, ascending = true))
            .containsExactly("AAPL", CashHolding.TICKER, "NEW", "TSLA").inOrder()
        assertThat(sort(HoldingSortKey.SYMBOL, ascending = false))
            .containsExactly("TSLA", "NEW", CashHolding.TICKER, "AAPL").inOrder()
    }
}
