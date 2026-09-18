package com.golddigger.app.data.remote.finnhub

import com.golddigger.app.data.remote.MissingApiKeyException
import com.golddigger.app.data.remote.NewsArticle
import com.golddigger.app.data.remote.PriceApiException
import com.golddigger.app.data.remote.RateLimitException
import com.golddigger.app.data.remote.RemoteQuote
import com.golddigger.app.data.remote.StockPriceApi
import com.golddigger.app.data.remote.StockMetrics
import com.golddigger.app.data.remote.StockProfile
import com.golddigger.app.data.remote.SymbolSearchResult
import retrofit2.HttpException
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Named

class FinnhubStockPriceApi @Inject constructor(
    private val service: FinnhubService,
    @Named("priceApiKey") private val apiKey: String,
) : StockPriceApi {

    /** Finnhub's free `/quote` endpoint is one symbol per call. */
    override val maxSymbolsPerQuoteRequest: Int = 1

    override suspend fun searchSymbols(query: String): List<SymbolSearchResult> {
        requireKey()
        if (query.isBlank()) return emptyList()
        return runCatching { service.search(query.trim()) }
            .map { resp ->
                resp.result
                    .filter { it.symbol.isNotBlank() && !it.symbol.contains('.') }
                    .map {
                        SymbolSearchResult(
                            symbol = it.symbol,
                            displaySymbol = it.displaySymbol.ifBlank { it.symbol },
                            description = it.description,
                            type = it.type,
                        )
                    }
            }
            .getOrElse { throw it.toDomain() }
    }

    override suspend fun fetchProfile(ticker: String): StockProfile? {
        requireKey()
        val dto = runCatching { service.profile(ticker.trim().uppercase()) }
            .getOrElse { throw it.toDomain() }
        if (dto.name.isNullOrBlank() && dto.industry.isNullOrBlank()) return null
        return StockProfile(
            ticker = ticker.trim().uppercase(),
            companyName = dto.name?.takeIf { it.isNotBlank() },
            sector = dto.industry?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun fetchMetrics(ticker: String): StockMetrics {
        if (apiKey.isBlank()) return StockMetrics()
        // Best-effort: `stock/metric` is on Finnhub's free tier, but swallow any
        // failure (403 on some plans, network) — beta is enrichment, not core.
        val dto = runCatching { service.metric(ticker.trim().uppercase()) }.getOrNull()
        val beta = dto?.metric?.beta?.takeIf { it.isFinite() && it != 0.0 }
        return StockMetrics(beta = beta)
    }

    override suspend fun fetchQuotes(tickers: List<String>): List<RemoteQuote> {
        requireKey()
        // Single-symbol provider: one call per ticker. The caller has already
        // throttled and sized this list, so we just execute it.
        return tickers.mapNotNull { ticker ->
            val dto = runCatching { service.quote(ticker.trim().uppercase()) }
                .getOrElse { throw it.toDomain() }
            // Finnhub answers unknown symbols with an all-zero body.
            if (dto.current <= 0.0) return@mapNotNull null
            RemoteQuote(
                ticker = ticker.trim().uppercase(),
                price = dto.current,
                dayChangePct = dto.percentChange
                    ?: percentFrom(dto.current, dto.previousClose),
                asOfEpochMs = if (dto.timestamp > 0) dto.timestamp * 1000 else System.currentTimeMillis(),
            )
        }
    }

    override suspend fun fetchCompanyNews(
        ticker: String,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): List<NewsArticle> {
        requireKey()
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val from = LocalDate.ofEpochDay(fromEpochDay).format(fmt)
        val to = LocalDate.ofEpochDay(toEpochDay).format(fmt)
        val items = runCatching {
            service.companyNews(ticker.trim().uppercase(), from, to)
        }.getOrElse { throw it.toDomain() }

        return items
            .asSequence()
            .filter { it.headline.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.id }
            .sortedByDescending { it.datetime }
            .map {
                NewsArticle(
                    id = it.id,
                    headline = it.headline.trim(),
                    summary = it.summary.trim(),
                    source = it.source.ifBlank { "News" },
                    url = it.url,
                    imageUrl = it.image.takeIf { url -> url.startsWith("http") },
                    publishedAtEpochMs = if (it.datetime > 0) it.datetime * 1000 else System.currentTimeMillis(),
                )
            }
            .toList()
    }

    private fun requireKey() {
        if (apiKey.isBlank()) throw MissingApiKeyException()
    }

    private fun percentFrom(current: Double, previousClose: Double): Double =
        if (previousClose > 0) (current - previousClose) / previousClose * 100 else 0.0

    private fun Throwable.toDomain(): Throwable = when (this) {
        is RateLimitException, is MissingApiKeyException, is PriceApiException -> this
        is HttpException -> if (code() == 429) {
            RateLimitException(retryAfterSeconds = response()?.headers()?.get("Retry-After")?.toLongOrNull())
        } else {
            PriceApiException("Finnhub HTTP ${code()}", this)
        }
        is IOException -> PriceApiException("Network error: ${message ?: "offline"}", this)
        else -> PriceApiException(message ?: "Unknown price API error", this)
    }
}
