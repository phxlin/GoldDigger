package com.golddigger.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.golddigger.app.core.SyncConfig
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.data.settings.SyncSettings
import com.golddigger.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.toJavaDuration

/**
 * Owns the WorkManager registration for [PriceSyncWorker]. Re-registers whenever
 * the relevant settings change so the interval/enabled toggle take effect
 * without an app restart.
 */
@Singleton
class PriceSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Called once from Application.onCreate(). */
    fun initialize() {
        settingsRepository.settings
            .distinctUntilChanged { a, b ->
                a.backgroundSyncEnabled == b.backgroundSyncEnabled &&
                    a.refreshInterval == b.refreshInterval
            }
            .onEach { reschedule(it) }
            .launchIn(scope)
    }

    private fun reschedule(settings: SyncSettings) {
        if (!settings.backgroundSyncEnabled) {
            workManager.cancelUniqueWork(SyncConfig.WORK_NAME_PERIODIC_SYNC)
            return
        }
        val interval = settings.refreshInterval
            .coerceAtLeast(SyncConfig.MIN_REFRESH_INTERVAL)
            .toJavaDuration()

        val request = PeriodicWorkRequestBuilder<PriceSyncWorker>(interval)
            .addTag(PriceSyncWorker.TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncConfig.WORK_NAME_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Fire-and-forget immediate sync, e.g. right after the user adds a holding. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<PriceSyncWorker>()
            .addTag(PriceSyncWorker.TAG)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniqueWork(
            "${SyncConfig.WORK_NAME_PERIODIC_SYNC}-oneshot",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
