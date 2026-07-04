package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity

/**
 * Room entity persisting playback position for a content item, scoped to a profile.
 *
 * The composite primary key `(profileId, contentId, contentType)` mirrors the domain
 * model identity of [com.bobot.iptvapp.domain.model.PlaybackProgress]: one progress
 * record per content item per profile.
 *
 * Powers the "Continue Watching" row (Task 23): the DAO orders records by
 * [lastUpdatedMillis] descending so the most recently watched item appears first.
 *
 * ## contentType storage
 * [contentType] is stored as the [com.bobot.iptvapp.domain.model.ContentType] enum
 * name (a plain `String`, e.g. `"MOVIE"`). Using a raw String keeps composite
 * primary-key matching unambiguous and avoids converter indirection in PK columns.
 *
 * ## Migration policy
 * This table stores **user data** — proper Room migrations MUST be written for any
 * schema change. Destructive fallback is NOT acceptable once the app is released.
 */
@Entity(
    tableName = "playback_progress",
    primaryKeys = ["profileId", "contentId", "contentType"],
)
data class PlaybackProgressEntity(
    val profileId: String,
    val contentId: String,
    /** [com.bobot.iptvapp.domain.model.ContentType] enum name (e.g. `"MOVIE"`). */
    val contentType: String,
    val positionMillis: Long,
    val durationMillis: Long,
    /** Epoch-millisecond timestamp of the last write. Orders the "Continue Watching" row. */
    val lastUpdatedMillis: Long,
)
