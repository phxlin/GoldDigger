package com.golddigger.app.domain

import com.golddigger.app.core.FormationConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarketBenchmarkTest {

    private val preferred = FormationConfig.MARKET_BENCHMARK_FUNDS

    @Test
    fun `no benchmark fund held means no benchmark`() {
        assertThat(MarketBenchmark.heldFund(setOf("AAPL", "BND", "QQQM"))).isNull()
        assertThat(MarketBenchmark.heldFund(emptySet())).isNull()
    }

    @Test
    fun `a single held benchmark fund is used`() {
        val only = preferred.last()
        assertThat(MarketBenchmark.heldFund(setOf("AAPL", only))).isEqualTo(only)
    }

    @Test
    fun `the most-preferred held fund wins, whatever order they are held in`() {
        val first = preferred.first()
        val last = preferred.last()
        assertThat(MarketBenchmark.heldFund(setOf(last, "AAPL", first))).isEqualTo(first)
    }
}
