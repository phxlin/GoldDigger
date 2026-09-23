package com.golddigger.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tradable symbol. No ticker is ever seeded — rows appear only when the user
 * adds a holding or tags a symbol into a group.
 */
@Entity(tableName = "stocks")
data class StockEntity(
    @PrimaryKey val ticker: String,
    val companyName: String,
    val sector: String? = null,
    /**
     * Market beta (vs [com.golddigger.app.core.FormationConfig.DEFAULT_BETA_BENCHMARK]),
     * from the price provider or estimated from price history. Null until fetched —
     * not every provider returns it. Powers the Formation view's role assignment.
     */
    val beta: Double? = null,
    /**
     * Whether [beta] came from the local, no-network estimate rather than the
     * provider — null for a row written before this distinction existed. A
     * locally-estimated (or provenance-unknown) beta is always recomputed on
     * the next risk refresh rather than trusted as a cache, since the estimate
     * is cheap and the underlying algorithm can change (e.g. the move to
     * daily-close alignment); only a provider-sourced value is worth
     * preserving across a transient provider failure.
     */
    val betaIsEstimate: Boolean? = null,
    /** Trailing-window correlation of this holding to the portfolio's dominant sector, [-1, 1]. */
    val sectorCorrelation: Double? = null,
    /** When [beta] / [sectorCorrelation] were last refreshed (epoch ms), for the TTL. */
    val riskUpdatedAt: Long? = null,
    /**
     * True if this ticker is an ETF rather than an individual stock. Defaults to
     * false (assume individual stock) until classified — from the price
     * provider's search result type when the user adds/imports it, correctable
     * by hand on the Add/Edit screen.
     */
    val isEtf: Boolean = false,
)

@Entity(
    tableName = "holdings",
    foreignKeys = [
        ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["ticker"],
            childColumns = ["ticker"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ticker")],
)
data class HoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val shares: Double,
    /** Total dollars paid across all buys for this position. */
    val costBasis: Double,
    val dateAdded: Long,
    /**
     * Manual Formation-view role override ([com.golddigger.app.domain.model.FormationRole]
     * name), or null to use the computed role. Persisted so a user's read on a
     * stock survives the next metrics refresh.
     */
    val roleOverride: String? = null,
)

enum class TransactionType { BUY, SELL }

/**
 * Optional ledger. Holdings carry a denormalised [HoldingEntity.shares] /
 * [HoldingEntity.costBasis] for fast reads; when transactions exist they are the
 * authoritative source and the repository can recompute the holding from them.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["ticker"],
            childColumns = ["ticker"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("ticker")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val type: TransactionType,
    val shares: Double,
    val pricePerShare: Double,
    val timestamp: Long,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Desired share of total portfolio value, 0..100. Null = untracked. */
    val targetAllocationPct: Double? = null,
    /**
     * True if this bucket tracks ETFs, false if it tracks individual stocks.
     * Determines what a member ticker's allocation is measured against:
     * [targetAllocationPct] and the computed current % are a share of the
     * portfolio's total ETF value or total individual-stock value
     * respectively, not the whole portfolio — so an ETF-type group and a
     * stocks-type group each have their own independent 100%.
     */
    val isEtfGroup: Boolean = false,
)

@Entity(
    tableName = "stock_group_cross_ref",
    primaryKeys = ["ticker", "groupId"],
    foreignKeys = [
        ForeignKey(
            entity = StockEntity::class,
            parentColumns = ["ticker"],
            childColumns = ["ticker"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class StockGroupCrossRef(
    val ticker: String,
    val groupId: Long,
)

/**
 * Last known quote for a ticker. Always present once fetched so the UI can show
 * something (with a staleness badge) even when the network is down.
 */
@Entity(tableName = "price_cache")
data class PriceCacheEntity(
    @PrimaryKey val ticker: String,
    val price: Double,
    val dayChangePct: Double,
    val lastUpdated: Long,
)

/**
 * Cached company-news article for a ticker (from the price provider's news
 * endpoint). Persisted so the holding detail screen has something to show
 * offline and opens instantly. [fetchedAt] drives the refresh TTL.
 */
@Entity(tableName = "news_cache", primaryKeys = ["ticker", "id"])
data class NewsCacheEntity(
    val ticker: String,
    /** Provider's article id. */
    val id: Long,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val imageUrl: String?,
    val publishedAt: Long,
    val fetchedAt: Long,
)

/**
 * Rolling price history captured on each successful sync. Powers the detail
 * screen sparkline without needing a paid historical-candles endpoint.
 */
@Entity(
    tableName = "price_points",
    indices = [Index(value = ["ticker", "timestamp"])],
)
data class PricePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ticker: String,
    val price: Double,
    val timestamp: Long,
)
