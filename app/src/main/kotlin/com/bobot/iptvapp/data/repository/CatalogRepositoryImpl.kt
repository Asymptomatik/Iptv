package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity.Companion.SCOPE_ALL
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity.Companion.SCOPE_CATEGORIES
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
import com.bobot.iptvapp.domain.util.AccountKey
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.domain.util.accountKeyOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
 * ## Room on the happy path (schema v4)
 * The fallback above is the *failure* path. Room is also consulted **before** the data source
 * on every read, through [freshFromRoom], and the fetch is skipped entirely when the slice was
 * synced within [CATALOG_CACHE_TTL_MILLIS].
 *
 * This is the difference between a session cache and a real one. The `cachedAll*` fields above
 * only ever memoize the *unfiltered* lists, which the catalog screens never request: Home and
 * Search load one category at a time (the OOM fix documented on both ViewModels), so their
 * loads were memoized nowhere in this class. What made a second visit feel instant was
 * `HomeViewModel.requestedContentTypes`, a guard that dies with the ViewModel — so every process
 * restart replayed the full, minutes-long catalog fetch against a Room cache that already held
 * every row and was never asked. Freshness is tracked per slice in `catalog_sync`; see
 * [CatalogSyncEntity] for the grain and [freshFromRoom] for why rows alone could not decide it.
 *
 * ## Series detail write-through (Task 11b/25 carry-forward)
 * [getSeriesDetail] additionally persists the parent [Series] plus its full flattened
 * season/episode tree ([catalogCacheDao].upsertSeasons / upsertEpisodes) on every
 * successful fetch — best-effort, via the same [persistQuietly] helper. This closes the
 * gap left since Task 10: the `seasons`/`episodes` tables existed but were never written
 * to. [getCachedEpisodeWithSeries] reads this data back to resolve a "Continue Watching"
 * entry for series content without a network round-trip.
 *
 * ## Account partitioning (Task 3 carry-forward)
 * Every Room read and write in this class is scoped to the currently configured Xtream
 * account via [currentAccountKey], resolved fresh on each operation from
 * [credentialsProvider]`.getCredentials()`. This is a deliberate choice over caching the
 * account key in a field: resolving per-operation avoids any initialization-order race
 * with the `init {}` block's credentials observer, and sidesteps the `drop(1)` on
 * [CredentialsProvider.observeCredentials] entirely (that flow is only used for
 * *invalidating* the in-memory session cache, never for resolving the account key). When
 * [currentAccountKey] returns `null` (no credentials yet, e.g. before onboarding or right
 * after logout), Room is not touched at all: reads behave as an empty cache (preserving
 * [Resource.Error] on the fallback path) and writes are silently skipped.
 *
 * ## Full cache purge on logout (Task 4 carry-forward)
 * The `init {}` credentials observer distinguishes an account switch (non-null →
 * non-null) from a logout (→ `null`). Both invalidate the in-memory session cache via
 * [invalidateCaches], but only a logout additionally purges *every* Room partition
 * (see [purgeAllCachePartitionsQuietly]) — an account switch leaves other partitions
 * untouched, which per-account isolation already makes safe and which
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModel.onSaveServer]'s
 * credentials-rollback relies on.
 *
 * ## Dispatcher
 * All data source calls run on [ioDispatcher] (default: [kotlinx.coroutines.Dispatchers.IO])
 * via [flowOn] for Flows and direct suspension for one-shot methods.
 * Tests inject a [kotlinx.coroutines.test.TestDispatcher] via constructor injection.
 *
 * @param dataSource           The catalog data source (mock or remote).
 * @param catalogCacheDao      Room DAO backing the offline-first catalog cache.
 * @param epgDao               Room DAO for the EPG cache. Used *only* to purge `epg_programs`
 *                             on logout (Task 4 carry-forward) — [getEpg] never reads/writes it.
 * @param ioDispatcher         The IO dispatcher; injected for testability.
 * @param credentialsProvider  Observed for credential changes to trigger cache invalidation.
 * @param applicationScope     Application-scoped [CoroutineScope] for the cache-observer coroutine.
 */
