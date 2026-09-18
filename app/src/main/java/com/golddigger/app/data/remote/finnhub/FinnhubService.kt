package com.golddigger.app.data.remote.finnhub

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Raw Retrofit description of the Finnhub REST endpoints we use. The `token`
 * query parameter is injected by an OkHttp interceptor (see NetworkModule), so
 * it is intentionally absent here.
 */
interface FinnhubService {

    @GET("quote")
    suspend fun quote(@Query("symbol") symbol: String): FinnhubQuoteDto

    @GET("search")
    suspend fun search(@Query("q") query: String): FinnhubSearchResponse

    @GET("stock/profile2")
    suspend fun profile(@Query("symbol") symbol: String): FinnhubProfileDto

    @GET("stock/metric")
    suspend fun metric(
        @Query("symbol") symbol: String,
        @Query("metric") metric: String = "all",
    ): FinnhubMetricResponse

    @GET("company-news")
    suspend fun companyNews(
        @Query("symbol") symbol: String,
        @Query("from") from: String,
        @Query("to") to: String,
    ): List<FinnhubNewsItem>
}
