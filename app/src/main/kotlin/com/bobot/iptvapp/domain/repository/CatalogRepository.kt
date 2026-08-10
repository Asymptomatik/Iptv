package com.bobot.iptvapp.domain.repository

import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.util.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer contract for catalog data access.
 *
 * ViewModels collect from this repository and map [Resource] states to Compose UI states
 * (loading, content, error, empty). The repository hides whether data comes from the
 * mock source ([com.bobot.iptvapp.data.source.fake.FakeXtreamSource]) or the live
 * Xtream Codes API ([com.bobot.iptvapp.data.source.RemoteXtreamSource]).
 *
 * ## Flow vs suspend convention
 *
 * | Return type                   | Method type                   | Rationale                                    |
 * |-------------------------------|-------------------------------|----------------------------------------------|
 * | `Flow<Resource<List<T>>>`     | Category lists / stream rows  | Reactive; supports progressive loading states; session cache avoids re-fetches on re-collect |
 * | `suspend fun returning Resource<T>` | One-shot detail fetches | Single result; triggered by navigation events; no streaming needed |
 *
 * ## Lazy rendering / pagination
 * Xtream Codes returns full (non-paginated) lists. This repository exposes them as
 * complete lists. Large-catalog windowing is handled at the UI layer by
 * `LazyRow` / `LazyColumn` (Compose). No manual pagination is implemented here.
 *
 * ## Error handling
 * All [com.bobot.iptvapp.data.source.CatalogException] subtypes are caught and emitted
 * as [Resource.Error]. The UI should use the exception type (accessible via
 * [Resource.Error.throwable]) to decide which error state to render:
 *  - [com.bobot.iptvapp.data.source.CatalogException.AuthenticationFailed] → credentials screen
 *  - [com.bobot.iptvapp.data.source.CatalogException.NotFound] → "not found" placeholder
 *  - [com.bobot.iptvapp.data.source.CatalogException.NetworkError] → connectivity error with retry
 *
 * ## Implementation
 * @see com.bobot.iptvapp.data.repository.CatalogRepositoryImpl
 */
interface CatalogRepository {

    // ── Categories ────────────────────────────────────────────────────────────

    /**
     * Observes live-stream categories.
     * Emits [Resource.Loading] first, then [Resource.Success] or [Resource.Error].
     */
    fun observeLiveCategories(): Flow<Resource<List<Category>>>

    /**
     * Observes VOD (movie) categories.
     * Emits [Resource.Loading] first, then [Resource.Success] or [Resource.Error].
     */
    fun observeVodCategories(): Flow<Resource<List<Category>>>

    /**
     * Observes series categories.
     * Emits [Resource.Loading] first, then [Resource.Success] or [Resource.Error].
     */
    fun observeSeriesCategories(): Flow<Resource<List<Category>>>

    // ── Stream lists ──────────────────────────────────────────────────────────

    /**
     * Observes live channels, optionally filtered by [categoryId].
     * Pass `null` to observe all channels across categories.
     * Emits [Resource.Loading] first, then [Resource.Success] or [Resource.Error].
     */
    fun getLiveChannels(categoryId: String? = null): Flow<Resource<List<Channel>>>

    /**
     * Observes movies, optionally filtered by [categoryId].
     * Pass `null` to observe all movies across categories.
     * Emits [Resource.Loading] first, then [Resource.Success] or [Resource.Error].
     */
    fun getMovies(categoryId: String? = null): Flow<Resource<List<Movie>>>

    /**
     * Observes series metadata list, optionally filtered by [categoryId].
     * The returned [Series] objects have empty [Series.seasons]; call [getSeriesDetail]
     * to load the full season/episode tree for a specific series.
     * Pass `null` to observe all series across categories.
     * Emits [Resource.Loading] first, then [Resource.Success] or [Resource.Error].
     */
    fun getSeriesList(categoryId: String? = null): Flow<Resource<List<Series>>>

    // ── Detail (one-shot) ─────────────────────────────────────────────────────

    /**
     * Fetches extended metadata for a single movie (e.g. plot, duration, cover art).
     *
     * @param movieId The stream ID matching [Movie.id].
     * @return [Resource.Success] with the populated [Movie], or [Resource.Error]
     *         (wrapping [com.bobot.iptvapp.data.source.CatalogException.NotFound]
     *         when the movie does not exist on the server).
     */
    suspend fun getMovieDetail(movieId: String): Resource<Movie>

    /**
     * Fetches the full season/episode tree for a series.
     *
     * @param seriesId The series ID matching [Series.id].
     * @return [Resource.Success] with [Series.seasons] populated, or [Resource.Error].
     */
    suspend fun getSeriesDetail(seriesId: String): Resource<Series>

    /**
     * Fetches EPG programme entries for a live channel.
     *
     * [channelId] is the numeric stream ID ([Channel.id]), which maps to the `stream_id` query
     * parameter of the `get_short_epg` endpoint. Both sources take it — the fake used to want
     * [Channel.epgChannelId] instead, and callers obliging it got an empty EPG from the real
     * one every time (QA finding N3).
     *
     * @param channelId Stream identifier for the EPG request ([Channel.id]).
     * @param limit     Maximum number of programme entries. `null` for server-default.
     * @return [Resource.Success] with the EPG list (may be empty for channels without EPG
     *         data), or [Resource.Error] on network failure.
     */
    suspend fun getEpg(channelId: String, limit: Int? = null): Resource<List<EpgProgram>>

    // ── Cache resolution (Task 24/25 — Continue Watching for series) ─────────

