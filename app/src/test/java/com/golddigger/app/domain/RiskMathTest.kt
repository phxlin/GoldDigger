package com.golddigger.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RiskMathTest {

    @Test
    fun `returns are period over period fractional changes`() {
        val r = RiskMath.returns(listOf(100.0, 110.0, 99.0))
        assertThat(r).hasSize(2)
        assertThat(r[0]).isWithin(1e-9).of(0.10)
        assertThat(r[1]).isWithin(1e-9).of(-0.10)
    }

    @Test
    fun `perfectly co-moving series correlate at 1`() {
        val a = listOf(0.01, -0.02, 0.03, -0.01, 0.02)
        val b = a.map { it * 2.0 } // same direction, twice the size
        assertThat(RiskMath.correlation(a, b)!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `opposite series correlate at -1`() {
        val a = listOf(0.01, -0.02, 0.03, -0.01, 0.02)
        val b = a.map { -it }
        assertThat(RiskMath.correlation(a, b)!!).isWithin(1e-9).of(-1.0)
    }

    @Test
    fun `correlation of a flat series is null, not NaN`() {
        val a = listOf(0.01, -0.02, 0.03)
        val flat = listOf(0.0, 0.0, 0.0)
        assertThat(RiskMath.correlation(a, flat)).isNull()
    }

    @Test
    fun `beta of a 2x-levered series against its benchmark is 2`() {
        val market = listOf(0.01, -0.02, 0.03, -0.015, 0.02, -0.01)
        val asset = market.map { it * 2.0 }
        assertThat(RiskMath.beta(asset, market)!!).isWithin(1e-9).of(2.0)
    }

    @Test
    fun `beta against a varianceless benchmark is null`() {
        assertThat(RiskMath.beta(listOf(0.01, 0.02, -0.01), listOf(0.0, 0.0, 0.0))).isNull()
    }

    @Test
    fun `realized volatility needs a minimum number of returns`() {
        val few = listOf(0.01, -0.01, 0.02)
        assertThat(RiskMath.realizedVolatility(few, minReturns = 10)).isNull()
        assertThat(RiskMath.realizedVolatility(few, minReturns = 2)).isNotNull()
    }

    @Test
    fun `realized volatility is the standard deviation of returns`() {
        val returns = List(20) { if (it % 2 == 0) 0.05 else -0.05 }
        assertThat(RiskMath.realizedVolatility(returns, minReturns = 5)!!).isWithin(1e-9).of(0.05)
    }
}
