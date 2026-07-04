package com.bobot.iptvapp.domain.repository

import com.bobot.iptvapp.domain.model.Profile
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer contract for user profile management.
 *
 * Profiles are entirely local constructs — they are not synced to the Xtream Codes
 * server. Each profile maintains its own favorites and playback progress records,
 * enabling multi-user households to share a single server account with independent
 * watch histories.
 *
 * ## Flow vs suspend convention
 * | Return type        | Method type | Rationale                                            |
 * |--------------------|-------------|------------------------------------------------------|
 * | `Flow<List<T>>`    | observeProfiles | Reactive; profile screen reacts to changes         |
 * | `suspend`          | All write ops + getProfile | One-shot; triggered by user actions      |
 *
 * ## Implementation
 * @see com.bobot.iptvapp.data.repository.ProfileRepositoryImpl
 */
interface ProfileRepository {

    /**
     * Observes all profiles ordered alphabetically by name.
     * Emits a new list whenever the profiles table changes.
     */
    fun observeProfiles(): Flow<List<Profile>>

    /**
     * Returns the profile with the given [id], or `null` when not found.
     */
    suspend fun getProfile(id: String): Profile?

    /**
     * Creates a new profile with a generated UUID id.
     *
     * @param name      Display name chosen by the user.
     * @param avatarUrl Optional URL to a profile avatar image.
     * @return The newly created [Profile] with its generated [Profile.id].
     */
    suspend fun createProfile(name: String, avatarUrl: String? = null): Profile

    /**
     * Persists changes to an existing profile (name and/or avatar URL).
     *
     * The [Profile.id] is used to locate the record in the database.
     *
     * @return The same [profile] object, for convenience in call chains.
     */
    suspend fun updateProfile(profile: Profile): Profile

    /**
     * Deletes the profile identified by [id].
     *
     * Callers are responsible for also cleaning up any associated favorites and
     * playback progress records via [FavoritesRepository.clearFavorites] and
     * [PlaybackProgressRepository.clearProgress].
     */
    suspend fun deleteProfile(id: String)
}
