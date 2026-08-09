package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index

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
 * ## Account partitioning (schema v3)
 * [accountKey] (see [com.bobot.iptvapp.domain.util.accountKeyOf]) is part of the
 * composite primary key so that the cache is isolated per Xtream account.
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(
    tableName = "series",
    primaryKeys = ["accountKey", "id"],
    indices = [Index(value = ["accountKey", "categoryId"])],
)
data class SeriesEntity(
    val accountKey: String,
    val id: String,
    val title: String,
    val coverUrl: String?,
    val plot: String?,
    /** Logical FK to [CategoryEntity.id]. */
    val categoryId: String,
    val rating: String?,
    val year: Int?,
)
