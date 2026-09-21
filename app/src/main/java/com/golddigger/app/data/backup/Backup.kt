package com.golddigger.app.data.backup

import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.settings.SyncSettings
import com.golddigger.app.domain.model.FormationRole
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * JSON backup format. Every DTO field is nullable so a malformed or hand-edited
 * file is rejected with a clear message by [Backup.parse] instead of producing
 * half-initialised entities.
 *
 * What's in a backup: everything the user entered or that can't be re-fetched —
 * holdings (cash included), stock metadata (name, sector, ETF flag), groups and
 * their members, Formation role overrides, sync settings, and the recorded price
 * history (the provider's free tier has no historical-candles endpoint, so it
 * can't be rebuilt). Not in a backup: cached quotes, news, and beta/correlation,
 * which refresh on their own after an import.
 *
 * An import replaces those tables wholesale, so every list section must be
 * present (an empty list is fine): a file that merely omits one would otherwise
 * silently wipe that data. Only [BackupFile.settings] is optional.
 */
@Serializable
data class BackupFile(
    val app: String? = null,
    val version: Int? = null,
    val exportedAt: String? = null,
    val settings: BackupSettings? = null,
    val stocks: List<StockDto>? = null,
    val holdings: List<HoldingDto>? = null,
    val groups: List<GroupDto>? = null,
    val groupMembers: List<GroupMemberDto>? = null,
    /** Ticker -> that ticker's recorded price points, oldest first. */
    val priceHistory: Map<String, List<PricePointDto>>? = null,
)

@Serializable
data class BackupSettings(
    val refreshIntervalMinutes: Int? = null,
    val batchSize: Int? = null,
    val backgroundSyncEnabled: Boolean? = null,
    val marketHoursOnly: Boolean? = null,
)

@Serializable
data class StockDto(
    val ticker: String? = null,
    val companyName: String? = null,
    val sector: String? = null,
    val isEtf: Boolean? = null,
)

@Serializable
data class HoldingDto(
    val id: Long? = null,
    val ticker: String? = null,
    val shares: Double? = null,
    val costBasis: Double? = null,
    val dateAdded: Long? = null,
    val roleOverride: String? = null,
)

@Serializable
data class GroupDto(
    val id: Long? = null,
    val name: String? = null,
    val targetAllocationPct: Double? = null,
    val isEtfGroup: Boolean? = null,
)

@Serializable
data class GroupMemberDto(
    val ticker: String? = null,
    val groupId: Long? = null,
)

/** Short keys: there can be tens of thousands of these in one file. */
@Serializable
data class PricePointDto(
    val t: Long? = null,
    val p: Double? = null,
)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ParsedBackup(
    val settings: BackupSettings?,
    val stocks: List<StockEntity>,
    val holdings: List<HoldingEntity>,
    val groups: List<GroupEntity>,
    val groupMembers: List<StockGroupCrossRef>,
    val pricePoints: List<PricePointEntity>,
)

object Backup {
    const val APP_ID = "GoldDigger"
    const val VERSION = 1

    // Compact on purpose: the price history dominates the file size.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun toJson(
        stocks: List<StockEntity>,
        holdings: List<HoldingEntity>,
        groups: List<GroupEntity>,
        groupMembers: List<StockGroupCrossRef>,
        pricePoints: List<PricePointEntity>,
        settings: SyncSettings,
        now: Instant = Instant.now(),
    ): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            app = APP_ID,
            version = VERSION,
            exportedAt = now.toString(),
            settings = BackupSettings(
                refreshIntervalMinutes = settings.refreshInterval.inWholeMinutes.toInt(),
                batchSize = settings.batchSize,
                backgroundSyncEnabled = settings.backgroundSyncEnabled,
                marketHoursOnly = settings.marketHoursOnly,
            ),
            stocks = stocks.map { StockDto(it.ticker, it.companyName, it.sector, it.isEtf) },
            holdings = holdings.map {
                HoldingDto(it.id, it.ticker, it.shares, it.costBasis, it.dateAdded, it.roleOverride)
            },
            groups = groups.map { GroupDto(it.id, it.name, it.targetAllocationPct, it.isEtfGroup) },
            groupMembers = groupMembers.map { GroupMemberDto(it.ticker, it.groupId) },
            priceHistory = pricePoints
                .groupBy { it.ticker }
                .mapValues { (_, points) -> points.map { PricePointDto(it.timestamp, it.price) } },
        ),
    )

    /** Throws [BackupException], leaving the caller free to change nothing, if [text] isn't a valid backup. */
    fun parse(text: String): ParsedBackup {
        if (text.isBlank()) throw BackupException("This file is empty.")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("This file isn't a valid GoldDigger backup.", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("This file isn't a valid GoldDigger backup.", e)
        }

        if (file.app != APP_ID) fail("This doesn't look like a GoldDigger backup.")
        val version = file.version ?: fail("The backup has no version number.")
        if (version > VERSION) fail("This backup was made by a newer version of the app.")

        val stocks = (file.stocks ?: fail("The backup contains no stock list.")).map { s ->
            val ticker = ticker(s.ticker, "A stock")
            StockEntity(
                ticker = ticker,
                companyName = s.companyName?.trim()?.takeIf { it.isNotEmpty() } ?: ticker,
                sector = s.sector?.trim()?.takeIf { it.isNotEmpty() },
                isEtf = s.isEtf ?: false,
            )
        }
        val stockTickers = stocks.map { it.ticker }.toSet()
        if (stockTickers.size != stocks.size) fail("The backup lists the same stock twice.")

        val holdings = (file.holdings ?: fail("The backup contains no holdings list.")).map { h ->
            val ticker = ticker(h.ticker, "A holding")
            if (ticker !in stockTickers) fail("A holding refers to $ticker, which isn't in the backup's stock list.")
            val id = h.id?.takeIf { it > 0 } ?: fail("A holding is missing a valid id.")
            HoldingEntity(
                id = id,
                ticker = ticker,
                shares = amount(h.shares, "shares", allowZero = false),
                costBasis = amount(h.costBasis, "costBasis", allowZero = true),
                dateAdded = h.dateAdded ?: fail("A holding is missing its date added."),
                // An unrecognised role is dropped rather than rejecting the whole file:
                // it just falls back to the computed role.
                roleOverride = h.roleOverride?.takeIf { r -> FormationRole.entries.any { it.name == r } },
            )
        }
        if (holdings.map { it.id }.toSet().size != holdings.size) fail("The backup has duplicate holding ids.")
        holdings.groupBy { it.ticker }.entries.firstOrNull { it.value.size > 1 }?.let {
            fail("The backup has more than one holding for ${it.key}.")
        }

        val groups = (file.groups ?: fail("The backup contains no groups list.")).map { g ->
            val target = g.targetAllocationPct
            if (target != null && (!target.isFinite() || target < 0.0 || target > 100.0)) {
                fail("A group's target allocation must be between 0 and 100.")
            }
            GroupEntity(
                id = g.id?.takeIf { it > 0 } ?: fail("A group is missing a valid id."),
                name = g.name?.trim()?.takeIf { it.isNotEmpty() } ?: fail("A group is missing its name."),
                targetAllocationPct = target,
                isEtfGroup = g.isEtfGroup ?: false,
            )
        }
        val groupIds = groups.map { it.id }.toSet()
        if (groupIds.size != groups.size) fail("The backup has duplicate group ids.")

        val groupMembers = (file.groupMembers ?: fail("The backup contains no group memberships list.")).map { m ->
            val ticker = ticker(m.ticker, "A group membership")
            val groupId = m.groupId ?: fail("A group membership is missing its group.")
            if (ticker !in stockTickers) fail("A group membership refers to $ticker, which isn't in the backup's stock list.")
            if (groupId !in groupIds) fail("A group membership refers to a group that isn't in the backup.")
            StockGroupCrossRef(ticker, groupId)
        }.distinct()

        val pricePoints = (file.priceHistory ?: fail("The backup contains no price history.")).flatMap { (rawTicker, points) ->
            val ticker = ticker(rawTicker, "A price-history entry")
            // History for a ticker the backup doesn't otherwise contain would just be
            // an orphan the app prunes on its next sync, so it's skipped, not an error.
            if (ticker !in stockTickers) return@flatMap emptyList()
            points.map { p ->
                PricePointEntity(
                    ticker = ticker,
                    price = amount(p.p, "a price in $ticker's history", allowZero = false),
                    timestamp = p.t?.takeIf { it >= 0 } ?: fail("A price point for $ticker is missing its time."),
                )
            }
        }

        return ParsedBackup(file.settings, stocks, holdings, groups, groupMembers, pricePoints)
    }

    private fun fail(message: String): Nothing = throw BackupException(message)

    private fun ticker(raw: String?, what: String): String =
        raw?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: fail("$what is missing its ticker.")

    private fun amount(v: Double?, field: String, allowZero: Boolean): Double {
        if (v == null || !v.isFinite() || v < 0.0 || (!allowZero && v == 0.0)) {
            fail("Invalid value for $field in the backup.")
        }
        return v
    }
}
