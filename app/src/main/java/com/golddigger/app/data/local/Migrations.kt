package com.golddigger.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Holdings data is precious — a user would be upset to lose it on an app update —
 * so the database is versioned from v1 and every schema change ships an explicit
 * migration plus a migration test (see `MigrationTest` in androidTest).
 *
 * How to add a migration when the schema changes to vN:
 *  1. Bump [GoldDiggerDatabase.VERSION] to N and change the entities.
 *  2. Build once so KSP writes `app/schemas/.../N.json` (the previous version's
 *     JSON is already committed, which is what `MigrationTestHelper` replays).
 *  3. Add a `Migration(N-1, N)` object here and include it in [ALL].
 *  4. Add a case to `MigrationTest` that runs (N-1) -> N and asserts the data
 *     survived. Never edit or renumber a migration that has shipped.
 */
object Migrations {

    /** Every shipped migration, in order. */
    val ALL: Array<Migration>
        get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

    /**
     * v1 -> v2: adds the `news_cache` table backing the holding-detail news feed.
     * Purely additive — no existing table is touched, so no user data is at risk.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `news_cache` (
                    `ticker` TEXT NOT NULL,
                    `id` INTEGER NOT NULL,
                    `headline` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `url` TEXT NOT NULL,
                    `imageUrl` TEXT,
                    `publishedAt` INTEGER NOT NULL,
                    `fetchedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`ticker`, `id`)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v2 -> v3: adds the risk columns backing the Soccer Formation view —
     * `stocks.beta`, `stocks.sectorCorrelation`, `stocks.riskUpdatedAt` and
     * `holdings.roleOverride`. All nullable and additive, so no existing user
     * data is touched or at risk.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `stocks` ADD COLUMN `beta` REAL")
            db.execSQL("ALTER TABLE `stocks` ADD COLUMN `sectorCorrelation` REAL")
            db.execSQL("ALTER TABLE `stocks` ADD COLUMN `riskUpdatedAt` INTEGER")
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `roleOverride` TEXT")
        }
    }

    /**
     * v3 -> v4: adds `stocks.isEtf`, backing the Portfolio screen's ETF section
     * and the Formation view's per-holding ETF badge. Additive with a `false`
     * default, so every existing stock is simply treated as an individual stock
     * until the user (re-)classifies it.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `stocks` ADD COLUMN `isEtf` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v4 -> v5: adds `groups.isEtfGroup`, so a user-defined group can be typed
     * as tracking ETFs or individual stocks — its allocation % is then measured
     * against that type's own total, not the whole portfolio. Additive with a
     * `false` default, so every existing group is simply treated as an
     * individual-stocks group until the user (re-)classifies it.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `groups` ADD COLUMN `isEtfGroup` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v5 -> v6: adds `stocks.betaIsEstimate`, distinguishing a provider-sourced
     * beta from one this app estimated locally from price history. Left NULL
     * (unknown provenance) for every existing row, which the repository treats
     * the same as a local estimate — always recomputed on the next risk
     * refresh rather than trusted indefinitely, since a pre-migration beta may
     * have been estimated by an older, less accurate algorithm.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `stocks` ADD COLUMN `betaIsEstimate` INTEGER")
        }
    }
}
