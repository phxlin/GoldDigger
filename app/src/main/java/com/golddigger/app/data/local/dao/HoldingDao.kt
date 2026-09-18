package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.relation.HoldingRow
import kotlinx.coroutines.flow.Flow

@Dao
interface HoldingDao {

    @Insert
    suspend fun insert(holding: HoldingEntity): Long

    @Update
    suspend fun update(holding: HoldingEntity)

    @Delete
    suspend fun delete(holding: HoldingEntity)

    @Query("SELECT * FROM holdings WHERE id = :id")
    suspend fun findById(id: Long): HoldingEntity?

    @Query("SELECT * FROM holdings WHERE ticker = :ticker LIMIT 1")
    suspend fun findByTicker(ticker: String): HoldingEntity?

    @Query(
        """
        SELECT h.id, h.ticker, h.shares, h.costBasis, h.dateAdded,
               s.companyName AS companyName, s.sector AS sector, s.isEtf AS isEtf,
               p.price AS price, p.dayChangePct AS dayChangePct,
               p.lastUpdated AS priceUpdatedAt
        FROM holdings h
        INNER JOIN stocks s ON s.ticker = h.ticker
        LEFT JOIN price_cache p ON p.ticker = h.ticker
        ORDER BY h.ticker
        """,
    )
    fun observeHoldingRows(): Flow<List<HoldingRow>>

    @Query(
        """
        SELECT h.id, h.ticker, h.shares, h.costBasis, h.dateAdded,
               s.companyName AS companyName, s.sector AS sector, s.isEtf AS isEtf,
               p.price AS price, p.dayChangePct AS dayChangePct,
               p.lastUpdated AS priceUpdatedAt
        FROM holdings h
        INNER JOIN stocks s ON s.ticker = h.ticker
        LEFT JOIN price_cache p ON p.ticker = h.ticker
        WHERE h.id = :id
        """,
    )
    fun observeHoldingRow(id: Long): Flow<HoldingRow?>

    /**
     * Sum of shares*price across every priced holding (cash included — its
     * cached price is always 1.0) — the denominator a single holding's
     * [com.golddigger.app.domain.model.HoldingValuation.portfolioWeightPct]
     * is measured against. Lets [observeHoldingRow] serve Holding Detail
     * without recomputing [com.golddigger.app.domain.PortfolioCalculator]
     * over every holding just to get one row's share of the total.
     */
    @Query(
        """
        SELECT SUM(h.shares * p.price)
        FROM holdings h
        INNER JOIN price_cache p ON p.ticker = h.ticker
        WHERE p.price > 0
        """,
    )
    fun observeTotalMarketValue(): Flow<Double?>

    @Query("SELECT DISTINCT ticker FROM holdings")
    suspend fun distinctTickers(): List<String>

    @Query("SELECT COUNT(*) FROM holdings")
    fun observeCount(): Flow<Int>

    /** Formation-view manual role overrides (holding id -> role name), non-null rows only. */
    @Query("SELECT id, roleOverride FROM holdings WHERE roleOverride IS NOT NULL")
    fun observeRoleOverrides(): Flow<List<HoldingRoleOverride>>

    @Query("UPDATE holdings SET roleOverride = :role WHERE id = :id")
    suspend fun setRoleOverride(id: Long, role: String?)
}

data class HoldingRoleOverride(
    val id: Long,
    val roleOverride: String?,
)
