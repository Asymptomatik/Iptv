package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import com.bobot.iptvapp.domain.model.ContentType

/**
 * Room entity caching an Xtream Codes content category.
 *
 * [contentType] is stored as a TEXT column using the
 * [com.bobot.iptvapp.data.local.Converters] TypeConverter registered on
 * [com.bobot.iptvapp.data.local.IptvDatabase]. The enum name (e.g. `"MOVIE"`) is
 * written to and read from the database automatically.
 *
 * Corresponds to domain model [com.bobot.iptvapp.domain.model.Category].
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * ## Account partitioning (schema v3)
 * [accountKey] (see [com.bobot.iptvapp.domain.util.accountKeyOf]) is part of the
 * composite primary key so that the cache is isolated per Xtream account — rows from
 * one account are never visible when reading under another. [contentType] is a plain
 * column and does not participate in the primary key.
 *
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(tableName = "categories", primaryKeys = ["accountKey", "id"])
data class CategoryEntity(
    val accountKey: String,
    val id: String,
    val name: String,
    /**
     * Stored as [ContentType] enum name via [com.bobot.iptvapp.data.local.Converters].
     * Queries filtering by this column must bind the enum name String.
     */
    val contentType: ContentType,
)
