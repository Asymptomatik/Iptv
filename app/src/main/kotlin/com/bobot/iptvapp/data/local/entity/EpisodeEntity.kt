package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching a single episode of a series.
 *
 * ## Key design (Task 5 carry-forward)
 * [seriesId] and [seasonNumber] are logical foreign keys to [SeasonEntity], injected at
 * the entity layer (not present in the domain model [com.bobot.iptvapp.domain.model.Episode]).
 * They enable efficient queries such as "all episodes for series X, season Y" without
 * joining through a seasons table.
 *
 * The episode [id] is the Xtream Codes stream ID (unique across all episodes) and serves
 * as the single-column primary key.
 *
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    /** Logical FK to [SeriesEntity.id]. */
    val seriesId: String,
    /** Logical FK to [SeasonEntity.seasonNumber] within the parent series. */
    val seasonNumber: Int,
    val title: String,
    val episodeNumber: Int,
    val plot: String?,
    val durationMillis: Long?,
    val containerExtension: String?,
    val coverUrl: String?,
)
