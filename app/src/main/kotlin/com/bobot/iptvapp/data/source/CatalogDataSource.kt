package com.bobot.iptvapp.data.source

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series

/**
 * Contract for catalog data access — shared by the mock and the real Xtream Codes source.
 *
 * All methods suspend and return domain models directly (no DTOs exposed). Implementations
 * are responsible for mapping from their respective data source to the domain types defined
 * in [com.bobot.iptvapp.domain.model].
 *
 * ## Implementations
 *  - [com.bobot.iptvapp.data.source.fake.FakeXtreamSource] — in-memory mock, selected
 *    when `BuildConfig.USE_MOCK_DATA = true` (default during development).
 *  - [RemoteXtreamSource] — live Xtream Codes network source, selected when
 *    `BuildConfig.USE_MOCK_DATA = false`. Requires Task 8 to be fully implemented.
 *
 * ## Error handling
 * Implementations may throw [CatalogException] for domain-level failures (auth, not found)
 * or any IO/network exception for transport-level failures. Callers (repositories, Task 8)
 * should wrap calls in `runCatching` or try/catch and translate errors for the UI layer.
 *
 * ## Series detail lazy loading
 * [getSeriesList] returns stubs with [com.bobot.iptvapp.domain.model.Series.seasons] empty.
 * Call [getSeriesInfo] to load the full season/episode tree for a given series.
 */
interface CatalogDataSource {

    /**
     * Validates the current server credentials against the Xtream Codes server.
     *
     * The fake implementation always returns success.
     * The real implementation (Task 8) hits the `player_api.php` auth endpoint.
     *
     * @return [Result.success] when the server accepts the credentials.
     *         [Result.failure] wrapping [CatalogException.AuthenticationFailed] when
     *         the server rejects them.
     */
    suspend fun authenticate(): Result<Unit>

    // ── Categories ───────────────────────────────────────────────────────────

    /** Returns all live-stream categories. */
    suspend fun getLiveCategories(): List<Category>

    /** Returns all VOD (movie) categories. */
    suspend fun getVodCategories(): List<Category>

    /** Returns all series categories. */
    suspend fun getSeriesCategories(): List<Category>

    // ── Stream lists ─────────────────────────────────────────────────────────

    /**
     * Returns live channels, optionally filtered by [categoryId].
     * Pass `null` to retrieve all channels across all categories.
     */
    suspend fun getLiveChannels(categoryId: String? = null): List<Channel>

    /**
     * Returns movies, optionally filtered by [categoryId].
     * Pass `null` to retrieve all movies across all categories.
     */
    suspend fun getMovies(categoryId: String? = null): List<Movie>

    /**
     * Returns series metadata list, optionally filtered by [categoryId].
     *
     * The returned [Series] objects have [Series.seasons] set to an empty list.
     * Call [getSeriesInfo] to load the full season/episode tree for a specific series.
     *
     * Pass `null` to retrieve all series across all categories.
     */
    suspend fun getSeriesList(categoryId: String? = null): List<Series>

    // ── Detail endpoints ─────────────────────────────────────────────────────

    /**
     * Returns extended metadata for a single movie.
     *
     * In the fake source, this returns the same data as [getMovies] since all fields
     * are already populated in-memory. The real source (Task 8) makes an additional
     * `get_vod_info` API call to retrieve plot, duration, and cover art.
     *
     * @throws [CatalogException.NotFound] when no movie with [movieId] exists.
     */
    suspend fun getMovieInfo(movieId: String): Movie

    /**
     * Returns the full season/episode tree for a series.
     *
     * The returned [Series] has [Series.seasons] populated with all episodes.
     * This corresponds to the `get_series_info` Xtream Codes endpoint.
     *
     * @throws [CatalogException.NotFound] when no series with [seriesId] exists.
     */
    suspend fun getSeriesInfo(seriesId: String): Series

    // ── EPG ──────────────────────────────────────────────────────────────────

    /**
     * Returns EPG programme entries for a live channel.
     *
     * [channelId] matches [com.bobot.iptvapp.domain.model.Channel.epgChannelId].
     * Channels without an [epgChannelId] have no EPG data — return an empty list for those.
     *
     * @param channelId The EPG channel identifier (e.g. "bbc.world").
     * @param limit     Maximum number of entries to return. Pass `null` for all available.
     * @return An empty list when [channelId] is unknown or has no EPG data.
     */
    suspend fun getShortEpg(channelId: String, limit: Int? = null): List<EpgProgram>
}
