package com.golddigger.app.di

import android.content.Context
import androidx.room.Room
import com.golddigger.app.data.local.GoldDiggerDatabase
import com.golddigger.app.data.local.Migrations
import com.golddigger.app.data.local.dao.BackupDao
import com.golddigger.app.data.local.dao.GroupDao
import com.golddigger.app.data.local.dao.HoldingDao
import com.golddigger.app.data.local.dao.NewsDao
import com.golddigger.app.data.local.dao.PriceDao
import com.golddigger.app.data.local.dao.StockDao
import com.golddigger.app.data.local.dao.TransactionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GoldDiggerDatabase =
        Room.databaseBuilder(context, GoldDiggerDatabase::class.java, GoldDiggerDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            // A downgrade should never happen in production, but if a debug build
            // with a higher version is replaced by an older APK we recreate
            // rather than crash. Upgrades always go through explicit migrations.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun provideStockDao(db: GoldDiggerDatabase): StockDao = db.stockDao()
    @Provides fun provideHoldingDao(db: GoldDiggerDatabase): HoldingDao = db.holdingDao()
    @Provides fun provideTransactionDao(db: GoldDiggerDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideGroupDao(db: GoldDiggerDatabase): GroupDao = db.groupDao()
    @Provides fun providePriceDao(db: GoldDiggerDatabase): PriceDao = db.priceDao()
    @Provides fun provideNewsDao(db: GoldDiggerDatabase): NewsDao = db.newsDao()
    @Provides fun provideBackupDao(db: GoldDiggerDatabase): BackupDao = db.backupDao()
}
