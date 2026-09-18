package com.golddigger.app.data.remote.finnhub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FinnhubQuoteDto(
    @SerialName("c") val current: Double = 0.0,
    @SerialName("d") val change: Double? = null,
    @SerialName("dp") val percentChange: Double? = null,
    @SerialName("h") val high: Double = 0.0,
    @SerialName("l") val low: Double = 0.0,
    @SerialName("o") val open: Double = 0.0,
    @SerialName("pc") val previousClose: Double = 0.0,
    @SerialName("t") val timestamp: Long = 0,
)

@Serializable
data class FinnhubSearchResponse(
    val count: Int = 0,
    val result: List<FinnhubSearchItem> = emptyList(),
)

@Serializable
data class FinnhubSearchItem(
    val symbol: String = "",
    val displaySymbol: String = "",
    val description: String = "",
    val type: String = "",
)

@Serializable
data class FinnhubProfileDto(
    val name: String? = null,
    @SerialName("finnhubIndustry") val industry: String? = null,
    val ticker: String? = null,
    val exchange: String? = null,
)

@Serializable
data class FinnhubMetricResponse(
    val metric: FinnhubMetric = FinnhubMetric(),
)

@Serializable
data class FinnhubMetric(
    val beta: Double? = null,
)

@Serializable
data class FinnhubNewsItem(
    val id: Long = 0,
    val headline: String = "",
    val summary: String = "",
    val source: String = "",
    val url: String = "",
    val image: String = "",
    /** Epoch seconds. */
    val datetime: Long = 0,
    val category: String = "",
    val related: String = "",
)
