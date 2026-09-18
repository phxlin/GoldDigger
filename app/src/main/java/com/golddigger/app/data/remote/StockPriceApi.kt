package com.golddigger.app.data.remote

/**
 * Provider-agnostic contract for the price backend. The repository and the rest
 * of the app depend only on this interface; the concrete provider (Finnhub today,
 * Alpha Vantage / Twelve Data / IEX tomorrow) is bound in [com.golddigger.app.di.NetworkModule]
 * and can be swapped without touching the repository, ViewModels or UI.
 *
 * Implementations are responsible only for turning a request into the *fewest
 * possible* network calls and mapping the response. Cross-ticker throttling,
 * batching policy and caching all live in the repository layer.
 */
interface StockPriceApi {

    /**
     * Largest number of symbols the provider will return from a single quote
     * request. Finnhub's free tier is single-symbol, so this is 1 and the
     * repository issues throttled sequential calls; a provider with a
     * comma-separated multi-symbol endpoint reports e.g. 100 here and the
     * repository packs that many per call.
     */
    val maxSymbolsPerQuoteRequest: Int

    /** Ticker lookup for search-as-you-type. Returns [] on no match. */
    suspend fun searchSymbols(query: String): List<SymbolSearchResult>

    /** Company name + sector for a freshly added ticker. Null if unknown. */
    suspend fun fetchProfile(ticker: String): StockProfile?

    /**
     * Supplementary risk metrics (currently just beta) for the Formation view.
     * This is best-effort enrichment, not core data: implementations should
     * return [StockMetrics] with null fields rather than throwing when the
     * provider doesn't expose them or the plan doesn't allow it.
     */
    suspend fun fetchMetrics(ticker: String): StockMetrics

    /**
     * Fetch quotes for [tickers] (already sized within [maxSymbolsPerQuoteRequest]
     * and throttled by the caller).
     *
     * @throws RateLimitException on HTTP 429 / provider rate-limit response
     * @throws MissingApiKeyException if no key is configured
     * @throws PriceApiException on any other failure
     */
    suspend fun fetchQuotes(tickers: List<String>): List<RemoteQuote>

    /**
     * Recent company news for [ticker] between [fromEpochDay] and [toEpochDay]
     * (inclusive, `LocalDate.toEpochDay()`). Same exception contract as
     * [fetchQuotes]. Returns [] if the provider has nothing.
     */
    suspend fun fetchCompanyNews(
        ticker: String,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): List<NewsArticle>
}

data class NewsArticle(
    val id: Long,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val imageUrl: String?,
    val publishedAtEpochMs: Long,
)

data class SymbolSearchResult(
    val symbol: String,
    val displaySymbol: String,
    val description: String,
    val type: String,
)

/**
 * Best-effort ETF classification from the search provider's free-text [SymbolSearchResult.type]
 * (Finnhub returns e.g. "Common Stock", "ETP", "ETF", "Mutual Fund"). Just a
 * sensible default — the Add/Edit and import-review screens both let the user
 * correct it by hand.
 */
fun SymbolSearchResult.isLikelyEtf(): Boolean =
    type.contains("ETF", ignoreCase = true) || type.contains("ETP", ignoreCase = true)

data class StockProfile(
    val ticker: String,
    val companyName: String?,
    val sector: String?,
)

/** Best-effort risk metrics. All fields nullable — providers vary in coverage. */
data class StockMetrics(
    val beta: Double? = null,
)

data class RemoteQuote(
    val ticker: String,
    val price: Double,
    val dayChangePct: Double,
    val asOfEpochMs: Long,
)

class RateLimitException(val retryAfterSeconds: Long?) :
    Exception("Price provider rate limit reached")

class MissingApiKeyException :
    Exception("No price API key configured (set FINNHUB_API_KEY in local.properties)")

class PriceApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
