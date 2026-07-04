package com.bobot.iptvapp.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory that creates a configured [XtreamApi] Retrofit instance for a given server URL.
 *
 * ## Dynamic Base URL Approach
 *
 * Xtream Codes servers are user-provided at runtime (entered in the settings screen).
 * A fixed Retrofit base URL is therefore not viable. This factory holds the shared
 * [OkHttpClient] and [Json] singletons provided by the DI graph and builds a new
 * [Retrofit] instance — and consequently a new [XtreamApi] proxy — per server URL.
 *
 * Retrofit instance creation is cheap: all serialisation infrastructure is reused
 * from the shared singletons. The repository (Task 8) is responsible for caching the
 * [XtreamApi] instance if multiple calls are expected for the same server session.
 *
 * ## Alternatives considered
 * - **Host-selection OkHttp interceptor**: Would require thread-safe mutable state in
 *   an interceptor and complicates concurrent multi-server scenarios.
 * - **`@Url`-annotated Retrofit methods**: Requires callers to construct full URLs,
 *   defeating the purpose of a typed API interface.
 * - **The factory approach (chosen)**: One factory, many API instances. Clean, testable,
 *   and future-proof for profiles with different servers.
 *
 * @param okHttpClient Shared [OkHttpClient] singleton (with logging, timeouts, etc.).
 * @param json         Shared [Json] singleton configured with lenient parsing options.
 */
@Singleton
class XtreamApiFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    private val mediaType = "application/json".toMediaType()

    /**
     * Creates a [XtreamApi] for the given [baseUrl].
     *
     * @param baseUrl Root URL of the Xtream Codes server, e.g.
     *   `"http://example.com:8080"`. A trailing `/` is appended automatically.
     * @return A fully configured [XtreamApi] Retrofit proxy ready for use.
     */
    fun create(baseUrl: String): XtreamApi {
        val normalizedUrl = baseUrl.trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
            .create(XtreamApi::class.java)
    }
}
