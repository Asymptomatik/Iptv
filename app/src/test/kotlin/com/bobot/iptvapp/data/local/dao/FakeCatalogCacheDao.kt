package com.bobot.iptvapp.data.local.dao

import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity
import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.data.local.entity.EpisodeEntity
import com.bobot.iptvapp.data.local.entity.MovieEntity
import com.bobot.iptvapp.data.local.entity.SeasonEntity
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fake implementation of [CatalogCacheDao], mirroring the interface's real
 * (Task 3) shape — every entity carries an `accountKey`, and every store is keyed by the
 * composite `(accountKey, <natural key>)`, exactly matching the composite primary key
 * declared on each production entity.
 *
 * This is the fixture used by [com.bobot.iptvapp.data.repository.CatalogRepositoryAccountPartitionTest]
 * to verify the cross-account Room cache partitioning: two different Xtream accounts never
 * see each other's cached rows, and a returning account (same `accountKey`) finds its own
 * data intact. A real Room database is deliberately not used here — this project runs JVM
 * unit tests without Robolectric, so no test can instantiate an actual Room database; this
 * fake stands in for it.
 *
 * Storage is a set of plain [MutableMap]s keyed by `accountKey` plus each entity's natural
 * key. `observe*` methods return a [Flow] via [flowOf] over a snapshot of the current
 * in-memory state at call time — not a live query — which is sufficient here since every
 * caller in [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl] collects with
 * `.first()` immediately after invoking the method.
 *
 * ## Simulating a failing purge (Task 4 carry-forward)
 * [onGlobalClear] is an optional hook invoked at the start of every global (unpartitioned)
 * `clearAll*`/`clearChannels`/`clearMovies`/`clearSeries` method — the ones
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl.purgeAllCachePartitionsQuietly]
 * calls on logout. Set it to a lambda that throws to make every such call fail, without
 * touching the targeted per-account `clear*ByType`/`clear*BySeriesId` methods used
 * elsewhere. Left `null` (default) it is a no-op, preserving the existing six tests'
 * behaviour.
 */
class FakeCatalogCacheDao : CatalogCacheDao {

    /** See class KDoc "Simulating a failing purge". */
    var onGlobalClear: (() -> Unit)? = null

    private val categories = mutableMapOf<Pair<String, String>, CategoryEntity>()
    private val channels = mutableMapOf<Pair<String, String>, ChannelEntity>()
    private val movies = mutableMapOf<Pair<String, String>, MovieEntity>()
    private val series = mutableMapOf<Pair<String, String>, SeriesEntity>()
    private val seasons = mutableMapOf<Triple<String, String, Int>, SeasonEntity>()
    private val episodes = mutableMapOf<Pair<String, String>, EpisodeEntity>()
    private val syncMarkers = mutableMapOf<Triple<String, String, String>, CatalogSyncEntity>()

    /**
     * Marks a slice as synced [ageMillis] ago, so a test can put the cache on either side of the
     * repository's TTL without a clock abstraction. Writing the age rather than the instant is
     * what keeps the assertions readable: `markSyncedAgo(..., ageMillis = 0)` is a cache that was
     * just filled, a large age is one that has expired.
     */
    fun markSyncedAgo(accountKey: String, contentType: String, scope: String, ageMillis: Long) {
        syncMarkers[Triple(accountKey, contentType, scope)] = CatalogSyncEntity(
            accountKey = accountKey,
            contentType = contentType,
            scope = scope,
            syncedAtMillis = System.currentTimeMillis() - ageMillis,
        )
    }

    /** `null` when the slice was never synced — mirrors [getSyncedAtMillis]'s own contract. */
    fun syncedAtMillisOrNull(accountKey: String, contentType: String, scope: String): Long? =
        syncMarkers[Triple(accountKey, contentType, scope)]?.syncedAtMillis

    // ── Categories ────────────────────────────────────────────────────────────

