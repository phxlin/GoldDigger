package com.golddigger.app.ui.formation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.domain.model.Formation
import com.golddigger.app.domain.model.FormationRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FormationUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val formation: Formation? = null,
) {
    val isEmpty: Boolean get() = !loading && (formation?.isEmpty ?: true)
}

@HiltViewModel
class FormationViewModel @Inject constructor(
    private val repository: PortfolioRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<FormationUiState> =
        combine(repository.observeFormation(), refreshing) { formation, isRefreshing ->
            FormationUiState(loading = false, refreshing = isRefreshing, formation = formation)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FormationUiState(),
        )

    init {
        // Populates beta/correlation on first open; the TTL keeps it cheap after.
        viewModelScope.launch { repository.refreshRiskMetrics(force = false) }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refreshPrices(force = true)
                repository.refreshRiskMetrics(force = true)
            } finally {
                refreshing.value = false
            }
        }
    }

    /** [role] == null clears the override and returns the holding to its computed role. */
    fun setRole(holdingId: Long, role: FormationRole?) {
        viewModelScope.launch { repository.setRoleOverride(holdingId, role) }
    }
}
