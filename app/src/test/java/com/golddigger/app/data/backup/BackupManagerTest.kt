package com.golddigger.app.data.backup

import androidx.room.withTransaction
import com.golddigger.app.data.UserDataLock
import com.golddigger.app.data.local.GoldDiggerDatabase
import com.golddigger.app.data.local.dao.BackupDao
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.settings.SettingsRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

class BackupManagerTest {

    private val db = mockk<GoldDiggerDatabase>(relaxed = true)
    private val backupDao = mockk<BackupDao>(relaxed = true)
    private val priceDao = mockk<PriceDao>(relaxed = true)
    private val newsDao = mockk<NewsDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private val lock = UserDataLock()
    private val manager = BackupManager(db, backupDao, priceDao, newsDao, settings, lock, Dispatchers.Unconfined) { 1_000L }

    private val validBackup = """{"app":"GoldDigger","version":1,
        "settings":{"refreshIntervalMinutes":30,"batchSize":1},
        "stocks":[{"ticker":"VOO"}],
        "holdings":[{"id":1,"ticker":"VOO","shares":2,"costBasis":100,"dateAdded":5}],
        "groups":[],"groupMembers":[],"priceHistory":{}}"""

    @Before
    fun runTransactionBodiesInline() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { db.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            secondArg<suspend () -> Any?>().invoke()
        }
    }

    @After
    fun tearDown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    @Test
    fun `a settings failure after the data commits is reported as a partial import`() = runTest {
        coEvery { settings.setBatchSize(any()) } throws IOException("disk full")

        val summary = manager.importJson(validBackup)

        assertThat(summary.settingsApplied).isFalse()
        assertThat(summary.holdings).isEqualTo(1)
        coVerify(exactly = 1) { backupDao.insertHoldings(any()) }
    }

    @Test
    fun `a clean import applies the settings and says so`() = runTest {
        val summary = manager.importJson(validBackup)

        assertThat(summary.settingsApplied).isTrue()
        coVerify { settings.setRefreshIntervalMinutes(30) }
        coVerify { settings.setBatchSize(1) }
    }

    @Test
    fun `deleting all data clears the user tables and keeps the settings`() = runTest {
        manager.deleteAll()

        coVerify(exactly = 1) { backupDao.deleteAllStocks() }
        coVerify(exactly = 1) { backupDao.deleteAllGroups() }
        coVerify(exactly = 1) { backupDao.deleteAllPricePoints() }
        coVerify(exactly = 1) { priceDao.pruneOrphans() }
        coVerify(exactly = 1) { newsDao.pruneOrphans() }
        coVerify(exactly = 0) { settings.setBatchSize(any()) }
        coVerify(exactly = 0) { settings.setRefreshIntervalMinutes(any()) }
    }

    @Test
    fun `hasData follows the database, holdings or groups`() = runTest {
        every { backupDao.observeHasData() } returns flowOf(true)

        assertThat(manager.hasData.first()).isTrue()
    }

    @Test
    fun `deleting waits for a sync write that holds the data lock`() = runTest {
        val release = CompletableDeferred<Unit>()
        val syncWrite = launch { lock.withLock { release.await() } }
        runCurrent()

        val delete = launch { manager.deleteAll() }
        runCurrent()
        coVerify(exactly = 0) { backupDao.deleteAllStocks() }

        release.complete(Unit)
        syncWrite.join()
        delete.join()
        coVerify(exactly = 1) { backupDao.deleteAllStocks() }
    }

    @Test
    fun `importing waits for a sync write that holds the data lock`() = runTest {
        val release = CompletableDeferred<Unit>()
        val syncWrite = launch { lock.withLock { release.await() } }
        runCurrent()

        val import = launch { manager.importJson(validBackup) }
        runCurrent()
        coVerify(exactly = 0) { backupDao.insertHoldings(any()) }

        release.complete(Unit)
        syncWrite.join()
        import.join()
        coVerify(exactly = 1) { backupDao.insertHoldings(any()) }
    }

    @Test
    fun `an invalid backup touches nothing`() = runTest {
        val result = runCatching { manager.importJson("""{"app":"GoldDigger","version":1,"stocks":[]}""") }

        assertThat(result.exceptionOrNull()).isInstanceOf(BackupException::class.java)
        coVerify(exactly = 0) { backupDao.deleteAllStocks() }
        coVerify(exactly = 0) { backupDao.deleteAllGroups() }
        coVerify(exactly = 0) { backupDao.deleteAllPricePoints() }
    }
}
