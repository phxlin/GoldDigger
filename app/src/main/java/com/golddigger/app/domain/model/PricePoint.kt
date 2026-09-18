package com.golddigger.app.domain.model

/** One recorded price at a point in time — the raw material for the Holding Detail price chart. */
data class PricePoint(val timestamp: Long, val price: Double)
