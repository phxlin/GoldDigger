package com.golddigger.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.golddigger.app.core.MarketHours
import com.golddigger.app.data.repository.PortfolioRepository
import com.golddigger.app.data.repository.SyncState
import com.golddigger.app.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic background price sync. Constraints (network, battery) are attached by
 * [PriceSyncScheduler]; this worker only decides whether *now* is a good time
 * and delegates the actual work — batching, throttling, caching — to the
 * repository, which is the single source of truth.
 */
@HiltWorker
class PriceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: PortfolioRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.backgroundSyncEnabled) return Result.success()
        if (settings.marketHoursOnly && !MarketHours.isMarketOpen()) return Result.success()

        val result = repository.refreshPrices(force = false)
        // Cheap: honours a 7-day TTL, so the vast majority of runs are a no-op.
        repository.refreshRiskMetrics(force = false)

        return when (result) {
            is SyncState.Offline -> Result.retry()
            // Rate limiting is expected on a free tier — wait for the next
            // scheduled run rather than burning retries/backoff.
            is SyncState.RateLimited,
            is SyncState.NoApiKey,
            is SyncState.UpToDate,
            SyncState.Idle,
            SyncState.Syncing,
            -> Result.success()
        }
    }

    companion object {
        const val TAG = "PriceSyncWorker"
    }
}
