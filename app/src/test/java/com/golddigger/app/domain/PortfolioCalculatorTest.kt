package com.golddigger.app.domain

import com.golddigger.app.domain.model.GroupInput
import com.golddigger.app.domain.model.HoldingInput
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortfolioCalculatorTest {

    private val calculator = PortfolioCalculator()

    private fun holding(
        id: Long,
        ticker: String,
        shares: Double,
        costBasis: Double,
        price: Double?,
        dayChangePct: Double? = 0.0,
        sector: String? = null,
        isEtf: Boolean = false,
    ) = HoldingInput(
        holdingId = id,
        ticker = ticker,
        companyName = "$ticker Inc",
        sector = sector,
        shares = shares,
        costBasis = costBasis,
        price = price,
        dayChangePct = dayChangePct,
        priceUpdatedAt = 1_000L,
        isEtf = isEtf,
    )

    @Test
    fun `empty portfolio has zero totals`() {
        val summary = calculator.summarize(emptyList())
        assertThat(summary.totalMarketValue).isEqualTo(0.0)
        assertThat(summary.totalGainLoss).isEqualTo(0.0)
        assertThat(summary.holdings).isEmpty()
    }

    @Test
    fun `single holding gain is computed from price and cost basis`() {
        val summary = calculator.summarize(
            listOf(holding(1, "AAA", shares = 10.0, costBasis = 1_000.0, price = 150.0)),
        )
        assertThat(summary.totalMarketValue).isEqualTo(1_500.0)
        assertThat(summary.totalCostBasis).isEqualTo(1_000.0)
        assertThat(summary.totalGainLoss).isEqualTo(500.0)
        assertThat(summary.totalGainLossPct).isWithin(1e-9).of(50.0)
        assertThat(summary.holdings.single().avgCost).isEqualTo(100.0)
        assertThat(summary.holdings.single().portfolioWeightPct).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `unpriced holdings are excluded from totals but retained in the list`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AAA", shares = 10.0, costBasis = 1_000.0, price = 150.0),
                holding(2, "BBB", shares = 5.0, costBasis = 500.0, price = null),
            ),
        )
        assertThat(summary.holdingCount).isEqualTo(2)
        assertThat(summary.pricedHoldingCount).isEqualTo(1)
        assertThat(summary.hasUnpricedHoldings).isTrue()
        assertThat(summary.totalMarketValue).isEqualTo(1_500.0)
        // gain/loss compares only priced value against priced cost
        assertThat(summary.totalGainLoss).isEqualTo(500.0)
        assertThat(summary.holdings.first { it.ticker == "BBB" }.marketValue).isNull()
    }

    @Test
    fun `portfolio weights sum to 100 across priced holdings`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AAA", 10.0, 1_000.0, price = 100.0),
                holding(2, "BBB", 10.0, 1_000.0, price = 300.0),
            ),
        )
        val weightSum = summary.holdings.sumOf { it.portfolioWeightPct }
        assertThat(weightSum).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `day change value reflects each holding's percent move`() {
        val summary = calculator.summarize(
            listOf(holding(1, "AAA", shares = 10.0, costBasis = 900.0, price = 110.0, dayChangePct = 10.0)),
        )
        // prev value 1000, now 1100 -> +100
        assertThat(summary.dayChangeValue).isWithin(1e-6).of(100.0)
    }

    @Test
    fun `holdings are ordered by market value descending`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "SMALL", 1.0, 10.0, price = 10.0),
                holding(2, "BIG", 100.0, 10.0, price = 10.0),
            ),
        )
        assertThat(summary.holdings.map { it.ticker }).containsExactly("BIG", "SMALL").inOrder()
    }

    @Test
    fun `zero cost basis yields null gain percent, not a divide by zero`() {
        val summary = calculator.summarize(
            listOf(holding(1, "GIFT", shares = 10.0, costBasis = 0.0, price = 5.0)),
        )
        assertThat(summary.holdings.single().gainLossPct).isNull()
        assertThat(summary.holdings.single().gainLoss).isEqualTo(50.0)
    }

    @Test
    fun `group allocation and amount-to-target are computed`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AI1", 10.0, 100.0, price = 10.0),   // 100
                holding(2, "AI2", 10.0, 100.0, price = 10.0),   // 100
                holding(3, "OTHER", 10.0, 100.0, price = 80.0), // 800
            ),
        )
        // total = 1000, AI bucket = 200 -> 20%
        val allocations = calculator.allocations(
            listOf(GroupInput(1, "AI", targetAllocationPct = 50.0, tickers = setOf("AI1", "AI2"))),
            summary,
        )
        val ai = allocations.single()
        assertThat(ai.currentValue).isEqualTo(200.0)
        assertThat(ai.currentPct).isWithin(1e-9).of(20.0)
        // x = (50*1000 - 100*200) / (100 - 50) = (50000 - 20000) / 50 = 600
        assertThat(ai.amountToTarget).isWithin(1e-6).of(600.0)
    }

    @Test
    fun `overweight group reports a negative amount-to-target`() {
        val summary = calculator.summarize(
            listOf(holding(1, "X", 10.0, 100.0, price = 10.0)),
        )
        val allocations = calculator.allocations(
            listOf(GroupInput(1, "All-in", targetAllocationPct = 20.0, tickers = setOf("X"))),
            summary,
        )
        assertThat(allocations.single().amountToTarget!!).isLessThan(0.0)
    }

    @Test
    fun `an ETF-type group's percent is measured against total ETF value, not the whole portfolio`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AAPL", 10.0, 1_000.0, price = 100.0, isEtf = false), // 1000 stock
                holding(2, "VOO", 10.0, 1_000.0, price = 100.0, isEtf = true),   // 1000 ETF
                holding(3, "VTI", 10.0, 1_000.0, price = 100.0, isEtf = true),   // 1000 ETF
            ),
        )
        // Whole portfolio is 3000; ETF total alone is 2000.
        val allocations = calculator.allocations(
            listOf(GroupInput(1, "Core ETFs", targetAllocationPct = 50.0, tickers = setOf("VOO"), isEtfGroup = true)),
            summary,
        )
        val coreEtfs = allocations.single()
        assertThat(coreEtfs.currentValue).isEqualTo(1_000.0)
        // 1000 / 2000 (ETF total) = 50%, not 1000 / 3000 (portfolio total) = ~33.3%.
        assertThat(coreEtfs.currentPct).isWithin(1e-9).of(50.0)
    }

    @Test
    fun `a stocks-type group's percent excludes ETF value from its denominator`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AAPL", 10.0, 1_000.0, price = 100.0, isEtf = false), // 1000 stock
                holding(2, "MSFT", 10.0, 1_000.0, price = 100.0, isEtf = false), // 1000 stock
                holding(3, "VOO", 10.0, 1_000.0, price = 100.0, isEtf = true),   // 1000 ETF
            ),
        )
        val allocations = calculator.allocations(
            listOf(GroupInput(1, "Big Tech", targetAllocationPct = null, tickers = setOf("AAPL"), isEtfGroup = false)),
            summary,
        )
        // Stock total alone is 2000 (excludes the 1000 of ETF value): 1000 / 2000 = 50%.
        assertThat(allocations.single().currentPct).isWithin(1e-9).of(50.0)
    }

    @Test
    fun `a mismatched-type member does not inflate currentValue past its type's total`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AAPL", 10.0, 1_000.0, price = 100.0, isEtf = false), // 1000 stock
                holding(2, "VOO", 10.0, 1_000.0, price = 100.0, isEtf = true),   // 1000 ETF
            ),
        )
        // A stocks-type group that (incorrectly) also has an ETF ticker as a
        // member: the ETF's value must not count, since stockTotal (the
        // denominator) excludes it entirely.
        val allocations = calculator.allocations(
            listOf(GroupInput(1, "Big Tech", targetAllocationPct = null, tickers = setOf("AAPL", "VOO"), isEtfGroup = false)),
            summary,
        )
        val bigTech = allocations.single()
        assertThat(bigTech.currentValue).isEqualTo(1_000.0)
        assertThat(bigTech.currentPct).isWithin(1e-9).of(100.0)
    }

    @Test
    fun `a stock in two groups counts toward both`() {
        val summary = calculator.summarize(
            listOf(holding(1, "NVDA", 10.0, 100.0, price = 10.0)),
        )
        val allocations = calculator.allocations(
            listOf(
                GroupInput(1, "AI", 50.0, setOf("NVDA")),
                GroupInput(2, "Semis", 30.0, setOf("NVDA")),
            ),
            summary,
        )
        assertThat(allocations.map { it.currentValue }).containsExactly(100.0, 100.0)
    }

    @Test
    fun `a cash position (price 1, cost equals shares) contributes value but no gain`() {
        val summary = calculator.summarize(
            listOf(
                holding(1, "AAA", shares = 10.0, costBasis = 1_000.0, price = 150.0), // 1500
                holding(2, "\$CASH", shares = 500.0, costBasis = 500.0, price = 1.0),  // 500
            ),
        )
        assertThat(summary.totalMarketValue).isEqualTo(2_000.0)
        val cash = summary.holdings.first { it.ticker == "\$CASH" }
        assertThat(cash.marketValue).isEqualTo(500.0)
        assertThat(cash.gainLoss).isEqualTo(0.0)
        assertThat(cash.portfolioWeightPct).isWithin(1e-9).of(25.0)
    }

    @Test
    fun `calculator scales to hundreds of holdings`() {
        val many = (1..500).map { holding(it.toLong(), "T$it", shares = 1.0, costBasis = 1.0, price = 2.0) }
        val summary = calculator.summarize(many)
        assertThat(summary.holdingCount).isEqualTo(500)
        assertThat(summary.totalMarketValue).isEqualTo(1_000.0)
        assertThat(summary.holdings.sumOf { it.portfolioWeightPct }).isWithin(1e-6).of(100.0)
    }
}
