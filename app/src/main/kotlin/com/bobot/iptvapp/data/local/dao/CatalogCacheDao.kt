package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity
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
 * ## Account partitioning (schema v3)
 * Every entity in this DAO carries an `accountKey` column as part of its composite
 * primary key (see [com.bobot.iptvapp.domain.util.accountKeyOf]). All read queries and
 * all targeted deletes below accept `accountKey` and filter on it, so that content cached
 * under one Xtream account never surfaces when reading under another. [Upsert] write
 * methods do not need an explicit `accountKey` parameter — it travels on the entity
 * itself, set by the caller (repository layer) before persisting.
 *
 * The "clear entire table" methods ([clearAllCategories], [clearChannels], [clearMovies],
 * [clearSeries], [clearAllSeasons], [clearAllEpisodes]) remain global and unparameterised
 * by design — they back the full-logout purge, not per-account isolation.
 *
 * ## Migration policy
 * All tables in this DAO are catalog caches. Destructive fallback is acceptable for
 * cache tables in the event of a migration gap — the next app open re-populates them.
 * See [com.bobot.iptvapp.data.local.IptvDatabase] for the full migration policy.
 */
@Dao
interface CatalogCacheDao {

    // ── Categories ──────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of categories. */
    @Upsert
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    /**
     * Observes categories for [accountKey] whose `contentType` column equals the given
     * [contentType] name, ordered alphabetically by name.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name,
     *   e.g. `ContentType.LIVE.name`.
     */
    @Query("SELECT * FROM categories WHERE accountKey = :accountKey AND contentType = :contentType ORDER BY name ASC")
    fun observeCategoriesByType(accountKey: String, contentType: String): Flow<List<CategoryEntity>>

    /**
     * Deletes categories for [accountKey] whose `contentType` column equals the given
     * [contentType] name.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name.
     */
    @Query("DELETE FROM categories WHERE accountKey = :accountKey AND contentType = :contentType")
    suspend fun clearCategoriesByType(accountKey: String, contentType: String)

    /** Deletes all category rows, across every account. */
    @Query("DELETE FROM categories")
    suspend fun clearAllCategories()

    // ── Channels ────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of live channels. */
    @Upsert
    suspend fun upsertChannels(channels: List<ChannelEntity>)

    /** Observes all live channels for [accountKey], alphabetically by name. */
    @Query("SELECT * FROM channels WHERE accountKey = :accountKey ORDER BY name ASC")
    fun observeAllChannels(accountKey: String): Flow<List<ChannelEntity>>

    /** Observes live channels for [accountKey] belonging to the given [categoryId], alphabetically. */
    @Query("SELECT * FROM channels WHERE accountKey = :accountKey AND categoryId = :categoryId ORDER BY name ASC")
    fun observeChannelsByCategory(accountKey: String, categoryId: String): Flow<List<ChannelEntity>>

    /** Deletes all channel rows, across every account. */
    /**
     * One-shot lookup of a single channel for [accountKey] by its stream [id].
     * Returns `null` when the channel is not in the cache.
     */
    @Query("SELECT * FROM channels WHERE accountKey = :accountKey AND id = :id LIMIT 1")
    suspend fun getChannelById(accountKey: String, id: String): ChannelEntity?

    @Query("DELETE FROM channels")
    suspend fun clearChannels()

    // ── Movies ──────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of movies. */
    @Upsert
    suspend fun upsertMovies(movies: List<MovieEntity>)

    /** Observes all movies for [accountKey], alphabetically by title. */
    @Query("SELECT * FROM movies WHERE accountKey = :accountKey ORDER BY title ASC")
    fun observeAllMovies(accountKey: String): Flow<List<MovieEntity>>

    /** Observes movies for [accountKey] in the given [categoryId], alphabetically by title. */
    @Query("SELECT * FROM movies WHERE accountKey = :accountKey AND categoryId = :categoryId ORDER BY title ASC")
    fun observeMoviesByCategory(accountKey: String, categoryId: String): Flow<List<MovieEntity>>

    /**
     * One-shot lookup of a single movie for [accountKey] by its stream [id].
     * Returns `null` when the movie is not in the cache.
     */
    @Query("SELECT * FROM movies WHERE accountKey = :accountKey AND id = :id LIMIT 1")
    suspend fun getMovieById(accountKey: String, id: String): MovieEntity?

    /** Deletes all movie rows, across every account. */
    @Query("DELETE FROM movies")
    suspend fun clearMovies()

