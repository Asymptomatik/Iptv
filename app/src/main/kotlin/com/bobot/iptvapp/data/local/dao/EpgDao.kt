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
 * ## Account partitioning (schema v3)
 * [EpgProgramEntity] carries an `accountKey` column as part of its composite primary
 * key (see [com.bobot.iptvapp.domain.util.accountKeyOf]). All read queries and all
 * targeted deletes below accept `accountKey` and filter on it. [clearAll] remains
 * global and unparameterised — it backs the full-logout purge, not per-account
 * isolation.
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
     * Uses [Upsert] (INSERT OR REPLACE on the composite primary key
     * `accountKey + channelId + startMillis`). Re-upserting the same programme is
     * idempotent.
     */
    @Upsert
    suspend fun upsert(programs: List<EpgProgramEntity>)

    /**
     * Observes all EPG entries for [accountKey] and the given [channelId], ordered by
     * start time ascending (chronological order). Includes both past and future
     * programmes.
     *
     * [channelId] matches [com.bobot.iptvapp.domain.model.Channel.epgChannelId].
     */
    @Query("""
        SELECT * FROM epg_programs
        WHERE accountKey = :accountKey
          AND channelId   = :channelId
        ORDER BY startMillis ASC
    """)
    fun observeByChannelId(accountKey: String, channelId: String): Flow<List<EpgProgramEntity>>

    /**
     * Returns the currently airing programme for [accountKey] and a [channelId] at
     * [nowMillis]. Returns `null` when no programme covers the given instant.
     */
    @Query("""
        SELECT * FROM epg_programs
        WHERE accountKey  = :accountKey
          AND channelId   = :channelId
          AND startMillis <= :nowMillis
          AND endMillis    > :nowMillis
        LIMIT 1
    """)
    suspend fun getCurrentProgram(accountKey: String, channelId: String, nowMillis: Long): EpgProgramEntity?

    /**
     * Deletes all EPG entries for [accountKey] whose [EpgProgramEntity.endMillis] is
     * strictly before [beforeMillis].
     *
     * Typical usage: `pruneOldPrograms(accountKey, System.currentTimeMillis())` removes
     * all already-aired programmes for that account.
     */
    @Query("DELETE FROM epg_programs WHERE accountKey = :accountKey AND endMillis < :beforeMillis")
    suspend fun pruneOldPrograms(accountKey: String, beforeMillis: Long)

    /**
     * Deletes all EPG entries for [accountKey] and the given [channelId].
     * Useful when re-fetching EPG for a specific channel.
     */
    @Query("DELETE FROM epg_programs WHERE accountKey = :accountKey AND channelId = :channelId")
    suspend fun clearByChannelId(accountKey: String, channelId: String)

    /** Deletes all EPG programme rows, across every account. */
    @Query("DELETE FROM epg_programs")
    suspend fun clearAll()
}
