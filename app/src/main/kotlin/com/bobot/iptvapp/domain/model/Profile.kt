package com.bobot.iptvapp.domain.model

/**
 * A local user profile for personalised app experience.
 *
 * Profiles are entirely local constructs — they are not synced to the Xtream
 * Codes server. Each profile maintains its own favourites and playback progress
 * records (see [PlaybackProgress]), enabling multi-user households to share a
 * single server account with independent watch histories.
 *
 * Sourced from: local Room database only (Task 10). No Xtream Codes endpoint
 * is involved. Created and managed by the Profiles screen (Task 16).
 *
 * @property id        Locally generated unique identifier (e.g. UUID string).
 * @property name      Display name chosen by the user (e.g. "Alice", "Kids").
 * @property avatarUrl Optional remote or content-URI URL to a profile avatar image.
 *                     Null when the user has not set an avatar.
 */
data class Profile(
    val id: String,
    val name: String,
    val avatarUrl: String?,
)
