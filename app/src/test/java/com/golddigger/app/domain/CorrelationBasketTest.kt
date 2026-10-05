package com.golddigger.app.domain

import com.golddigger.app.domain.model.HoldingValuation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorrelationBasketTest {

    private fun holding(
        ticker: String,
        sector: String?,
        value: Double,
        isEtf: Boolean = false,
    ) = HoldingValuation(
        holdingId = ticker.hashCode().toLong(),
        ticker = ticker,
        companyName = "$ticker Inc",
        sector = sector,
        shares = 1.0,
        costBasis = value,
        avgCost = value,
        price = value,
        dayChangePct = 0.0,
        priceUpdatedAt = 1L,
        marketValue = value,
        gainLoss = 0.0,
        gainLossPct = 0.0,
        portfolioWeightPct = 0.0,
        isEtf = isEtf,
    )

    @Test
    fun `the largest real sector by value wins`() {
        val basket = CorrelationBasket.tickers(
            listOf(
                holding("A1", "Energy", 100.0),
                holding("A2", "Energy", 100.0),
                holding("B1", "Health", 150.0),
                holding("B2", "Health", 150.0),
                holding("B3", "Health", 10.0),
            ),
        )
        assertThat(basket).containsExactly("B1", "B2", "B3")
    }

    @Test
    fun `funds never count toward the group, however big they are`() {
        val basket = CorrelationBasket.tickers(
            listOf(
                holding("FUND1", "Fixed Income", 5_000.0, isEtf = true),
                holding("FUND2", "Fixed Income", 5_000.0, isEtf = true),
                holding("S1", "Energy", 10.0),
                holding("S2", "Energy", 10.0),
            ),
        )
        assertThat(basket).containsExactly("S1", "S2")
    }

    @Test
    fun `unlabelled stocks and cash do not form a group`() {
        val basket = CorrelationBasket.tickers(
            listOf(
                holding("U1", null, 1_000.0),
                holding("U2", "  ", 1_000.0),
                holding("\$CASH", "Cash", 9_000.0),
                holding("S1", "Energy", 10.0),
            ),
        )
        assertThat(basket).isEmpty()
    }

    @Test
    fun `a lone name in the top sector gives no group`() {
        val basket = CorrelationBasket.tickers(
            listOf(
                holding("BIG", "Energy", 1_000.0),
                holding("X1", "Health", 10.0),
                holding("X2", "Health", 10.0),
            ),
        )
        assertThat(basket).isEmpty()
    }

    @Test
    fun `an all-fund portfolio has no group`() {
        val basket = CorrelationBasket.tickers(
            listOf(
                holding("VTI", null, 100.0, isEtf = true),
                holding("BND", "Fixed Income", 100.0, isEtf = true),
            ),
        )
        assertThat(basket).isEmpty()
    }
}
