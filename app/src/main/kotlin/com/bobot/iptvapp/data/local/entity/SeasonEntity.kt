package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity

/**
 * Room entity caching a season of a series.
 *
 * ## Key design (Task 5 carry-forward)
 * The domain model [com.bobot.iptvapp.domain.model.Season] has no standalone id field.
 * The composite primary key `(seriesId, seasonNumber)` is the natural key: a season
 * number is unique within a series.
 *
 * [seriesId] is a logical foreign key to [SeriesEntity.id] (injected at the entity layer
 * as required by the Task 5 carry-forward note — not present in the domain model).
 *
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(
    tableName = "seasons",
    primaryKeys = ["seriesId", "seasonNumber"],
)
data class SeasonEntity(
    /** Logical FK to [SeriesEntity.id]. Part of composite primary key. */
    val seriesId: String,
    val seasonNumber: Int,
    val name: String?,
    val coverUrl: String?,
)