class CatalogRepositoryImpl @Inject constructor(
    private val dataSource: CatalogDataSource,
    private val catalogCacheDao: CatalogCacheDao,
    private val epgDao: EpgDao,
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
                .collect { credentials ->
                    // Both an account switch and a logout invalidate the in-memory session
                    // cache. Only a logout (credentials == null) additionally purges Room —
                    // see [purgeAllCachePartitionsQuietly] for why a switch must not (Task 4).
                    invalidateCaches()
                    if (credentials == null) {
                        purgeAllCachePartitionsQuietly()
                    }
                }
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
            // Resolved past the in-memory short-circuit, not before it: currentAccountKey()
            // reads DataStore and hashes, and once the catalog is loaded most collections are
            // served entirely from memory and would never use the key.
            val accountKey = currentAccountKey()
            freshFromRoom(accountKey, ContentType.LIVE, SCOPE_ALL) { key ->
                catalogCacheDao.observeAllChannels(key.value).first().toDomain()
            }?.let {
                // Promoted to the session cache exactly as a network result would be, so the
                // per-category branch below is served from memory for the rest of the session.
                cachedAllChannels = it
                emit(Resource.Success(it))
                return@flow
            }
            try {
                val result = dataSource.getLiveChannels(null)
                cachedAllChannels = result
                persistQuietly(accountKey) { key ->
                    catalogCacheDao.upsertChannels(result.toEntity(key))
                    markSynced(key, ContentType.LIVE, SCOPE_ALL)
                }
                emit(Resource.Success(result))
            } catch (t: Throwable) {
                rethrowIfCancellation(t)
                emitFromRoomCacheOrError(accountKey, t) { key ->
                    catalogCacheDao.observeAllChannels(key.value).first().toDomain()
                }
            }
        } else {
            // Derive from the cached full list when available to avoid a redundant
            // network call; otherwise fetch directly from the data source.
            val fromCache = cachedAllChannels?.filter { it.categoryId == categoryId }
            if (fromCache != null) {
                emit(Resource.Success(fromCache))
            } else {
                val accountKey = currentAccountKey()
                freshFromRoom(accountKey, ContentType.LIVE, categoryId) { key ->
                    catalogCacheDao.observeChannelsByCategory(key.value, categoryId).first().toDomain()
                }?.let { emit(Resource.Success(it)); return@flow }
                try {
                    val result = dataSource.getLiveChannels(categoryId)
                    persistQuietly(accountKey) { key ->
                        catalogCacheDao.upsertChannels(result.toEntity(key))
                        markSynced(key, ContentType.LIVE, categoryId)
                    }
                    emit(Resource.Success(result))
                } catch (t: Throwable) {
                    rethrowIfCancellation(t)
                    emitFromRoomCacheOrError(accountKey, t) { key ->
                        catalogCacheDao.observeChannelsByCategory(key.value, categoryId).first().toDomain()
                    }
                }
            }
        }
    }.flowOn(ioDispatcher)

    override fun getMovies(categoryId: String?): Flow<Resource<List<Movie>>> = flow {
        emit(Resource.Loading)
        if (categoryId == null) {
            cachedAllMovies?.let { emit(Resource.Success(it)); return@flow }
            val accountKey = currentAccountKey()
            freshFromRoom(accountKey, ContentType.MOVIE, SCOPE_ALL) { key ->
                catalogCacheDao.observeAllMovies(key.value).first().toDomain()
            }?.let {
                cachedAllMovies = it
                emit(Resource.Success(it))
                return@flow
            }
            try {
                val result = dataSource.getMovies(null)
                cachedAllMovies = result
                persistQuietly(accountKey) { key ->
                    catalogCacheDao.upsertMovies(result.toEntity(key))
                    markSynced(key, ContentType.MOVIE, SCOPE_ALL)
                }
                emit(Resource.Success(result))
            } catch (t: Throwable) {
                rethrowIfCancellation(t)
                emitFromRoomCacheOrError(accountKey, t) { key ->
                    catalogCacheDao.observeAllMovies(key.value).first().toDomain()
                }
            }
        } else {
            val fromCache = cachedAllMovies?.filter { it.categoryId == categoryId }
            if (fromCache != null) {
                emit(Resource.Success(fromCache))
            } else {
                val accountKey = currentAccountKey()
                freshFromRoom(accountKey, ContentType.MOVIE, categoryId) { key ->
                    catalogCacheDao.observeMoviesByCategory(key.value, categoryId).first().toDomain()
                }?.let { emit(Resource.Success(it)); return@flow }
                try {
                    val result = dataSource.getMovies(categoryId)
                    persistQuietly(accountKey) { key ->
                        catalogCacheDao.upsertMovies(result.toEntity(key))
                        markSynced(key, ContentType.MOVIE, categoryId)
                    }
                    emit(Resource.Success(result))
                } catch (t: Throwable) {
                    rethrowIfCancellation(t)
                    emitFromRoomCacheOrError(accountKey, t) { key ->
                        catalogCacheDao.observeMoviesByCategory(key.value, categoryId).first().toDomain()
                    }
                }
            }
        }
    }.flowOn(ioDispatcher)

    override fun getSeriesList(categoryId: String?): Flow<Resource<List<Series>>> = flow {
        emit(Resource.Loading)
        if (categoryId == null) {
            cachedAllSeries?.let { emit(Resource.Success(it)); return@flow }
            val accountKey = currentAccountKey()
            freshFromRoom(accountKey, ContentType.SERIES, SCOPE_ALL) { key ->
                catalogCacheDao.observeAllSeries(key.value).first().toDomain()
            }?.let {
                cachedAllSeries = it
                emit(Resource.Success(it))
                return@flow
            }
            try {
                val result = dataSource.getSeriesList(null)
                cachedAllSeries = result
                persistQuietly(accountKey) { key ->
                    catalogCacheDao.upsertSeries(result.toEntity(key))
                    markSynced(key, ContentType.SERIES, SCOPE_ALL)
                }
                emit(Resource.Success(result))
            } catch (t: Throwable) {
                rethrowIfCancellation(t)
                emitFromRoomCacheOrError(accountKey, t) { key ->
                    catalogCacheDao.observeAllSeries(key.value).first().toDomain()
                }
            }
        } else {
            val fromCache = cachedAllSeries?.filter { it.categoryId == categoryId }
            if (fromCache != null) {
                emit(Resource.Success(fromCache))
            } else {
                val accountKey = currentAccountKey()
                freshFromRoom(accountKey, ContentType.SERIES, categoryId) { key ->
                    catalogCacheDao.observeSeriesByCategory(key.value, categoryId).first().toDomain()
                }?.let { emit(Resource.Success(it)); return@flow }
                try {
                    val result = dataSource.getSeriesList(categoryId)
                    persistQuietly(accountKey) { key ->
                        catalogCacheDao.upsertSeries(result.toEntity(key))
                        markSynced(key, ContentType.SERIES, categoryId)
                    }
                    emit(Resource.Success(result))
                } catch (t: Throwable) {
                    rethrowIfCancellation(t)
                    emitFromRoomCacheOrError(accountKey, t) { key ->
                        catalogCacheDao.observeSeriesByCategory(key.value, categoryId).first().toDomain()
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
                rethrowIfCancellation(t)
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
                currentAccountKey()?.let { accountKey -> persistSeriesDetailQuietly(accountKey, result) }
                Resource.Success(result)
            } catch (t: Throwable) {
                rethrowIfCancellation(t)
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
                rethrowIfCancellation(t)
                Resource.Error(throwable = t)
            }
        }

    /**
     * Resolves a single live channel from the Room catalog cache.
     *
     * Cache-only, like [getCachedEpisodeWithSeries]: a miss (or a Room I/O failure) is a `null`
     * the caller is expected to handle, never an exception. [BouquetSeparator] is applied here
     * too — a separator row cached before QA finding Y2 was fixed must not come back as a
     * playable channel through this door either.
     *
     * Runs on [ioDispatcher] via [withContext] — see [getMovieDetail] for rationale.
     */
    override suspend fun getCachedChannel(channelId: String): Channel? =
        withContext(ioDispatcher) {
            try {
                val accountKey = currentAccountKey() ?: return@withContext null
                catalogCacheDao.getChannelById(accountKey.value, channelId)
                    ?.let { listOf(it).toDomain() }
                    ?.firstOrNull()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (cacheReadError: Throwable) {
                null
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
                val accountKey = currentAccountKey() ?: return@withContext null
                val episodeEntity =
                    catalogCacheDao.getEpisodeById(accountKey.value, episodeId) ?: return@withContext null
                val seriesEntity =
                    catalogCacheDao.getSeriesById(accountKey.value, episodeEntity.seriesId) ?: return@withContext null
                seriesEntity.toDomain() to episodeEntity.toDomain()
            } catch (t: Throwable) {
                rethrowIfCancellation(t)
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
                rethrowIfCancellation(t)
                // Guard against data sources that throw instead of returning Result.failure.
                Resource.Error(throwable = t)
            }
        }

    // ── Offline-first Room cache helpers (Task 11b carry-forward) ────────────

    /**
     * Resolves the cache partition key for the currently configured Xtream account, or
     * `null` when no credentials are configured (before onboarding, or after logout).
     *
     * Resolved fresh on every call — see class-level KDoc "Account partitioning" for why
     * this is deliberately not cached in a field.
     */
    private suspend fun currentAccountKey(): AccountKey? =
        credentialsProvider.getCredentials()?.let { accountKeyOf(it) }

    /**
     * Reads one catalog slice back from Room, but only when it is recent enough to serve as if
     * it had just been fetched. Returns `null` — meaning "go to the network" — when there is no
     * account, no marker, an expired marker, an empty result, or a failed read.
     *
     * ## Why the marker, and not just "are there rows?"
     * Rows alone cannot answer the question. They were already being written on every successful
     * fetch before this existed, and reading them unconditionally would pin the catalog to
     * whatever the first ever sync returned: new films would never appear, removed channels
     * would never go away, and the only escape would be the manual reload in Réglages. The
     * marker is what turns a permanent snapshot into a cache — see [CatalogSyncEntity] for why
     * it is a side table rather than a column on every row.
     *
     * ## Why the read is per-slice
     * [fetchFromCache] reads one category at a time, the same grain the network path uses, which
     * is what keeps the "Category-scoped, on-demand loading (OOM fix)" bound documented on
     * `HomeViewModel` intact: a warm start replaces N HTTP round-trips with N small local
     * queries, not with one query that materialises the whole bouquet at once.
     *
     * A failed read falls through to the network rather than surfacing: this sits on the happy
     * path, where a broken cache must cost latency, never an error the user can see.
     */
    private suspend fun <T> freshFromRoom(
        accountKey: AccountKey?,
        contentType: ContentType,
        scope: String,
        fetchFromCache: suspend (AccountKey) -> List<T>,
    ): List<T>? {
        if (accountKey == null) return null
        return try {
            val syncedAt = catalogCacheDao.getSyncedAtMillis(accountKey.value, contentType.name, scope)
                ?: return null
            // A clock moved backwards (timezone/NTP correction, or a restored backup) would make
            // `now - syncedAt` negative and the slice look eternally fresh; the lower bound sends
            // that case back to the network instead.
            val age = System.currentTimeMillis() - syncedAt
            if (age !in 0 until CATALOG_CACHE_TTL_MILLIS) return null
            fetchFromCache(accountKey).takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cacheReadError: Throwable) {
            null
        }
    }

    /**
     * Stamps a slice as synced *now*. Called from inside a [persistQuietly] block, always after
     * the rows it describes — a marker that lands without them would advertise a cache that is
     * not there, and would be believed for a full TTL.
     */
    private suspend fun markSynced(accountKey: AccountKey, contentType: ContentType, scope: String) {
        catalogCacheDao.upsertSyncMarker(
            CatalogSyncEntity(
                accountKey = accountKey.value,
                contentType = contentType.name,
                scope = scope,
                syncedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Persists a freshly fetched category list to the Room cache under [accountKey],
     * ignoring write failures.
     *
     * Caching is a side-effect of a successful fetch — a Room write error must not
     * downgrade an otherwise successful [Resource.Success] emission to an error.
     */
    private suspend fun persistCategoriesQuietly(
        accountKey: AccountKey,
        contentType: ContentType,
        categories: List<Category>,
    ) {
        persistQuietly {
            catalogCacheDao.upsertCategories(categories.toEntity(accountKey))
            markSynced(accountKey, contentType, SCOPE_CATEGORIES)
        }
    }

    /**
     * Persists a freshly fetched series detail — the series metadata plus its flattened
     * season/episode tree — to the Room cache under [accountKey], ignoring write failures
     * (Task 11b/25 carry-forward; see class-level KDoc "Series detail write-through").
     *
     * [series] is expected to have [Series.seasons] populated (each [Season] with its
     * [Season.episodes] populated), as returned by a successful `getSeriesInfo` call.
     * [Season.toEntity] / [Episode.toEntity] require the parent [Series.id] to be
     * injected explicitly since it is denormalised at the entity layer (not present on
     * the domain models).
     */
    private suspend fun persistSeriesDetailQuietly(accountKey: AccountKey, series: Series) {
        persistQuietly {
            catalogCacheDao.upsertSeries(listOf(series).toEntity(accountKey))
            catalogCacheDao.upsertSeasons(series.seasons.map { it.toEntity(series.id, accountKey) })
            catalogCacheDao.upsertEpisodes(
                series.seasons.flatMap { season -> season.episodes.toEntity(series.id, accountKey) },
            )
        }
    }

    /**
     * Rethrows [t] when it is this coroutine's own cancellation.
     *
     * Every public method here funnels failures into a [Resource.Error] (or a Room fallback) through
     * a broad `catch (Throwable)`. Left alone, those blocks also swallow the cancellation raised when
     * the caller's job goes away — a screen that navigates back would get a plausible-looking error
     * state instead of simply stopping. Called first in each such block so cancellation stays
     * cancellation.
     */
    private fun rethrowIfCancellation(t: Throwable) {
        if (t is CancellationException) throw t
    }

    /**
     * Runs a Room write [block], silently ignoring any failure (best-effort cache).
     *
     * Cancellation is deliberately *not* absorbed: swallowing it would leave the caller running with
     * an already-cancelled context, so the next suspension point would throw somewhere far less
     * expected — see [loadCategories], whose owner must reach its cleanup in a known state. It is
     * relayed to the immediate caller only; the callers that need it to travel further guard their
     * own `catch (Throwable)` with [rethrowIfCancellation].
     */
    private suspend fun persistQuietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // Best-effort cache write — a failure here does not affect the caller's
            // already-successful network/mock result.
        }
    }

    /**
     * Purges every Room cache partition, across *every* account, on logout (Task 4
     * carry-forward).
     *
     * Called only from the `init {}` credentials observer when [CredentialsProvider
     * .observeCredentials] emits `null` — never on an account switch (non-null →
     * non-null). Deliberately not merged into [invalidateCaches]: an account switch is
     * isolated by `accountKey` alone (see class-level KDoc "Account partitioning") and
     * must leave every partition intact, including the account being switched away
     * from — this is what lets [com.bobot.iptvapp.ui.screen.settings.SettingsViewModel
     * .onSaveServer] roll back to the previous credentials after a failed
     * authentication and still find that account's offline cache usable. A logout has
     * no "previous account" to protect, so every partition — not just the one active at
     * logout time — is cleared using the DAOs' unparameterised `clearAll*` methods.
     *
     * Uses the global (unpartitioned) clears documented on [CatalogCacheDao] and
     * [EpgDao.clearAll] — including `catalog_sync`, whose markers must go with the rows they
     * describe, or the next account would find a fresh-looking cache with nothing behind it.
     * `epg_programs` is owned by [EpgDao] rather
     * than [CatalogCacheDao] since [getEpg] is a pure network pass-through that never
     * touches Room; [epgDao] is injected into this class solely for this purge.
     *
     * Each table is purged through its own [persistQuietly] call rather than one call
     * wrapping all seven: a failure purging one table (Room I/O error) must not abort
     * the remaining purges, and must not crash this application-scoped observer, whose
     * next collection (a future logout or account switch) still needs to run normally.
     * [persistQuietly] relays [CancellationException] and swallows everything else,
     * exactly as it does for cache writes elsewhere in this class.
     */
    private suspend fun purgeAllCachePartitionsQuietly() {
        persistQuietly { catalogCacheDao.clearAllCategories() }
        persistQuietly { catalogCacheDao.clearChannels() }
        persistQuietly { catalogCacheDao.clearMovies() }
        persistQuietly { catalogCacheDao.clearSeries() }
        persistQuietly { catalogCacheDao.clearAllSeasons() }
        persistQuietly { catalogCacheDao.clearAllEpisodes() }
        persistQuietly { catalogCacheDao.clearAllSyncMarkers() }
        persistQuietly { epgDao.clearAll() }
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
     * Account-aware variant of [emitFromRoomCacheOrError]. When [accountKey] is `null`
     * (no credentials configured — see [currentAccountKey]), Room is not read at all and
     * [error] is emitted directly, matching the "empty cache" outcome of the accounted
     * path without ever touching the DAO.
     */
    private suspend fun <T> FlowCollector<Resource<List<T>>>.emitFromRoomCacheOrError(
        accountKey: AccountKey?,
        error: Throwable,
        fetchFromCache: suspend (AccountKey) -> List<T>,
    ) {
        if (accountKey == null) {
            emit(Resource.Error(throwable = error))
        } else {
            emitFromRoomCacheOrError(error) { fetchFromCache(accountKey) }
        }
    }

    /**
     * Account-aware variant of [persistQuietly] for the offline-first Room cache writes.
     * When [accountKey] is `null` (no credentials configured — see [currentAccountKey]),
     * the write is skipped entirely rather than persisted under a missing partition.
     */
    private suspend fun persistQuietly(accountKey: AccountKey?, block: suspend (AccountKey) -> Unit) {
        if (accountKey != null) {
            persistQuietly { block(accountKey) }
        }
    }

    /**
     * Value-returning form of [emitFromRoomCacheOrError], for callers that must obtain the terminal
     * [Resource] without being inside a [FlowCollector] — see [loadCategories], which resolves the
     * terminal value first and only emits it afterwards.
     */
    /**
     * Account-aware variant of [fromRoomCacheOrError], mirroring the [emitFromRoomCacheOrError]
     * overload above so the "no account ⇒ never touch Room" rule stays expressed in one place
     * rather than being re-opened at each call site.
     */
    private suspend fun <T> fromRoomCacheOrError(
        accountKey: AccountKey?,
        error: Throwable,
        fetchFromCache: suspend (AccountKey) -> List<T>,
    ): Resource<List<T>> =
        if (accountKey == null) {
            Resource.Error(throwable = error)
        } else {
            fromRoomCacheOrError(error) { fetchFromCache(accountKey) }
        }

    private suspend fun <T> fromRoomCacheOrError(
        error: Throwable,
        fetchFromCache: suspend () -> List<T>,
    ): Resource<List<T>> {
        val cached = try {
            fetchFromCache()
        } catch (cancellation: CancellationException) {
            // An empty cache is a legitimate answer; a cancelled read is not one, and must not be
            // laundered into a Resource.Error while the coroutine is already gone.
            throw cancellation
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
     * The shared attempt is not remembered: the owner retires [CategoryFetchState.inFlight] as soon
     * as it reaches its terminal value, *just before* publishing that value to the waiters, so a
     * *later* collection retries rather than being served a stale failure. That order is deliberate
     * — clearing after publishing would let a collector arriving in between rejoin an attempt that
     * is already over, and on a cancelled attempt rejoin it again and again.
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
     * The same gate guards [persistCategoriesQuietly]. Unlike the in-memory session cache
     * (which has no credential awareness), the Room cache is now **partitioned by account** via
     * `accountKey` in the composite primary key. Each persistent write targets the current account's
     * partition, resolved per-operation via [currentAccountKey]. This partition isolation makes the
     * in-flight window safe: even if an invalidation lands between the check and the write, the
     * persisted categories remain scoped to their account. The offline fallback can return data
     * only for the account currently in [CredentialsProvider] — never from a switched-away account.
     * Notably, this safety does **not** depend on an invalidation event ever firing: the `drop(1)`
     * on [CredentialsProvider.observeCredentials] (see class-level KDoc "Cache invalidation on
     * credential change") means the account active at process start never triggers one, yet that
     * is harmless here — a write from a previous account simply lands in that account's own
     * partition, which the current account's reads never touch.
     *
     * ## Cancellation
     * The attempt runs in its owner's coroutine, not in an external scope, so leaving the screen
     * still cancels the request. Waiters observe the owner's cancellation as a cancelled
     * [CompletableDeferred] and take the attempt over instead of failing — a waiter that is still
     * active must not be cancelled by an unrelated collector going away.
     *
     * That hand-over only works because the owner resolves the shared [CompletableDeferred] on
     * *every* exit path, from a [NonCancellable] `finally`. An owner that returned without resolving
     * it would leave its waiters suspended for the lifetime of the repository, which on screen reads
     * as a spinner that never stops.
     */
    private suspend fun loadCategories(
        state: CategoryFetchState,
        contentType: ContentType,
        readMemo: () -> List<Category>?,
        writeMemo: (List<Category>) -> Unit,
        fetch: suspend () -> List<Category>,
    ): Resource<List<Category>> {
        // Resolved once per call (i.e. once per flow collection triggering this attempt),
        // not cached in a field — see class-level KDoc "Account partitioning". A `null`
        // here (no credentials) simply means the owner below skips the Room write and the
        // Room fallback degrades to Resource.Error, exactly like an empty cache would.
        val accountKey = currentAccountKey()
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

            // Once we own [deferred], every exit path must resolve it and retire it from
            // [CategoryFetchState.inFlight], or the waiters stay suspended for ever. The owner's own
            // job may already be cancelled by the time we get here, so the cleanup runs in a
            // [NonCancellable] `finally` — a plain `mutex.withLock` on a cancelled job would throw
            // before [deferred] is ever resolved.
            var result: Resource<List<Category>>? = null
            try {
                // A recent enough Room copy stands in for the request entirely. Cheap on its own —
                // a category list is small — but it is what lets the *whole* tab load stay local:
                // LoadCategoryScopedCatalogUseCase waits on this terminal value before it can fetch
                // a single category, so one network round-trip here would put the network back in
                // front of an otherwise fully cached load.
                val cached = freshFromRoom(accountKey, contentType, SCOPE_CATEGORIES) { key ->
                    catalogCacheDao.observeCategoriesByType(key.value, contentType.name).first().toDomain()
                }
                val fresh = cached ?: fetch()
                val publishable = state.mutex.withLock {
                    (state.generation.get() == generation).also { if (it) writeMemo(fresh) }
                }
                // Same gate for Room: a result the memo refused is the previous account's and has no
                // business landing in the offline cache either. See "Invalidation generations".
                // Skipped outright for a cached result — rewriting Room with what it just returned
                // would only push the marker forward and keep the slice alive for ever.
                if (cached == null && publishable && accountKey != null) {
                    persistCategoriesQuietly(accountKey, contentType, fresh)
                }
                result = Resource.Success(fresh)
            } catch (cancellation: CancellationException) {
                // Leaves [result] null: the `finally` below cancels [deferred], which hands the
                // attempt over to whichever waiter is still active.
                throw cancellation
            } catch (t: Throwable) {
                // Deliberately not memoized, matching the previous behaviour: a Room fallback or an
                // error never becomes the session cache.
                result = fromRoomCacheOrError(accountKey, t) { key ->
                    catalogCacheDao.observeCategoriesByType(key.value, contentType.name).first().toDomain()
                }
            } finally {
                withContext(NonCancellable) {
                    state.mutex.withLock { if (state.inFlight === deferred) state.inFlight = null }
                }
                // Retired first, resolved second: a collector arriving in between starts a fresh
                // attempt rather than joining one that is already over.
                val terminal = result
                if (terminal != null) deferred.complete(terminal) else deferred.cancel()
            }
            return requireNotNull(result)
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
     *
     * The generation is bumped **before** the memo is cleared, and that order is load-bearing: with
     * the reverse order an in-flight attempt could slip between the two statements, still read its
     * own generation as current, and write the stale result back into a memo that has just been
     * emptied. Bumping first leaves only two outcomes — either the attempt wrote before the bump and
     * the clear that follows removes it, or it checks after the bump and refuses to write at all.
     */
    override fun invalidateCache(type: ContentType) {
        when (type) {
            ContentType.LIVE -> {
                liveCategoriesFetch.generation.incrementAndGet()
                cachedLiveCategories = null
                cachedAllChannels = null
            }
            ContentType.MOVIE -> {
                vodCategoriesFetch.generation.incrementAndGet()
                cachedVodCategories = null
                cachedAllMovies = null
            }
            ContentType.SERIES -> {
                seriesCategoriesFetch.generation.incrementAndGet()
                cachedSeriesCategories = null
                cachedAllSeries = null
            }
        }
    }

    /**
     * Drops the current account's freshness markers for [type], which is what makes the next
     * [freshFromRoom] miss and go back to the server. The cached rows survive on purpose — see
     * [CatalogRepository.invalidatePersistentCache] for why, and for why this is not folded into
     * [invalidateCache].
     */
    override suspend fun invalidatePersistentCache(type: ContentType) {
        val accountKey = currentAccountKey() ?: return
        persistQuietly { catalogCacheDao.clearSyncMarkersByType(accountKey.value, type.name) }
    }

    private companion object {
        /**
         * How long a synced catalog slice is served from Room before the server is consulted
         * again.
         *
         * Twenty-four hours is a deliberate trade against a full catalog load that takes about a
         * minute on this provider — the official app behaves the same way — and against a bouquet
         * that changes on the order of days, not minutes. It is the interval between *automatic*
         * refetches only: the per-type "recharger" actions in Réglages force one at any time
         * through [invalidatePersistentCache].
         */
        const val CATALOG_CACHE_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
