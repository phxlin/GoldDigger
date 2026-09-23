package com.golddigger.app.ui.holding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PriceRange
import com.golddigger.app.domain.model.PricePoint
import com.golddigger.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HoldingDetailUiState {
    data object Loading : HoldingDetailUiState
    data object Missing : HoldingDetailUiState
    data class Loaded(
        val holding: HoldingValuation,
        /** Price points within [selectedRange], oldest first. */
        val priceHistory: List<PricePoint>,
        val selectedRange: PriceRange,
        val allGroups: List<GroupEntity>,
        val memberGroupIds: Set<Long>,
        val news: List<NewsArticle>,
        val newsLoading: Boolean,
        val isRefreshing: Boolean,
    ) : HoldingDetailUiState {
        /** Price change across [priceHistory] (last minus first), or null with fewer than 2 points. */
        val rangeChange: Double?
            get() = priceHistory.takeIf { it.size >= 2 }?.let { it.last().price - it.first().price }

        /** [rangeChange] as a percent of the range's starting price. */
        val rangeChangePct: Double?
            get() = priceHistory.takeIf { it.size >= 2 && it.first().price > 0.0 }
                ?.let { (it.last().price - it.first().price) / it.first().price * 100 }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HoldingDetailViewModel @Inject constructor(
    private val repository: PortfolioRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val holdingId: Long = savedStateHandle.get<Long>(Routes.ARG_HOLDING_ID) ?: 0L

    private val newsLoading = MutableStateFlow(false)
    private val isRefreshing = MutableStateFlow(false)
    private val selectedRange = MutableStateFlow(PriceRange.ONE_DAY)

    val uiState = repository.observeHoldingValuation(holdingId)
        .flatMapLatest { valuation ->
            if (valuation == null) {
                flowOf(HoldingDetailUiState.Missing)
            } else {
                combine(
                    // Paired together (not combined as separate flows) so the
                    // range a page of history was fetched for always matches
                    // the range shown alongside it, with no combine-arity limit.
                    selectedRange.flatMapLatest { range ->
                        repository.observePriceHistory(
                            valuation.ticker,
                            range.sinceEpochMs(System.currentTimeMillis()),
                        ).map { history -> range to history }
                    },
                    repository.observeGroups(),
                    repository.observeGroupIdsForTicker(valuation.ticker),
                    repository.observeNews(valuation.ticker),
                    // Same pairing trick for the other two simple flags, again
                    // just to stay under combine's 5-flow overload.
                    combine(newsLoading, isRefreshing) { loading, refreshing -> loading to refreshing },
                ) { (range, history), groups, memberIds, news, (loadingNews, refreshing) ->
                    val memberIdSet = memberIds.toSet()
                    HoldingDetailUiState.Loaded(
                        holding = valuation,
                        priceHistory = history,
                        selectedRange = range,
                        // Mirrors GroupDetailViewModel's own filter: a group
                        // only offers itself to a holding of its own type, so
                        // an ETF can't end up dragged into a stocks-type
                        // group's percentage (or vice versa). An existing
                        // membership that predates this rule still shows up
                        // (and can be unchecked) even if the types no longer
                        // match, so there's always a way to undo it.
                        allGroups = groups.filter { it.isEtfGroup == valuation.isEtf || it.id in memberIdSet },
                        memberGroupIds = memberIdSet,
                        news = news,
                        newsLoading = loadingNews,
                        isRefreshing = refreshing,
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HoldingDetailUiState.Loading,
        )

    fun onRangeSelected(range: PriceRange) {
        selectedRange.value = range
    }

    init {
        // Kick off a news fetch as soon as we know the ticker.
        repository.observeHoldingValuation(holdingId)
            .map { it?.ticker }
            .distinctUntilChanged()
            .onEach { ticker -> if (ticker != null) loadNews(ticker, force = false) }
            .launchIn(viewModelScope)
    }

    fun refreshNews() {
        val ticker = (uiState.value as? HoldingDetailUiState.Loaded)?.holding?.ticker ?: return
        loadNews(ticker, force = true)
    }

    private fun loadNews(ticker: String, force: Boolean) {
        viewModelScope.launch {
            newsLoading.value = true
            try {
                repository.refreshNews(ticker, force = force)
            } finally {
                newsLoading.value = false
            }
        }
    }

    fun toggleGroup(ticker: String, groupId: Long) {
        val current = uiState.value
        if (current !is HoldingDetailUiState.Loaded) return
        val next = current.memberGroupIds.toMutableSet().apply {
            if (!add(groupId)) remove(groupId)
        }
        viewModelScope.launch { repository.setGroupsForHolding(ticker, next) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteHolding(holdingId)
            onDeleted()
        }
    }

    /**
     * Pull-to-refresh: refreshes price and news together, as two independent
     * operations each with its own loading indicator (the pull spinner for
     * price, the inline spinner in the news section for news) rather than
     * making one wait on the other.
     */
    fun refresh() {
        (uiState.value as? HoldingDetailUiState.Loaded)?.holding?.ticker?.let { ticker ->
            loadNews(ticker, force = true)
        }
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                repository.refreshPrices(force = true)
            } finally {
                isRefreshing.value = false
            }
        }
    }
}
