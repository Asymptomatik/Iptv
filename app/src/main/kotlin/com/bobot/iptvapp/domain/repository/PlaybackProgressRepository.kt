package com.bobot.iptvapp.domain.repository

import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer contract for persisting and querying playback progress.
 *
 * Powers the "Continue Watching" resume feature. The player writes progress records
 * via [upsertProgress] during and after playback. The home screen reads the most recent
 * records via [observeContinueWatching].
 *
 * Progress records are scoped per profile — each [profileId] has an independent history.
 * The composite key `(profileId, contentId, contentType)` identifies one record uniquely:
 * one position per content item per profile.
 *
 * ## Profile deletion
 * When a profile is deleted, call [clearProgress] to remove all associated progress records.
 * This is a responsibility of the caller, not handled automatically by the repository.
 *
 * ## Implementation
 * @see com.bobot.iptvapp.data.repository.PlaybackProgressRepositoryImpl
 */
interface PlaybackProgressRepository {

    /**
     * Inserts or updates a progress record.
     *
     * If a record already exists for the same composite key
     * (profileId + contentId + contentType), all fields are overwritten.
     */
    suspend fun upsertProgress(progress: PlaybackProgress)

    /**
     * Returns the progress record for a specific content item and profile.
     * Returns `null` when no progress has been recorded yet.
     *
     * @param contentType Classifies the content item (LIVE, MOVIE, or SERIES).
     */
    suspend fun getProgress(
        profileId: String,
        contentId: String,
        contentType: ContentType,
    ): PlaybackProgress?

    /**
     * Observes the "Continue Watching" list for [profileId].
     *
     * Results are ordered by most recently updated first
     * ([PlaybackProgress.lastUpdatedMillis] DESC). The [limit] parameter caps the
     * list length so the home screen row remains performant regardless of history size.
     *
     * Emits a new list whenever any progress record for this profile changes.
     *
     * @param profileId Profile whose history to observe.
     * @param limit     Maximum number of records to return. Defaults to 20.
     */
    fun observeContinueWatching(profileId: String, limit: Int = 20): Flow<List<PlaybackProgress>>

    /**
     * Deletes the progress record for a specific content item and profile.
     * No-op when no matching record exists.
     */
    suspend fun deleteProgress(
        profileId: String,
        contentId: String,
        contentType: ContentType,
    )

    /**
     * Deletes all progress records for [profileId].
     *
     * Intended to be called alongside [ProfileRepository.deleteProfile] when a profile
     * is removed, to prevent orphaned progress rows.
     */
    suspend fun clearProgress(profileId: String)
}