    override suspend fun upsertCategories(categories: List<CategoryEntity>) {
        categories.forEach { this.categories[it.accountKey to it.id] = it }
    }

    override fun observeCategoriesByType(accountKey: String, contentType: String): Flow<List<CategoryEntity>> =
        flowOf(categoriesByType(accountKey, contentType))

    override suspend fun getCategoriesByType(accountKey: String, contentType: String): List<CategoryEntity> =
        categoriesByType(accountKey, contentType)

    private fun categoriesByType(accountKey: String, contentType: String): List<CategoryEntity> =
        categories.values
            .filter { it.accountKey == accountKey && it.contentType.name == contentType }
            .sortedBy { it.name }

    override suspend fun clearCategoriesByType(accountKey: String, contentType: String) {
        categories.values.removeAll { it.accountKey == accountKey && it.contentType.name == contentType }
    }

    override suspend fun clearAllCategories() {
        onGlobalClear?.invoke()
        categories.clear()
    }

    // ── Channels ──────────────────────────────────────────────────────────────

    override suspend fun upsertChannels(channels: List<ChannelEntity>) {
        channels.forEach { this.channels[it.accountKey to it.id] = it }
    }

    override fun observeAllChannels(accountKey: String): Flow<List<ChannelEntity>> =
        flowOf(allChannels(accountKey))

    override suspend fun getAllChannels(accountKey: String): List<ChannelEntity> = allChannels(accountKey)

    private fun allChannels(accountKey: String): List<ChannelEntity> =
        channels.values.filter { it.accountKey == accountKey }.sortedBy { it.name }

    override fun observeChannelsByCategory(accountKey: String, categoryId: String): Flow<List<ChannelEntity>> =
        flowOf(channelsByCategory(accountKey, categoryId))

    override suspend fun getChannelsByCategory(accountKey: String, categoryId: String): List<ChannelEntity> =
        channelsByCategory(accountKey, categoryId)

    private fun channelsByCategory(accountKey: String, categoryId: String): List<ChannelEntity> =
        channels.values
            .filter { it.accountKey == accountKey && it.categoryId == categoryId }
            .sortedBy { it.name }

    override suspend fun getChannelById(accountKey: String, id: String): ChannelEntity? =
        channels[accountKey to id]

    override suspend fun clearChannels() {
        onGlobalClear?.invoke()
        channels.clear()
    }

    // ── Movies ────────────────────────────────────────────────────────────────

    override suspend fun upsertMovies(movies: List<MovieEntity>) {
        movies.forEach { this.movies[it.accountKey to it.id] = it }
    }

    override fun observeAllMovies(accountKey: String): Flow<List<MovieEntity>> = flowOf(allMovies(accountKey))

    override suspend fun getAllMovies(accountKey: String): List<MovieEntity> = allMovies(accountKey)

    private fun allMovies(accountKey: String): List<MovieEntity> =
        movies.values.filter { it.accountKey == accountKey }.sortedBy { it.title }

    override fun observeMoviesByCategory(accountKey: String, categoryId: String): Flow<List<MovieEntity>> =
        flowOf(moviesByCategory(accountKey, categoryId))

    override suspend fun getMoviesByCategory(accountKey: String, categoryId: String): List<MovieEntity> =
        moviesByCategory(accountKey, categoryId)

    private fun moviesByCategory(accountKey: String, categoryId: String): List<MovieEntity> =
        movies.values
            .filter { it.accountKey == accountKey && it.categoryId == categoryId }
            .sortedBy { it.title }

    override suspend fun getMovieById(accountKey: String, id: String): MovieEntity? = movies[accountKey to id]

    override suspend fun clearMovies() {
        onGlobalClear?.invoke()
        movies.clear()
    }

    // ── Series ────────────────────────────────────────────────────────────────

    override suspend fun upsertSeries(series: List<SeriesEntity>) {
        series.forEach { this.series[it.accountKey to it.id] = it }
    }

    override fun observeAllSeries(accountKey: String): Flow<List<SeriesEntity>> = flowOf(allSeries(accountKey))

