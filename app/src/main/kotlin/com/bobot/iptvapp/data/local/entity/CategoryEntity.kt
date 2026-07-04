package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
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
 * ## Migration policy
 * Catalog cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the cache from the Xtream Codes API.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    /**
     * Stored as [ContentType] enum name via [com.bobot.iptvapp.data.local.Converters].
     * Queries filtering by this column must bind the enum name String.
     */
    val contentType: ContentType,
)
