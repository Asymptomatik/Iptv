package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching an Xtream Codes VOD movie.
 *
 * All nullable fields mirror the domain model [com.bobot.iptvapp.domain.model.Movie].
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * [categoryId] is a logical foreign key to [CategoryEntity.id] (no DB-level constraint).
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(tableName = "movies")
data class MovieEntity(
    @PrimaryKey val id: String,
    val title: String,
    val posterUrl: String?,
    val plot: String?,
    /** Logical FK to [CategoryEntity.id]. */
    val categoryId: String,
    val rating: String?,
    val year: Int?,
    val addedMillis: Long?,
    val durationMillis: Long?,
    val containerExtension: String?,
)
