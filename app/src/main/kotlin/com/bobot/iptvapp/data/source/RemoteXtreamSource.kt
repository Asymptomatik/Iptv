package com.bobot.iptvapp.data.source

import com.bobot.iptvapp.data.remote.XtreamApi
import com.bobot.iptvapp.data.remote.XtreamApiFactory
import com.bobot.iptvapp.data.remote.mapper.toDomain
import com.bobot.iptvapp.di.ApplicationScope
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real Xtream Codes [CatalogDataSource] — calls the live Xtream Codes player API.
 *
 * ## Credential and API lifecycle
 * Credentials are obtained on every call via [credentialsProvider]. The [XtreamApi]
 * Retrofit proxy is cached per base URL so that the Retrofit client is not recreated on
 * every request (per the caching contract documented in [XtreamApiFactory]). If the
 * user switches to a different server URL, [apiCache] automatically creates and caches
 * a new proxy for that URL.
 *
 * ## API cache invalidation on credential change
 * An application-scoped coroutine (launched in [applicationScope]) observes
 * [credentialsProvider.observeCredentials()][CredentialsProvider.observeCredentials].
 * When credentials change (new server URL, re-authentication, or logout), [apiCache] is
 * cleared so stale Retrofit proxies are not reused for the new server configuration.
 * `drop(1)` skips the initial emission on collection start — only runtime changes
 * (user saves new credentials or logs out) trigger the cache clear.
 *
 * ## Error mapping
 *  - No credentials configured → [CatalogException.AuthenticationFailed]
 *  - `user_info.auth == 0` in auth response → [CatalogException.AuthenticationFailed]
 *  - Any IO / HTTP / JSON parsing exception → [CatalogException.NetworkError]
 *  - Already-domain [CatalogException] subtypes → re-thrown unchanged
 *
 * ## Thread safety
 * [apiCache] is a [ConcurrentHashMap] to allow safe concurrent access from multiple
 * coroutines running on [kotlinx.coroutines.Dispatchers.IO].
 *
 * ## Activation
 * Set `buildConfigField("boolean", "USE_MOCK_DATA", "false")` in `app/build.gradle.kts`
 * and configure credentials via the onboarding screen (Task 14) before use.
 *
 * @param apiFactory          Factory for creating per-URL [XtreamApi] instances.
 * @param credentialsProvider Provides current server credentials and credential-change events.
 * @param applicationScope    Application-scoped [CoroutineScope] for the cache-observer coroutine.
 */
