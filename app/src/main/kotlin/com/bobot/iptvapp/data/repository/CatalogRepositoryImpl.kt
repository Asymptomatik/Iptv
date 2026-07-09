package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.mapper.toDomain
import com.bobot.iptvapp.data.local.mapper.toEntity
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.di.ApplicationScope
import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Production implementation of [CatalogRepository].
 *
 * Delegates to the active [CatalogDataSource] (mock or remote, selected by
 * [com.bobot.iptvapp.di.DataSourceModule]) and wraps all results in [Resource] so
 * that Compose screens can declaratively handle loading / success / error states.
 *
 * ## Flow vs suspend convention
 * See [CatalogRepository] for the full rationale.
 *
 * ## Session caching
 * Full unfiltered lists (categoryId = `null`) are cached in [Volatile] fields after
 * the first successful fetch. Subsequent collections of the same Flow return the
 * cached list without hitting the data source again. Filtered views (non-null
 * categoryId) are derived from the cached full list when available, or fetched
 * directly from the data source when the cache is empty.
 *
 * ## Cache invalidation on credential change
 * An application-scoped coroutine (launched in [applicationScope]) observes
 * [credentialsProvider.observeCredentials()][CredentialsProvider.observeCredentials].
 * When credentials change (new server URL, re-authentication, or logout), all cached
 * lists are cleared via [invalidateCaches] before the next content request is made.
 * `drop(1)` skips the initial emission on collection start so only actual runtime
 * changes trigger invalidation, not the stored startup state.
 *
 * ## Offline-first Room cache (Task 11b carry-forward)
 * In addition to the in-memory session cache above, every successful network/mock fetch
 * of categories, channels, movies, and series is persisted to [catalogCacheDao] (Room) via
 * the `data.local.mapper` extension functions. If a subsequent fetch fails (data source
 * throws), the corresponding method falls back to reading the Room cache:
 *  - a non-empty cached result is emitted as [Resource.Success] (stale-but-usable data,
 *    enabling basic offline browsing of previously seen content),
 *  - an empty (or absent) cache preserves today's behaviour: [Resource.Error].
 *
 * Room reads/writes performed for this cache are best-effort: a failure while persisting
 * to Room does not turn a successful network fetch into an error, and a failure while
 * reading the Room fallback falls through to [Resource.Error] instead of crashing.
 *
 * ## Series detail write-through (Task 11b/25 carry-forward)
 * [getSeriesDetail] additionally persists the parent [Series] plus its full flattened
 * season/episode tree ([catalogCacheDao].upsertSeasons / upsertEpisodes) on every
 * successful fetch — best-effort, via the same [persistQuietly] helper. This closes the
 * gap left since Task 10: the `seasons`/`episodes` tables existed but were never written
 * to. [getCachedEpisodeWithSeries] reads this data back to resolve a "Continue Watching"
 * entry for series content without a network round-trip.
 *
 * ## Dispatcher
 * All data source calls run on [ioDispatcher] (default: [kotlinx.coroutines.Dispatchers.IO])
 * via [flowOn] for Flows and direct suspension for one-shot methods.
 * Tests inject a [kotlinx.coroutines.test.TestDispatcher] via constructor injection.
 *
 * @param dataSource           The catalog data source (mock or remote).
 * @param catalogCacheDao      Room DAO backing the offline-first catalog cache.
 * @param ioDispatcher         The IO dispatcher; injected for testability.
 * @param credentialsProvider  Observed for credential changes to trigger cache invalidation.
 * @param applicationScope     Application-scoped [CoroutineScope] for the cache-observer coroutine.
 */
