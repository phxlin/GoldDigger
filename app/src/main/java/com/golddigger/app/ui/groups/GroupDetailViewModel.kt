package com.golddigger.app.ui.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.GroupAllocation
import com.golddigger.app.domain.model.HoldingValuation
import com.golddigger.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState
    data object Missing : GroupDetailUiState
    data class Loaded(
        val group: GroupEntity,
        val allocation: GroupAllocation?,
        val allHoldings: List<HoldingValuation>,
        val memberTickers: Set<String>,
    ) : GroupDetailUiState
}

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val repository: PortfolioRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val groupId: Long = savedStateHandle.get<Long>(Routes.ARG_GROUP_ID) ?: 0L

    val uiState = combine(
        repository.observeGroups(),
        repository.observeGroupAllocations(),
        repository.observePortfolio(),
    ) { groups, allocations, summary ->
        val group = groups.firstOrNull { it.id == groupId }
            ?: return@combine GroupDetailUiState.Missing
        val allocation = allocations.firstOrNull { it.groupId == groupId }
        GroupDetailUiState.Loaded(
            group = group,
            allocation = allocation,
            // Only holdings matching this group's own type are offered as
            // members — a stocks-type group's % is measured against total
            // individual-stock value, so an ETF added here would inflate
            // currentValue against a denominator that excludes it.
            allHoldings = summary.holdings.filter { it.isEtf == group.isEtfGroup },
            memberTickers = allocation?.tickers?.toSet() ?: emptySet(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GroupDetailUiState.Loading,
    )

    fun toggleTicker(ticker: String) {
        val current = uiState.value as? GroupDetailUiState.Loaded ?: return
        viewModelScope.launch {
            if (ticker in current.memberTickers) {
                repository.removeTickerFromGroup(ticker, groupId)
            } else {
                repository.addTickerToGroup(ticker, groupId)
            }
        }
    }

    fun updateGroup(name: String, targetPct: Double?, isEtfGroup: Boolean) {
        val current = uiState.value as? GroupDetailUiState.Loaded ?: return
        viewModelScope.launch {
            repository.updateGroup(
                current.group.copy(
                    name = name.trim().ifBlank { current.group.name },
                    targetAllocationPct = targetPct,
                    isEtfGroup = isEtfGroup,
                ),
            )
        }
    }

    fun deleteGroup(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteGroup(groupId)
            onDeleted()
        }
    }
}
