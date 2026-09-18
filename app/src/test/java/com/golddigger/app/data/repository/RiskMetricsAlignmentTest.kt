package com.golddigger.app.data.repository

import com.golddigger.app.data.local.dao.GroupDao
import com.golddigger.app.data.local.dao.HoldingDao
import com.golddigger.app.data.local.dao.HoldingRoleOverride
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.local.dao.StockDao
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.local.relation.GroupWithStocks
import com.golddigger.app.data.local.relation.HoldingRow
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.remote.RemoteQuote
import com.golddigger.app.data.remote.StockMetrics
import com.golddigger.app.data.remote.StockPriceApi
import com.golddigger.app.data.remote.StockProfile
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.remote.throttle.RequestThrottler
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.data.settings.SyncSettings
import com.golddigger.app.domain.FormationClassifier
import com.golddigger.app.domain.PortfolioCalculator
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regression coverage for the risk-metrics timestamp-alignment fix: a
 * holding's return series and its sector-correlation basket's return series
 * used to be built from two *independently* intersected timestamp sets, so a
 * ticker held over a different date range than its basket-mates could end up
 * paired index-for-index against the wrong calendar days. [sectorCorrelation]
 * (exercised here through the public [PortfolioRepositoryImpl.refreshRiskMetrics])
 * now builds one shared timestamp axis up front and evaluates both series on
 * it, so this only reads the timestamps every involved ticker actually has.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RiskMetricsAlignmentTest {

    private val holdingRows = MutableStateFlow<List<HoldingRow>>(emptyList())
    private val points = mutableListOf<PricePointEntity>()
    private val stocks = mutableMapOf<String, StockEntity>()
    private val riskUpdates = mutableListOf<Triple<String, Double?, Double?>>()

    private val holdingDao = object : HoldingDao {
        override suspend fun insert(holding: com.golddigger.app.data.local.entity.HoldingEntity) = 0L
        override suspend fun update(holding: com.golddigger.app.data.local.entity.HoldingEntity) = Unit
        override suspend fun delete(holding: com.golddigger.app.data.local.entity.HoldingEntity) = Unit
        override suspend fun findById(id: Long) = null
        override suspend fun findByTicker(ticker: String) = null
        override fun observeHoldingRows(): Flow<List<HoldingRow>> = holdingRows
        override fun observeHoldingRow(id: Long) = MutableStateFlow<HoldingRow?>(null)
        override fun observeTotalMarketValue(): Flow<Double?> = MutableStateFlow(null)
        override suspend fun distinctTickers(): List<String> = holdingRows.value.map { it.ticker }.distinct()
        override fun observeCount(): Flow<Int> = MutableStateFlow(holdingRows.value.size)
        override fun observeRoleOverrides(): Flow<List<HoldingRoleOverride>> = MutableStateFlow(emptyList())
        override suspend fun setRoleOverride(id: Long, role: String?) = Unit
    }

    private val priceDao = object : PriceDao {
        override suspend fun upsert(price: PriceCacheEntity) = Unit
        override suspend fun upsertAll(prices: List<PriceCacheEntity>) = Unit
        override suspend fun find(ticker: String): PriceCacheEntity? = null
        override suspend fun all(): List<PriceCacheEntity> = emptyList()
        override fun observeAll(): Flow<List<PriceCacheEntity>> = MutableStateFlow(emptyList())
        override suspend fun oldestUpdateAmong(tickers: List<String>): Long? = null
        override suspend fun insertPoint(point: PricePointEntity) = Unit
        override suspend fun insertPoints(points2: List<PricePointEntity>) = Unit
        override fun observeRecentPoints(ticker: String, limit: Int): Flow<List<PricePointEntity>> =
            MutableStateFlow(emptyList())
        override fun observePointsSince(ticker: String, sinceEpochMs: Long): Flow<List<PricePointEntity>> =
            MutableStateFlow(emptyList())
        override fun observeAllPoints(): Flow<List<PricePointEntity>> = MutableStateFlow(emptyList())
        override suspend fun allPoints(): List<PricePointEntity> = points.toList()
        override suspend fun recentPointsAsc(ticker: String, limit: Int): List<PricePointEntity> = emptyList()
        override suspend fun trimHistory(keep: Int) = Unit
        override suspend fun pruneOrphans() = Unit
        override suspend fun prunePointOrphans() = Unit
    }

    private val stockDao = object : StockDao {
        override suspend fun upsert(stock: StockEntity) { stocks[stock.ticker] = stock }
        override suspend fun findByTicker(ticker: String): StockEntity? = stocks[ticker]
        override fun observeAll(): Flow<List<StockEntity>> = MutableStateFlow(stocks.values.toList())
        override suspend fun allTickers(): List<String> = stocks.keys.toList()
        override suspend fun all(): List<StockEntity> = stocks.values.toList()
        override suspend fun insertIfAbsent(stock: StockEntity) {
            stocks.putIfAbsent(stock.ticker, stock)
        }
        override suspend fun updateRisk(ticker: String, beta: Double?, sectorCorrelation: Double?, updatedAt: Long) {
            riskUpdates += Triple(ticker, beta, sectorCorrelation)
            stocks[ticker]?.let { stocks[ticker] = it.copy(beta = beta, sectorCorrelation = sectorCorrelation, riskUpdatedAt = updatedAt) }
        }
        override suspend fun updateInstrumentType(ticker: String, isEtf: Boolean) = Unit
        override suspend fun pruneOrphans() = Unit
    }

    private val groupDao = object : GroupDao {
        override suspend fun insert(group: GroupEntity) = 1L
        override suspend fun update(group: GroupEntity) = Unit
        override suspend fun delete(group: GroupEntity) = Unit
        override fun observeGroups(): Flow<List<GroupEntity>> = MutableStateFlow(emptyList())
        override fun observeGroupsWithStocks(): Flow<List<GroupWithStocks>> = MutableStateFlow(emptyList())
        override fun observeGroupWithStocks(id: Long): Flow<GroupWithStocks?> = MutableStateFlow(null)
        override suspend fun addStockToGroup(ref: StockGroupCrossRef) = Unit
        override suspend fun removeStockFromGroup(ref: StockGroupCrossRef) = Unit
        override fun observeGroupIdsForTicker(ticker: String): Flow<List<Long>> = MutableStateFlow(emptyList())
        override suspend fun clearGroupsForTicker(ticker: String) = Unit
    }

    private val newsDao = mockk<NewsDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()

    private fun row(ticker: String, sector: String, shares: Double, price: Double) = HoldingRow(
        id = ticker.hashCode().toLong(),
        ticker = ticker,
        shares = shares,
        costBasis = shares * price,
        dateAdded = 0,
        companyName = "$ticker Inc",
        sector = sector,
        price = price,
        dayChangePct = 0.0,
        priceUpdatedAt = 1L,
    )

    private fun repository(): PortfolioRepositoryImpl {
        every { settingsRepository.settings } returns MutableStateFlow(
            SyncSettings(
                refreshInterval = kotlin.time.Duration.parse("15m"),
                batchSize = 1,
                backgroundSyncEnabled = true,
                marketHoursOnly = false,
            ),
        )
        val api = object : StockPriceApi {
            override val maxSymbolsPerQuoteRequest = 1
            override suspend fun searchSymbols(query: String): List<SymbolSearchResult> = emptyList()
            override suspend fun fetchProfile(ticker: String): StockProfile? = null
            override suspend fun fetchMetrics(ticker: String): StockMetrics = StockMetrics(beta = null)
            override suspend fun fetchQuotes(tickers: List<String>): List<RemoteQuote> = emptyList()
            override suspend fun fetchCompanyNews(ticker: String, fromEpochDay: Long, toEpochDay: Long): List<NewsArticle> = emptyList()
        }
        return PortfolioRepositoryImpl(
            holdingDao = holdingDao,
            stockDao = stockDao,
            groupDao = groupDao,
            priceDao = priceDao,
            newsDao = newsDao,
            api = api,
            throttler = RequestThrottler(maxPermits = 100, windowMillis = 1000, clock = { 0L }, sleep = {}),
            settingsRepository = settingsRepository,
            calculator = PortfolioCalculator(),
            formationClassifier = FormationClassifier(),
            io = UnconfinedTestDispatcher(),
            clock = { 100_000L },
        )
    }

    /**
     * AAA and BBB are the portfolio's only two (same-sector) holdings. AAA
     * has price points for t=1..20; BBB only starts syncing later, t=6..25 —
     * the two series only truly overlap on t=6..20 (15 points). Over exactly
     * that shared window BBB's price is set to double AAA's; outside it each
     * ticker's price is unrelated to the other's, so pairing by raw list
     * index instead of shared timestamps (the bug) pulls in mismatched days
     * for both the correlation estimate (AAA vs. its one basket-mate, BBB)
     * and the fallback-beta estimate (AAA vs. the whole non-cash portfolio,
     * i.e. AAA+BBB weighted by shares).
     */
    private fun aaa(t: Int) = 100.0 + 3 * t + 5 * (t % 3)

    private fun seedOverlappingTickers() {
        for (t in 1..20) points += PricePointEntity(ticker = "AAA", price = aaa(t), timestamp = t.toLong())
        for (t in 6..20) points += PricePointEntity(ticker = "BBB", price = 2 * aaa(t), timestamp = t.toLong())
        for (t in 21..25) points += PricePointEntity(ticker = "BBB", price = 9_999.0, timestamp = t.toLong())

        stocks["AAA"] = StockEntity("AAA", "AAA Inc", sector = "Tech")
        stocks["BBB"] = StockEntity("BBB", "BBB Inc", sector = "Tech")
        holdingRows.value = listOf(
            row("AAA", "Tech", shares = 1.0, price = aaa(20)),
            row("BBB", "Tech", shares = 1.0, price = 9_999.0),
        )
    }

    @Test
    fun `correlation is computed over the timestamps both tickers actually share, not by raw list index`() = runTest {
        // Over the shared window BBB = 2*AAA, so their per-period returns are
        // identical and correlation must come out as exactly 1.0. Pairing by
        // raw list index instead of shared timestamps (the bug) would pull
        // correlation away from 1.0.
        seedOverlappingTickers()

        repository().refreshRiskMetrics(force = true)

        val aaaCorrelation = riskUpdates.first { it.first == "AAA" }.third
        assertThat(aaaCorrelation).isNotNull()
        assertThat(aaaCorrelation!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `fallback beta is computed over the timestamps asset and market actually share, not by raw list index`() = runTest {
        // The (self-inclusive, share-weighted) market series here is
        // AAA(t) + BBB(t) = AAA(t) + 2*AAA(t) = 3*AAA(t) at every shared
        // timestamp — a constant multiple of AAA's own series. Percent
        // returns are scale-invariant, so the market's returns equal AAA's
        // own returns exactly, making beta = cov(A,A)/var(A) = 1.0 *only if*
        // both series are evaluated on the same 15-point shared window. The
        // old bug evaluated the asset over its own full 20-point range and
        // the market over the separately-intersected 15-point range, then
        // zipped whatever came out by index regardless of length — for this
        // non-constant-return series that does not also land on 1.0.
        seedOverlappingTickers()

        repository().refreshRiskMetrics(force = true)

        val aaaBeta = riskUpdates.first { it.first == "AAA" }.second
        assertThat(aaaBeta).isNotNull()
        assertThat(aaaBeta!!).isWithin(1e-9).of(1.0)
    }
}