    /**
     * Resolves a series episode and its parent series from a raw episode identifier,
     * reading exclusively from the offline-first Room catalog cache — no network call
     * is made.
     *
     * This is used to render a "Continue Watching" entry for series content: a saved
     * [com.bobot.iptvapp.domain.model.PlaybackProgress] only stores the episode id, so the
     * UI needs a way to recover the episode's display metadata and its parent series
     * (poster, title) without navigating through the series detail screen.
     *
     * Unlike [getSeriesDetail], this method does not return a [Resource]: a cache miss
     * here is not a failure to report to the UI, it is an expected, silent outcome — the
     * corresponding "Continue Watching" entry is simply omitted (graceful degradation).
     * The episode/series pair is only resolvable once the series detail screen has been
     * opened at least once for that series (which is what populates the season/episode
     * cache — see [getSeriesDetail]).
     *
     * @param episodeId The stream ID matching [Episode.id] (the `contentId` of a
     *   series-type [com.bobot.iptvapp.domain.model.PlaybackProgress]).
     * @return The resolved `Pair<Series, Episode>`, or `null` when the episode or its
     *   parent series is not present in the cache.
     */
    suspend fun getCachedEpisodeWithSeries(episodeId: String): Pair<Series, Episode>?

    /**
     * Resolves a single live channel from the offline-first Room catalog cache — no network
     * call is made, and a miss is a silent `null` rather than a [Resource].
     *
     * Exists because the live detail screen needs exactly one channel's metadata, and the only
     * way to get it used to be [getLiveChannels] with `categoryId = null` — the *unfiltered*
     * list. That is the heaviest request the API offers, and it is one the catalog screens never
     * make (they load per category), so it was never cached either: opening a channel meant
     * downloading the entire bouquet to read one row out of it, every single time.
     *
     * Unlike the list reads, this lookup is not TTL-gated. Channel metadata — name, logo, EPG id
     * — is stable, the caller falls back to the network when it misses, and the row is refreshed
     * whenever its category is. Gating it would reintroduce the full-bouquet download for no
     * practical gain in freshness.
     *
     * @param channelId The stream ID matching [Channel.id].
     * @return The cached channel, or `null` when it is absent (or no credentials are configured).
     */
    suspend fun getCachedChannel(channelId: String): Channel?

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Validates the current server credentials.
     *
     * @return [Resource.Success] when the server accepts the credentials.
     *         [Resource.Error] wrapping [com.bobot.iptvapp.data.source.CatalogException.AuthenticationFailed]
     *         when the server rejects them or when no credentials are configured.
     */
    suspend fun authenticate(): Resource<Unit>

    // ── Cache management ──────────────────────────────────────────────────────

    /**
     * Invalidates all in-memory session caches held by this repository.
     *
     * This method affects **only the in-memory session cache** — it does not access the
     * Room database. Account switches need no Room invalidation: each account's data
     * partition is self-contained. To force the persistent cache to be refetched, see
     * [invalidatePersistentCache].
     *
     * Called automatically by [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl]
     * when credentials change (via [com.bobot.iptvapp.data.source.CredentialsProvider.observeCredentials]),
     * ensuring that the next request fetches fresh content from the new server.
     *
     * Can also be called explicitly in tests or debug scenarios to force a clean fetch.
     *
     * For a narrower invalidation scoped to a single content type (e.g. after a
     * per-type filter change such as language), prefer [invalidateCache] instead —
     * it avoids discarding session caches for content types that are unaffected.
     */
    fun invalidateCaches()

    /**
     * Invalidates the in-memory session cache for a single [ContentType] only.
     *
     * This method affects **only the in-memory session cache** — it does not access the
     * Room database. Pair it with [invalidatePersistentCache] when the caller wants the
     * next read to reach the server rather than Room.
     *
     * Unlike [invalidateCaches] (which clears every cached list and category across
     * all content types — used for global events such as a credentials/server change),
     * this method clears only the cache(s) associated with [type]:
     *  - [ContentType.LIVE] → cached live categories + cached channel list.
     *  - [ContentType.MOVIE] → cached VOD categories + cached movie list.
     *  - [ContentType.SERIES] → cached series categories + cached series list.
     *
     * Intended for targeted reloads (e.g. a per-type content filter change) where
     * invalidating the other, unrelated content types' caches would cause needless
     * re-fetches.
     *
     * @param type the content type whose in-memory cache should be cleared.
     */
    fun invalidateCache(type: ContentType)

    /**
     * Marks the Room catalog cache for [type] as stale, so the next read refetches from the
     * server instead of being served locally.
     *
     * Deliberately separate from [invalidateCache] rather than folded into it. The two have
     * different callers and must keep different reach: [invalidateCache] also fires from the
     * application-scoped credentials observer, where "the current account" has *already*
     * become the new one — clearing persistent freshness there would throw away a perfectly
     * valid cache belonging to the account the user just switched to, and cost them a full
     * refetch for nothing. This method is for the explicit, user-initiated reload in Réglages,
     * where a refetch is exactly what was asked for.
     *
     * Only the current account's markers for [type] are dropped; the cached rows themselves
     * are left in place, so the reload degrades to today's offline fallback if the network is
     * down rather than emptying the screen. Callers normally pair this with [invalidateCache]
     * for the same [type] — the in-memory session cache would otherwise answer first and the
     * reload would appear to do nothing.
     *
     * A no-op when no credentials are configured, and best-effort: a Room failure leaves the
     * markers as they were rather than surfacing an error.
     *
     * @param type the content type whose persistent cache should be considered stale.
     */
    suspend fun invalidatePersistentCache(type: ContentType)
}
