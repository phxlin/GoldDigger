package com.golddigger.app.domain.model

/** Input to [com.golddigger.app.domain.PortfolioCalculator]. UI-free. */
data class HoldingInput(
    val holdingId: Long,
    val ticker: String,
    val companyName: String,
    val sector: String?,
    val shares: Double,
    /** Total dollars paid for the position. */
    val costBasis: Double,
    /** Latest known price, or null if never fetched. */
    val price: Double?,
    val dayChangePct: Double?,
    val priceUpdatedAt: Long?,
    val isEtf: Boolean = false,
)

data class HoldingValuation(
    val holdingId: Long,
    val ticker: String,
    val companyName: String,
    val sector: String?,
    val shares: Double,
    val costBasis: Double,
    val avgCost: Double,
    val price: Double?,
    val dayChangePct: Double?,
    val priceUpdatedAt: Long?,
    /** null when no price is known yet. */
    val marketValue: Double?,
    val gainLoss: Double?,
    val gainLossPct: Double?,
    /** Share of total *priced* portfolio value, 0..100. */
    val portfolioWeightPct: Double,
    val isEtf: Boolean = false,
)

data class PortfolioSummary(
    val totalMarketValue: Double,
    val totalCostBasis: Double,
    val totalGainLoss: Double,
    val totalGainLossPct: Double,
    val dayChangeValue: Double,
    val holdingCount: Int,
    /** How many holdings actually have a price (rest are excluded from value). */
    val pricedHoldingCount: Int,
    val holdings: List<HoldingValuation>,
) {
    val hasUnpricedHoldings: Boolean get() = pricedHoldingCount < holdingCount
}

data class GroupInput(
    val groupId: Long,
    val name: String,
    val targetAllocationPct: Double?,
    val tickers: Set<String>,
    /** True if this bucket tracks ETFs; false for individual stocks. See [GroupAllocation.isEtfGroup]. */
    val isEtfGroup: Boolean = false,
)

data class GroupAllocation(
    val groupId: Long,
    val name: String,
    val targetPct: Double?,
    val currentValue: Double,
    /**
     * [currentValue] as a percentage of that *type's* total — total ETF value
     * if [isEtfGroup], else total individual-stock value — not the whole
     * portfolio. An ETF-type group and a stocks-type group are each measured
     * against their own 100%.
     */
    val currentPct: Double,
    /**
     * Dollars that would need to be added to this bucket (nothing else changing)
     * to reach [targetPct]. Null when no target set; negative means overweight.
     */
    val amountToTarget: Double?,
    val tickers: List<String>,
    val isEtfGroup: Boolean = false,
)
