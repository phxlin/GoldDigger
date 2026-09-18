package com.golddigger.app.ui.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.ui.components.ChartSlice
import com.golddigger.app.ui.components.chartColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which type of group the Groups screen shows on a given page. */
enum class GroupsTab { STOCKS, ETFS }

data class GroupsUiState(
    val loading: Boolean = true,
    /** Individual-stock-type groups, ordered by current value descending. */
    val stockGroups: List<GroupAllocation> = emptyList(),
    /** ETF-type groups, ordered by current value descending. */
    val etfGroups: List<GroupAllocation> = emptyList(),
    /** One slice per [stockGroups] entry, by current value — for the tab's donut chart. */
    val stockSlices: List<ChartSlice> = emptyList(),
    /** One slice per [etfGroups] entry, by current value — for the tab's donut chart. */
    val etfSlices: List<ChartSlice> = emptyList(),
    val totalPortfolioValue: Double = 0.0,
    val hasHoldings: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && stockGroups.isEmpty() && etfGroups.isEmpty()
}

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val repository: PortfolioRepository,
) : ViewModel() {

    val uiState = combine(
        repository.observeGroupAllocations(),
        repository.observePortfolio(),
    ) { allocations, summary ->
        val (etfGroups, stockGroups) = allocations
            .sortedByDescending { it.currentValue }
            .partition { it.isEtfGroup }
        GroupsUiState(
            loading = false,
            stockGroups = stockGroups,
            etfGroups = etfGroups,
            stockSlices = stockGroups.toSlices(),
            etfSlices = etfGroups.toSlices(),
            totalPortfolioValue = summary.totalMarketValue,
            hasHoldings = summary.holdingCount > 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsUiState())

    fun createGroup(name: String, targetPct: Double?, isEtfGroup: Boolean) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createGroup(name.trim(), targetPct, isEtfGroup) }
    }

    private fun List<GroupAllocation>.toSlices(): List<ChartSlice> {
        val priced = filter { it.currentValue > 0.0 }
        return priced.mapIndexed { i, g -> ChartSlice(g.name, g.currentValue, chartColor(i, priced.size)) }
    }
}
