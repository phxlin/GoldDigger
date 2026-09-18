package com.golddigger.app.data.repository

import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.domain.model.Formation
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import com.golddigger.app.domain.model.PricePoint
import com.golddigger.app.data.local.entity.GroupEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the UI. Network data always lands in Room first;
 * every `observe*` stream below is backed by Room, so screens keep working
 * offline and update automatically after a sync.
 */
interface PortfolioRepository {

    val holdingCount: Flow<Int>
    val syncState: StateFlow<SyncState>

    fun observePortfolio(): Flow<PortfolioSummary>
    fun observeHoldingValuation(holdingId: Long): Flow<HoldingValuation?>

    /**
     * [ticker]'s recorded price history at or after [sinceEpochMs] (0 = all
     * retained history), oldest first. Backs the Holding Detail range
     * selector — each [com.golddigger.app.domain.model.PriceRange] resolves
     * to a lower-bound timestamp before calling this.
     */
    fun observePriceHistory(ticker: String, sinceEpochMs: Long): Flow<List<PricePoint>>

    /** Cached company news for [ticker], newest first. Room-backed (works offline). */
    fun observeNews(ticker: String): Flow<List<NewsArticle>>

    /**
     * Fetch company news into the cache. No-op for cash and (unless [force]) when
     * the cache was refreshed within the TTL. Throttled and rate-limit aware.
     */
    suspend fun refreshNews(ticker: String, force: Boolean = false): Result<Unit>

    /**
     * The Soccer Formation view: holdings mapped onto pitch roles by their risk
     * metrics, with zone sizing and "gap" insights. Room-backed and recomputed
     * whenever holdings, prices, risk metrics or manual overrides change.
     */
    fun observeFormation(): Flow<Formation>

    /**
     * Refresh per-holding risk metrics (beta from the provider, dominant-sector
     * correlation from accumulated price history). Respects
     * [com.golddigger.app.core.FormationConfig.METRICS_TTL] unless [force].
     * Throttled and rate-limit aware.
     */
    suspend fun refreshRiskMetrics(force: Boolean = false): Result<Unit>

    /** Set (or clear, with null) the manual Formation role for a holding. */
    suspend fun setRoleOverride(holdingId: Long, role: FormationRole?)

    fun observeGroups(): Flow<List<GroupEntity>>
    fun observeGroupAllocations(): Flow<List<GroupAllocation>>
    fun observeGroupIdsForTicker(ticker: String): Flow<List<Long>>

    suspend fun searchSymbols(query: String): Result<List<SymbolSearchResult>>

    /**
     * Add [shares] of [ticker] at a total [costBasis]. If [ticker] is already
     * held, this merges into that position — shares and cost basis both sum,
     * so the average cost updates itself — rather than creating a second lot
     * for the same stock (the same behaviour [addCash] already has for the
     * synthetic cash position).
     */
    suspend fun addHolding(
        ticker: String,
        companyName: String?,
        sector: String?,
        shares: Double,
        costBasis: Double,
    ): Long

    suspend fun updateHolding(holdingId: Long, shares: Double, costBasis: Double)
    suspend fun deleteHolding(holdingId: Long)

    /**
     * Reclassify [ticker] as an ETF ([isEtf] true) or an individual stock
     * (false). Independent of [addHolding]/[updateHolding] so correcting the
     * classification never re-triggers a profile lookup or touches shares/cost.
     * No-op if [ticker] isn't tracked yet.
     */
    suspend fun setInstrumentType(ticker: String, isEtf: Boolean)

    /**
     * Add [amount] dollars to the cash position (creating it if absent). Cash is
     * a synthetic holding priced at 1.0 and is never sent to the price provider.
     */
    suspend fun addCash(amount: Double)

    /**
     * Create a group. [isEtfGroup] determines what its allocation % is a
     * share of — total ETF value if true, else total individual-stock value.
     */
    suspend fun createGroup(name: String, targetAllocationPct: Double?, isEtfGroup: Boolean = false): Long
    suspend fun updateGroup(group: GroupEntity)
    suspend fun deleteGroup(groupId: Long)
    suspend fun setGroupsForHolding(ticker: String, groupIds: Set<Long>)
    suspend fun addTickerToGroup(ticker: String, groupId: Long)
    suspend fun removeTickerFromGroup(ticker: String, groupId: Long)

    /**
     * Refresh quotes for every held ticker. Respects the cache freshness window
     * unless [force] is set, batches into the fewest calls the provider allows,
     * and never exceeds the configured rate limit. Safe to call concurrently —
     * overlapping calls coalesce.
     */
    suspend fun refreshPrices(force: Boolean): SyncState
}
