package com.golddigger.app.util

import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.data.repository.SyncState
import com.golddigger.app.domain.model.Formation
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import com.golddigger.app.domain.model.PricePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Hand-controlled [PortfolioRepository] for UI tests. */
@Singleton
class FakePortfolioRepository @Inject constructor() : PortfolioRepository {

    val portfolio = MutableStateFlow(
        PortfolioSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, emptyList()),
    )
    val groups = MutableStateFlow<List<GroupEntity>>(emptyList())
    val allocations = MutableStateFlow<List<GroupAllocation>>(emptyList())
    val formation = MutableStateFlow(
        Formation(
            zones = emptyList(),
            bench = emptyList(),
            insights = emptyList(),
            dominantSectorLabel = null,
            totalValue = 0.0,
            nonCashValue = 0.0,
        ),
    )
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)

    override val holdingCount: Flow<Int> = portfolio.map { it.holdingCount }
    override val syncState = _syncState.asStateFlow()

    override fun observePortfolio(): Flow<PortfolioSummary> = portfolio
    override fun observeHoldingValuation(holdingId: Long): Flow<HoldingValuation?> =
        portfolio.map { s -> s.holdings.firstOrNull { it.holdingId == holdingId } }
    override fun observePriceHistory(ticker: String, sinceEpochMs: Long): Flow<List<PricePoint>> =
        MutableStateFlow(emptyList())
    override fun observeNews(ticker: String): Flow<List<NewsArticle>> =
        MutableStateFlow(emptyList())
    override suspend fun refreshNews(ticker: String, force: Boolean): Result<Unit> =
        Result.success(Unit)
    override fun observeFormation(): Flow<Formation> = formation
    override suspend fun refreshRiskMetrics(force: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun setRoleOverride(holdingId: Long, role: FormationRole?) = Unit

    override fun observeGroups(): Flow<List<GroupEntity>> = groups
    override fun observeGroupAllocations(): Flow<List<GroupAllocation>> = allocations
    override fun observeGroupIdsForTicker(ticker: String): Flow<List<Long>> =
        MutableStateFlow(emptyList())

    override suspend fun searchSymbols(query: String): Result<List<SymbolSearchResult>> =
        Result.success(emptyList())
    override suspend fun addHolding(
        ticker: String,
        companyName: String?,
        sector: String?,
        shares: Double,
        costBasis: Double,
    ): Long = 1L
    override suspend fun updateHolding(holdingId: Long, shares: Double, costBasis: Double) = Unit
    override suspend fun setInstrumentType(ticker: String, isEtf: Boolean) = Unit
    override suspend fun addCash(amount: Double) = Unit
    override suspend fun deleteHolding(holdingId: Long) = Unit
    override suspend fun createGroup(name: String, targetAllocationPct: Double?, isEtfGroup: Boolean): Long = 1L
    override suspend fun updateGroup(group: GroupEntity) = Unit
    override suspend fun deleteGroup(groupId: Long) = Unit
    override suspend fun setGroupsForHolding(ticker: String, groupIds: Set<Long>) = Unit
    override suspend fun addTickerToGroup(ticker: String, groupId: Long) = Unit
    override suspend fun removeTickerFromGroup(ticker: String, groupId: Long) = Unit
    override suspend fun refreshPrices(force: Boolean): SyncState = _syncState.value
}
