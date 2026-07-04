package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bobot.iptvapp.data.local.entity.FavoriteEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for managing user favorites ("My List" feature).
 *
 * Favorites are **user data** and must survive app upgrades. Any schema change to
 * [FavoriteEntity] requires a proper Room migration.
 *
 * ## contentType parameter convention
 * The [contentType] parameter in query methods accepts the
 * [com.bobot.iptvapp.domain.model.ContentType] enum name as a `String`
 * (e.g. `"MOVIE"`, `"LIVE"`, `"SERIES"`). The Task 11 repository layer is responsible
 * for the `ContentType.name` conversion before calling these methods.
 *
 * ## Toggle pattern
 * Add a favorite: call [insert].
 * Remove a favorite: call [deleteByKeys] (or [delete] if the entity is in hand).
 * Check state reactively: collect [isFavorite].
 */
@Dao
interface FavoriteDao {

    /**
     * Adds a content item to the user's list.
     *
     * Uses [OnConflictStrategy.REPLACE]: if the same composite key already exists
     * (a re-favorite after removal), the row is replaced and [FavoriteEntity.addedAt]
     * is updated to the new timestamp.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    /**
     * Removes a favorite by entity reference (PK-matched delete).
     */
    @Delete
    suspend fun delete(favorite: FavoriteEntity)

    /**
     * Removes a favorite identified by its composite key.
     *
     * Prefer this over [delete] when only the key fields are known (avoids constructing
     * a full [FavoriteEntity] with a placeholder [FavoriteEntity.addedAt]).
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name.
     */
    @Query("DELETE FROM favorites WHERE profileId = :profileId AND contentId = :contentId AND contentType = :contentType")
    suspend fun deleteByKeys(profileId: String, contentId: String, contentType: String)

    /**
     * Observes all favorites for a given [profileId], ordered by most recently added first.
     * Emits a new list whenever the favorites table changes for that profile.
     */
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun observeFavorites(profileId: String): Flow<List<FavoriteEntity>>

    /**
     * Observes whether a specific content item is in the user's favorites list.
     * Emits `true` when the record exists, `false` when it does not.
     *
     * Collect this to drive the heart/bookmark toggle icon on content cards without
     * polling.
     *
     * @param contentType [com.bobot.iptvapp.domain.model.ContentType] enum name.
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM favorites
            WHERE profileId  = :profileId
              AND contentId  = :contentId
              AND contentType = :contentType
        )
    """)
    fun isFavorite(profileId: String, contentId: String, contentType: String): Flow<Boolean>

    /**
     * Deletes all favorite records belonging to [profileId].
     *
     * Call this when a user profile is deleted to prevent orphaned rows.
     * The companion call for playback progress is
     * [com.bobot.iptvapp.data.local.dao.PlaybackProgressDao.clearByProfileId].
     */
    @Query("DELETE FROM favorites WHERE profileId = :profileId")
    suspend fun clearByProfileId(profileId: String)
}
