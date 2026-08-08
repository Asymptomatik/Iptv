package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.EpisodeEntity
import com.bobot.iptvapp.data.local.entity.SeasonEntity
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.util.AccountKey

/**
 * Mapping functions for the Series → Season → Episode entity hierarchy.
 *
 * ## Assembly responsibility
 * The Season/Episode hierarchy is stored in separate tables (`series`, `seasons`, `episodes`).
 * Assembling the full tree — [SeriesEntity] + its [SeasonEntity]s + their [EpisodeEntity]s —
 * is the responsibility of the repository or use-case layer, not the mapper.
 *
 * The `seasons` and `episodes` parameters with empty defaults allow mappers to be called
 * either with pre-assembled children (full detail view) or without (list view):
 *  - List view: `seriesEntity.toDomain()` — seasons = emptyList()
 *  - Detail view: `seriesEntity.toDomain(assembledSeasons)` — full tree
 *
 * ## Denormalised fields
 * [SeasonEntity.seriesId] and [EpisodeEntity.seriesId] + [EpisodeEntity.seasonNumber]
 * are foreign keys injected at the entity layer (not present in the domain models).
 * The `toEntity()` functions require them as explicit parameters.
 *
 * These mappers are used by the offline-first catalog cache integration layer.
 * See [com.bobot.iptvapp.data.local.dao.CatalogCacheDao] for the underlying DAO.
 */

// ── Series ────────────────────────────────────────────────────────────────────

/**
 * Maps a [SeriesEntity] to the domain [Series].
 *
 * @param seasons Pre-assembled [Season] list. Pass an empty list (default) for the
 *   list-only view; pass the full assembled tree for the detail view.
 */
fun SeriesEntity.toDomain(seasons: List<Season> = emptyList()): Series = Series(
    id = id,
    title = title,
    coverUrl = coverUrl,
    plot = plot,
    categoryId = categoryId,
    rating = rating,
    year = year,
    seasons = seasons,
)

/**
 * Maps a domain [Series] to [SeriesEntity], stripping the seasons (stored separately).
 *
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun Series.toEntity(accountKey: AccountKey): SeriesEntity = SeriesEntity(
    accountKey = accountKey.value,
    id = id,
    title = title,
    coverUrl = coverUrl,
    plot = plot,
    categoryId = categoryId,
    rating = rating,
    year = year,
)

// ── Season ────────────────────────────────────────────────────────────────────

/**
 * Maps a [SeasonEntity] to the domain [Season].
 *
 * @param episodes Pre-assembled [Episode] list. Pass an empty list (default) for
 *   the season-header-only view; pass the full list for the detail view.
 */
fun SeasonEntity.toDomain(episodes: List<Episode> = emptyList()): Season = Season(
    seasonNumber = seasonNumber,
    name = name,
    coverUrl = coverUrl,
    episodes = episodes,
)

/**
 * Maps a domain [Season] to [SeasonEntity].
 *
 * @param seriesId The parent series identifier — required because [Season] does not
 *   carry it (it is a denormalised FK injected at the entity layer).
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun Season.toEntity(seriesId: String, accountKey: AccountKey): SeasonEntity = SeasonEntity(
    accountKey = accountKey.value,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    name = name,
    coverUrl = coverUrl,
)

// ── Episode ───────────────────────────────────────────────────────────────────

/** Maps an [EpisodeEntity] to the domain [Episode]. */
fun EpisodeEntity.toDomain(): Episode = Episode(
    id = id,
    title = title,
    episodeNumber = episodeNumber,
    seasonNumber = seasonNumber,
    plot = plot,
    durationMillis = durationMillis,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
)

/**
 * Maps a domain [Episode] to [EpisodeEntity].
 *
 * @param seriesId The parent series identifier — required because [Episode] does not
 *   carry it (it is a denormalised FK injected at the entity layer).
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun Episode.toEntity(seriesId: String, accountKey: AccountKey): EpisodeEntity = EpisodeEntity(
    accountKey = accountKey.value,
    id = id,
    seriesId = seriesId,
    seasonNumber = seasonNumber,
    title = title,
    episodeNumber = episodeNumber,
    plot = plot,
    durationMillis = durationMillis,
    containerExtension = containerExtension,
    coverUrl = coverUrl,
)

// ── List convenience ──────────────────────────────────────────────────────────

@JvmName("episodeEntitiesToDomain")
fun List<EpisodeEntity>.toDomain(): List<Episode> = map { it.toDomain() }
fun List<Episode>.toEntity(seriesId: String, accountKey: AccountKey): List<EpisodeEntity> =
    map { it.toEntity(seriesId, accountKey) }
@JvmName("seriesEntitiesToDomain")
fun List<SeriesEntity>.toDomain(): List<Series> = map { it.toDomain() }

/**
 * Maps a list of domain [Series] to [SeriesEntity] list, stripping seasons from each
 * (stored separately). Used by the offline-first catalog cache write-through in
 * [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl].
 *
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of each entity's composite
 *   primary key.
 */
fun List<Series>.toEntity(accountKey: AccountKey): List<SeriesEntity> = map { it.toEntity(accountKey) }
