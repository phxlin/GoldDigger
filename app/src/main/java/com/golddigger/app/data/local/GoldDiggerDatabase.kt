package com.golddigger.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.golddigger.app.data.local.dao.GroupDao
import com.golddigger.app.data.local.dao.HoldingDao
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.local.dao.StockDao
import com.golddigger.app.data.local.dao.TransactionDao
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.NewsCacheEntity
import com.golddigger.app.data.local.entity.PriceCacheEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.local.entity.TransactionEntity

@Database(
    entities = [
        StockEntity::class,
        HoldingEntity::class,
        TransactionEntity::class,
        GroupEntity::class,
        StockGroupCrossRef::class,
        PriceCacheEntity::class,
        PricePointEntity::class,
        NewsCacheEntity::class,
    ],
    version = GoldDiggerDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GoldDiggerDatabase : RoomDatabase() {

    abstract fun stockDao(): StockDao
    abstract fun holdingDao(): HoldingDao
    abstract fun transactionDao(): TransactionDao
    abstract fun groupDao(): GroupDao
    abstract fun priceDao(): PriceDao
    abstract fun newsDao(): NewsDao

    companion object {
        const val VERSION = 5
        const val NAME = "golddigger.db"
    }
}
