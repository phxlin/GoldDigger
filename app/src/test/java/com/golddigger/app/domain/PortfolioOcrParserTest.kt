package com.golddigger.app.domain

import com.golddigger.app.domain.model.OcrToken
import com.golddigger.app.domain.model.ParseConfidence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PortfolioOcrParserTest {

    /** A word at ([left], [top])..([left]+[width], [top]+20). */
    private fun tok(text: String, left: Float, top: Float, width: Float = text.length * 14f) =
        OcrToken(text, left, top, left + width, top + 20f)

    @Test
    fun `header row anchors shares and avg cost columns`() {
        val tokens = listOf(
            // header, y=0
            tok("Symbol", 10f, 0f), tok("Shares", 150f, 0f), tok("Avg", 280f, 0f), tok("Cost", 320f, 0f),
            // row 1, y=50
            tok("NVDA", 10f, 50f), tok("10", 155f, 50f), tok("$120.50", 280f, 50f),
            // row 2, y=100
            tok("AAPL", 10f, 100f), tok("15.5", 150f, 100f), tok("$200.00", 280f, 100f),
        )
        val result = PortfolioOcrParser.parse(tokens)

        assertThat(result).hasSize(2)
        val nvda = result.first { it.ticker == "NVDA" }
        assertThat(nvda.shares).isEqualTo(10.0)
        assertThat(nvda.avgPrice).isEqualTo(120.50)
        assertThat(nvda.confidence).isEqualTo(ParseConfidence.HIGH)
        val aapl = result.first { it.ticker == "AAPL" }
        assertThat(aapl.shares).isEqualTo(15.5)
        assertThat(aapl.avgPrice).isEqualTo(200.00)
    }

    @Test
    fun `a dollar-prefixed number is a price even without a header`() {
        val tokens = listOf(tok("MSFT", 0f, 0f), tok("5", 100f, 0f), tok("$410.25", 200f, 0f))
        val result = PortfolioOcrParser.parse(tokens)

        assertThat(result).hasSize(1)
        assertThat(result.single().shares).isEqualTo(5.0)
        assertThat(result.single().avgPrice).isEqualTo(410.25)
        assertThat(result.single().confidence).isEqualTo(ParseConfidence.HIGH)
    }

    @Test
    fun `comma-grouped shares and a currency price are parsed as numbers`() {
        val tokens = listOf(tok("KO", 0f, 0f), tok("1,250", 100f, 0f), tok("$45.30", 200f, 0f))
        val result = PortfolioOcrParser.parse(tokens).single()

        assertThat(result.shares).isEqualTo(1250.0)
        assertThat(result.avgPrice).isEqualTo(45.30)
    }

    @Test
    fun `a summary row with no ticker-shaped token is dropped, not misread as a holding`() {
        val tokens = listOf(
            tok("TOTAL", 0f, 0f), tok("PORTFOLIO", 70f, 0f), tok("VALUE", 200f, 0f), tok("$45,000.00", 280f, 0f),
        )
        assertThat(PortfolioOcrParser.parse(tokens)).isEmpty()
    }

    @Test
    fun `a share-class ticker like BRK-B is recognized`() {
        val tokens = listOf(tok("BRK.B", 0f, 0f), tok("3", 120f, 0f), tok("$410,000.00", 200f, 0f))
        val result = PortfolioOcrParser.parse(tokens).single()

        assertThat(result.ticker).isEqualTo("BRK.B")
        assertThat(result.shares).isEqualTo(3.0)
        assertThat(result.avgPrice).isEqualTo(410000.00)
    }

    @Test
    fun `a row with only one number is returned as low confidence with the other field null`() {
        val tokens = listOf(tok("IBM", 0f, 0f), tok("$210.00", 100f, 0f))
        val result = PortfolioOcrParser.parse(tokens).single()

        assertThat(result.avgPrice).isEqualTo(210.00)
        assertThat(result.shares).isNull()
        assertThat(result.confidence).isEqualTo(ParseConfidence.LOW)
    }

    @Test
    fun `rows are re-derived from word geometry, not from input list order`() {
        // Two rows' words interleaved in the list, as a recognizer reading
        // column-by-column might return them, rather than row-by-row.
        val tokens = listOf(
            tok("NVDA", 10f, 0f), tok("AAPL", 10f, 60f),
            tok("$120.00", 200f, 0f), tok("$200.00", 200f, 60f),
            tok("10", 100f, 0f), tok("20", 100f, 60f),
        ).shuffled(kotlin.random.Random(42))

        val result = PortfolioOcrParser.parse(tokens)

        assertThat(result).hasSize(2)
        assertThat(result.first { it.ticker == "NVDA" }.shares).isEqualTo(10.0)
        assertThat(result.first { it.ticker == "AAPL" }.avgPrice).isEqualTo(200.00)
    }

    @Test
    fun `without a header, shares and avg cost are the two leftmost numeric columns`() {
        // Symbol, Shares, Avg cost, Current price, Market value — no header text.
        fun row(ticker: String, y: Float, shares: String, avg: String, current: String, value: String) = listOf(
            tok(ticker, 0f, y), tok(shares, 100f, y), tok(avg, 200f, y), tok(current, 320f, y), tok(value, 440f, y),
        )
        val tokens = row("NVDA", 0f, "10", "$100.00", "$120.00", "$1,200.00") +
            row("AAPL", 60f, "20", "$150.00", "$180.00", "$3,600.00") +
            row("MSFT", 120f, "5", "$300.00", "$310.00", "$1,550.00")

        val result = PortfolioOcrParser.parse(tokens)

        assertThat(result).hasSize(3)
        val nvda = result.first { it.ticker == "NVDA" }
        assertThat(nvda.shares).isEqualTo(10.0)
        assertThat(nvda.avgPrice).isEqualTo(100.00) // avg cost, not current price or market value
    }

    @Test
    fun `avg cost is derived from shares, market value and a gain when there is no direct cost column`() {
        val tokens = listOf(
            // header: Symbol, Shares, Value, Gain — no Avg/Cost/Price column at all
            tok("Symbol", 0f, 0f), tok("Shares", 120f, 0f), tok("Value", 250f, 0f), tok("Gain", 380f, 0f),
            // NVDA: 10 shares, $1,200.00 market value, +$200.00 gain -> avg cost = (1200-200)/10 = 100
            tok("NVDA", 0f, 50f), tok("10", 120f, 50f), tok("$1,200.00", 250f, 50f), tok("+$200.00", 380f, 50f),
        )
        val result = PortfolioOcrParser.parse(tokens).single()

        assertThat(result.ticker).isEqualTo("NVDA")
        assertThat(result.shares).isEqualTo(10.0)
        assertThat(result.avgPrice).isWithin(1e-9).of(100.0)
        assertThat(result.confidence).isEqualTo(ParseConfidence.HIGH)
    }

    @Test
    fun `a loss (negative gain) is subtracted correctly when deriving avg cost`() {
        val tokens = listOf(
            tok("Symbol", 0f, 0f), tok("Shares", 120f, 0f), tok("Balance", 250f, 0f), tok("P/L", 380f, 0f),
            // AAPL: 5 shares, $900.00 value, -$100.00 loss -> cost was $1,000 -> avg cost = 200
            tok("AAPL", 0f, 50f), tok("5", 120f, 50f), tok("$900.00", 250f, 50f), tok("-$100.00", 380f, 50f),
        )
        val result = PortfolioOcrParser.parse(tokens).single()

        assertThat(result.shares).isEqualTo(5.0)
        assertThat(result.avgPrice).isWithin(1e-9).of(200.0)
    }

    @Test
    fun `a direct avg-cost column is still preferred over deriving one`() {
        val tokens = listOf(
            tok("Symbol", 0f, 0f), tok("Shares", 120f, 0f), tok("Avg", 250f, 0f),
            tok("Value", 380f, 0f), tok("Gain", 500f, 0f),
            // If the derivation ran instead, it would say (1200-999)/10 = 20.1 -- it must not.
            tok("NVDA", 0f, 50f), tok("10", 120f, 50f), tok("$100.00", 250f, 50f),
            tok("$1,200.00", 380f, 50f), tok("+$999.00", 500f, 50f),
        )
        val result = PortfolioOcrParser.parse(tokens).single()

        assertThat(result.avgPrice).isEqualTo(100.0)
    }

    @Test
    fun `an empty photo yields no candidates`() {
        assertThat(PortfolioOcrParser.parse(emptyList())).isEmpty()
    }
}
