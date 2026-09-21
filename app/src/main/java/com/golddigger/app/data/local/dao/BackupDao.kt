package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * Whole-table reads, wipes and bulk inserts used only by
 * [com.golddigger.app.data.backup.BackupManager], kept apart from the
 * per-feature DAOs so backup export/import never widens their interfaces.
 */
@Dao
interface BackupDao {

    /** True while there is anything an import or delete would replace: a holding or a group. */
    @Query("SELECT EXISTS(SELECT 1 FROM holdings) OR EXISTS(SELECT 1 FROM groups)")
    fun observeHasData(): Flow<Boolean>

    @Query("SELECT * FROM stocks ORDER BY ticker")
    suspend fun stocks(): List<StockEntity>

    @Query("SELECT * FROM holdings ORDER BY id")
    suspend fun holdings(): List<HoldingEntity>

    @Query("SELECT * FROM groups ORDER BY id")
    suspend fun groups(): List<GroupEntity>

    @Query("SELECT * FROM stock_group_cross_ref ORDER BY groupId, ticker")
    suspend fun groupMembers(): List<StockGroupCrossRef>

    @Query("SELECT * FROM price_points ORDER BY ticker, timestamp")
    suspend fun pricePoints(): List<PricePointEntity>

    // Deleting stocks cascades to holdings, transactions and group memberships;
    // deleting groups cascades to memberships.
    @Query("DELETE FROM stocks")
    suspend fun deleteAllStocks()

    @Query("DELETE FROM groups")
    suspend fun deleteAllGroups()

    @Query("DELETE FROM price_points")
    suspend fun deleteAllPricePoints()

    @Insert
    suspend fun insertStocks(items: List<StockEntity>)

    @Insert
    suspend fun insertHoldings(items: List<HoldingEntity>)

    @Insert
    suspend fun insertGroups(items: List<GroupEntity>)

    @Insert
    suspend fun insertGroupMembers(items: List<StockGroupCrossRef>)

    @Insert
    suspend fun insertPricePoints(items: List<PricePointEntity>)
}
