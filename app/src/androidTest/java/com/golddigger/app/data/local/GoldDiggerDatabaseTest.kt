package com.golddigger.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.NewsCacheEntity
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoldDiggerDatabaseTest {

    private lateinit var db: GoldDiggerDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GoldDiggerDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun holdingRowJoinsCompanyAndPrice() = runTest {
        db.stockDao().upsert(StockEntity("NVDA", "NVIDIA Corp", "Semiconductors"))
        db.holdingDao().insert(HoldingEntity(ticker = "NVDA", shares = 3.0, costBasis = 300.0, dateAdded = 0))

        db.holdingDao().observeHoldingRows().test {
            val beforePrice = awaitItem().single()
            assertThat(beforePrice.companyName).isEqualTo("NVIDIA Corp")
            assertThat(beforePrice.price).isNull()

            db.priceDao().upsert(PriceCacheEntity("NVDA", 120.0, 2.0, 999))
            val afterPrice = awaitItem().single()
            assertThat(afterPrice.price).isEqualTo(120.0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deletingHoldingKeepsAStillGroupedStock_thenPrunesWhenUngrouped() = runTest {
        db.stockDao().upsert(StockEntity("AAPL", "Apple", null))
        val holdingId = db.holdingDao().insert(
            HoldingEntity(ticker = "AAPL", shares = 1.0, costBasis = 10.0, dateAdded = 0),
        )
        val groupId = db.groupDao().insert(GroupEntity(name = "Big Tech", targetAllocationPct = 40.0))
        db.groupDao().addStockToGroup(StockGroupCrossRef("AAPL", groupId))

        // Deleting the holding removes the position...
        db.holdingDao().delete(db.holdingDao().findById(holdingId)!!)
        db.stockDao().pruneOrphans()
        assertThat(db.holdingDao().distinctTickers()).isEmpty()

        // ...but the stock and its group membership survive because it is still
        // tagged into a group.
        assertThat(db.stockDao().findByTicker("AAPL")).isNotNull()
        db.groupDao().observeGroupIdsForTicker("AAPL").test {
            assertThat(awaitItem()).containsExactly(groupId)
            cancelAndIgnoreRemainingEvents()
        }

        // Remove it from the group and prune -> the orphan stock is gone.
        db.groupDao().clearGroupsForTicker("AAPL")
        db.stockDao().pruneOrphans()
        assertThat(db.stockDao().findByTicker("AAPL")).isNull()
    }

    @Test
    fun priceHistoryTrimKeepsNewestPointsPerTicker() = runTest {
        db.stockDao().upsert(StockEntity("T", "T Inc", null))
        repeat(150) { i ->
            db.priceDao().insertPoint(
                com.golddigger.app.data.local.entity.PricePointEntity(
                    ticker = "T", price = i.toDouble(), timestamp = i.toLong(),
                ),
            )
        }
        db.priceDao().trimHistory(120)

        db.priceDao().observeRecentPoints("T", limit = 500).test {
            val points = awaitItem()
            assertThat(points).hasSize(120)
            assertThat(points.first().price).isEqualTo(149.0) // newest first
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun roleOverridePersistsAndIsObservedForFormation() = runTest {
        db.stockDao().upsert(StockEntity("NVDA", "NVIDIA", "Semiconductors"))
        val id = db.holdingDao().insert(
            HoldingEntity(ticker = "NVDA", shares = 2.0, costBasis = 200.0, dateAdded = 0),
        )

        db.holdingDao().observeRoleOverrides().test {
            assertThat(awaitItem()).isEmpty()

            db.holdingDao().setRoleOverride(id, "DEFENSE")
            assertThat(awaitItem().single().roleOverride).isEqualTo("DEFENSE")

            db.holdingDao().setRoleOverride(id, null)
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun riskColumnsRoundTrip() = runTest {
        db.stockDao().upsert(StockEntity("NVDA", "NVIDIA", "Semiconductors"))
        db.stockDao().updateRisk("NVDA", beta = 1.8, sectorCorrelation = 0.72, updatedAt = 123L)
        val stock = db.stockDao().findByTicker("NVDA")!!
        assertThat(stock.beta).isEqualTo(1.8)
        assertThat(stock.sectorCorrelation).isEqualTo(0.72)
        assertThat(stock.riskUpdatedAt).isEqualTo(123L)
    }

    @Test
    fun newsCacheObservesNewestFirstAndTrimsPerTicker() = runTest {
        db.stockDao().upsert(StockEntity("NVDA", "NVIDIA", null))
        db.newsDao().upsertAll(
            (1..40).map { i ->
                NewsCacheEntity(
                    ticker = "NVDA",
                    id = i.toLong(),
                    headline = "Story $i",
                    summary = "",
                    source = "Wire",
                    url = "https://example.com/$i",
                    imageUrl = null,
                    publishedAt = i.toLong() * 1000,
                    fetchedAt = 0,
                )
            },
        )
        db.newsDao().trim(25)

        db.newsDao().observeForTicker("NVDA", limit = 100).test {
            val items = awaitItem()
            assertThat(items).hasSize(25)
            assertThat(items.first().headline).isEqualTo("Story 40")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
