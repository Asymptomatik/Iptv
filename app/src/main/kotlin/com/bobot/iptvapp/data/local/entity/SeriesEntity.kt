package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching Xtream Codes series metadata (list-level, no season/episode tree).
 *
 * Corresponds to domain model [com.bobot.iptvapp.domain.model.Series] (with
 * [com.bobot.iptvapp.domain.model.Series.seasons] left empty at this layer — seasons and
 * episodes are stored in separate tables, [SeasonEntity] and [EpisodeEntity], and
 * assembled in Task 11 repositories).
 *
 * [categoryId] is a logical foreign key to [CategoryEntity.id] (no DB-level constraint).
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    val coverUrl: String?,
    val plot: String?,
    /** Logical FK to [CategoryEntity.id]. */
    val categoryId: String,
    val rating: String?,
    val year: Int?,
)
