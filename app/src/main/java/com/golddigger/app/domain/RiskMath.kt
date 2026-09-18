package com.golddigger.app.domain

import kotlin.math.sqrt

/**
 * Pure statistics for the Formation risk model — no Android, no coroutines, no
 * I/O. Kept separate from [FormationClassifier] so the numeric building blocks
 * (returns, correlation, beta, realized volatility) can be pinned down with
 * their own unit tests.
 *
 * All functions operate on plain `Double` lists and return `null` rather than
 * `NaN`/exception when the input is too short or degenerate (e.g. a flat
 * series), so callers can treat "not enough data" uniformly.
 */
object RiskMath {

    /** Simple period-over-period returns from a price series (length n -> n-1). */
    fun returns(prices: List<Double>): List<Double> =
        prices.zipWithNext { prev, next -> if (prev > 0.0) (next - prev) / prev else 0.0 }

    fun mean(xs: List<Double>): Double =
        if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

    /** Population variance. */
    fun variance(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return xs.sumOf { (it - m) * (it - m) } / xs.size
    }

    fun stdDev(xs: List<Double>): Double = sqrt(variance(xs))

    /** Population covariance of two equal-length series. */
    fun covariance(xs: List<Double>, ys: List<Double>): Double {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return 0.0
        val mx = mean(xs.take(n))
        val my = mean(ys.take(n))
        var acc = 0.0
        for (i in 0 until n) acc += (xs[i] - mx) * (ys[i] - my)
        return acc / n
    }

    /**
     * Pearson correlation of two return series, in [-1, 1]. Null when either
     * series is too short or has no variance (a flat line correlates with
     * nothing meaningfully).
     */
    fun correlation(xs: List<Double>, ys: List<Double>): Double? {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return null
        val a = xs.take(n)
        val b = ys.take(n)
        val denom = stdDev(a) * stdDev(b)
        if (denom <= 0.0) return null
        return (covariance(a, b) / denom).coerceIn(-1.0, 1.0)
    }

    /**
     * Beta of an asset's returns against a benchmark's returns:
     * `cov(asset, benchmark) / var(benchmark)`. Null when the benchmark has no
     * variance or the series are too short.
     */
    fun beta(assetReturns: List<Double>, benchmarkReturns: List<Double>): Double? {
        val n = minOf(assetReturns.size, benchmarkReturns.size)
        if (n < 2) return null
        val a = assetReturns.take(n)
        val b = benchmarkReturns.take(n)
        val varB = variance(b)
        if (varB <= 0.0) return null
        return covariance(a, b) / varB
    }

    /**
     * Realized volatility = standard deviation of the supplied return series.
     * Deliberately *not* annualized: the app's price points are intraday sync
     * snapshots, not daily closes, so a √252 scaling would be meaningless.
     * Null when there are too few returns to be worth trusting.
     */
    fun realizedVolatility(returns: List<Double>, minReturns: Int): Double? {
        if (returns.size < minReturns) return null
        return stdDev(returns)
    }
}
