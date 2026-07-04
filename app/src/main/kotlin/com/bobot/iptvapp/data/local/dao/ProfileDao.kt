package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.ProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for user profile CRUD operations.
 *
 * Profiles are **user data** and the source of truth for multi-profile support.
 * They survive app upgrades — any schema change to [ProfileEntity] requires a
 * proper Room migration.
 *
 * All write methods are `suspend`; observable reads return [Flow] so Compose screens
 * can react to profile changes without manual refresh.
 */
@Dao
interface ProfileDao {

    /**
     * Inserts a new profile or replaces an existing one with the same [ProfileEntity.id].
     * Use for both create and update operations.
     */
    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    /**
     * Inserts or replaces multiple profiles in a single transaction.
     */
    @Upsert
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    /**
     * Deletes the given profile, matched by its [ProfileEntity.id] primary key.
     */
    @Delete
    suspend fun delete(profile: ProfileEntity)

    /**
     * Deletes the profile with the given [id]. No-op when no profile with that id exists.
     */
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Observes all profiles, ordered alphabetically by [ProfileEntity.name].
     * Emits a new list whenever the profiles table changes.
     */
    @Query("SELECT * FROM profiles ORDER BY name ASC")
    fun getAll(): Flow<List<ProfileEntity>>

    /**
     * Retrieves a profile by its [id]. Returns `null` when not found.
     */
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProfileEntity?
}
