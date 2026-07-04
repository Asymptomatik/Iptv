package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a local user profile.
 *
 * Profiles are the source of truth for user identity within the app.
 * They are NOT synced to the Xtream Codes server — local only.
 *
 * Corresponds to domain model [com.bobot.iptvapp.domain.model.Profile].
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * ## Migration policy
 * This table stores **user data** — proper Room migrations MUST be written for
 * any schema change. Destructive fallback is NOT acceptable once the app is released.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUrl: String?,
)
