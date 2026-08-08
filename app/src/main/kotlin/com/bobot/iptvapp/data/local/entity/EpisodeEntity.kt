package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity

/**
 * Room entity caching a single episode of a series.
 *
 * ## Key design (Task 5 carry-forward)
 * [seriesId] and [seasonNumber] are logical foreign keys to [SeasonEntity], injected at
 * the entity layer (not present in the domain model [com.bobot.iptvapp.domain.model.Episode]).
 * They enable efficient queries such as "all episodes for series X, season Y" without
 * joining through a seasons table.
 *
 * The episode [id] is the Xtream Codes stream ID, unique across all episodes for a given
 * account; combined with [accountKey] it forms the composite primary key.
 *
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * ## Account partitioning (schema v3)
 * [accountKey] (see [com.bobot.iptvapp.domain.util.accountKeyOf]) is part of the
 * composite primary key so that the cache is isolated per Xtream account.
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(tableName = "episodes", primaryKeys = ["accountKey", "id"])
data class EpisodeEntity(
    val accountKey: String,
    val id: String,
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