    // ── Series ──────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of series metadata entries. */
    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)

    /** Observes all series for [accountKey], alphabetically by title. */
    @Query("SELECT * FROM series WHERE accountKey = :accountKey ORDER BY title ASC")
    fun observeAllSeries(accountKey: String): Flow<List<SeriesEntity>>

    /** Observes series for [accountKey] in the given [categoryId], alphabetically by title. */
    @Query("SELECT * FROM series WHERE accountKey = :accountKey AND categoryId = :categoryId ORDER BY title ASC")
    fun observeSeriesByCategory(accountKey: String, categoryId: String): Flow<List<SeriesEntity>>

    /**
     * One-shot lookup of a single series for [accountKey] by its [id].
     * Returns `null` when the series is not in the cache.
     */
    @Query("SELECT * FROM series WHERE accountKey = :accountKey AND id = :id LIMIT 1")
    suspend fun getSeriesById(accountKey: String, id: String): SeriesEntity?

    /** Deletes all series metadata rows, across every account. */
    @Query("DELETE FROM series")
    suspend fun clearSeries()

    // ── Seasons ─────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of seasons. */
    @Upsert
    suspend fun upsertSeasons(seasons: List<SeasonEntity>)

    /**
     * Observes seasons for [accountKey] and a specific [seriesId], ordered by season
     * number ascending.
     */
    @Query("SELECT * FROM seasons WHERE accountKey = :accountKey AND seriesId = :seriesId ORDER BY seasonNumber ASC")
    fun observeSeasonsBySeriesId(accountKey: String, seriesId: String): Flow<List<SeasonEntity>>

    /** Deletes seasons for [accountKey] belonging to the given [seriesId]. */
    @Query("DELETE FROM seasons WHERE accountKey = :accountKey AND seriesId = :seriesId")
    suspend fun clearSeasonsBySeriesId(accountKey: String, seriesId: String)

    /** Deletes all season rows, across every account. */
    @Query("DELETE FROM seasons")
    suspend fun clearAllSeasons()

    // ── Episodes ────────────────────────────────────────────────────────────

    /** Inserts or refreshes a list of episodes. */
    @Upsert
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    /**
     * Observes episodes for [accountKey] and a specific series and season, ordered by
     * episode number.
     */
    @Query("""
        SELECT * FROM episodes
        WHERE accountKey   = :accountKey
          AND seriesId      = :seriesId
          AND seasonNumber  = :seasonNumber
        ORDER BY episodeNumber ASC
    """)
    fun observeEpisodesBySeriesAndSeason(
        accountKey: String,
        seriesId: String,
        seasonNumber: Int,
    ): Flow<List<EpisodeEntity>>

    /**
     * Observes all episodes for [accountKey] and a series across all seasons, ordered
     * for sequential playback (season ASC, episode ASC).
     */
    @Query("""
        SELECT * FROM episodes
        WHERE accountKey = :accountKey
          AND seriesId    = :seriesId
        ORDER BY seasonNumber ASC, episodeNumber ASC
    """)
    fun observeEpisodesBySeriesId(accountKey: String, seriesId: String): Flow<List<EpisodeEntity>>

    /**
     * One-shot lookup of a single episode for [accountKey] by its stream [id].
     * Returns `null` when the episode is not in the cache.
     */
    @Query("SELECT * FROM episodes WHERE accountKey = :accountKey AND id = :id LIMIT 1")
    suspend fun getEpisodeById(accountKey: String, id: String): EpisodeEntity?

    /** Deletes episodes for [accountKey] belonging to the given [seriesId]. */
    @Query("DELETE FROM episodes WHERE accountKey = :accountKey AND seriesId = :seriesId")
    suspend fun clearEpisodesBySeriesId(accountKey: String, seriesId: String)

    /** Deletes all episode rows, across every account. */
    @Query("DELETE FROM episodes")
    suspend fun clearAllEpisodes()

    // ── Sync markers ────────────────────────────────────────────────────────

    /**
     * Records that a catalog slice was just filled from the API — see [CatalogSyncEntity]
     * for the grain and for why the timestamp lives beside the rows rather than on them.
     *
     * Written by the repository in the same best-effort block as the rows themselves, and
     * always *after* them: a marker without its rows would claim a fresh cache that is not
     * there, whereas rows without a marker only cost a refetch.
     */
    @Upsert
    suspend fun upsertSyncMarker(marker: CatalogSyncEntity)

    /**
     * Returns when the slice was last synced, or `null` when it never was.
     *
     * `null` is the honest answer for "no marker" and the repository treats it exactly like
     * an expired one, so a cache written before schema v4 — rows present, marker absent —
     * is refetched once rather than served as if it were fresh.
     */
    @Query(
        "SELECT syncedAtMillis FROM catalog_sync " +
            "WHERE accountKey = :accountKey AND contentType = :contentType AND scope = :scope LIMIT 1",
    )
    suspend fun getSyncedAtMillis(accountKey: String, contentType: String, scope: String): Long?

    /**
     * Drops every marker of one content type for [accountKey], which is what makes the next
     * read miss and refetch. Backs the per-type "recharger" actions in Réglages.
     */
    @Query("DELETE FROM catalog_sync WHERE accountKey = :accountKey AND contentType = :contentType")
    suspend fun clearSyncMarkersByType(accountKey: String, contentType: String)

    /** Deletes all sync markers, across every account. Backs the full-logout purge. */
    @Query("DELETE FROM catalog_sync")
    suspend fun clearAllSyncMarkers()
}
