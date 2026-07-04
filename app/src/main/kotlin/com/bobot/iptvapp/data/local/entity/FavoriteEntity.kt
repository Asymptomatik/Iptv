package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity

/**
 * Room entity representing a user-favorited content item scoped to a profile.
 *
 * The composite primary key `(profileId, contentId, contentType)` guarantees that
 * each content item can be favorited at most once per profile. Toggling a favorite
 * is implemented as insert (add) + deleteByKeys (remove) at the DAO layer.
 *
 * ## contentType storage
 * [contentType] is stored as the [com.bobot.iptvapp.domain.model.ContentType] enum
 * name (a plain `String`, e.g. `"MOVIE"`). Storing it as a raw String — rather than
 * the enum type with a TypeConverter — keeps composite primary-key matching unambiguous
 * and avoids any converter indirection in the PK columns.
 *
 * ## Migration policy
 * This table stores **user data** — proper Room migrations MUST be written for any
 * schema change. Destructive fallback is NOT acceptable once the app is released.
 */
@Entity(
    tableName = "favorites",
    primaryKeys = ["profileId", "contentId", "contentType"],
)
data class FavoriteEntity(
    val profileId: String,
    val contentId: String,
    /** [com.bobot.iptvapp.domain.model.ContentType] enum name (e.g. `"MOVIE"`). */
    val contentType: String,
    /** Epoch-millisecond timestamp when this item was added to the user's list. */
    val addedAt: Long,
)
