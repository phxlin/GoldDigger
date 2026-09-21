package com.golddigger.app.data.repository

import app.cash.turbine.test
import com.golddigger.app.data.UserDataLock
import com.golddigger.app.data.local.dao.GroupDao
import com.golddigger.app.data.local.dao.HoldingDao
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.local.dao.StockDao
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.dao.HoldingRoleOverride
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.local.relation.GroupWithStocks
import com.golddigger.app.data.local.relation.HoldingRow
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.remote.RateLimitException
import com.golddigger.app.data.remote.RemoteQuote
import com.golddigger.app.data.remote.StockMetrics
import com.golddigger.app.data.remote.StockPriceApi
import com.golddigger.app.data.remote.StockProfile
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.remote.throttle.RequestThrottler
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.domain.FormationClassifier
import com.golddigger.app.domain.PortfolioCalculator
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class PortfolioRepositoryImplTest {

    private val holdingRows = MutableStateFlow<List<HoldingRow>>(emptyList())
    private val prices = mutableListOf<PriceCacheEntity>()
    // Separate from `holdingRows` (which drives observePortfolio() tests directly):
    // a minimal, stateful ticker->holding map so addHolding's merge-on-add
    // behaviour is actually exercised, not just stubbed to no-ops.
    private val holdingsByTicker = mutableMapOf<String, HoldingEntity>()
    private var nextHoldingId = 1L

    private val holdingDao = object : HoldingDao {
        override suspend fun insert(holding: HoldingEntity): Long {
            val id = nextHoldingId++
            holdingsByTicker[holding.ticker] = holding.copy(id = id)
            return id
        }
        override suspend fun update(holding: HoldingEntity) {
            holdingsByTicker[holding.ticker] = holding
        }
        override suspend fun delete(holding: HoldingEntity) = Unit
        override suspend fun findById(id: Long): HoldingEntity? =
            holdingsByTicker.values.firstOrNull { it.id == id }
        override suspend fun findByTicker(ticker: String): HoldingEntity? = holdingsByTicker[ticker]
        override fun observeHoldingRows(): Flow<List<HoldingRow>> = holdingRows
        override fun observeHoldingRow(id: Long): Flow<HoldingRow?> =
            holdingRows.map { rows -> rows.firstOrNull { it.id == id } }
        override fun observeTotalMarketValue(): Flow<Double?> = holdingRows.map { rows ->
            val priced = rows.filter { (it.price ?: 0.0) > 0.0 }
            if (priced.isEmpty()) null else priced.sumOf { it.shares * it.price!! }
        }
        override suspend fun distinctTickers(): List<String> =
            holdingRows.value.map { it.ticker }.distinct()
        override fun observeCount(): Flow<Int> = holdingRows.map { it.size }
        override fun observeRoleOverrides(): Flow<List<HoldingRoleOverride>> =
            MutableStateFlow(emptyList())
        override suspend fun setRoleOverride(id: Long, role: String?) = Unit
    }

    private val recordedPoints = mutableListOf<PricePointEntity>()

    /** The clock the repository under test reads; tests move it to pick market-open vs closed. */
    private var nowMs = 10_000L

    private val priceDao = object : PriceDao {
        override suspend fun upsert(price: PriceCacheEntity) { replace(price) }
        override suspend fun upsertAll(prices2: List<PriceCacheEntity>) { prices2.forEach(::replace) }
        override suspend fun find(ticker: String) = prices.firstOrNull { it.ticker == ticker }
        override suspend fun all(): List<PriceCacheEntity> = prices.toList()
        override fun observeAll(): Flow<List<PriceCacheEntity>> = MutableStateFlow(prices.toList())
        override suspend fun oldestUpdateAmong(tickers: List<String>): Long? =
            prices.filter { it.ticker in tickers }.minOfOrNull { it.lastUpdated }
        override suspend fun insertPoint(point: PricePointEntity) = Unit
        override suspend fun insertPoints(points: List<PricePointEntity>) { recordedPoints += points }
        override suspend fun latestPoints(tickers: List<String>): List<PricePointEntity> =
            recordedPoints.filter { it.ticker in tickers }
                .groupBy { it.ticker }
                .map { (_, pts) -> pts.maxBy { it.timestamp } }
        override fun observeRecentPoints(ticker: String, limit: Int): Flow<List<PricePointEntity>> =
            MutableStateFlow(emptyList())
        override fun observePointsSince(ticker: String, sinceEpochMs: Long): Flow<List<PricePointEntity>> =
            MutableStateFlow(emptyList())
        override fun observeAllPoints(): Flow<List<PricePointEntity>> = MutableStateFlow(emptyList())
        override suspend fun allPoints(): List<PricePointEntity> = emptyList()
        override suspend fun recentPointsAsc(ticker: String, limit: Int): List<PricePointEntity> = emptyList()
        override suspend fun trimHistory(keep: Int) = Unit
        override suspend fun pruneOrphans() = Unit
        override suspend fun prunePointOrphans() = Unit
        private fun replace(p: PriceCacheEntity) {
            prices.removeAll { it.ticker == p.ticker }
            prices += p
        }
    }

    private val groupDao = object : GroupDao {
        override suspend fun insert(group: GroupEntity) = 1L
        override suspend fun update(group: GroupEntity) = Unit
        override suspend fun delete(group: GroupEntity) = Unit
        override fun observeGroups(): Flow<List<GroupEntity>> = MutableStateFlow(emptyList())
        override fun observeGroupsWithStocks(): Flow<List<GroupWithStocks>> =
            MutableStateFlow(emptyList())
        override fun observeGroupWithStocks(id: Long): Flow<GroupWithStocks?> =
            MutableStateFlow(null)
        override suspend fun addStockToGroup(ref: StockGroupCrossRef) = Unit
        override suspend fun removeStockFromGroup(ref: StockGroupCrossRef) = Unit
        override fun observeGroupIdsForTicker(ticker: String): Flow<List<Long>> =
            MutableStateFlow(emptyList())
        override suspend fun clearGroupsForTicker(ticker: String) = Unit
    }

    private val stockDao = mockk<StockDao>(relaxed = true)
    private val newsDao = mockk<NewsDao>(relaxed = true)

    private val settingsRepository = mockk<SettingsRepository>()
    private val calculator = PortfolioCalculator()

    private fun repo(api: StockPriceApi) = PortfolioRepositoryImpl(
        holdingDao = holdingDao,
        stockDao = stockDao,
        groupDao = groupDao,
        priceDao = priceDao,
        newsDao = newsDao,
        api = api,
        throttler = RequestThrottler(maxPermits = 100, windowMillis = 1000, clock = { 0L }, sleep = {}),
        settingsRepository = settingsRepository,
        calculator = calculator,
        formationClassifier = FormationClassifier(),
        userDataLock = UserDataLock(),
        io = UnconfinedTestDispatcher(),
        clock = { nowMs },
    ).also {
        every { settingsRepository.settings } returns MutableStateFlow(
            com.golddigger.app.data.settings.SyncSettings(
                refreshInterval = kotlin.time.Duration.parse("15m"),
                batchSize = 1,
                backgroundSyncEnabled = true,
                marketHoursOnly = false,
            ),
        )
    }

    private fun row(ticker: String, shares: Double, cost: Double, price: Double?) = HoldingRow(
        id = ticker.hashCode().toLong(),
        ticker = ticker,
        shares = shares,
        costBasis = cost,
        dateAdded = 0,
        companyName = "$ticker Inc",
        sector = null,
        price = price,
        dayChangePct = 0.0,
        priceUpdatedAt = price?.let { 1L },
    )

    @Test
    fun `observePortfolio re-emits when the holdings table changes`() = runTest {
        val api = FakeApi(quotes = emptyList())
        val repository = repo(api)

        repository.observePortfolio().test {
            assertThat(awaitItem().holdingCount).isEqualTo(0)

            holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = 150.0))
            val next = awaitItem()
            assertThat(next.holdingCount).isEqualTo(1)
            assertThat(next.totalMarketValue).isEqualTo(1_500.0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshPrices writes quotes into the cache and reports UpToDate`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = null))
        val api = FakeApi(quotes = listOf(RemoteQuote("AAA", 200.0, 1.5, 5L)))
        val repository = repo(api)

        val state = repository.refreshPrices(force = true)

        assertThat(state).isInstanceOf(SyncState.UpToDate::class.java)
        assertThat(priceDao.find("AAA")?.price).isEqualTo(200.0)
    }

    @Test
    fun `adding a holding for an already-held ticker merges into that lot`() = runTest {
        val repository = repo(FakeApi(quotes = emptyList()))

        repository.addHolding("AAA", "AAA Inc", null, shares = 10.0, costBasis = 1_000.0)
        repository.addHolding("AAA", "AAA Inc", null, shares = 5.0, costBasis = 600.0)

        val merged = holdingDao.findByTicker("AAA")
        assertThat(merged?.shares).isEqualTo(15.0)
        assertThat(merged?.costBasis).isEqualTo(1_600.0)
        // One lot, not two.
        assertThat(holdingsByTicker).hasSize(1)
    }

    @Test
    fun `a 429 from the provider surfaces as RateLimited, not an error`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = null))
        val api = FakeApi(quotes = emptyList(), rateLimit = RateLimitException(retryAfterSeconds = 30))
        val repository = repo(api)

        val state = repository.refreshPrices(force = true)

        assertThat(state).isInstanceOf(SyncState.RateLimited::class.java)
        assertThat((state as SyncState.RateLimited).nextAllowedEpochMs).isEqualTo(10_000L + 30_000L)
    }

    /** 15:00 UTC on the given day of September 2026 (11:00 in New York). */
    private fun sept2026At15Utc(day: Int) =
        ZonedDateTime.of(2026, 9, day, 15, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    // 2026-09-14 is a Monday: 15:00 UTC is 11:00 in New York, mid-session.
    private val marketOpenMs = sept2026At15Utc(14)
    // 2026-09-13 is a Sunday.
    private val marketClosedMs = sept2026At15Utc(13)

    @Test
    fun `quotes fetched for a holding deleted mid-sync are not written back`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = null))
        val api = FakeApi(quotes = listOf(RemoteQuote("AAA", 200.0, 1.5, 5L))) {
            holdingRows.value = emptyList() // Delete all data lands while the request is out
        }

        repo(api).refreshPrices(force = true)

        assertThat(priceDao.find("AAA")).isNull()
        assertThat(recordedPoints).isEmpty()
    }

    @Test
    fun `news fetched for a holding deleted mid-request is not stored`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = 150.0))
        val api = FakeApi(quotes = emptyList(), news = listOf(article())) {
            holdingRows.value = emptyList()
        }

        repo(api).refreshNews("AAA", force = true)

        coVerify(exactly = 0) { newsDao.upsertAll(any()) }
    }

    @Test
    fun `news for a holding that is still held is stored`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = 150.0))

        repo(FakeApi(quotes = emptyList(), news = listOf(article()))).refreshNews("AAA", force = true)

        coVerify(exactly = 1) { newsDao.upsertAll(any()) }
    }

    private fun article() = NewsArticle(1L, "Headline", "Summary", "Source", "https://example.com", null, 1L)

    @Test
    fun `a price that hasn't moved is not recorded again while the market is closed`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = null))
        val repository = repo(FakeApi(quotes = listOf(RemoteQuote("AAA", 200.0, 0.0, 5L))))

        nowMs = marketClosedMs
        repository.refreshPrices(force = true)
        nowMs = marketClosedMs + 15 * 60_000
        repository.refreshPrices(force = true)
        nowMs = marketClosedMs + 30 * 60_000
        repository.refreshPrices(force = true)

        assertThat(recordedPoints).hasSize(1)
    }

    @Test
    fun `a price that moved after hours is recorded`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = null))
        val quotes = mutableListOf(RemoteQuote("AAA", 200.0, 0.0, 5L))
        val repository = repo(FakeApi(quotes = quotes))

        nowMs = marketClosedMs
        repository.refreshPrices(force = true)
        quotes[0] = RemoteQuote("AAA", 201.5, 0.0, 5L)
        nowMs = marketClosedMs + 15 * 60_000
        repository.refreshPrices(force = true)

        assertThat(recordedPoints.map { it.price }).containsExactly(200.0, 201.5).inOrder()
    }

    @Test
    fun `every sync is recorded while the market is open even if the price is unchanged`() = runTest {
        holdingRows.value = listOf(row("AAA", 10.0, 1_000.0, price = null))
        val repository = repo(FakeApi(quotes = listOf(RemoteQuote("AAA", 200.0, 0.0, 5L))))

        nowMs = marketOpenMs
        repository.refreshPrices(force = true)
        nowMs = marketOpenMs + 15 * 60_000
        repository.refreshPrices(force = true)

        assertThat(recordedPoints).hasSize(2)
    }

    private class FakeApi(
        private val quotes: List<RemoteQuote>,
        private val rateLimit: RateLimitException? = null,
        private val news: List<NewsArticle> = emptyList(),
        /** Runs when a request is in flight, to simulate something changing while it's out. */
        private val onFetch: () -> Unit = {},
    ) : StockPriceApi {
        override val maxSymbolsPerQuoteRequest = 1
        override suspend fun searchSymbols(query: String): List<SymbolSearchResult> = emptyList()
        override suspend fun fetchProfile(ticker: String): StockProfile? = null
        override suspend fun fetchMetrics(ticker: String): StockMetrics = StockMetrics()
        override suspend fun fetchQuotes(tickers: List<String>): List<RemoteQuote> {
            onFetch()
            rateLimit?.let { throw it }
            return quotes.filter { it.ticker in tickers.map(String::uppercase) }
        }

        override suspend fun fetchCompanyNews(
            ticker: String,
            fromEpochDay: Long,
            toEpochDay: Long,
        ): List<NewsArticle> {
            onFetch()
            return news
        }
    }
}
