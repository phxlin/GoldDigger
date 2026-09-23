package com.golddigger.app.data.repository

import com.golddigger.app.data.UserDataLock
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
import com.golddigger.app.data.remote.finnhub.FinnhubMetric
import com.golddigger.app.data.remote.finnhub.FinnhubMetricResponse
import com.golddigger.app.data.remote.finnhub.FinnhubService
import com.golddigger.app.data.remote.finnhub.FinnhubStockPriceApi
import com.golddigger.app.data.remote.throttle.RequestThrottler
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.data.settings.SyncSettings
import com.golddigger.app.domain.FormationClassifier
import com.golddigger.app.domain.PortfolioCalculator
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Exercises the beta-provenance fix end to end through the real
 * [FinnhubStockPriceApi] (only [FinnhubService], the Retrofit interface, is
 * mocked) rather than a fake [com.golddigger.app.data.remote.StockPriceApi]
 * that simply throws on command. This is what actually proves the fix: a
 * fake that already throws on cue can't catch a real adapter that instead
 * *swallows* the failure into a benign-looking empty response, which is
 * exactly the bug `FinnhubStockPriceApiTest` and this file were added for.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FinnhubBetaProvenanceIntegrationTest {

    private val holdingRows = MutableStateFlow<List<HoldingRow>>(emptyList())
    private val points = mutableListOf<PricePointEntity>()
    private val stocks = mutableMapOf<String, StockEntity>()
    private val finnhubService = mockk<FinnhubService>()

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
        override suspend fun insertPoints(points: List<PricePointEntity>) = Unit
        override suspend fun latestPoints(tickers: List<String>): List<PricePointEntity> = emptyList()
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
        override suspend fun updateRisk(
            ticker: String,
            beta: Double?,
            betaIsEstimate: Boolean?,
            sectorCorrelation: Double?,
            updatedAt: Long,
        ) {
            stocks[ticker]?.let {
                stocks[ticker] = it.copy(
                    beta = beta,
                    betaIsEstimate = betaIsEstimate,
                    sectorCorrelation = sectorCorrelation,
                    riskUpdatedAt = updatedAt,
                )
            }
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

    @Suppress("SameParameterValue")
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

    private fun day(t: Int): Long = LocalDate.of(2026, 1, 1).plusDays(t.toLong())
        .atTime(12, 0)
        .atZone(ZoneId.of("America/New_York"))
        .toInstant()
        .toEpochMilli()

    /** A non-constant-return price series so a real fallback beta can be derived. */
    private fun price(t: Int): Double = 100.0 + 3 * t + 5 * (t % 3)

    /** Seeds a single held ticker, AAA, with 20 daily price points (well over the 12-day minimum). */
    private fun seedSingleHolding() {
        for (t in 1..20) points += PricePointEntity(ticker = "AAA", price = price(t), timestamp = day(t))
        holdingRows.value = listOf(row("AAA", "Tech", shares = 1.0, price = price(20)))
    }

    private fun repository(): PortfolioRepositoryImpl {
        every { settingsRepository.settings } returns MutableStateFlow(
            SyncSettings(
                refreshInterval = kotlin.time.Duration.parse("15m"),
                batchSize = 1,
                backgroundSyncEnabled = true,
                marketHoursOnly = false,
            ),
        )
        return PortfolioRepositoryImpl(
            holdingDao = holdingDao,
            stockDao = stockDao,
            groupDao = groupDao,
            priceDao = priceDao,
            newsDao = newsDao,
            api = FinnhubStockPriceApi(finnhubService, apiKey = "test-key"),
            throttler = RequestThrottler(maxPermits = 100, windowMillis = 1000, clock = { 0L }, sleep = {}),
            userDataLock = UserDataLock(),
            settingsRepository = settingsRepository,
            calculator = PortfolioCalculator(),
            formationClassifier = FormationClassifier(),
            io = UnconfinedTestDispatcher(),
            clock = { 100_000L },
        )
    }

    @Test
    fun `a real provider outage preserves the cached provider beta`() = runTest {
        seedSingleHolding()
        stocks["AAA"] = StockEntity("AAA", "AAA Inc", sector = "Tech", beta = 1.23, betaIsEstimate = false)
        coEvery { finnhubService.metric(any(), any()) } throws IOException("offline")

        repository().refreshRiskMetrics(force = true)

        val updated = stocks.getValue("AAA")
        assertThat(updated.beta).isEqualTo(1.23)
        assertThat(updated.betaIsEstimate).isFalse()
    }

    @Test
    fun `a real successful response with no beta falls back to the local estimate`() = runTest {
        seedSingleHolding()
        stocks["AAA"] = StockEntity("AAA", "AAA Inc", sector = "Tech", beta = 1.23, betaIsEstimate = false)
        coEvery { finnhubService.metric(any(), any()) } returns
            FinnhubMetricResponse(metric = FinnhubMetric(beta = null))

        repository().refreshRiskMetrics(force = true)

        val updated = stocks.getValue("AAA")
        // Single-held-ticker portfolio: the fallback estimate measures AAA
        // against a market series that, with only itself held, is AAA's own
        // series again, so it always comes out to exactly 1.0 — a value that
        // could only come from the local estimate, never from the stale 1.23.
        assertThat(updated.beta).isNotNull()
        assertThat(updated.beta!!).isWithin(1e-9).of(1.0)
        assertThat(updated.betaIsEstimate).isTrue()
    }
}