class CatalogRepositoryImpl @Inject constructor(
    private val dataSource: CatalogDataSource,
    private val catalogCacheDao: CatalogCacheDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val credentialsProvider: CredentialsProvider,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : CatalogRepository {

    // ── Session cache ─────────────────────────────────────────────────────────
    //
    // @Volatile ensures cross-thread visibility. Each field is written once
    // (on the first successful fetch) and cleared on credential change.
    // Concurrent first-fetches may both write to the field; the last write wins,
    // which is acceptable since both would contain equivalent data from the same
    // server endpoint.

    @Volatile private var cachedLiveCategories: List<Category>? = null
    @Volatile private var cachedVodCategories: List<Category>? = null
    @Volatile private var cachedSeriesCategories: List<Category>? = null

    @Volatile private var cachedAllChannels: List<Channel>? = null
    @Volatile private var cachedAllMovies: List<Movie>? = null
    @Volatile private var cachedAllSeries: List<Series>? = null

    // ── Cache invalidation observer ───────────────────────────────────────────

    init {
        // Observe credential changes and invalidate the session cache when they occur.
        // drop(1) skips the initial emission on collection start — we only react to
        // runtime changes (user saves new credentials or logs out), not to the startup
        // state read from DataStore.
        // distinctUntilChanged() suppresses duplicate emissions (e.g. saving the same
        // credentials twice) to avoid unnecessary cache clears.
        applicationScope.launch {
            credentialsProvider.observeCredentials()
                .drop(1)
                .distinctUntilChanged()
                .collect { invalidateCaches() }
        }
    }

    // ── Categories ────────────────────────────────────────────────────────────

    override fun observeLiveCategories(): Flow<Resource<List<Category>>> = flow {
        emit(Resource.Loading)
        cachedLiveCategories?.let { emit(Resource.Success(it)); return@flow }
        try {
            val result = dataSource.getLiveCategories()
            cachedLiveCategories = result
            persistCategoriesQuietly(result)
            emit(Resource.Success(result))
        } catch (t: Throwable) {
            emitCategoriesFromRoomCacheOrError(ContentType.LIVE, t)
        }
    }.flowOn(ioDispatcher)

    override fun observeVodCategories(): Flow<Resource<List<Category>>> = flow {
        emit(Resource.Loading)
        cachedVodCategories?.let { emit(Resource.Success(it)); return@flow }
        try {
            val result = dataSource.getVodCategories()
            cachedVodCategories = result
            persistCategoriesQuietly(result)
            emit(Resource.Success(result))
        } catch (t: Throwable) {
            emitCategoriesFromRoomCacheOrError(ContentType.MOVIE, t)
        }
    }.flowOn(ioDispatcher)

    override fun observeSeriesCategories(): Flow<Resource<List<Category>>> = flow {
        emit(Resource.Loading)
        cachedSeriesCategories?.let { emit(Resource.Success(it)); return@flow }
        try {
            val result = dataSource.getSeriesCategories()
            cachedSeriesCategories = result
            persistCategoriesQuietly(result)
            emit(Resource.Success(result))
        } catch (t: Throwable) {
            emitCategoriesFromRoomCacheOrError(ContentType.SERIES, t)
        }
    }.flowOn(ioDispatcher)

    // ── Stream lists ──────────────────────────────────────────────────────────

    override fun getLiveChannels(categoryId: String?): Flow<Resource<List<Channel>>> = flow {
        emit(Resource.Loading)
        if (categoryId == null) {
            cachedAllChannels?.let { emit(Resource.Success(it)); return@flow }
            try {
                val result = dataSource.getLiveChannels(null)
                cachedAllChannels = result
                persistQuietly { catalogCacheDao.upsertChannels(result.toEntity()) }
                emit(Resource.Success(result))
            } catch (t: Throwable) {
                emitFromRoomCacheOrError(t) { catalogCacheDao.observeAllChannels().first().toDomain() }
            }
        } else {
            // Derive from the cached full list when available to avoid a redundant
            // network call; otherwise fetch directly from the data source.
            val fromCache = cachedAllChannels?.filter { it.categoryId == categoryId }
            if (fromCache != null) {
                emit(Resource.Success(fromCache))
            } else {
                try {
                    val result = dataSource.getLiveChannels(categoryId)
                    persistQuietly { catalogCacheDao.upsertChannels(result.toEntity()) }
                    emit(Resource.Success(result))
                } catch (t: Throwable) {
                    emitFromRoomCacheOrError(t) {
                        catalogCacheDao.observeChannelsByCategory(categoryId).first().toDomain()
                    }
                }
            }
        }
    }.flowOn(ioDispatcher)

    override fun getMovies(categoryId: String?): Flow<Resource<List<Movie>>> = flow {
        emit(Resource.Loading)
        if (categoryId == null) {
            cachedAllMovies?.let { emit(Resource.Success(it)); return@flow }
            try {
                val result = dataSource.getMovies(null)
                cachedAllMovies = result
                persistQuietly { catalogCacheDao.upsertMovies(result.toEntity()) }
                emit(Resource.Success(result))
            } catch (t: Throwable) {
                emitFromRoomCacheOrError(t) { catalogCacheDao.observeAllMovies().first().toDomain() }
            }
        } else {
            val fromCache = cachedAllMovies?.filter { it.categoryId == categoryId }
            if (fromCache != null) {
                emit(Resource.Success(fromCache))
            } else {
                try {
                    val result = dataSource.getMovies(categoryId)
                    persistQuietly { catalogCacheDao.upsertMovies(result.toEntity()) }
                    emit(Resource.Success(result))
                } catch (t: Throwable) {
                    emitFromRoomCacheOrError(t) {
                        catalogCacheDao.observeMoviesByCategory(categoryId).first().toDomain()
                    }
                }
            }
        }
    }.flowOn(ioDispatcher)

    override fun getSeriesList(categoryId: String?): Flow<Resource<List<Series>>> = flow {
        emit(Resource.Loading)
        if (categoryId == null) {
            cachedAllSeries?.let { emit(Resource.Success(it)); return@flow }
            try {
                val result = dataSource.getSeriesList(null)
                cachedAllSeries = result
                persistQuietly { catalogCacheDao.upsertSeries(result.toEntity()) }
                emit(Resource.Success(result))
            } catch (t: Throwable) {
                emitFromRoomCacheOrError(t) { catalogCacheDao.observeAllSeries().first().toDomain() }
            }
        } else {
            val fromCache = cachedAllSeries?.filter { it.categoryId == categoryId }
            if (fromCache != null) {
                emit(Resource.Success(fromCache))
            } else {
                try {
                    val result = dataSource.getSeriesList(categoryId)
                    persistQuietly { catalogCacheDao.upsertSeries(result.toEntity()) }
                    emit(Resource.Success(result))
                } catch (t: Throwable) {
                    emitFromRoomCacheOrError(t) {
                        catalogCacheDao.observeSeriesByCategory(categoryId).first().toDomain()
                    }
                }
            }
        }
    }.flowOn(ioDispatcher)

    // ── Detail (one-shot) ─────────────────────────────────────────────────────

    /**
     * Fetches extended metadata for a single movie.
     *
     * The body runs on [ioDispatcher] via [withContext] so data source I/O never
     * executes on the caller's thread, regardless of which dispatcher the caller uses.
     */
    override suspend fun getMovieDetail(movieId: String): Resource<Movie> =
        withContext(ioDispatcher) {
            try {
                Resource.Success(dataSource.getMovieInfo(movieId))
            } catch (t: Throwable) {
                Resource.Error(throwable = t)
            }
        }

    /**
     * Fetches the full season/episode tree for a series.
     *
     * On success, write-through persists the series metadata plus its flattened
     * season/episode tree to the Room catalog cache (see class-level KDoc,
     * "Series detail write-through") before returning — best-effort, via
     * [persistSeriesDetailQuietly].
     *
     * Runs on [ioDispatcher] via [withContext] — see [getMovieDetail] for rationale.
     */
    override suspend fun getSeriesDetail(seriesId: String): Resource<Series> =
        withContext(ioDispatcher) {
            try {
                val result = dataSource.getSeriesInfo(seriesId)
                persistSeriesDetailQuietly(result)
                Resource.Success(result)
            } catch (t: Throwable) {
                Resource.Error(throwable = t)
            }
        }

    /**
     * Fetches EPG programme entries for a live channel.
     *
     * Runs on [ioDispatcher] via [withContext] — see [getMovieDetail] for rationale.
     */
    override suspend fun getEpg(channelId: String, limit: Int?): Resource<List<EpgProgram>> =
        withContext(ioDispatcher) {
            try {
                Resource.Success(dataSource.getShortEpg(channelId, limit))
            } catch (t: Throwable) {
                Resource.Error(throwable = t)
            }
        }

    /**
     * Resolves a series episode and its parent series from the Room catalog cache.
     *
     * Read-only, cache-only lookup — no network call is made and no [Resource] wrapper
     * is used. A cache miss (episode absent, or episode present but its parent series
     * absent) is a legitimate, expected outcome for content whose series detail screen
     * has never been visited, not an error to surface to the UI. Any unexpected
     * [Throwable] (e.g. a Room I/O failure) is likewise treated as a miss so this method
     * never propagates an exception to the caller.
     *
     * Runs on [ioDispatcher] via [withContext] — see [getMovieDetail] for rationale.
     */
    override suspend fun getCachedEpisodeWithSeries(episodeId: String): Pair<Series, Episode>? =
        withContext(ioDispatcher) {
            try {
                val episodeEntity = catalogCacheDao.getEpisodeById(episodeId) ?: return@withContext null
                val seriesEntity = catalogCacheDao.getSeriesById(episodeEntity.seriesId) ?: return@withContext null
                seriesEntity.toDomain() to episodeEntity.toDomain()
            } catch (t: Throwable) {
                null
            }
        }

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Validates the current server credentials.
     *
     * Runs on [ioDispatcher] via [withContext] — see [getMovieDetail] for rationale.
     */
    override suspend fun authenticate(): Resource<Unit> =
        withContext(ioDispatcher) {
            try {
                dataSource.authenticate().fold(
                    onSuccess = { Resource.Success(Unit) },
                    onFailure = { t -> Resource.Error(throwable = t) },
                )
            } catch (t: Throwable) {
                // Guard against data sources that throw instead of returning Result.failure.
                Resource.Error(throwable = t)
            }
        }

    // ── Offline-first Room cache helpers (Task 11b carry-forward) ────────────

    /**
     * Persists a freshly fetched category list to the Room cache, ignoring write failures.
     *
     * Caching is a side-effect of a successful fetch — a Room write error must not
     * downgrade an otherwise successful [Resource.Success] emission to an error.
     */
    private suspend fun persistCategoriesQuietly(categories: List<Category>) {
        persistQuietly { catalogCacheDao.upsertCategories(categories.toEntity()) }
    }

    /**
     * Persists a freshly fetched series detail — the series metadata plus its flattened
     * season/episode tree — to the Room cache, ignoring write failures (Task 11b/25
     * carry-forward; see class-level KDoc "Series detail write-through").
     *
     * [series] is expected to have [Series.seasons] populated (each [Season] with its
     * [Season.episodes] populated), as returned by a successful `getSeriesInfo` call.
     * [Season.toEntity] / [Episode.toEntity] require the parent [Series.id] to be
     * injected explicitly since it is denormalised at the entity layer (not present on
     * the domain models).
     */
    private suspend fun persistSeriesDetailQuietly(series: Series) {
        persistQuietly {
            catalogCacheDao.upsertSeries(listOf(series).toEntity())
            catalogCacheDao.upsertSeasons(series.seasons.map { it.toEntity(series.id) })
            catalogCacheDao.upsertEpisodes(
                series.seasons.flatMap { season -> season.episodes.toEntity(series.id) },
            )
        }
    }

    /** Runs a Room write [block], silently ignoring any failure (best-effort cache). */
    private suspend fun persistQuietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // Best-effort cache write — a failure here does not affect the caller's
            // already-successful network/mock result.
        }
    }

    /**
     * Emits the Room-cached categories for [contentType] as [Resource.Success] when
     * non-empty, otherwise emits [Resource.Error] wrapping the original fetch [error].
     */
    private suspend fun FlowCollector<Resource<List<Category>>>.emitCategoriesFromRoomCacheOrError(
        contentType: ContentType,
        error: Throwable,
    ) {
        emitFromRoomCacheOrError(error) {
            catalogCacheDao.observeCategoriesByType(contentType.name).first().toDomain()
        }
    }

    /**
     * Reads a Room cache fallback via [fetchFromCache] and emits it as [Resource.Success]
     * when non-empty. Falls back to [Resource.Error] wrapping [error] when the cache is
     * empty or the cache read itself fails.
     */
    private suspend fun <T> FlowCollector<Resource<List<T>>>.emitFromRoomCacheOrError(
        error: Throwable,
        fetchFromCache: suspend () -> List<T>,
    ) {
        val cached = try {
            fetchFromCache()
        } catch (cacheReadError: Throwable) {
            emptyList()
        }
        if (cached.isNotEmpty()) {
            emit(Resource.Success(cached))
        } else {
            emit(Resource.Error(throwable = error))
        }
    }

    // ── Cache management ──────────────────────────────────────────────────────

    override fun invalidateCaches() {
        ContentType.entries.forEach { invalidateCache(it) }
    }

    override fun invalidateCache(type: ContentType) {
        when (type) {
            ContentType.LIVE -> {
                cachedLiveCategories = null
                cachedAllChannels = null
            }
            ContentType.MOVIE -> {
                cachedVodCategories = null
                cachedAllMovies = null
            }
            ContentType.SERIES -> {
                cachedSeriesCategories = null
                cachedAllSeries = null
            }
        }
    }
}
