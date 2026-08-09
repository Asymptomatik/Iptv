package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity caching an Xtream Codes live broadcast channel.
 *
 * Corresponds to domain model [com.bobot.iptvapp.domain.model.Channel].
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * [categoryId] is a logical foreign key to [CategoryEntity.id] (no DB-level constraint;
 * referential integrity is managed at the repository layer).
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
    tableName = "channels",
    primaryKeys = ["accountKey", "id"],
    indices = [Index(value = ["accountKey", "categoryId"])],
)
data class ChannelEntity(
    val accountKey: String,
    val id: String,
    val name: String,
    val logoUrl: String?,
    /** Logical FK to [CategoryEntity.id]. */
    val categoryId: String,
    val epgChannelId: String?,
)
