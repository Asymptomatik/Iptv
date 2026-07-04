package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.data.local.entity.EpisodeEntity
import com.bobot.iptvapp.data.local.entity.MovieEntity
import com.bobot.iptvapp.data.local.entity.SeasonEntity
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the offline-first catalog cache.
 *
 * Covers all catalog entity types: categories, live channels, movies, series (metadata),
 * seasons, and episodes. EPG entries are managed separately by [EpgDao].
 *
 * ## Upsert strategy
 * All write methods use [Upsert] (INSERT OR REPLACE on the primary key). Calling upsert
 * with the same set of items is idempotent and refreshes stale data in place. Incremental
 * refreshes (e.g. a new episode added to an existing series) work correctly without
 * clearing the table first.
 *
 * ## contentType query convention
 * DAO methods that filter by `contentType` accept the
 * [com.bobot.iptvapp.domain.model.ContentType] enum name as a `String`
 * (e.g. `ContentType.MOVIE.name`). The Task 11 repository layer is responsible for
 * the conversion. The TEXT column value in the database is the enum name written by
 * [com.bobot.iptvapp.data.local.Converters].
 *
 * ## Migration policy
 * All tables in this DAO are catalog caches. Destructive fallback is acceptable for
 * cache tables in the event of a migration gap — the next app open re-populates them.
 * See [com.bobot.iptvapp.data.local.IptvDatabase] for the full migration policy.
 */
@Dao
interface CatalogCacheDao {

    // ── Categories ────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of categories. */
    @Upsert
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    /**
     * Observes categories whose `contentType` column equals the given [contentType] name,
     * ordered alphabetically by name.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name,
     *   e.g. `ContentType.LIVE.name`.
     */
    @Query("SELECT * FROM categories WHERE contentType = :contentType ORDER BY name ASC")
    fun observeCategoriesByType(contentType: String): Flow<List<CategoryEntity>>

    /**
     * Deletes all categories whose `contentType` column equals the given [contentType] name.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name.
     */
    @Query("DELETE FROM categories WHERE contentType = :contentType")
    suspend fun clearCategoriesByType(contentType: String)

    /** Deletes all category rows. */
    @Query("DELETE FROM categories")
    suspend fun clearAllCategories()

    // ── Channels ──────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of live channels. */
    @Upsert
    suspend fun upsertChannels(channels: List<ChannelEntity>)

    /** Observes all live channels alphabetically by name. */
    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>

    /** Observes live channels belonging to the given [categoryId], alphabetically. */
    @Query("SELECT * FROM channels WHERE categoryId = :categoryId ORDER BY name ASC")
    fun observeChannelsByCategory(categoryId: String): Flow<List<ChannelEntity>>

    /** Deletes all channel rows. */
    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    // ── Movies ────────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of movies. */
    @Upsert
    suspend fun upsertMovies(movies: List<MovieEntity>)

    /** Observes all movies alphabetically by title. */
    @Query("SELECT * FROM movies ORDER BY title ASC")
    fun observeAllMovies(): Flow<List<MovieEntity>>

    /** Observes movies in the given [categoryId], alphabetically by title. */
    @Query("SELECT * FROM movies WHERE categoryId = :categoryId ORDER BY title ASC")
    fun observeMoviesByCategory(categoryId: String): Flow<List<MovieEntity>>

    /**
     * One-shot lookup of a single movie by its stream [id].
     * Returns `null` when the movie is not in the cache.
     */
    @Query("SELECT * FROM movies WHERE id = :id LIMIT 1")
    suspend fun getMovieById(id: String): MovieEntity?

    /** Deletes all movie rows. */
    @Query("DELETE FROM movies")
    suspend fun clearMovies()

    // ── Series ────────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of series metadata entries. */
    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)

    /** Observes all series alphabetically by title. */
    @Query("SELECT * FROM series ORDER BY title ASC")
    fun observeAllSeries(): Flow<List<SeriesEntity>>

    /** Observes series in the given [categoryId], alphabetically by title. */
    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY title ASC")
    fun observeSeriesByCategory(categoryId: String): Flow<List<SeriesEntity>>

    /**
     * One-shot lookup of a single series by its [id].
     * Returns `null` when the series is not in the cache.
     */
    @Query("SELECT * FROM series WHERE id = :id LIMIT 1")
    suspend fun getSeriesById(id: String): SeriesEntity?

    /** Deletes all series metadata rows. */
    @Query("DELETE FROM series")
    suspend fun clearSeries()

    // ── Seasons ───────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of seasons. */
    @Upsert
    suspend fun upsertSeasons(seasons: List<SeasonEntity>)

    /**
     * Observes seasons for a specific [seriesId], ordered by season number ascending.
     */
    @Query("SELECT * FROM seasons WHERE seriesId = :seriesId ORDER BY seasonNumber ASC")
    fun observeSeasonsBySeriesId(seriesId: String): Flow<List<SeasonEntity>>

    /** Deletes all seasons belonging to the given [seriesId]. */
    @Query("DELETE FROM seasons WHERE seriesId = :seriesId")
    suspend fun clearSeasonsBySeriesId(seriesId: String)

    /** Deletes all season rows. */
    @Query("DELETE FROM seasons")
    suspend fun clearAllSeasons()

    // ── Episodes ──────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of episodes. */
    @Upsert
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    /**
     * Observes episodes for a specific series and season, ordered by episode number.
     */
    @Query("""
        SELECT * FROM episodes
        WHERE seriesId    = :seriesId
          AND seasonNumber = :seasonNumber
        ORDER BY episodeNumber ASC
    """)
    fun observeEpisodesBySeriesAndSeason(seriesId: String, seasonNumber: Int): Flow<List<EpisodeEntity>>

    /**
     * Observes all episodes for a series across all seasons, ordered for sequential
     * playback (season ASC, episode ASC).
     */
    @Query("""
        SELECT * FROM episodes
        WHERE seriesId = :seriesId
        ORDER BY seasonNumber ASC, episodeNumber ASC
    """)
    fun observeEpisodesBySeriesId(seriesId: String): Flow<List<EpisodeEntity>>

    /**
     * One-shot lookup of a single episode by its stream [id].
     * Returns `null` when the episode is not in the cache.
     */
    @Query("SELECT * FROM episodes WHERE id = :id LIMIT 1")
    suspend fun getEpisodeById(id: String): EpisodeEntity?

    /** Deletes all episodes belonging to the given [seriesId]. */
    @Query("DELETE FROM episodes WHERE seriesId = :seriesId")
    suspend fun clearEpisodesBySeriesId(seriesId: String)

    /** Deletes all episode rows. */
    @Query("DELETE FROM episodes")
    suspend fun clearAllEpisodes()
}
