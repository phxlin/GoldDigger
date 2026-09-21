package com.golddigger.app.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golddigger.app.core.SyncConfig
import com.golddigger.app.data.backup.BackupException
import com.golddigger.app.data.backup.BackupManager
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.data.settings.SyncSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
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
    private val backupManager: BackupManager,
    private val portfolioRepository: PortfolioRepository,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    /** One-shot status for the screen's snackbar; call [messageShown] once it's been displayed. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    /** Whether any holding or group exists, so importing a backup can say whether it would replace anything. Null until known. */
    val hasData: StateFlow<Boolean?> = backupManager.hasData
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    fun exportBackup(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = backupManager.exportJson()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Couldn't open the file")
                }
                "Backup saved."
            } catch (e: Exception) {
                "Couldn't save the backup."
            }
        }
    }

    fun importBackup(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val text = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IOException("Couldn't open the file")
                }
                val summary = backupManager.importJson(text)
                // Prices, news and risk metrics aren't part of a backup; fetch them for what was imported.
                launch {
                    portfolioRepository.refreshPrices(force = false)
                    portfolioRepository.refreshRiskMetrics(force = false)
                }
                val imported = "Backup imported: ${summary.holdings} ${if (summary.holdings == 1) "holding" else "holdings"}, " +
                    "${summary.groups} ${if (summary.groups == 1) "group" else "groups"}."
                if (summary.settingsApplied) imported else "$imported Your sync settings couldn't be imported."
            } catch (e: BackupException) {
                "Not imported: ${e.message}"
            } catch (e: Exception) {
                "Couldn't read that file."
            }
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            _message.value = try {
                backupManager.deleteAll()
                "All holdings and groups deleted."
            } catch (e: Exception) {
                "Couldn't delete your data."
            }
        }
    }

    private fun SyncSettings.toUiState() = SettingsUiState(
        refreshIntervalMinutes = refreshInterval.inWholeMinutes.toInt(),
        batchSize = batchSize,
        backgroundSyncEnabled = backgroundSyncEnabled,
        marketHoursOnly = marketHoursOnly,
    )
}
