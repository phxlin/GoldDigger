package com.golddigger.app.domain

import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.domain.model.GroupInput
import com.golddigger.app.domain.model.HoldingInput
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import javax.inject.Inject

/**
 * All monetary math for the app lives here — pure functions, no Android, no
 * coroutines, no I/O — so correctness can be pinned down with unit tests
 * independent of how many holdings or groups exist. ViewModels call this; they
 * never do arithmetic themselves.
 *
 * Holdings without a known price are kept in the output (so the UI can show them
 * with a "no price yet" state) but are excluded from value and gain/loss totals.
 */
class PortfolioCalculator @Inject constructor() {

    fun summarize(holdings: List<HoldingInput>): PortfolioSummary {
        val priced = holdings.filter { it.price != null && it.price > 0.0 }
        val totalMarketValue = priced.sumOf { it.price!! * it.shares }
        val totalCostAll = holdings.sumOf { it.costBasis }
        val totalCostPriced = priced.sumOf { it.costBasis }
        val totalGainLoss = totalMarketValue - totalCostPriced
        val totalGainLossPct =
            if (totalCostPriced > 0.0) totalGainLoss / totalCostPriced * 100 else 0.0

        val dayChangeValue = priced.sumOf { h ->
            val mv = h.price!! * h.shares
            val pct = h.dayChangePct ?: 0.0
            if (pct <= -100.0) 0.0 else mv - mv / (1 + pct / 100.0)
        }

        val valuations = holdings.map { h ->
            val marketValue = h.price?.takeIf { it > 0.0 }?.let { it * h.shares }
            val gainLoss = marketValue?.let { it - h.costBasis }
            HoldingValuation(
                holdingId = h.holdingId,
                ticker = h.ticker,
                companyName = h.companyName,
                sector = h.sector,
                shares = h.shares,
                costBasis = h.costBasis,
                avgCost = if (h.shares > 0.0) h.costBasis / h.shares else 0.0,
                price = h.price,
                dayChangePct = h.dayChangePct,
                priceUpdatedAt = h.priceUpdatedAt,
                isEtf = h.isEtf,
                marketValue = marketValue,
                gainLoss = gainLoss,
                gainLossPct = gainLoss?.let {
                    if (h.costBasis > 0.0) it / h.costBasis * 100 else null
                },
                portfolioWeightPct =
                    if (totalMarketValue > 0.0 && marketValue != null) {
                        marketValue / totalMarketValue * 100
                    } else {
                        0.0
                    },
            )
        }.sortedByDescending { it.marketValue ?: -1.0 }

        return PortfolioSummary(
            totalMarketValue = totalMarketValue,
            totalCostBasis = totalCostAll,
            totalGainLoss = totalGainLoss,
            totalGainLossPct = totalGainLossPct,
            dayChangeValue = dayChangeValue,
            holdingCount = holdings.size,
            pricedHoldingCount = priced.size,
            holdings = valuations,
        )
    }

    fun allocations(
        groups: List<GroupInput>,
        summary: PortfolioSummary,
    ): List<GroupAllocation> {
        val valueByTicker = summary.holdings
            .groupBy { it.ticker }
            .mapValues { (_, rows) -> rows.sumOf { it.marketValue ?: 0.0 } }
        val isEtfByTicker = summary.holdings.associate { it.ticker to it.isEtf }
        // A group's target/current % is measured against its own type's total
        // (all ETF value, or all individual-stock value), not the whole
        // portfolio — so an ETF-type group and a stocks-type group each have
        // their own independent 100% to be a share of.
        val etfTotal = summary.holdings.filter { it.isEtf }.sumOf { it.marketValue ?: 0.0 }
        val stockTotal = summary.holdings.filterNot { it.isEtf }.sumOf { it.marketValue ?: 0.0 }

        return groups.map { g ->
            val total = if (g.isEtfGroup) etfTotal else stockTotal
            // A member whose own isEtf no longer matches the group's type
            // (e.g. reclassified after being added, or added before the
            // membership screen restricted by type) doesn't count toward
            // this group's value — otherwise it would inflate currentValue
            // against a denominator (etfTotal/stockTotal) that excludes it,
            // pushing currentPct past 100%.
            val currentValue = g.tickers
                .filter { isEtfByTicker[it] == g.isEtfGroup }
                .sumOf { valueByTicker[it] ?: 0.0 }
            val currentPct = if (total > 0.0) currentValue / total * 100 else 0.0
            val amountToTarget = g.targetAllocationPct?.let { t ->
                // total == 0 means the user holds nothing of this type at all
                // (group or otherwise) — the formula below assumes there's an
                // existing type-total to be a fraction of, and is singular at
                // zero (it would return 0 regardless of t, which reads as
                // "already on target" while currentPct simultaneously and
                // correctly shows 0%). Null matches how the UI already treats
                // "not computable" for the t<=0/t>=100 cases below.
                if (t <= 0.0 || t >= 100.0 || total <= 0.0) {
                    null
                } else {
                    (t * total - 100 * currentValue) / (100 - t)
                }
            }
            GroupAllocation(
                groupId = g.groupId,
                name = g.name,
                targetPct = g.targetAllocationPct,
                currentValue = currentValue,
                currentPct = currentPct,
                amountToTarget = amountToTarget,
                tickers = g.tickers.sorted(),
                isEtfGroup = g.isEtfGroup,
            )
        }
    }
}
