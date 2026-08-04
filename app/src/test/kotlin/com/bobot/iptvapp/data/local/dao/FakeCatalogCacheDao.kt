package com.bobot.iptvapp.data.local.dao

import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.data.local.entity.EpisodeEntity
import com.bobot.iptvapp.data.local.entity.MovieEntity
import com.bobot.iptvapp.data.local.entity.SeasonEntity
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory fake implementation of [CatalogCacheDao], deliberately mirroring the interface's
 * **current** shape — a single unpartitioned store per entity type, with no notion of account.
 *
 * This is the fixture used by [com.bobot.iptvapp.data.repository.CatalogRepositoryAccountPartitionTest]
 * to reproduce (and, in a later task, verify the fix for) the cross-account cache bleed bug: two
 * different Xtream accounts sharing the same Room tables means data written under one account is
 * visible to another. A real Room database is deliberately not used here — this project runs JVM
 * unit tests without Robolectric, so no test can instantiate an actual Room database; this fake
 * stands in for it.
 *
 * Storage is a set of plain [MutableMap]s keyed by each entity's natural key, honoring the same
 * filters ([observeCategoriesByType]'s `contentType`, `*ByCategory`'s `categoryId`, `*BySeriesId`'s
 * `seriesId`, one-shot `getXById`'s `id`) as the production Room queries. `observe*` methods return
 * a [Flow] via [flowOf] over a snapshot of the current in-memory state at call time — not a live
 * query — which is sufficient here since every caller in [CatalogRepositoryImpl] collects with
 * `.first()` immediately after invoking the method.
 */
class FakeCatalogCacheDao : CatalogCacheDao {

    private val categories = mutableMapOf<String, CategoryEntity>()
    private val channels = mutableMapOf<String, ChannelEntity>()
    private val movies = mutableMapOf<String, MovieEntity>()
    private val series = mutableMapOf<String, SeriesEntity>()
    private val seasons = mutableMapOf<Pair<String, Int>, SeasonEntity>()
    private val episodes = mutableMapOf<String, EpisodeEntity>()

    // ── Categories ────────────────────────────────────────────────────────────

    override suspend fun upsertCategories(categories: List<CategoryEntity>) {
        categories.forEach { this.categories[it.id] = it }
    }

    override fun observeCategoriesByType(contentType: String): Flow<List<CategoryEntity>> =
        flowOf(categories.values.filter { it.contentType.name == contentType }.sortedBy { it.name })

    override suspend fun clearCategoriesByType(contentType: String) {
        categories.values.removeAll { it.contentType.name == contentType }
    }

    override suspend fun clearAllCategories() {
        categories.clear()
    }

    // ── Channels ──────────────────────────────────────────────────────────────

    override suspend fun upsertChannels(channels: List<ChannelEntity>) {
        channels.forEach { this.channels[it.id] = it }
    }

    override fun observeAllChannels(): Flow<List<ChannelEntity>> =
        flowOf(channels.values.sortedBy { it.name })

    override fun observeChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>> =
        flowOf(channels.values.filter { it.categoryId == categoryId }.sortedBy { it.name })

    override suspend fun clearChannels() {
        channels.clear()
    }

    // ── Movies ────────────────────────────────────────────────────────────────

    override suspend fun upsertMovies(movies: List<MovieEntity>) {
        movies.forEach { this.movies[it.id] = it }
    }

    override fun observeAllMovies(): Flow<List<MovieEntity>> =
        flowOf(movies.values.sortedBy { it.title })

    override fun observeMoviesByCategory(categoryId: String): Flow<List<MovieEntity>> =
        flowOf(movies.values.filter { it.categoryId == categoryId }.sortedBy { it.title })

    override suspend fun getMovieById(id: String): MovieEntity? = movies[id]

    override suspend fun clearMovies() {
        movies.clear()
    }

    // ── Series ────────────────────────────────────────────────────────────────

    override suspend fun upsertSeries(series: List<SeriesEntity>) {
        series.forEach { this.series[it.id] = it }
    }

    override fun observeAllSeries(): Flow<List<SeriesEntity>> =
        flowOf(series.values.sortedBy { it.title })

    override fun observeSeriesByCategory(categoryId: String): Flow<List<SeriesEntity>> =
        flowOf(series.values.filter { it.categoryId == categoryId }.sortedBy { it.title })

    override suspend fun getSeriesById(id: String): SeriesEntity? = series[id]

    override suspend fun clearSeries() {
        series.clear()
    }

    // ── Seasons ───────────────────────────────────────────────────────────────

    override suspend fun upsertSeasons(seasons: List<SeasonEntity>) {
        seasons.forEach { this.seasons[it.seriesId to it.seasonNumber] = it }
    }

    override fun observeSeasonsBySeriesId(seriesId: String): Flow<List<SeasonEntity>> =
        flowOf(seasons.values.filter { it.seriesId == seriesId }.sortedBy { it.seasonNumber })

    override suspend fun clearSeasonsBySeriesId(seriesId: String) {
        seasons.keys.removeAll { it.first == seriesId }
    }

    override suspend fun clearAllSeasons() {
        seasons.clear()
    }

    // ── Episodes ──────────────────────────────────────────────────────────────

    override suspend fun upsertEpisodes(episodes: List<EpisodeEntity>) {
        episodes.forEach { this.episodes[it.id] = it }
    }

    override fun observeEpisodesBySeriesAndSeason(
        seriesId: String,
        seasonNumber: Int,
    ): Flow<List<EpisodeEntity>> =
        flowOf(
            episodes.values
                .filter { it.seriesId == seriesId && it.seasonNumber == seasonNumber }
                .sortedBy { it.episodeNumber },
        )

    override fun observeEpisodesBySeriesId(seriesId: String): Flow<List<EpisodeEntity>> =
        flowOf(
            episodes.values
                .filter { it.seriesId == seriesId }
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })),
        )

    override suspend fun getEpisodeById(id: String): EpisodeEntity? = episodes[id]

    override suspend fun clearEpisodesBySeriesId(seriesId: String) {
        episodes.values.removeAll { it.seriesId == seriesId }
    }

    override suspend fun clearAllEpisodes() {
        episodes.clear()
    }
}