    override suspend fun getAllSeries(accountKey: String): List<SeriesEntity> = allSeries(accountKey)

    private fun allSeries(accountKey: String): List<SeriesEntity> =
        series.values.filter { it.accountKey == accountKey }.sortedBy { it.title }

    override fun observeSeriesByCategory(accountKey: String, categoryId: String): Flow<List<SeriesEntity>> =
        flowOf(seriesByCategory(accountKey, categoryId))

    override suspend fun getSeriesByCategory(accountKey: String, categoryId: String): List<SeriesEntity> =
        seriesByCategory(accountKey, categoryId)

    private fun seriesByCategory(accountKey: String, categoryId: String): List<SeriesEntity> =
        series.values
            .filter { it.accountKey == accountKey && it.categoryId == categoryId }
            .sortedBy { it.title }

    override suspend fun getSeriesById(accountKey: String, id: String): SeriesEntity? = series[accountKey to id]

    override suspend fun clearSeries() {
        onGlobalClear?.invoke()
        series.clear()
    }

    // ── Seasons ───────────────────────────────────────────────────────────────

    override suspend fun upsertSeasons(seasons: List<SeasonEntity>) {
        seasons.forEach { this.seasons[Triple(it.accountKey, it.seriesId, it.seasonNumber)] = it }
    }

    override fun observeSeasonsBySeriesId(accountKey: String, seriesId: String): Flow<List<SeasonEntity>> =
        flowOf(
            seasons.values
                .filter { it.accountKey == accountKey && it.seriesId == seriesId }
                .sortedBy { it.seasonNumber },
        )

    override suspend fun clearSeasonsBySeriesId(accountKey: String, seriesId: String) {
        seasons.keys.removeAll { it.first == accountKey && it.second == seriesId }
    }

    override suspend fun clearAllSeasons() {
        onGlobalClear?.invoke()
        seasons.clear()
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override suspend fun upsertEpisodes(episodes: List<EpisodeEntity>) {
        episodes.forEach { this.episodes[it.accountKey to it.id] = it }
    }

    override fun observeEpisodesBySeriesAndSeason(
        accountKey: String,
        seriesId: String,
        seasonNumber: Int,
    ): Flow<List<EpisodeEntity>> =
        flowOf(
            episodes.values
                .filter { it.accountKey == accountKey && it.seriesId == seriesId && it.seasonNumber == seasonNumber }
                .sortedBy { it.episodeNumber },
        )

    override fun observeEpisodesBySeriesId(accountKey: String, seriesId: String): Flow<List<EpisodeEntity>> =
        flowOf(
            episodes.values
                .filter { it.accountKey == accountKey && it.seriesId == seriesId }
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })),
        )

    override suspend fun getEpisodeById(accountKey: String, id: String): EpisodeEntity? = episodes[accountKey to id]

    override suspend fun clearEpisodesBySeriesId(accountKey: String, seriesId: String) {
        episodes.values.removeAll { it.accountKey == accountKey && it.seriesId == seriesId }
    }

    override suspend fun clearAllEpisodes() {
        onGlobalClear?.invoke()
        episodes.clear()
    }

    // ── Sync markers ──────────────────────────────────────────────────────────

    override suspend fun upsertSyncMarker(marker: CatalogSyncEntity) {
        syncMarkers[Triple(marker.accountKey, marker.contentType, marker.scope)] = marker
    }

    override suspend fun getSyncedAtMillis(accountKey: String, contentType: String, scope: String): Long? =
        syncMarkers[Triple(accountKey, contentType, scope)]?.syncedAtMillis

    override suspend fun clearSyncMarkersByType(accountKey: String, contentType: String) {
        syncMarkers.values.removeAll { it.accountKey == accountKey && it.contentType == contentType }
    }

    override suspend fun clearAllSyncMarkers() {
        onGlobalClear?.invoke()
        syncMarkers.clear()
    }
}
