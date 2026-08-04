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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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
    // server endpoint. That tolerance still applies to the stream-list fields, but
    // no longer to the three category fields: concurrent collectors now share a single
    // attempt, and a result issued before an invalidation can no longer be written at
    // all — see "Concurrent first fetches (categories)" below.

    @Volatile private var cachedLiveCategories: List<Category>? = null
    @Volatile private var cachedVodCategories: List<Category>? = null
    @Volatile private var cachedSeriesCategories: List<Category>? = null

    // ── Concurrent first fetches (categories) ─────────────────────────────────
    //
    // The category Flows below are cold: each collection runs the whole body, so
    // concurrent collectors all read the memo above as null and each fire their own
    // request. That is not hypothetical — HomeViewModel.loadCatalogTab collects a tab's
    // categories Flow twice at once (buildRowsFlow observes it for language derivation,
    // LoadCategoryScopedCatalogUseCase awaits its first terminal value), so every tab
    // load doubled the categories request.
    //
    // See [loadCategories] for how one attempt is shared, why the shared terminal covers
    // failures too, and how invalidation generations keep a mid-flight request from
    // repopulating a cache that was just cleared.
    //
    // One state per content type, not one shared: the repository should never serialize
    // requests to independent endpoints just because two screens want different types at
    // the same time.
    //
    // Only the categories are guarded. The stream-list caches keep their documented
    // "last write wins" tolerance: nothing collects those Flows twice concurrently.

    private class CategoryFetchState {
        val mutex = Mutex()

        /** Bumped by [invalidateCache], which is not `suspend` and so cannot take [mutex]. */
        val generation = AtomicInteger(0)

        /** The attempt currently in flight, or `null` when none is. Guarded by [mutex]. */
        var inFlight: CompletableDeferred<Resource<List<Category>>>? = null

        /** [generation] as captured when [inFlight] started. Guarded by [mutex]. */
        var inFlightGeneration: Int = 0
    }

    private val liveCategoriesFetch = CategoryFetchState()
    private val vodCategoriesFetch = CategoryFetchState()
    private val seriesCategoriesFetch = CategoryFetchState()

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
        // Resolved first, emitted after: nothing is emitted while a lock is held.
        emit(
            loadCategories(
                state = liveCategoriesFetch,
                contentType = ContentType.LIVE,
                readMemo = { cachedLiveCategories },
                writeMemo = { cachedLiveCategories = it },
                fetch = { dataSource.getLiveCategories() },
            ),
        )
    }.flowOn(ioDispatcher)

    override fun observeVodCategories(): Flow<Resource<List<Category>>> = flow {
        emit(Resource.Loading)
        cachedVodCategories?.let { emit(Resource.Success(it)); return@flow }
        emit(
            loadCategories(
                state = vodCategoriesFetch,
                contentType = ContentType.MOVIE,
                readMemo = { cachedVodCategories },
                writeMemo = { cachedVodCategories = it },
                fetch = { dataSource.getVodCategories() },
            ),
        )
    }.flowOn(ioDispatcher)

    override fun observeSeriesCategories(): Flow<Resource<List<Category>>> = flow {
        emit(Resource.Loading)
        cachedSeriesCategories?.let { emit(Resource.Success(it)); return@flow }
        emit(
            loadCategories(
                state = seriesCategoriesFetch,
                contentType = ContentType.SERIES,
                readMemo = { cachedSeriesCategories },
                writeMemo = { cachedSeriesCategories = it },
                fetch = { dataSource.getSeriesCategories() },
            ),
        )
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
     * Reads a Room cache fallback via [fetchFromCache] and emits it as [Resource.Success]
     * when non-empty. Falls back to [Resource.Error] wrapping [error] when the cache is
     * empty or the cache read itself fails.
     */
    private suspend fun <T> FlowCollector<Resource<List<T>>>.emitFromRoomCacheOrError(
        error: Throwable,
        fetchFromCache: suspend () -> List<T>,
    ) {
        emit(fromRoomCacheOrError(error, fetchFromCache))
    }

    /**
     * Value-returning form of [emitFromRoomCacheOrError], for callers that must obtain the terminal
     * [Resource] without being inside a [FlowCollector] — see [loadCategories], which resolves the
     * terminal value first and only emits it afterwards.
     */
    private suspend fun <T> fromRoomCacheOrError(
        error: Throwable,
        fetchFromCache: suspend () -> List<T>,
    ): Resource<List<T>> {
        val cached = try {
            fetchFromCache()
        } catch (cacheReadError: Throwable) {
            emptyList()
        }
        return if (cached.isNotEmpty()) {
            Resource.Success(cached)
        } else {
            Resource.Error(throwable = error)
        }
    }

    /**
     * Resolves one content type's categories, collapsing concurrent first loads into a **single
     * attempt** whose terminal [Resource] every participant shares.
     *
     * ## Why single-flight rather than "memoize on success"
     * A plain mutex around the fetch only de-duplicates an attempt that *succeeds and fills the
     * memo*. Two collectors hitting an offline server would still run two full requests back to
     * back — worse than before, since the mutex serializes what used to be concurrent, roughly
     * doubling the time before both branches reach their terminal value. Sharing the in-flight
     * attempt's result instead covers every terminal alike: a network [Resource.Success], a Room
     * cache fallback, or a [Resource.Error].
     *
     * The shared attempt is not remembered: [CategoryFetchState.inFlight] is cleared as soon as it
     * resolves, so a *later* collection retries rather than being served a stale failure.
     *
     * ## Invalidation generations
     * [invalidateCache] can fire at any moment from the application-scoped credentials observer,
     * which is not tied to any collector's job — so it lands mid-flight rather than cancelling the
     * request. Each attempt captures [CategoryFetchState.generation] when it starts and may only
     * publish its result to the memo if that generation is still current, otherwise a request issued
     * with the previous account's credentials could silently become the new session's cache.
     * Collectors arriving after an invalidation refuse to join an attempt from an older generation
     * and start their own, so a retry genuinely refetches.
     *
     * ## Cancellation
     * The attempt runs in its owner's coroutine, not in an external scope, so leaving the screen
     * still cancels the request. Waiters observe the owner's cancellation as a cancelled
     * [CompletableDeferred] and take the attempt over instead of failing — a waiter that is still
     * active must not be cancelled by an unrelated collector going away.
     */
    private suspend fun loadCategories(
        state: CategoryFetchState,
        contentType: ContentType,
        readMemo: () -> List<Category>?,
        writeMemo: (List<Category>) -> Unit,
        fetch: suspend () -> List<Category>,
    ): Resource<List<Category>> {
        while (true) {
            readMemo()?.let { return Resource.Success(it) }

            var owner = false
            var generation = 0
            var attempt: CompletableDeferred<Resource<List<Category>>>? = null
            state.mutex.withLock {
                // Re-read under the lock: a concurrent attempt may have filled the memo while we
                // were queued, in which case there is nothing left to do.
                readMemo()?.let { return Resource.Success(it) }
                val current = state.inFlight
                if (current != null && state.inFlightGeneration == state.generation.get()) {
                    attempt = current
                } else {
                    generation = state.generation.get()
                    attempt = CompletableDeferred<Resource<List<Category>>>().also {
                        state.inFlight = it
                        state.inFlightGeneration = generation
                    }
                    owner = true
                }
            }
            val deferred = requireNotNull(attempt)

            if (!owner) {
                try {
                    return deferred.await()
                } catch (cancellation: CancellationException) {
                    // Our own cancellation must propagate; the owner's must not take us down.
                    if (!currentCoroutineContext().isActive) throw cancellation
                    continue
                }
            }

            val result = try {
                val fresh = fetch()
                state.mutex.withLock {
                    if (state.generation.get() == generation) writeMemo(fresh)
                }
                persistCategoriesQuietly(fresh)
                Resource.Success(fresh)
            } catch (cancellation: CancellationException) {
                state.mutex.withLock { if (state.inFlight === deferred) state.inFlight = null }
                deferred.cancel(cancellation)
                throw cancellation
            } catch (t: Throwable) {
                // Deliberately not memoized, matching the previous behaviour: a Room fallback or an
                // error never becomes the session cache.
                fromRoomCacheOrError(t) {
                    catalogCacheDao.observeCategoriesByType(contentType.name).first().toDomain()
                }
            }
            state.mutex.withLock { if (state.inFlight === deferred) state.inFlight = null }
            deferred.complete(result)
            return result
        }
    }

    // ── Cache management ──────────────────────────────────────────────────────

    override fun invalidateCaches() {
        ContentType.entries.forEach { invalidateCache(it) }
    }

    /**
     * Clearing the memo is not enough on its own: a categories request may be in flight right now
     * (this is called from the application-scoped credentials observer, which cancels nothing), and
     * it would otherwise complete afterwards and repopulate the cache it was meant to invalidate —
     * with the *previous* account's data. Bumping the generation makes that late result
     * unpublishable and stops any collector arriving from here on from joining that attempt. See
     * [loadCategories], "Invalidation generations".
     */
    override fun invalidateCache(type: ContentType) {
        when (type) {
            ContentType.LIVE -> {
                cachedLiveCategories = null
                cachedAllChannels = null
                liveCategoriesFetch.generation.incrementAndGet()
            }
            ContentType.MOVIE -> {
                cachedVodCategories = null
                cachedAllMovies = null
                vodCategoriesFetch.generation.incrementAndGet()
            }
            ContentType.SERIES -> {
                cachedSeriesCategories = null
                cachedAllSeries = null
                seriesCategoriesFetch.generation.incrementAndGet()
            }
        }
    }
}
