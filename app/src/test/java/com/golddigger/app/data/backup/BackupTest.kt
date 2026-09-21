package com.golddigger.app.data.backup

import com.golddigger.app.core.CashHolding
import com.golddigger.app.data.local.entity.GroupEntity
import com.golddigger.app.data.local.entity.HoldingEntity
import com.golddigger.app.data.local.entity.PricePointEntity
import com.golddigger.app.data.local.entity.StockEntity
import com.golddigger.app.data.local.entity.StockGroupCrossRef
import com.golddigger.app.data.settings.SyncSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class BackupTest {

    private val stocks = listOf(
        StockEntity("NVDA", "NVIDIA Corp", "Semiconductors", beta = 1.7, isEtf = false),
        StockEntity("VOO", "Vanguard S&P 500 ETF", null, isEtf = true),
        StockEntity(CashHolding.TICKER, CashHolding.NAME, CashHolding.SECTOR),
    )
    private val holdings = listOf(
        HoldingEntity(id = 1, ticker = "NVDA", shares = 40.043, costBasis = 7283.82, dateAdded = 1_700_000_000_000, roleOverride = "ATTACK"),
        HoldingEntity(id = 2, ticker = "VOO", shares = 15.0, costBasis = 6300.0, dateAdded = 1_700_000_100_000),
        HoldingEntity(id = 3, ticker = CashHolding.TICKER, shares = 22_727.82, costBasis = 22_727.82, dateAdded = 1_700_000_200_000),
    )
    private val groups = listOf(
        GroupEntity(id = 10, name = "AI", targetAllocationPct = 25.0, isEtfGroup = false),
        GroupEntity(id = 11, name = "Index funds", targetAllocationPct = null, isEtfGroup = true),
    )
    private val members = listOf(StockGroupCrossRef("NVDA", 10), StockGroupCrossRef("VOO", 11))
    private val points = listOf(
        PricePointEntity(ticker = "NVDA", price = 210.5, timestamp = 1_700_000_000_000),
        PricePointEntity(ticker = "NVDA", price = 219.76, timestamp = 1_700_000_900_000),
        PricePointEntity(ticker = "VOO", price = 420.0, timestamp = 1_700_000_000_000),
    )
    private val syncSettings = SyncSettings(
        refreshInterval = 30.minutes,
        batchSize = 1,
        backgroundSyncEnabled = false,
        marketHoursOnly = false,
    )

    private fun exported(now: Instant = Instant.parse("2026-09-19T12:00:00Z")) =
        Backup.toJson(stocks, holdings, groups, members, points, syncSettings, now)

    private fun rejected(json: String): String =
        assertThrows(BackupException::class.java) { Backup.parse(json) }.message!!

    @Test
    fun `an exported backup parses back to exactly the same data`() {
        val parsed = Backup.parse(exported())

        assertThat(parsed.holdings).containsExactlyElementsIn(holdings).inOrder()
        assertThat(parsed.groups).containsExactlyElementsIn(groups).inOrder()
        assertThat(parsed.groupMembers).containsExactlyElementsIn(members)
        assertThat(parsed.pricePoints).containsExactlyElementsIn(points).inOrder()
        assertThat(parsed.settings).isEqualTo(
            BackupSettings(refreshIntervalMinutes = 30, batchSize = 1, backgroundSyncEnabled = false, marketHoursOnly = false),
        )
    }

    @Test
    fun `stock metadata survives but cached risk metrics are not part of a backup`() {
        val parsed = Backup.parse(exported())

        val nvda = parsed.stocks.single { it.ticker == "NVDA" }
        assertThat(nvda.companyName).isEqualTo("NVIDIA Corp")
        assertThat(nvda.sector).isEqualTo("Semiconductors")
        assertThat(nvda.isEtf).isFalse()
        assertThat(nvda.beta).isNull()
        assertThat(parsed.stocks.single { it.ticker == "VOO" }.isEtf).isTrue()
    }

    @Test
    fun `the cash position round-trips under its dollar-sign ticker`() {
        val parsed = Backup.parse(exported())

        assertThat(parsed.holdings.map { it.ticker }).contains(CashHolding.TICKER)
        assertThat(parsed.stocks.map { it.ticker }).contains(CashHolding.TICKER)
    }

    @Test
    fun `an empty portfolio round-trips`() {
        val json = Backup.toJson(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), syncSettings)

        val parsed = Backup.parse(json)

        assertThat(parsed.stocks).isEmpty()
        assertThat(parsed.holdings).isEmpty()
        assertThat(parsed.groups).isEmpty()
        assertThat(parsed.pricePoints).isEmpty()
    }

    @Test
    fun `unknown fields from a newer minor format are ignored`() {
        val json = exported().replaceFirst("{", """{"somethingNew":{"a":1},""")

        assertThat(Backup.parse(json).holdings).hasSize(3)
    }

    @Test
    fun `optional fields and settings may be absent but every list section must be present`() {
        val json = """{"app":"GoldDigger","version":1,"stocks":[{"ticker":"aapl"}],
            "holdings":[{"id":1,"ticker":" aapl ","shares":2,"costBasis":300,"dateAdded":5}],
            "groups":[],"groupMembers":[],"priceHistory":{}}"""

        val parsed = Backup.parse(json)

        assertThat(parsed.holdings.single().ticker).isEqualTo("AAPL")
        assertThat(parsed.stocks.single().companyName).isEqualTo("AAPL")
        assertThat(parsed.stocks.single().isEtf).isFalse()
        assertThat(parsed.groups).isEmpty()
        assertThat(parsed.pricePoints).isEmpty()
        assertThat(parsed.settings).isNull()
    }

    @Test
    fun `a backup missing a list section is rejected instead of wiping that data`() {
        val base = """"app":"GoldDigger","version":1,"stocks":[]"""
        assertThat(rejected("{$base,\"groups\":[],\"groupMembers\":[],\"priceHistory\":{}}")).contains("no holdings")
        assertThat(rejected("{$base,\"holdings\":[],\"groupMembers\":[],\"priceHistory\":{}}")).contains("no groups")
        assertThat(rejected("{$base,\"holdings\":[],\"groups\":[],\"priceHistory\":{}}")).contains("no group memberships")
        assertThat(rejected("{$base,\"holdings\":[],\"groups\":[],\"groupMembers\":[]}")).contains("no price history")
    }

    @Test
    fun `an unrecognised role override is dropped instead of rejecting the file`() {
        val json = exported().replace("\"ATTACK\"", "\"STRIKER\"")

        assertThat(Backup.parse(json).holdings.first().roleOverride).isNull()
    }

    @Test
    fun `price history for a ticker the backup doesn't contain is skipped`() {
        val json = exported().replace("\"priceHistory\":{", "\"priceHistory\":{\"GHOST\":[{\"t\":1,\"p\":9.0}],")

        val parsed = Backup.parse(json)

        assertThat(parsed.pricePoints.map { it.ticker }.toSet()).containsExactly("NVDA", "VOO")
    }

    @Test
    fun `text that is not JSON is rejected`() {
        assertThat(rejected("not json at all")).contains("valid GoldDigger backup")
        assertThat(rejected("[1,2,3]")).contains("valid GoldDigger backup")
    }

    @Test
    fun `an empty or blank file is rejected`() {
        assertThat(rejected("")).contains("empty")
        assertThat(rejected("   \n")).contains("empty")
    }

    @Test
    fun `a backup from another app is rejected`() {
        assertThat(rejected(exported().replace("\"GoldDigger\"", "\"Avalanche\""))).contains("doesn't look like a GoldDigger backup")
    }

    @Test
    fun `a missing or newer version is rejected`() {
        assertThat(rejected("""{"app":"GoldDigger","stocks":[]}""")).contains("no version")
        assertThat(rejected(exported().replace("\"version\":1", "\"version\":2"))).contains("newer version")
    }

    @Test
    fun `a holding with zero, negative or non-numeric shares is rejected`() {
        assertThat(rejected(exported().replace("\"shares\":15.0", "\"shares\":0.0"))).contains("shares")
        assertThat(rejected(exported().replace("\"shares\":15.0", "\"shares\":-3.0"))).contains("shares")
        assertThat(rejected(exported().replace("\"shares\":15.0", "\"shares\":\"lots\""))).contains("valid GoldDigger backup")
    }

    @Test
    fun `a negative cost basis is rejected but a zero one is allowed`() {
        assertThat(rejected(exported().replace("\"costBasis\":6300.0", "\"costBasis\":-1.0"))).contains("costBasis")
        assertThat(Backup.parse(exported().replace("\"costBasis\":6300.0", "\"costBasis\":0.0")).holdings).hasSize(3)
    }

    @Test
    fun `a holding whose ticker isn't in the stock list is rejected`() {
        val json = exported().replace("\"ticker\":\"VOO\",\"shares\"", "\"ticker\":\"QQQ\",\"shares\"")

        assertThat(rejected(json)).contains("QQQ")
    }

    @Test
    fun `duplicate holding ids or two holdings for one ticker are rejected`() {
        assertThat(rejected(exported().replace("\"id\":2,", "\"id\":1,"))).contains("duplicate holding ids")
        assertThat(rejected(exported().replace("\"ticker\":\"VOO\",\"shares\"", "\"ticker\":\"NVDA\",\"shares\""))).contains("more than one holding for NVDA")
    }

    @Test
    fun `a holding or group without a usable id is rejected`() {
        assertThat(rejected(exported().replace("\"id\":1,", "\"id\":0,"))).contains("valid id")
        assertThat(rejected(exported().replace("\"id\":10,", "\"id\":-5,"))).contains("valid id")
    }

    @Test
    fun `a group with a blank name or an out-of-range target is rejected`() {
        assertThat(rejected(exported().replace("\"name\":\"AI\"", "\"name\":\"  \""))).contains("name")
        assertThat(rejected(exported().replace("\"targetAllocationPct\":25.0", "\"targetAllocationPct\":140.0"))).contains("between 0 and 100")
    }

    @Test
    fun `a group membership pointing at a missing group or stock is rejected`() {
        assertThat(rejected(exported().replace("\"groupId\":11", "\"groupId\":99"))).contains("group that isn't in the backup")
        assertThat(rejected(exported().replace("{\"ticker\":\"VOO\",\"groupId\"", "{\"ticker\":\"QQQ\",\"groupId\""))).contains("QQQ")
    }

    @Test
    fun `a non-positive price in the history is rejected`() {
        assertThat(rejected(exported().replace("\"p\":210.5", "\"p\":0.0"))).contains("price")
        assertThat(rejected(exported().replace("\"p\":210.5", "\"p\":-4.0"))).contains("price")
    }

    @Test
    fun `duplicate stocks are rejected`() {
        val json = exported().replace("\"ticker\":\"VOO\",\"companyName\"", "\"ticker\":\"NVDA\",\"companyName\"")

        assertThat(rejected(json)).contains("same stock twice")
    }

    @Test
    fun `the export records which app and format version wrote it`() {
        val json = exported()

        assertThat(json).contains("\"app\":\"GoldDigger\"")
        assertThat(json).contains("\"version\":1")
        assertThat(json).contains("\"exportedAt\":\"2026-09-19T12:00:00Z\"")
    }
}
