package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.PlaybackProgressEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for persisting and querying playback progress ("Continue Watching" feature).
 *
 * Progress records are **user data** and must survive app upgrades. Any schema change to
 * [PlaybackProgressEntity] requires a proper Room migration.
 *
 * ## Upsert semantics
 * [upsert] performs INSERT OR REPLACE on the composite primary key
 * `(profileId, contentId, contentType)`. The player writes a new record when playback
 * begins and updates it periodically, and on exit. The "last updated" timestamp
 * ([PlaybackProgressEntity.lastUpdatedMillis]) governs the "Continue Watching" ordering.
 *
 * ## contentType parameter convention
 * The [contentType] parameter in query methods accepts the
 * [com.bobot.iptvapp.domain.model.ContentType] enum name as a `String`
 * (e.g. `"MOVIE"`, `"SERIES"`). The Task 11 repository layer handles conversion.
 */
@Dao
interface PlaybackProgressDao {

    /**
     * Inserts or updates a progress record.
     *
     * If a record with the same composite key already exists (same profile + content),
     * all fields including [PlaybackProgressEntity.positionMillis] and
     * [PlaybackProgressEntity.lastUpdatedMillis] are updated.
     */
    @Upsert
    suspend fun upsert(progress: PlaybackProgressEntity)

    /**
     * Observes the "Continue Watching" list for a given [profileId].
     *
     * Results are ordered by [PlaybackProgressEntity.lastUpdatedMillis] descending so
     * the most recently watched item appears first in the home screen row.
     * [limit] caps the result set so the home screen row remains performant regardless
     * of history size (Task 11 carry-forward: enforce LIMIT at the DAO level).
     * Emits a new list whenever any progress record for this profile changes.
     *
     * @param profileId Profile whose continue-watching history to observe.
     * @param limit     Maximum number of records returned.
     */
    @Query("""
        SELECT * FROM playback_progress
        WHERE profileId = :profileId
        ORDER BY lastUpdatedMillis DESC
        LIMIT :limit
    """)
    fun observeContinueWatching(profileId: String, limit: Int): Flow<List<PlaybackProgressEntity>>

    /**
     * Retrieves the progress record for a specific content item within a profile.
     * Returns `null` when no progress has been recorded yet.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name.
     */
    @Query("""
        SELECT * FROM playback_progress
        WHERE profileId  = :profileId
          AND contentId  = :contentId
          AND contentType = :contentType
        LIMIT 1
    """)
    suspend fun getProgress(
        profileId: String,
        contentId: String,
        contentType: String,
    ): PlaybackProgressEntity?

    /**
     * Deletes the given progress record by entity reference (PK-matched delete).
     */
    @Delete
    suspend fun delete(progress: PlaybackProgressEntity)

    /**
     * Deletes a progress record by composite key. Prefer this over [delete] when
     * only the key fields are available.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name.
     */
    @Query("""
        DELETE FROM playback_progress
        WHERE profileId  = :profileId
          AND contentId  = :contentId
          AND contentType = :contentType
    """)
    suspend fun deleteByKeys(profileId: String, contentId: String, contentType: String)

    /**
     * Deletes all progress records belonging to [profileId].
     *
     * Call this when a user profile is deleted to prevent orphaned rows.
     * The companion call for favorites is [com.bobot.iptvapp.data.local.dao.FavoriteDao.clearByProfileId].
     */
    @Query("DELETE FROM playback_progress WHERE profileId = :profileId")
    suspend fun clearByProfileId(profileId: String)
}
