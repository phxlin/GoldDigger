package com.golddigger.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.core.SyncConfig
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.data.settings.SyncSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

data class SettingsUiState(
    val refreshIntervalMinutes: Int = SyncConfig.DEFAULT_REFRESH_INTERVAL.inWholeMinutes.toInt(),
    val batchSize: Int = SyncConfig.DEFAULT_BATCH_SIZE,
    val backgroundSyncEnabled: Boolean = true,
    val marketHoursOnly: Boolean = true,
    val minIntervalMinutes: Int = SyncConfig.MIN_REFRESH_INTERVAL.inWholeMinutes.toInt(),
    val maxIntervalMinutes: Int = SyncConfig.MAX_REFRESH_INTERVAL.inWholeMinutes.toInt(),
    val requestsPerMinuteCap: Int = SyncConfig.MAX_REQUESTS_PER_MINUTE,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState = settingsRepository.settings
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setRefreshInterval(minutes: Int) {
        viewModelScope.launch { settingsRepository.setRefreshIntervalMinutes(minutes) }
    }

    fun setBatchSize(size: Int) {
        viewModelScope.launch { settingsRepository.setBatchSize(size) }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBackgroundSyncEnabled(enabled) }
    }

    fun setMarketHoursOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMarketHoursOnly(enabled) }
    }

    private fun SyncSettings.toUiState() = SettingsUiState(
        refreshIntervalMinutes = refreshInterval.inWholeMinutes.toInt(),
        batchSize = batchSize,
        backgroundSyncEnabled = backgroundSyncEnabled,
        marketHoursOnly = marketHoursOnly,
    )
}
