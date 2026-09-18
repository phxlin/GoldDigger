package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.golddigger.app.data.local.entity.NewsCacheEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {

    @Upsert
    suspend fun upsertAll(items: List<NewsCacheEntity>)

    @Query(
        "SELECT * FROM news_cache WHERE ticker = :ticker " +
            "ORDER BY publishedAt DESC LIMIT :limit",
    )
    fun observeForTicker(ticker: String, limit: Int = 20): Flow<List<NewsCacheEntity>>

    @Query("SELECT MAX(fetchedAt) FROM news_cache WHERE ticker = :ticker")
    suspend fun lastFetchedAt(ticker: String): Long?

    @Query("DELETE FROM news_cache WHERE ticker NOT IN (SELECT ticker FROM stocks)")
    suspend fun pruneOrphans()

    /** Keep only the newest [keep] articles per ticker. */
    @Query(
        """
        DELETE FROM news_cache
        WHERE id NOT IN (
            SELECT n.id FROM news_cache n
            WHERE n.ticker = news_cache.ticker
            ORDER BY n.publishedAt DESC
            LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int = 25)
}
