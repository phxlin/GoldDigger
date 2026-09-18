package com.golddigger.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.data.repository.SyncState
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.domain.model.PortfolioSummary
import com.golddigger.app.ui.common.displayLabel
import com.golddigger.app.ui.components.ChartSlice
import com.golddigger.app.ui.components.chartColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which instrument-type group the Portfolio screen is currently showing. */
enum class PortfolioTab { STOCKS, ETFS }

/** Column the holdings list can be ordered by. */
enum class HoldingSortKey { SYMBOL, PRICE, VALUE }

data class HoldingSort(
    val key: HoldingSortKey = HoldingSortKey.VALUE,
    val ascending: Boolean = false,
)

/**
 * Orders the holdings list for display. Unpriced holdings always sink to the
 * bottom of a Price/Value sort regardless of direction, then fall back to
 * alphabetical among themselves.
 */
fun sortHoldings(
    holdings: List<HoldingValuation>,
    sort: HoldingSort,
): List<HoldingValuation> {
    if (sort.key == HoldingSortKey.SYMBOL) {
        val byName = compareBy<HoldingValuation> { it.displayLabel().lowercase() }
        return if (sort.ascending) holdings.sortedWith(byName) else holdings.sortedWith(byName.reversed())
    }
    val hasValue: (HoldingValuation) -> Boolean = when (sort.key) {
        HoldingSortKey.PRICE -> { h -> h.price != null }
        else -> { h -> h.marketValue != null }
    }
    val metric: (HoldingValuation) -> Double = when (sort.key) {
        HoldingSortKey.PRICE -> { h -> h.price ?: 0.0 }
        else -> { h -> h.marketValue ?: 0.0 }
    }
    val (priced, unpriced) = holdings.partition(hasValue)
    val cmp = compareBy<HoldingValuation> { metric(it) }
    val orderedPriced = if (sort.ascending) priced.sortedWith(cmp) else priced.sortedWith(cmp.reversed())
    return orderedPriced + unpriced.sortedBy { it.displayLabel().lowercase() }
}

/**
 * One instrument-type section of the Portfolio screen ("Individual Stocks" or
 * "ETFs"). [slices] are built only from [holdings], so the legend's per-slice
 * percentage is this group's share of *its own* total — every group's slices
 * sum to 100% independently, unlike a portfolio-wide allocation chart.
 */
data class PortfolioGroup(
    val label: String,
    val holdings: List<HoldingValuation> = emptyList(),
    val slices: List<ChartSlice> = emptyList(),
    val totalValue: Double = 0.0,
)

data class DashboardUiState(
    val loading: Boolean = true,
    val isRefreshing: Boolean = false,
    val summary: PortfolioSummary? = null,
    /** Individual stocks (and cash) from [summary], ordered per [sort]. */
    val stocks: PortfolioGroup = PortfolioGroup("Individual Stocks"),
    /** ETFs from [summary], ordered per [sort]. */
    val etfs: PortfolioGroup = PortfolioGroup("ETFs"),
    val sort: HoldingSort = HoldingSort(),
    val syncState: SyncState = SyncState.Idle,
) {
    val isEmpty: Boolean get() = !loading && (summary?.holdingCount ?: 0) == 0
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: PortfolioRepository,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val sort = MutableStateFlow(HoldingSort())

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observePortfolio(),
        repository.syncState,
        isRefreshing,
        sort,
    ) { summary, sync, refreshing, sortState ->
        val (etfHoldings, stockHoldings) = summary.holdings.applySort(sortState).partition { it.isEtf }
        DashboardUiState(
            loading = false,
            isRefreshing = refreshing,
            summary = summary,
            stocks = stockHoldings.toGroup("Individual Stocks"),
            etfs = etfHoldings.toGroup("ETFs"),
            sort = sortState,
            syncState = sync,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    init {
        viewModelScope.launch { repository.refreshPrices(force = false) }
    }

    /** Tap a column header: same column flips direction, a new column starts fresh. */
    fun onSortSelected(key: HoldingSortKey) {
        sort.update { current ->
            if (current.key == key) {
                current.copy(ascending = !current.ascending)
            } else {
                HoldingSort(key = key, ascending = key == HoldingSortKey.SYMBOL)
            }
        }
    }

    private fun List<HoldingValuation>.applySort(sort: HoldingSort) = sortHoldings(this, sort)

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            try {
                repository.refreshPrices(force = true)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun addCash(amount: Double) {
        if (amount <= 0.0) return
        viewModelScope.launch {
            repository.addCash(amount)
            repository.refreshPrices(force = false)
        }
    }

    private fun List<HoldingValuation>.toGroup(label: String): PortfolioGroup {
        val totalValue = sumOf { it.marketValue ?: 0.0 }
        return PortfolioGroup(label = label, holdings = this, slices = toValueSlices(), totalValue = totalValue)
    }

    private fun List<HoldingValuation>.toValueSlices(): List<ChartSlice> {
        val priced = filter { (it.marketValue ?: 0.0) > 0.0 }
        if (priced.isEmpty()) return emptyList()
        val maxSlices = 8
        return if (priced.size <= maxSlices) {
            priced.mapIndexed { i, h ->
                ChartSlice(h.displayLabel(), h.marketValue!!, chartColor(i, priced.size))
            }
        } else {
            val top = priced.take(maxSlices - 1)
            val otherValue = priced.drop(maxSlices - 1).sumOf { it.marketValue!! }
            top.mapIndexed { i, h ->
                ChartSlice(h.displayLabel(), h.marketValue!!, chartColor(i, maxSlices))
            } + ChartSlice("Other", otherValue, chartColor(maxSlices - 1, maxSlices))
        }
    }

    val refreshingFlow: StateFlow<Boolean> get() = isRefreshing.asStateFlow()
}
