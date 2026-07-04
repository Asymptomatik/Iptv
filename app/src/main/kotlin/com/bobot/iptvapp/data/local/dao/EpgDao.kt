package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.EpgProgramEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the EPG (Electronic Programme Guide) cache.
 *
 * EPG data is fetched from the Xtream Codes API (`get_short_epg`, `get_epg`) and
 * cached locally for instant display without network round-trips during live playback
 * or channel browsing.
 *
 * ## Pruning
 * EPG programmes accumulate over time. Call [pruneOldPrograms] periodically (e.g. on
 * app foreground or after a refresh) to delete rows whose [EpgProgramEntity.endMillis]
 * is in the past. Pass `System.currentTimeMillis()` as the threshold.
 *
 * ## Migration policy
 * EPG cache — destructive fallback is acceptable. The next app open re-fetches
 * and rebuilds the EPG cache from the Xtream Codes API.
 */
@Dao
interface EpgDao {

    /**
     * Inserts or updates a batch of EPG programme entries.
     *
     * Uses [Upsert] (INSERT OR REPLACE on the composite primary key `channelId + startMillis`).
     * Re-upserting the same programme is idempotent.
     */
    @Upsert
    suspend fun upsert(programs: List<EpgProgramEntity>)

    /**
     * Observes all EPG entries for the given [channelId], ordered by start time ascending
     * (chronological order). Includes both past and future programmes.
     *
     * [channelId] matches [com.bobot.iptvapp.domain.model.Channel.epgChannelId].
     */
    @Query("""
        SELECT * FROM epg_programs
        WHERE channelId = :channelId
        ORDER BY startMillis ASC
    """)
    fun observeByChannelId(channelId: String): Flow<List<EpgProgramEntity>>

    /**
     * Returns the currently airing programme for a [channelId] at [nowMillis].
     * Returns `null` when no programme covers the given instant.
     */
    @Query("""
        SELECT * FROM epg_programs
        WHERE channelId   = :channelId
          AND startMillis <= :nowMillis
          AND endMillis    > :nowMillis
        LIMIT 1
    """)
    suspend fun getCurrentProgram(channelId: String, nowMillis: Long): EpgProgramEntity?

    /**
     * Deletes all EPG entries whose [EpgProgramEntity.endMillis] is strictly before
     * [beforeMillis].
     *
     * Typical usage: `pruneOldPrograms(System.currentTimeMillis())` removes all
     * already-aired programmes.
     */
    @Query("DELETE FROM epg_programs WHERE endMillis < :beforeMillis")
    suspend fun pruneOldPrograms(beforeMillis: Long)

    /**
     * Deletes all EPG entries for the given [channelId].
     * Useful when re-fetching EPG for a specific channel.
     */
    @Query("DELETE FROM epg_programs WHERE channelId = :channelId")
    suspend fun clearByChannelId(channelId: String)

    /** Deletes all EPG programme rows. */
    @Query("DELETE FROM epg_programs")
    suspend fun clearAll()
}
