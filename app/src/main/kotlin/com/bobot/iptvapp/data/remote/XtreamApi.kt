package com.bobot.iptvapp.data.remote

import com.bobot.iptvapp.data.remote.dto.AccountInfoDto
import com.bobot.iptvapp.data.remote.dto.CategoryDto
import com.bobot.iptvapp.data.remote.dto.EpgListingDto
import com.bobot.iptvapp.data.remote.dto.LiveStreamDto
import com.bobot.iptvapp.data.remote.dto.SeriesDto
import com.bobot.iptvapp.data.remote.dto.SeriesInfoDto
import com.bobot.iptvapp.data.remote.dto.VodInfoDto
import com.bobot.iptvapp.data.remote.dto.VodStreamDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the Xtream Codes player API.
 *
 * All calls are routed through the single `player_api.php` endpoint; the `action`
 * query parameter selects the operation. Use [Action] constants to avoid string typos.
 *
 * **Authentication**: every call requires [username] and [password] as query
 * parameters. These are the user's Xtream Codes credentials, not HTTP auth headers.
 *
 * **Dynamic base URL**: this interface must not be used directly. Obtain an instance
 * via [XtreamApiFactory.create], which wires the correct server base URL at runtime.
 *
 * **Nullable query params**: Retrofit omits a query parameter from the request URL
 * when its value is `null`. This is used for optional filters such as [categoryId]
 * in stream-list methods.
 */
interface XtreamApi {

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Authenticates the credentials and returns account/server metadata.
     *
     * Endpoint: `player_api.php?username=X&password=Y` (no `action` param).
     * A successful response has `user_info.auth == 1`.
     */
    @GET("player_api.php")
    suspend fun authenticate(
        @Query("username") username: String,
        @Query("password") password: String,
    ): AccountInfoDto

    // ── Categories ───────────────────────────────────────────────────────────

    /** Returns all live-stream categories. Pass [Action.GET_LIVE_CATEGORIES]. */
    @GET("player_api.php")
    suspend fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
    ): List<CategoryDto>

    /** Returns all VOD categories. Pass [Action.GET_VOD_CATEGORIES]. */
    @GET("player_api.php")
    suspend fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
    ): List<CategoryDto>

    /** Returns all series categories. Pass [Action.GET_SERIES_CATEGORIES]. */
    @GET("player_api.php")
    suspend fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
    ): List<CategoryDto>

    // ── Stream lists ─────────────────────────────────────────────────────────

    /**
     * Returns all live streams, optionally filtered by [categoryId].
     *
     * Pass [Action.GET_LIVE_STREAMS]. When [categoryId] is `null`, all live streams
     * across all categories are returned.
     */
    @GET("player_api.php")
    suspend fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @Query("category_id") categoryId: String? = null,
    ): List<LiveStreamDto>

    /**
     * Returns all VOD streams, optionally filtered by [categoryId].
     *
     * Pass [Action.GET_VOD_STREAMS]. When [categoryId] is `null`, all VOD entries
     * across all categories are returned.
     */
    @GET("player_api.php")
    suspend fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @Query("category_id") categoryId: String? = null,
    ): List<VodStreamDto>

    /**
     * Returns all series, optionally filtered by [categoryId].
     *
     * Pass [Action.GET_SERIES]. When [categoryId] is `null`, all series across all
     * categories are returned.
     */
    @GET("player_api.php")
    suspend fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @Query("category_id") categoryId: String? = null,
    ): List<SeriesDto>

    // ── Detail endpoints ─────────────────────────────────────────────────────

    /**
     * Returns extended metadata for a single VOD entry identified by [vodId].
     *
     * Pass [Action.GET_VOD_INFO]. The response includes cover art, plot, duration,
     * and the stream identification data needed to build the playback URL.
     */
    @GET("player_api.php")
    suspend fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @Query("vod_id") vodId: String,
    ): VodInfoDto

    /**
     * Returns the full season/episode tree for a series identified by [seriesId].
     *
     * Pass [Action.GET_SERIES_INFO]. The response nests episode lists inside a
     * `Map<seasonNumber, List<Episode>>` structure (see [SeriesInfoDto.episodes]).
     */
    @GET("player_api.php")
    suspend fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @Query("series_id") seriesId: String,
    ): SeriesInfoDto

    // ── EPG ──────────────────────────────────────────────────────────────────

    /**
     * Returns the short EPG listing for a live stream identified by [streamId].
     *
     * Pass [Action.GET_SHORT_EPG]. [limit] constrains how many programme records are
     * returned (e.g. 4 for "now + next 3"). Pass `null` to let the server decide.
     *
     * **Note**: title and description fields in the response are Base64-encoded.
     * The mapper ([com.bobot.iptvapp.data.remote.mapper.toEpgProgram]) decodes them.
     */
    @GET("player_api.php")
    suspend fun getShortEpg(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String,
        @Query("stream_id") streamId: String,
        @Query("limit") limit: Int? = null,
    ): EpgListingDto

    // ── Action constants ──────────────────────────────────────────────────────

    /**
     * Action strings for the `action` query parameter.
     *
     * Usage:
     * ```kotlin
     * api.getLiveCategories(username, password, XtreamApi.Action.GET_LIVE_CATEGORIES)
     * ```
     */
    object Action {
        const val GET_LIVE_CATEGORIES = "get_live_categories"
        const val GET_VOD_CATEGORIES = "get_vod_categories"
        const val GET_SERIES_CATEGORIES = "get_series_categories"
        const val GET_LIVE_STREAMS = "get_live_streams"
        const val GET_VOD_STREAMS = "get_vod_streams"
        const val GET_SERIES = "get_series"
        const val GET_VOD_INFO = "get_vod_info"
        const val GET_SERIES_INFO = "get_series_info"
        const val GET_SHORT_EPG = "get_short_epg"
    }
}
