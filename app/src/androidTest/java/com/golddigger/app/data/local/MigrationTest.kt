package com.golddigger.app.data.local

import android.content.ContentValues
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.golddigger.app.data.local.Migrations.MIGRATION_1_2
import com.golddigger.app.data.local.Migrations.MIGRATION_2_3
import com.golddigger.app.data.local.Migrations.MIGRATION_3_4
import com.golddigger.app.data.local.Migrations.MIGRATION_4_5
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Proves the migration machinery is wired end to end:
 *  - each version's schema JSON is exported and loadable,
 *  - a v1 database can be created, migrated to v2, and its data survives,
 *  - the production Room builder (with [Migrations.ALL]) opens cleanly.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @Suppress("DEPRECATION")
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GoldDiggerDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun version1SchemaIsCreatable() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            assertThat(db.version).isEqualTo(1)
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To2_keepsHoldingsAndAddsNewsTable() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.insert(
                "stocks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("ticker", "AAPL")
                    put("companyName", "Apple Inc")
                    putNull("sector")
                },
            )
            db.insert(
                "holdings",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("ticker", "AAPL")
                    put("shares", 10.0)
                    put("costBasis", 1500.0)
                    put("dateAdded", 0L)
                },
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT shares, costBasis FROM holdings WHERE ticker = 'AAPL'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getDouble(0)).isEqualTo(10.0)
            assertThat(c.getDouble(1)).isEqualTo(1500.0)
        }
        // The new table exists and is queryable.
        db.query("SELECT COUNT(*) FROM news_cache").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3_keepsDataAndAddsRiskColumns() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.insert(
                "stocks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("ticker", "NVDA")
                    put("companyName", "NVIDIA Corp")
                    put("sector", "Semiconductors")
                },
            )
            db.insert(
                "holdings",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("ticker", "NVDA")
                    put("shares", 4.0)
                    put("costBasis", 400.0)
                    put("dateAdded", 0L)
                },
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        // Existing rows are untouched...
        db.query("SELECT shares, costBasis, roleOverride FROM holdings WHERE ticker = 'NVDA'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getDouble(0)).isEqualTo(4.0)
            assertThat(c.getDouble(1)).isEqualTo(400.0)
            assertThat(c.isNull(2)).isTrue()
        }
        // ...and the new risk columns exist and default to null.
        db.query("SELECT beta, sectorCorrelation, riskUpdatedAt FROM stocks WHERE ticker = 'NVDA'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
            assertThat(c.isNull(1)).isTrue()
            assertThat(c.isNull(2)).isTrue()
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_keepsDataAndAddsIsEtfColumn() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.insert(
                "stocks",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("ticker", "VOO")
                    put("companyName", "Vanguard S&P 500 ETF")
                    putNull("sector")
                },
            )
            db.insert(
                "holdings",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("ticker", "VOO")
                    put("shares", 2.0)
                    put("costBasis", 900.0)
                    put("dateAdded", 0L)
                },
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        // Existing rows are untouched...
        db.query("SELECT shares, costBasis FROM holdings WHERE ticker = 'VOO'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getDouble(0)).isEqualTo(2.0)
            assertThat(c.getDouble(1)).isEqualTo(900.0)
        }
        // ...and the new column exists and defaults every existing stock to "not an ETF".
        db.query("SELECT isEtf FROM stocks WHERE ticker = 'VOO'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5_keepsDataAndAddsIsEtfGroupColumn() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.insert(
                "groups",
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                ContentValues().apply {
                    put("id", 1L)
                    put("name", "Bonds")
                    put("targetAllocationPct", 20.0)
                },
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        // Existing rows are untouched...
        db.query("SELECT name, targetAllocationPct FROM groups WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("Bonds")
            assertThat(c.getDouble(1)).isEqualTo(20.0)
        }
        // ...and the new column exists and defaults every existing group to "individual stocks".
        db.query("SELECT isEtfGroup FROM groups WHERE id = 1").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        db.close()
    }

    @Test
    fun productionBuilderOpensWithAllMigrations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, GoldDiggerDatabase::class.java, "open-test.db")
            .addMigrations(*Migrations.ALL)
            .build()
        db.openHelper.writableDatabase // force open
        assertThat(db.isOpen).isTrue()
        db.close()
        context.deleteDatabase("open-test.db")
    }
}
