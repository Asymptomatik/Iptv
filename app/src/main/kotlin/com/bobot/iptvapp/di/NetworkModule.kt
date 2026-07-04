package com.bobot.iptvapp.di

import com.bobot.iptvapp.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing network-layer singletons for the Xtream Codes client.
 *
 * ## Provided bindings
 *
 * | Binding            | Scope     | Description                                              |
 * |--------------------|-----------|----------------------------------------------------------|
 * | [Json]             | Singleton | Tolerant kotlinx.serialization instance                  |
 * | [OkHttpClient]     | Singleton | Shared HTTP client with logging interceptor (debug only) |
 *
 * ## What is NOT provided here
 * [com.bobot.iptvapp.data.remote.XtreamApiFactory] is injected directly by Hilt via
 * its `@Inject constructor` — no `@Provides` entry is needed. The factory uses [Json]
 * and [OkHttpClient] from this module to build a per-server-URL [retrofit2.Retrofit]
 * instance at runtime (see [com.bobot.iptvapp.data.remote.XtreamApiFactory] KDoc for
 * the full dynamic base URL rationale).
 *
 * ## JSON configuration
 * Xtream Codes API payloads are notoriously inconsistent: field types may vary across
 * server implementations, unknown fields appear regularly, and some fields carry blank
 * strings where null would be semantically correct. The [Json] instance is configured
 * with maximum tolerance:
 * - `ignoreUnknownKeys = true` — silently drops unrecognised fields
 * - `isLenient = true`         — accepts unquoted strings, trailing commas, etc.
 * - `coerceInputValues = true` — coerces incompatible values to the type's default
 *   (e.g. a JSON null for a non-null field with a default value uses that default)
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Provides the shared [Json] instance used by the kotlinx-serialization Retrofit
     * converter and anywhere else in the data layer that needs to parse Xtream JSON.
     *
     * This instance must never be used for serialising outgoing data that requires
     * strict type conformance — it is intentionally lenient for Xtream API consumption.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Provides the shared [OkHttpClient] for all HTTP calls in the app.
     *
     * Configuration:
     * - **Logging**: A [HttpLoggingInterceptor] at [HttpLoggingInterceptor.Level.BODY]
     *   is added only in debug builds ([BuildConfig.DEBUG]). Release builds produce no
     *   network logs, protecting user credentials from appearing in logcat.
     * - **Connect timeout**: 30 s — time to establish the TCP connection.
     * - **Read timeout**: 60 s — generous allowance for slow Xtream servers under load
     *   (stream-list payloads can be large).
     * - **Write timeout**: 30 s — relevant for upload operations (currently none).
     * - **Retries**: OkHttp's built-in retry-on-connection-failure is kept enabled
     *   (default). Task-level retry logic belongs in the repository layer.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BODY
                        },
                    )
                }
            }
            .build()
}
