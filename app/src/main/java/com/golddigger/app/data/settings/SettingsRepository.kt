package com.golddigger.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.golddigger.app.core.SyncConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class SyncSettings(
    val refreshInterval: Duration,
    val batchSize: Int,
    val backgroundSyncEnabled: Boolean,
    val marketHoursOnly: Boolean,
)

/**
 * User-tunable sync behaviour. Defaults come from [SyncConfig]; these overrides
 * exist so a user on a paid API tier can shorten the interval / raise the batch
 * size without an app update.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val refreshMinutes = intPreferencesKey("refresh_interval_minutes")
        val batchSize = intPreferencesKey("batch_size")
        val backgroundEnabled = booleanPreferencesKey("background_sync_enabled")
        val marketHoursOnly = booleanPreferencesKey("market_hours_only")
    }

    val settings: Flow<SyncSettings> = dataStore.data.map { prefs ->
        SyncSettings(
            refreshInterval = (prefs[Keys.refreshMinutes]
                ?: SyncConfig.DEFAULT_REFRESH_INTERVAL.inWholeMinutes.toInt()).minutes
                .coerceIn(SyncConfig.MIN_REFRESH_INTERVAL, SyncConfig.MAX_REFRESH_INTERVAL),
            batchSize = (prefs[Keys.batchSize] ?: SyncConfig.DEFAULT_BATCH_SIZE)
                .coerceAtLeast(1),
            backgroundSyncEnabled = prefs[Keys.backgroundEnabled] ?: true,
            marketHoursOnly = prefs[Keys.marketHoursOnly] ?: true,
        )
    }

    suspend fun setRefreshIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(
            SyncConfig.MIN_REFRESH_INTERVAL.inWholeMinutes.toInt(),
            SyncConfig.MAX_REFRESH_INTERVAL.inWholeMinutes.toInt(),
        )
        dataStore.edit { it[Keys.refreshMinutes] = clamped }
    }

    suspend fun setBatchSize(size: Int) {
        dataStore.edit { it[Keys.batchSize] = size.coerceAtLeast(1) }
    }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.backgroundEnabled] = enabled }
    }

    suspend fun setMarketHoursOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.marketHoursOnly] = enabled }
    }
}
