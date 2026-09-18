package com.golddigger.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.golddigger.app.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE ticker = :ticker ORDER BY timestamp")
    suspend fun forTicker(ticker: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE ticker = :ticker ORDER BY timestamp DESC")
    fun observeForTicker(ticker: String): Flow<List<TransactionEntity>>
}
