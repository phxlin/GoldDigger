package com.golddigger.app.data.backup

import androidx.room.withTransaction
import com.golddigger.app.core.CashHolding
import com.golddigger.app.data.UserDataLock
import com.golddigger.app.data.local.GoldDiggerDatabase
import com.golddigger.app.data.local.dao.BackupDao
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.settings.SettingsRepository
import com.golddigger.app.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** [settingsApplied] is false when the data was imported but writing the sync settings failed. */
data class ImportSummary(val holdings: Int, val groups: Int, val settingsApplied: Boolean = true)

/**
 * Reads the whole user-data set out to a [Backup] JSON string, and replaces it
 * from one. Everything an import touches happens in a single Room transaction,
 * and the file is fully validated before that starts, so an invalid backup
 * leaves the existing data exactly as it was.
 */
@Singleton
class BackupManager @Inject constructor(
    private val db: GoldDiggerDatabase,
    private val backupDao: BackupDao,
    private val priceDao: PriceDao,
    private val newsDao: NewsDao,
    private val settingsRepository: SettingsRepository,
    private val userDataLock: UserDataLock,
    @IoDispatcher private val io: CoroutineDispatcher,
    @Named("epochClock") private val clock: () -> Long,
) {

    /** Whether there is any holding or group on the device, i.e. whether an import would replace something. */
    val hasData: Flow<Boolean> get() = backupDao.observeHasData()

    suspend fun exportJson(): String = withContext(io) {
        // One transaction so the tables are read as a single consistent snapshot,
        // even if a background price sync is writing at the same time.
        db.withTransaction {
            Backup.toJson(
                stocks = backupDao.stocks(),
                holdings = backupDao.holdings(),
                groups = backupDao.groups(),
                groupMembers = backupDao.groupMembers(),
                pricePoints = backupDao.pricePoints(),
                settings = settingsRepository.settings.first(),
            )
        }
    }

    /**
     * Removes every holding, group and recorded price from this device (sync settings are
     * kept), in one transaction.
     */
    suspend fun deleteAll() = withContext(io) {
        inTransaction {
            clearUserData()
            priceDao.pruneOrphans()
            newsDao.pruneOrphans()
        }
    }

    /**
     * Replaces all local holdings, groups and price history with [text]'s
     * contents. Throws [BackupException] if it isn't a valid backup; nothing
     * changes in that case.
     */
    suspend fun importJson(text: String): ImportSummary = withContext(io) {
        val parsed = Backup.parse(text)

        inTransaction {
            clearUserData()

            // Parents before children: stocks and groups, then what references them.
            backupDao.insertStocks(parsed.stocks)
            backupDao.insertGroups(parsed.groups)
            backupDao.insertHoldings(parsed.holdings)
            backupDao.insertGroupMembers(parsed.groupMembers)
            backupDao.insertPricePoints(parsed.pricePoints)

            // Cached quotes/news for tickers that are gone; everything still held
            // keeps its cached price until the next sync refreshes it.
            priceDao.pruneOrphans()
            newsDao.pruneOrphans()

            // Cash is never quoted, so nothing would ever re-create its pinned $1
            // price if an import onto a fresh install left it without one.
            if (parsed.holdings.any { CashHolding.isCashTicker(it.ticker) }) {
                priceDao.upsert(PriceCacheEntity(CashHolding.TICKER, price = 1.0, dayChangePct = 0.0, lastUpdated = clock()))
            }
        }

        // The data above is already committed, so a settings failure is reported
        // as a partial import rather than thrown as if nothing had changed.
        val settingsApplied = runCatching {
            parsed.settings?.let { s ->
                s.refreshIntervalMinutes?.let { settingsRepository.setRefreshIntervalMinutes(it) }
                s.batchSize?.let { settingsRepository.setBatchSize(it) }
                s.backgroundSyncEnabled?.let { settingsRepository.setBackgroundSyncEnabled(it) }
                s.marketHoursOnly?.let { settingsRepository.setMarketHoursOnly(it) }
            }
        }.onFailure { if (it is CancellationException) throw it }.isSuccess

        ImportSummary(holdings = parsed.holdings.size, groups = parsed.groups.size, settingsApplied = settingsApplied)
    }

    /** A Room transaction that can't interleave with a sync's price / news writes. */
    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        userDataLock.withLock { db.withTransaction { block() } }

    private suspend fun clearUserData() {
        // Cascades take holdings, group memberships and transactions with them.
        backupDao.deleteAllStocks()
        backupDao.deleteAllGroups()
        backupDao.deleteAllPricePoints()
    }
}
