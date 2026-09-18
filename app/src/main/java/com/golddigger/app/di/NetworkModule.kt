package com.golddigger.app.di

import com.golddigger.app.BuildConfig
import com.golddigger.app.core.SyncConfig
import com.golddigger.app.data.remote.StockPriceApi
import com.golddigger.app.data.remote.finnhub.FinnhubService
import com.golddigger.app.data.remote.finnhub.FinnhubStockPriceApi
import com.golddigger.app.data.remote.throttle.RequestThrottler
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Named("priceApiKey")
    fun providePriceApiKey(): String = BuildConfig.FINNHUB_API_KEY

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(@Named("priceApiKey") apiKey: String): OkHttpClient {
        val timeout = SyncConfig.NETWORK_TIMEOUT.inWholeSeconds
        return OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .apply {
                // Added before the token-injecting interceptor (interceptors
                // run in the order added) so debug logs see the request as it
                // looked before the API key was appended to its URL — logging
                // it after would print the live key in every Logcat line.
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .addInterceptor { chain ->
                // Inject the provider token as a query param so Retrofit
                // interfaces stay key-agnostic.
                val request = chain.request()
                val url = request.url.newBuilder()
                    .addQueryParameter("token", apiKey)
                    .build()
                chain.proceed(request.newBuilder().url(url).build())
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.PRICE_API_BASE_URL.toHttpUrl())
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideFinnhubService(retrofit: Retrofit): FinnhubService =
        retrofit.create(FinnhubService::class.java)

    /**
     * Binds the concrete provider. Swap this one line (and the base URL /
     * service) to move to Alpha Vantage, Twelve Data, IEX, etc.
     */
    @Provides
    @Singleton
    fun provideStockPriceApi(impl: FinnhubStockPriceApi): StockPriceApi = impl

    @Provides
    @Singleton
    fun provideRequestThrottler(): RequestThrottler = RequestThrottler(
        maxPermits = SyncConfig.MAX_REQUESTS_PER_MINUTE,
        windowMillis = SyncConfig.THROTTLE_WINDOW.inWholeMilliseconds,
    )
}
