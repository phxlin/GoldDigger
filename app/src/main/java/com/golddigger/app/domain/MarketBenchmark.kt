package com.golddigger.app.domain

import com.golddigger.app.core.FormationConfig

/** Picks which held fund, if any, stands in for the market when estimating beta. */
object MarketBenchmark {

    /** The most-preferred benchmark fund among [heldTickers], or null if none is held. */
    fun heldFund(heldTickers: Set<String>): String? =
        FormationConfig.MARKET_BENCHMARK_FUNDS.firstOrNull { it in heldTickers }
}
