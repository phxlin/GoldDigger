package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {

    @Upsert
    suspend fun upsert(price: PriceCacheEntity)

    @Upsert
    suspend fun upsertAll(prices: List<PriceCacheEntity>)

    @Query("SELECT * FROM price_cache WHERE ticker = :ticker")
    suspend fun find(ticker: String): PriceCacheEntity?

    @Query("SELECT * FROM price_cache")
    suspend fun all(): List<PriceCacheEntity>

    @Query("SELECT * FROM price_cache")
    fun observeAll(): Flow<List<PriceCacheEntity>>

    @Query("SELECT MIN(lastUpdated) FROM price_cache WHERE ticker IN (:tickers)")
    suspend fun oldestUpdateAmong(tickers: List<String>): Long?

    @Insert
    suspend fun insertPoint(point: PricePointEntity)

    @Insert
    suspend fun insertPoints(points: List<PricePointEntity>)

    /** The most recently recorded point for each of [tickers] that has any. */
    @Query(
        "SELECT * FROM price_points WHERE ticker IN (:tickers) AND timestamp = " +
            "(SELECT MAX(timestamp) FROM price_points p WHERE p.ticker = price_points.ticker)",
    )
    suspend fun latestPoints(tickers: List<String>): List<PricePointEntity>

    @Query(
        "SELECT * FROM price_points WHERE ticker = :ticker " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    fun observeRecentPoints(ticker: String, limit: Int = 60): Flow<List<PricePointEntity>>

    /**
     * [ticker]'s recorded price points at or after [sinceEpochMs], oldest
     * first — the Holding Detail range selector (1D/5D/1M/…) resolves its
     * chosen window to a lower-bound timestamp and reads through here; pass 0
     * for "everything retained" (the Max range).
     */
    @Query(
        "SELECT * FROM price_points WHERE ticker = :ticker AND timestamp >= :sinceEpochMs " +
            "ORDER BY timestamp ASC",
    )
    fun observePointsSince(ticker: String, sinceEpochMs: Long): Flow<List<PricePointEntity>>

    /**
     * All retained price points, oldest first. Bounded by [trimHistory]
     * (default 5000 per ticker), so this stays small even with a large
     * portfolio. Used by the Formation view to estimate realized volatility
     * and sector correlation.
     */
    @Query("SELECT * FROM price_points ORDER BY timestamp ASC")
    fun observeAllPoints(): Flow<List<PricePointEntity>>

    @Query("SELECT * FROM price_points ORDER BY timestamp ASC")
    suspend fun allPoints(): List<PricePointEntity>

    @Query(
        "SELECT * FROM price_points WHERE ticker = :ticker " +
            "ORDER BY timestamp ASC LIMIT :limit",
    )
    suspend fun recentPointsAsc(ticker: String, limit: Int): List<PricePointEntity>

    /** Drops cached quotes for tickers that no longer exist in `stocks`. */
    @Query("DELETE FROM price_cache WHERE ticker NOT IN (SELECT ticker FROM stocks)")
    suspend fun pruneOrphans()

    /** Drops recorded price points for tickers that no longer exist in `stocks`. */
    @Query("DELETE FROM price_points WHERE ticker NOT IN (SELECT ticker FROM stocks)")
    suspend fun prunePointOrphans()

    /**
     * Keep the history table bounded: drop all but the newest [keep] points
     * per ticker. Raised well past a single day's worth of 15-minute syncs so
     * the Holding Detail range selector's longer windows (1M/6M/1Y/5Y/…) have
     * real accumulated data to show over time, instead of it being trimmed
     * away within a day or two of being written.
     */
    @Query(
        """
        DELETE FROM price_points
        WHERE id NOT IN (
            SELECT id FROM price_points p
            WHERE p.ticker = price_points.ticker
            ORDER BY timestamp DESC
            LIMIT :keep
        )
        """,
    )
    suspend fun trimHistory(keep: Int = 5000)
}
