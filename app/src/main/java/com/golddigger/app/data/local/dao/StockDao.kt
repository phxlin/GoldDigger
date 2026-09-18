package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.golddigger.app.data.local.entity.StockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StockDao {

    @Upsert
    suspend fun upsert(stock: StockEntity)

    @Query("SELECT * FROM stocks WHERE ticker = :ticker")
    suspend fun findByTicker(ticker: String): StockEntity?

    @Query("SELECT * FROM stocks ORDER BY ticker")
    fun observeAll(): Flow<List<StockEntity>>

    @Query("SELECT ticker FROM stocks")
    suspend fun allTickers(): List<String>

    @Query("SELECT * FROM stocks")
    suspend fun all(): List<StockEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(stock: StockEntity)

    /** Formation-view risk metrics, written by the weekly refresh. */
    @Query(
        """
        UPDATE stocks
        SET beta = :beta, sectorCorrelation = :sectorCorrelation, riskUpdatedAt = :updatedAt
        WHERE ticker = :ticker
        """,
    )
    suspend fun updateRisk(
        ticker: String,
        beta: Double?,
        sectorCorrelation: Double?,
        updatedAt: Long,
    )

    /** Reclassifies a tracked ticker as an ETF or individual stock. */
    @Query("UPDATE stocks SET isEtf = :isEtf WHERE ticker = :ticker")
    suspend fun updateInstrumentType(ticker: String, isEtf: Boolean)

    /** Removes stocks that are no longer referenced by any holding or group. */
    @Query(
        """
        DELETE FROM stocks
        WHERE ticker NOT IN (SELECT ticker FROM holdings)
          AND ticker NOT IN (SELECT ticker FROM stock_group_cross_ref)
        """,
    )
    suspend fun pruneOrphans()
}
