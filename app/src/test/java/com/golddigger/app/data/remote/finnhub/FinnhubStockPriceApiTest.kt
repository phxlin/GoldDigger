package com.golddigger.app.data.remote.finnhub

import com.golddigger.app.data.remote.PriceApiException
import com.golddigger.app.data.remote.RateLimitException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import com.google.common.truth.Truth.assertThat
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * [FinnhubStockPriceApi.fetchMetrics] used to swallow every failure (network,
 * HTTP, even a 403 from a plan that lacks the endpoint) into the same
 * `StockMetrics(beta = null)` a ticker with no provider beta produces on a
 * genuinely successful call. [PortfolioRepositoryImpl][com.golddigger.app.data.repository.PortfolioRepositoryImpl]
 * relies on telling those two apart to decide whether a cached provider beta
 * is worth keeping across this call, so the two cases must now be
 * distinguishable at this layer: a real failure propagates as the usual
 * [PriceApiException]/[RateLimitException], only a truly successful response
 * with no beta value returns quietly.
 */
class FinnhubStockPriceApiTest {

    private val service = mockk<FinnhubService>()

    private fun api(apiKey: String = "test-key") = FinnhubStockPriceApi(service, apiKey)

    private fun httpException(code: Int, retryAfterSeconds: String? = null): HttpException {
        val builder = okhttp3.Response.Builder()
            .code(code)
            .message("error")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("http://localhost/").build())
        retryAfterSeconds?.let { builder.header("Retry-After", it) }
        val response = Response.error<FinnhubMetricResponse>("".toResponseBody(null), builder.build())
        return HttpException(response)
    }

    @Test
    fun `a network failure propagates as PriceApiException rather than an empty result`() = runTest {
        coEvery { service.metric(any(), any()) } throws IOException("offline")

        val error = kotlin.runCatching { api().fetchMetrics("AAPL") }.exceptionOrNull()

        assertThat(error).isInstanceOf(PriceApiException::class.java)
    }

    @Test
    fun `a non-rate-limit HTTP failure propagates as PriceApiException`() = runTest {
        coEvery { service.metric(any(), any()) } throws httpException(403)

        val error = kotlin.runCatching { api().fetchMetrics("AAPL") }.exceptionOrNull()

        assertThat(error).isInstanceOf(PriceApiException::class.java)
    }

    @Test
    fun `a 429 propagates as RateLimitException, not a swallowed empty result`() = runTest {
        coEvery { service.metric(any(), any()) } throws httpException(429, retryAfterSeconds = "30")

        val error = kotlin.runCatching { api().fetchMetrics("AAPL") }.exceptionOrNull()

        assertThat(error).isInstanceOf(RateLimitException::class.java)
        assertThat((error as RateLimitException).retryAfterSeconds).isEqualTo(30L)
    }

    @Test
    fun `coroutine cancellation propagates uncaught rather than becoming a PriceApiException`() = runTest {
        coEvery { service.metric(any(), any()) } throws CancellationException("cancelled")

        val error = kotlin.runCatching { api().fetchMetrics("AAPL") }.exceptionOrNull()

        assertThat(error).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a genuinely successful response with no beta returns quietly, not as a failure`() = runTest {
        coEvery { service.metric(any(), any()) } returns FinnhubMetricResponse(metric = FinnhubMetric(beta = null))

        val result = api().fetchMetrics("BNDX")

        assertThat(result.beta).isNull()
    }

    @Test
    fun `a successful response with a beta returns it`() = runTest {
        coEvery { service.metric(any(), any()) } returns FinnhubMetricResponse(metric = FinnhubMetric(beta = 1.42))

        val result = api().fetchMetrics("NVDA")

        assertThat(result.beta).isEqualTo(1.42)
    }
}
