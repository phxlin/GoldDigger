package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.local.relation.GroupWithStocks
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Delete
    suspend fun delete(group: GroupEntity)

    @Query("SELECT * FROM `groups` ORDER BY name")
    fun observeGroups(): Flow<List<GroupEntity>>

    @Transaction
    @Query("SELECT * FROM `groups` ORDER BY name")
    fun observeGroupsWithStocks(): Flow<List<GroupWithStocks>>

    @Transaction
    @Query("SELECT * FROM `groups` WHERE id = :id")
    fun observeGroupWithStocks(id: Long): Flow<GroupWithStocks?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addStockToGroup(ref: StockGroupCrossRef)

    @Delete
    suspend fun removeStockFromGroup(ref: StockGroupCrossRef)

    @Query("SELECT groupId FROM stock_group_cross_ref WHERE ticker = :ticker")
    fun observeGroupIdsForTicker(ticker: String): Flow<List<Long>>

    @Query("DELETE FROM stock_group_cross_ref WHERE ticker = :ticker")
    suspend fun clearGroupsForTicker(ticker: String)
}