@Singleton
class RemoteXtreamSource @Inject constructor(
    private val apiFactory: XtreamApiFactory,
    private val credentialsProvider: CredentialsProvider,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : CatalogDataSource {

    /**
     * Per-base-URL cache of [XtreamApi] instances.
     *
     * [XtreamApiFactory.create] is cheap but Retrofit proxy creation still allocates;
     * caching avoids that overhead on every call within a session. A different base URL
     * naturally yields a different cache entry, so server switches are handled correctly.
     * The cache is cleared automatically when credentials change (see `init` block).
     */
    private val apiCache = ConcurrentHashMap<String, XtreamApi>()

    init {
        // Clear the per-URL Retrofit proxy cache whenever credentials change at runtime.
        // This ensures a new server URL gets a fresh Retrofit instance rather than
        // hitting a stale cached entry. drop(1) skips the initial DataStore read on
        // collection start; we only clear on actual runtime credential changes.
        applicationScope.launch {
            credentialsProvider.observeCredentials()
                .drop(1)
                .distinctUntilChanged()
                .collect { apiCache.clear() }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the active [XtreamCredentials] from [credentialsProvider] and returns
     * the cached (or newly created) [XtreamApi] instance for that base URL, along with
     * the credentials needed for query parameters.
     *
     * @throws CatalogException.AuthenticationFailed if no credentials are configured.
     */
    private suspend fun resolveApi(): Pair<XtreamApi, XtreamCredentials> {
        val creds = credentialsProvider.getCredentials()
            ?: throw CatalogException.AuthenticationFailed(
                "No server credentials configured. " +
                    "Complete onboarding or set credentials via DataStoreCredentialsProvider.",
            )
        val api = apiCache.computeIfAbsent(creds.baseUrl) { url -> apiFactory.create(url) }
        return api to creds
    }

    /**
     * Executes [block] and maps any non-[CatalogException] to [CatalogException.NetworkError],
     * ensuring the caller only needs to handle domain-level exceptions.
     */
    private suspend fun <T> safeCall(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: CatalogException) {
            throw e // already a domain exception — pass through unchanged
        } catch (e: Exception) {
            throw CatalogException.NetworkError(
                message = "Network or parsing error: ${e.message}",
                cause = e,
            )
        }
    }

    // ── Authentication ────────────────────────────────────────────────────────

    override suspend fun authenticate(): Result<Unit> {
        return try {
            val (api, creds) = resolveApi()
            val response = safeCall { api.authenticate(creds.username, creds.password) }
            if (response.userInfo?.auth != 1) {
                Result.failure(CatalogException.AuthenticationFailed())
            } else {
                Result.success(Unit)
            }
        } catch (e: CatalogException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                CatalogException.NetworkError("Authentication request failed: ${e.message}", e),
            )
        }
    }

    // ── Categories ────────────────────────────────────────────────────────────

    override suspend fun getLiveCategories(): List<Category> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getLiveCategories(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_LIVE_CATEGORIES,
            ).toDomain(ContentType.LIVE)
        }
    }

    override suspend fun getVodCategories(): List<Category> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getVodCategories(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_VOD_CATEGORIES,
            ).toDomain(ContentType.MOVIE)
        }
    }

    override suspend fun getSeriesCategories(): List<Category> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getSeriesCategories(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_SERIES_CATEGORIES,
            ).toDomain(ContentType.SERIES)
        }
    }

    // ── Stream lists ──────────────────────────────────────────────────────────

    override suspend fun getLiveChannels(categoryId: String?): List<Channel> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getLiveStreams(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_LIVE_STREAMS,
                categoryId = categoryId,
            ).toDomain()
        }
    }

    override suspend fun getMovies(categoryId: String?): List<Movie> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getVodStreams(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_VOD_STREAMS,
                categoryId = categoryId,
            ).toDomain()
        }
    }

    override suspend fun getSeriesList(categoryId: String?): List<Series> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getSeries(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_SERIES,
                categoryId = categoryId,
            ).toDomain()
        }
    }

    // ── Detail endpoints ──────────────────────────────────────────────────────

    override suspend fun getMovieInfo(movieId: String): Movie {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getVodInfo(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_VOD_INFO,
                vodId = movieId,
            ).toDomain(
                fallbackStreamId = movieId,
                // categoryId is embedded in the detail DTO; "" is a safe fallback
                // for the rare case where movieData and info both omit it.
                fallbackCategoryId = "",
            )
        }
    }

    override suspend fun getSeriesInfo(seriesId: String): Series {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getSeriesInfo(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_SERIES_INFO,
                seriesId = seriesId,
            ).toDomain(seriesId = seriesId)
        }
    }

    // ── EPG ───────────────────────────────────────────────────────────────────

    /**
     * Returns EPG programme entries for a live channel identified by [channelId].
     *
     * **Note on ID semantics**: The Xtream Codes `get_short_epg` endpoint accepts a
     * numeric `stream_id` (the same as [com.bobot.iptvapp.domain.model.Channel.id]).
     * Pass [Channel.id] from the caller, not [Channel.epgChannelId] — as of QA finding N3 the
     * [FakeXtreamSource] takes the same id, so there is no longer a per-source distinction for
     * callers to handle.
     */
    override suspend fun getShortEpg(channelId: String, limit: Int?): List<EpgProgram> {
        val (api, creds) = resolveApi()
        return safeCall {
            api.getShortEpg(
                username = creds.username,
                password = creds.password,
                action = XtreamApi.Action.GET_SHORT_EPG,
                streamId = channelId,
                limit = limit,
            ).toDomain(fallbackChannelId = channelId)
        }
    }
}
