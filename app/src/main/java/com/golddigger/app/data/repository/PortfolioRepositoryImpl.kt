package com.golddigger.app.data.repository

import com.golddigger.app.core.CashHolding
import com.golddigger.app.core.FormationConfig
import com.golddigger.app.core.MarketHours
import com.golddigger.app.core.SyncConfig
import com.golddigger.app.data.UserDataLock
import com.golddigger.app.data.local.dao.GroupDao
import com.golddigger.app.data.local.dao.HoldingDao
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.local.dao.StockDao
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.NewsCacheEntity
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.local.relation.HoldingRow
import com.golddigger.app.data.remote.MissingApiKeyException
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.remote.PriceApiException
import com.golddigger.app.data.remote.RateLimitException
import com.golddigger.app.data.remote.RemoteQuote
import com.golddigger.app.data.remote.StockPriceApi
import com.golddigger.app.data.remote.SymbolSearchResult
import com.golddigger.app.data.remote.throttle.RequestThrottler
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.di.IoDispatcher
import com.golddigger.app.domain.FormationClassifier
import com.golddigger.app.domain.PortfolioCalculator
import com.golddigger.app.domain.RiskMath
import com.golddigger.app.domain.model.Formation
import com.golddigger.app.domain.model.FormationInput
import com.golddigger.app.domain.model.FormationRole
import com.golddigger.app.domain.model.GroupInput
import com.golddigger.app.domain.model.HoldingInput
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import com.golddigger.app.domain.model.PricePoint
import com.golddigger.app.domain.model.RiskProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class PortfolioRepositoryImpl @Inject constructor(
    private val holdingDao: HoldingDao,
    private val stockDao: StockDao,
    private val groupDao: GroupDao,
    private val priceDao: PriceDao,
    private val newsDao: NewsDao,
    private val api: StockPriceApi,
    private val throttler: RequestThrottler,
    private val settingsRepository: SettingsRepository,
    private val calculator: PortfolioCalculator,
    private val formationClassifier: FormationClassifier,
    private val userDataLock: UserDataLock,
    @IoDispatcher private val io: CoroutineDispatcher,
    @Named("epochClock") private val clock: () -> Long,
) : PortfolioRepository {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    override val syncState = _syncState.asStateFlow()

    /** Guards [refreshPrices] so overlapping refreshes coalesce into one. */
    private val syncMutex = Mutex()

    /** Guards [refreshRiskMetrics] the same way. */
    private val riskMutex = Mutex()

    /**
     * Guards the find-existing-or-insert step in [addHolding]/[addCash] —
     * both are check-then-act against [HoldingDao], which has no DB-level
     * uniqueness constraint on ticker, so two calls for the same ticker
     * landing at the same time could otherwise both see no existing row and
     * both insert, producing two holdings for one ticker.
     */
    private val holdingWriteMutex = Mutex()

    override val holdingCount: Flow<Int> = holdingDao.observeCount()

    override fun observePortfolio(): Flow<PortfolioSummary> =
        holdingDao.observeHoldingRows()
            .map { rows -> calculator.summarize(rows.map { it.toInput() }) }
            .flowOn(io)

    override fun observeHoldingValuation(holdingId: Long): Flow<HoldingValuation?> =
        // A dedicated single-row query, not observePortfolio().map { ... } —
        // that would recompute calculator.summarize() over every holding on
        // any change to any holding, just to pick out one row. summarize()
        // still handles the per-holding math (avg cost, gain/loss) correctly
        // on a one-item list; only portfolioWeightPct needs the real
        // portfolio total, fetched separately and substituted in below.
        combine(
            holdingDao.observeHoldingRow(holdingId),
            holdingDao.observeTotalMarketValue(),
        ) { row, totalMarketValue ->
            row?.let {
                val valuation = calculator.summarize(listOf(it.toInput())).holdings.single()
                val total = totalMarketValue ?: 0.0
                valuation.copy(
                    portfolioWeightPct = if (total > 0.0 && valuation.marketValue != null) {
                        valuation.marketValue / total * 100
                    } else {
                        0.0
                    },
                )
            }
        }
            .distinctUntilChanged()
            .flowOn(io)

    override fun observePriceHistory(ticker: String, sinceEpochMs: Long): Flow<List<PricePoint>> =
        priceDao.observePointsSince(ticker, sinceEpochMs)
            .map { points -> points.map { PricePoint(it.timestamp, it.price) } }
            .flowOn(io)

    override fun observeNews(ticker: String): Flow<List<NewsArticle>> =
        newsDao.observeForTicker(ticker.uppercase())
            .map { rows -> rows.map { it.toArticle() } }
            .flowOn(io)

    override suspend fun refreshNews(ticker: String, force: Boolean): Result<Unit> =
        withContext(io) {
            if (CashHolding.isCashTicker(ticker)) return@withContext Result.success(Unit)
            val symbol = ticker.trim().uppercase()
            val now = clock()
            val lastFetched = newsDao.lastFetchedAt(symbol)
            if (!force && lastFetched != null &&
                now - lastFetched < SyncConfig.NEWS_TTL.inWholeMilliseconds
            ) {
                return@withContext Result.success(Unit)
            }
            runCatching {
                throttler.acquire()
                val today = LocalDate.now()
                val articles = api.fetchCompanyNews(
                    ticker = symbol,
                    fromEpochDay = today.minusDays(SyncConfig.NEWS_LOOKBACK_DAYS).toEpochDay(),
                    toEpochDay = today.toEpochDay(),
                )
                if (articles.isNotEmpty()) {
                    userDataLock.withLock<Unit> {
                        // The holding may have been deleted (or replaced by an import) while
                        // the request was in flight; don't store news for a ticker that's gone.
                        if (symbol in holdingDao.distinctTickers()) {
                            newsDao.upsertAll(
                                articles.take(SyncConfig.NEWS_MAX_PER_TICKER)
                                    .map { it.toEntity(symbol, now) },
                            )
                            newsDao.trim(SyncConfig.NEWS_MAX_PER_TICKER)
                        }
                    }
                }
            }.onFailure { if (it is RateLimitException) markRateLimited(it) }
        }

    override fun observeGroups(): Flow<List<GroupEntity>> = groupDao.observeGroups()

    override fun observeGroupAllocations() =
        combine(groupDao.observeGroupsWithStocks(), observePortfolio()) { groups, summary ->
            calculator.allocations(
                groups = groups.map { g ->
                    GroupInput(
                        groupId = g.group.id,
                        name = g.group.name,
                        targetAllocationPct = g.group.targetAllocationPct,
                        tickers = g.stocks.map { it.ticker }.toSet(),
                        isEtfGroup = g.group.isEtfGroup,
                    )
                },
                summary = summary,
            )
        }.flowOn(io)

    override fun observeGroupIdsForTicker(ticker: String): Flow<List<Long>> =
        groupDao.observeGroupIdsForTicker(ticker.uppercase())

    // --- Formation view --------------------------------------------------

    override fun observeFormation(): Flow<Formation> =
        combine(
            observePortfolio(),
            observeGroupAllocations(),
            stockDao.observeAll(),
            priceDao.observeAllPoints(),
            holdingDao.observeRoleOverrides(),
        ) { summary, groupAllocs, stocks, points, overrides ->
            val pointsByTicker = points.groupBy { it.ticker }
            val risk: Map<String, RiskProfile> = stocks.associate { s ->
                val series = pointsByTicker[s.ticker].orEmpty()
                    .sortedBy { it.timestamp }
                    .map { it.price }
                s.ticker to RiskProfile(
                    ticker = s.ticker,
                    beta = s.beta,
                    sectorCorrelation = s.sectorCorrelation,
                    realizedVolatility = RiskMath.realizedVolatility(
                        RiskMath.returns(series),
                        FormationConfig.MIN_POINTS_FOR_ESTIMATE,
                    ),
                )
            }
            val overrideMap: Map<Long, FormationRole> = overrides.mapNotNull { o ->
                o.roleOverride
                    ?.let { name -> runCatching { FormationRole.valueOf(name) }.getOrNull() }
                    ?.let { role -> o.id to role }
            }.toMap()

            formationClassifier.classify(
                FormationInput(
                    holdings = summary.holdings,
                    risk = risk,
                    overrides = overrideMap,
                    groupAllocations = groupAllocs,
                ),
            )
        }.flowOn(io)

    override suspend fun setRoleOverride(holdingId: Long, role: FormationRole?) = withContext(io) {
        holdingDao.setRoleOverride(holdingId, role?.name)
    }

    override suspend fun refreshRiskMetrics(force: Boolean): Result<Unit> = withContext(io) {
        // See refreshPrices' comment: waits its turn instead of bailing out,
        // so a caller's own "is refreshing" state can't clear itself while a
        // metrics refresh started elsewhere is still running.
        riskMutex.withLock {
            runCatching {
                val now = clock()
                val heldTickers = holdingDao.distinctTickers()
                    .filterNot { CashHolding.isCashTicker(it) }
                if (heldTickers.isEmpty()) return@runCatching

                val stocks = stockDao.all().associateBy { it.ticker }
                // Backfill: same reasoning as addHolding's sector fallback,
                // for bond ETFs added before this existed.
                stocks.values
                    .filter { it.sector == null && it.ticker in FormationConfig.FIXED_INCOME_ETFS }
                    .forEach { stockDao.upsert(it.copy(sector = "Fixed Income")) }

                val summary = observePortfolio().first()
                val sharesByTicker = summary.holdings
                    .filterNot { CashHolding.isCashTicker(it.ticker) }
                    .associate { it.ticker to it.shares }
                val pointsByTicker = priceDao.allPoints().groupBy { it.ticker }

                // "Dominant sector" basket used for the correlation estimate: the
                // set of held tickers in the largest sector by value (if it has
                // more than one name), else the whole non-cash book.
                val bySector = summary.holdings
                    .filterNot { CashHolding.isCashTicker(it.ticker) }
                    .groupBy { it.sector?.takeIf { s -> s.isNotBlank() } ?: "Unclassified" }
                val topSector = bySector.entries
                    .maxByOrNull { (_, rows) -> rows.sumOf { it.marketValue ?: 0.0 } }
                val basketTickers = topSector
                    ?.takeIf { it.value.size >= 2 }
                    ?.value?.map { it.ticker }?.toSet()
                    ?: sharesByTicker.keys

                for (ticker in heldTickers) {
                    val stock = stocks[ticker] ?: continue
                    val fresh = !force && stock.riskUpdatedAt != null &&
                        now - stock.riskUpdatedAt < FormationConfig.METRICS_TTL.inWholeMilliseconds
                    if (fresh) continue

                    throttler.acquire()
                    val metrics = runCatching { api.fetchMetrics(ticker) }
                        .onFailure { if (it is RateLimitException) throw it }
                    val providerBeta = metrics.getOrNull()?.beta
                    // Only a beta this app previously confirmed came from the
                    // provider is worth keeping across a failed fetch this
                    // cycle (a transient outage) — stock.beta with
                    // betaIsEstimate != false covers both a locally-estimated
                    // value and a pre-migration row of unknown provenance,
                    // neither of which should be trusted indefinitely; both
                    // are recomputed fresh below instead, since the estimate
                    // is a pure, cheap, no-network calculation anyway.
                    val (beta, betaIsEstimate) = when {
                        providerBeta != null -> providerBeta to false
                        metrics.isFailure && stock.betaIsEstimate == false && stock.beta != null ->
                            stock.beta to false
                        else ->
                            fallbackBeta(ticker, pointsByTicker, sharesByTicker) to true
                    }
                    // Not falling back to stock.sectorCorrelation here: unlike
                    // beta, this never touches the network, so a null result
                    // means "not enough trading-day history yet" rather than a
                    // transient failure — keeping a stale value would leave a
                    // holding like a low-beta bond ETF stuck on a one-session
                    // correlation reading for weeks.
                    val correlation = sectorCorrelation(
                        ticker = ticker,
                        basketTickers = basketTickers - ticker,
                        pointsByTicker = pointsByTicker,
                        sharesByTicker = sharesByTicker,
                    )

                    stockDao.updateRisk(ticker, beta, betaIsEstimate, correlation, now)
                }
            }.onFailure { if (it is RateLimitException) markRateLimited(it) }
        }
    }

    /**
     * Beta estimated locally when the provider has none: the holding's return
     * series against the whole non-cash portfolio's value series. A stand-in for
     * a true SPY benchmark, which needs historical candles the free tier denies.
     */
    private fun fallbackBeta(
        ticker: String,
        pointsByTicker: Map<String, List<PricePointEntity>>,
        sharesByTicker: Map<String, Double>,
    ): Double? {
        val marketTickers = sharesByTicker.keys
        val dailyByTicker = dailyClosesByTicker(marketTickers + ticker, pointsByTicker)
        // Asset and benchmark series must share one date axis: computing each
        // side's "common dates" independently (over a different set of
        // tickers) can yield lists of a different length, or the same length
        // but different actual trading days — RiskMath then pairs them by
        // list index, so an axis mismatch silently corrupts the estimate.
        val dates = commonDates(marketTickers + ticker, dailyByTicker)
        if (dates.size < FormationConfig.MIN_POINTS_FOR_ESTIMATE) return null
        val assetSeries = seriesAt(dates, setOf(ticker), dailyByTicker, mapOf(ticker to 1.0))
        val marketSeries = seriesAt(dates, marketTickers, dailyByTicker, sharesByTicker)
        return RiskMath.beta(RiskMath.returns(assetSeries), RiskMath.returns(marketSeries))
    }

    private fun sectorCorrelation(
        ticker: String,
        basketTickers: Set<String>,
        pointsByTicker: Map<String, List<PricePointEntity>>,
        sharesByTicker: Map<String, Double>,
    ): Double? {
        if (basketTickers.isEmpty()) return null
        val dailyByTicker = dailyClosesByTicker(basketTickers + ticker, pointsByTicker)
        val dates = commonDates(basketTickers + ticker, dailyByTicker)
        if (dates.size < FormationConfig.MIN_POINTS_FOR_ESTIMATE) return null
        val assetSeries = seriesAt(dates, setOf(ticker), dailyByTicker, mapOf(ticker to 1.0))
        val basketSeries = seriesAt(dates, basketTickers, dailyByTicker, sharesByTicker)
        return RiskMath.correlation(RiskMath.returns(assetSeries), RiskMath.returns(basketSeries))
    }

    /**
     * Each of [tickers]' last recorded price on each trading day it has one.
     * Beta/correlation are measured on this daily axis rather than raw sync
     * timestamps so they reflect day-over-day moves: aligning on exact ticks
     * lets one session's shared intraday drift look like a strong
     * relationship even between assets with nothing really in common (see the
     * BNDX/bond-ETF sector-correlation investigation this rule replaces).
     */
    private fun dailyClosesByTicker(
        tickers: Set<String>,
        pointsByTicker: Map<String, List<PricePointEntity>>,
    ): Map<String, Map<LocalDate, Double>> =
        tickers.associateWith { t ->
            pointsByTicker[t].orEmpty()
                .groupBy { MarketHours.sessionDate(it.timestamp) }
                .mapValues { (_, points) -> points.maxBy { it.timestamp }.price }
        }

    /** Trading days where *every* one of [tickers] has a daily close, sorted. */
    private fun commonDates(
        tickers: Set<String>,
        dailyByTicker: Map<String, Map<LocalDate, Double>>,
    ): List<LocalDate> {
        if (tickers.isEmpty()) return emptyList()
        return tickers
            .map { t -> dailyByTicker[t].orEmpty().keys }
            .reduce { acc, keys -> acc intersect keys }
            .sorted()
    }

    /**
     * The weighted sum `Σ close(d) · weight` for [tickers] at each of
     * [dates] — always called with the *same* [dates] for both sides of a
     * beta/correlation estimate, so the resulting series are paired by index
     * on the same trading day, not just by length.
     */
    private fun seriesAt(
        dates: List<LocalDate>,
        tickers: Set<String>,
        dailyByTicker: Map<String, Map<LocalDate, Double>>,
        weightByTicker: Map<String, Double>,
    ): List<Double> =
        dates.map { d ->
            tickers.sumOf { t -> (dailyByTicker[t]?.get(d) ?: 0.0) * (weightByTicker[t] ?: 1.0) }
        }

    override suspend fun searchSymbols(query: String): Result<List<SymbolSearchResult>> =
        withContext(io) {
            runCatching {
                throttler.acquire()
                api.searchSymbols(query)
            }.onFailure { if (it is RateLimitException) markRateLimited(it) }
        }

    override suspend fun addHolding(
        ticker: String,
        companyName: String?,
        sector: String?,
        shares: Double,
        costBasis: Double,
    ): Long = withContext(io) {
        val symbol = ticker.trim().uppercase()
        var name = companyName?.trim()?.takeIf { it.isNotBlank() }
        var industry = sector?.trim()?.takeIf { it.isNotBlank() }

        val existingStock = stockDao.findByTicker(symbol)
        // Only worth a network round trip when we're missing a real name OR
        // a sector for this stock — checking name alone meant a caller that
        // already has a name (e.g. from symbol search results, which supply
        // a description but never a sector) never got a profile fetch at
        // all, so sector stayed unset forever for every holding added that
        // way. Re-adding to an already-tracked ticker with both already
        // known (e.g. merging in more shares) still skips the network call.
        val missingName = name == null && (existingStock == null || existingStock.companyName == existingStock.ticker)
        val missingSector = industry == null && existingStock?.sector == null
        if (missingName || missingSector) {
            runCatching {
                throttler.acquire()
                api.fetchProfile(symbol)
            }.getOrNull()?.let { profile ->
                name = profile.companyName ?: name
                industry = profile.sector ?: industry
            }
        }
        // The provider never returns a sector for funds; without this a bond
        // ETF would fall into the same bucket as equity ETFs below.
        if (industry == null && symbol in FormationConfig.FIXED_INCOME_ETFS) {
            industry = "Fixed Income"
        }

        when {
            existingStock == null ->
                stockDao.upsert(StockEntity(symbol, name ?: symbol, industry))
            existingStock.companyName == existingStock.ticker && name != null ->
                stockDao.upsert(existingStock.copy(companyName = name, sector = industry ?: existingStock.sector))
            // Already has a real name, but was missing a sector and the
            // profile fetch above (triggered by missingSector) just found
            // one — without this branch that fetched sector is computed
            // into the local `industry` var and then silently dropped,
            // since the branch above requires a placeholder companyName.
            existingStock.sector == null && industry != null ->
                stockDao.upsert(existingStock.copy(sector = industry))
        }

        // A ticker you already hold merges into that position — shares and
        // cost basis both sum, so the average cost updates itself — rather
        // than creating a second lot for the same stock. Same pattern
        // addCash already uses for the synthetic cash position.
        holdingWriteMutex.withLock {
            val existingHolding = holdingDao.findByTicker(symbol)
            if (existingHolding != null) {
                holdingDao.update(
                    existingHolding.copy(
                        shares = existingHolding.shares + shares,
                        costBasis = existingHolding.costBasis + costBasis,
                    ),
                )
                existingHolding.id
            } else {
                holdingDao.insert(
                    HoldingEntity(
                        ticker = symbol,
                        shares = shares,
                        costBasis = costBasis,
                        dateAdded = clock(),
                    ),
                )
            }
        }
    }

    override suspend fun updateHolding(holdingId: Long, shares: Double, costBasis: Double) =
        withContext(io) {
            val current = holdingDao.findById(holdingId) ?: return@withContext
            holdingDao.update(current.copy(shares = shares, costBasis = costBasis))
        }

    override suspend fun setInstrumentType(ticker: String, isEtf: Boolean) = withContext(io) {
        val symbol = ticker.trim().uppercase()
        if (stockDao.findByTicker(symbol) != null) stockDao.updateInstrumentType(symbol, isEtf)
    }

    override suspend fun addCash(amount: Double) = withContext(io) {
        if (amount <= 0.0) return@withContext
        stockDao.upsert(StockEntity(CashHolding.TICKER, CashHolding.NAME, CashHolding.SECTOR))
        priceDao.upsert(PriceCacheEntity(CashHolding.TICKER, price = 1.0, dayChangePct = 0.0, lastUpdated = clock()))
        holdingWriteMutex.withLock<Unit> {
            val existing = holdingDao.findByTicker(CashHolding.TICKER)
            if (existing != null) {
                holdingDao.update(
                    existing.copy(
                        shares = existing.shares + amount,
                        costBasis = existing.costBasis + amount,
                    ),
                )
            } else {
                holdingDao.insert(
                    HoldingEntity(
                        ticker = CashHolding.TICKER,
                        shares = amount,
                        costBasis = amount,
                        dateAdded = clock(),
                    ),
                )
            }
        }
    }

    override suspend fun deleteHolding(holdingId: Long) = withContext(io) {
        val current = holdingDao.findById(holdingId) ?: return@withContext
        holdingDao.delete(current)
        stockDao.pruneOrphans()
        priceDao.pruneOrphans()
        priceDao.prunePointOrphans()
        newsDao.pruneOrphans()
    }

    override suspend fun createGroup(name: String, targetAllocationPct: Double?, isEtfGroup: Boolean): Long =
        withContext(io) {
            groupDao.insert(
                GroupEntity(
                    name = name.trim(),
                    targetAllocationPct = targetAllocationPct,
                    isEtfGroup = isEtfGroup,
                ),
            )
        }

    override suspend fun updateGroup(group: GroupEntity) = withContext(io) {
        groupDao.update(group)
    }

    override suspend fun deleteGroup(groupId: Long) = withContext(io) {
        groupDao.delete(GroupEntity(id = groupId, name = ""))
        stockDao.pruneOrphans()
    }

    override suspend fun setGroupsForHolding(ticker: String, groupIds: Set<Long>) =
        withContext(io) {
            val symbol = ticker.uppercase()
            groupDao.clearGroupsForTicker(symbol)
            groupIds.forEach { groupId ->
                groupDao.addStockToGroup(StockGroupCrossRef(symbol, groupId))
            }
        }

    override suspend fun addTickerToGroup(ticker: String, groupId: Long) = withContext(io) {
        groupDao.addStockToGroup(StockGroupCrossRef(ticker.uppercase(), groupId))
    }

    override suspend fun removeTickerFromGroup(ticker: String, groupId: Long) = withContext(io) {
        groupDao.removeStockFromGroup(StockGroupCrossRef(ticker.uppercase(), groupId))
    }

    override suspend fun refreshPrices(force: Boolean): SyncState = withContext(io) {
        // Waits its turn rather than bailing out immediately when another
        // refresh is already running: a tryLock-and-bail here would let a
        // caller's local "is refreshing" flag flip back to false while a
        // sync started elsewhere is still genuinely in flight. Once this
        // caller gets the lock, the real work is very likely already done —
        // it'll just re-check staleness (fast, no network) and return.
        syncMutex.withLock {
            _syncState.value = SyncState.Syncing
            // Cash is synthetic — never quote it against the provider.
            val tickers = holdingDao.distinctTickers()
                .filterNot { CashHolding.isCashTicker(it) }
            if (tickers.isEmpty()) {
                return@withContext SyncState.UpToDate(clock()).also { _syncState.value = it }
            }

            val settings = settingsRepository.settings.first()
            val caches = priceDao.all().associateBy { it.ticker }
            val now = clock()

            val stale = if (force) {
                tickers
            } else {
                tickers.filter { t ->
                    val cache = caches[t] ?: return@filter true
                    !SyncConfig.isFresh(cache.lastUpdated, now, settings.refreshInterval)
                }
            }
            if (stale.isEmpty()) {
                // Scoped to the real (non-cash) tickers, same as
                // oldestUpdateAmong below — caches also holds the synthetic
                // CASH row, whose timestamp is set once at add-time and never
                // refreshed, and would otherwise pin this timestamp to that.
                val oldest = priceDao.oldestUpdateAmong(tickers) ?: now
                return@withContext SyncState.UpToDate(oldest).also { _syncState.value = it }
            }

            val perCall = minOf(settings.batchSize, api.maxSymbolsPerQuoteRequest)
                .coerceAtLeast(1)
            val collected = mutableListOf<RemoteQuote>()
            var rateLimit: RateLimitException? = null
            var networkError: PriceApiException? = null

            loop@ for (chunk in stale.chunked(perCall)) {
                throttler.acquire()
                try {
                    collected += api.fetchQuotes(chunk)
                } catch (e: RateLimitException) {
                    rateLimit = e
                    break@loop
                } catch (e: MissingApiKeyException) {
                    return@withContext SyncState.NoApiKey.also { _syncState.value = it }
                } catch (e: PriceApiException) {
                    networkError = e
                    break@loop
                }
            }

            if (collected.isNotEmpty()) persistQuotes(collected, now)
            val lastUpdated = priceDao.oldestUpdateAmong(tickers)

            val result = when {
                rateLimit != null -> SyncState.RateLimited(
                    lastUpdatedEpochMs = lastUpdated,
                    nextAllowedEpochMs = rateLimit.retryAfterSeconds
                        ?.let { now + it * 1000 }
                        ?: (now + SyncConfig.THROTTLE_WINDOW.inWholeMilliseconds),
                )
                networkError != null -> SyncState.Offline(lastUpdated)
                else -> SyncState.UpToDate(now)
            }
            result.also { _syncState.value = it }
        }
    }

    private suspend fun persistQuotes(allQuotes: List<RemoteQuote>, fetchedAt: Long) {
        userDataLock.withLock<Unit> {
            // The network calls are done and the write phase holds the lock, so what's held
            // can't change under us any more. A holding deleted (or replaced by an import)
            // while the quotes were in flight must not get prices written back for it.
            val held = holdingDao.distinctTickers().toSet()
            val quotes = allQuotes.filter { it.ticker.uppercase() in held }
            if (quotes.isEmpty()) return@withLock

            priceDao.upsertAll(
                quotes.map {
                    PriceCacheEntity(
                        ticker = it.ticker,
                        price = it.price,
                        dayChangePct = it.dayChangePct,
                        lastUpdated = fetchedAt,
                    )
                },
            )
            // While the market is open every sync is a data point (the risk maths pairs
            // tickers by timestamp). Outside it a quote identical to the last recorded
            // one adds nothing but a flat stretch to the chart, so only a price that
            // actually moved, e.g. an after-hours trade, is recorded.
            val marketOpen = MarketHours.isMarketOpen(Instant.ofEpochMilli(fetchedAt).atZone(ZoneOffset.UTC))
            val toRecord = if (marketOpen) {
                quotes
            } else {
                val lastPrice = priceDao.latestPoints(quotes.map { it.ticker }).associate { it.ticker to it.price }
                quotes.filter { lastPrice[it.ticker] != it.price }
            }
            if (toRecord.isNotEmpty()) {
                priceDao.insertPoints(
                    toRecord.map { PricePointEntity(ticker = it.ticker, price = it.price, timestamp = fetchedAt) },
                )
                priceDao.trimHistory()
            }
        }
    }

    private fun markRateLimited(e: RateLimitException) {
        val now = clock()
        _syncState.value = SyncState.RateLimited(
            lastUpdatedEpochMs = null,
            nextAllowedEpochMs = e.retryAfterSeconds?.let { now + it * 1000 }
                ?: (now + SyncConfig.THROTTLE_WINDOW.inWholeMilliseconds),
        )
    }

    private fun NewsCacheEntity.toArticle() = NewsArticle(
        id = id,
        headline = headline,
        summary = summary,
        source = source,
        url = url,
        imageUrl = imageUrl,
        publishedAtEpochMs = publishedAt,
    )

    private fun NewsArticle.toEntity(ticker: String, fetchedAt: Long) = NewsCacheEntity(
        ticker = ticker,
        id = id,
        headline = headline,
        summary = summary,
        source = source,
        url = url,
        imageUrl = imageUrl,
        publishedAt = publishedAtEpochMs,
        fetchedAt = fetchedAt,
    )

    private fun HoldingRow.toInput() = HoldingInput(
        holdingId = id,
        ticker = ticker,
        companyName = companyName,
        sector = sector,
        shares = shares,
        costBasis = costBasis,
        price = price,
        dayChangePct = dayChangePct,
        priceUpdatedAt = priceUpdatedAt,
        isEtf = isEtf,
    )
}
