package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity caching an EPG (Electronic Programme Guide) entry.
 *
 * ## Key design (Task 5 carry-forward)
 * The domain model [com.bobot.iptvapp.domain.model.EpgProgram] has no standalone id.
 * The composite primary key `(accountKey, channelId, startMillis)` is the natural key: a
 * programme start time is unique per channel per account.
 *
 * [channelId] matches [com.bobot.iptvapp.domain.model.Channel.epgChannelId].
 *
 * ## Cache lifecycle
 * Old programmes are pruned via [com.bobot.iptvapp.data.local.dao.EpgDao.pruneOldPrograms]
 * to prevent unbounded growth. The prune threshold is determined by the caller (typically
 * "delete entries whose [endMillis] is before now").
 *
 * Entity-to-domain mapping is handled in Task 11 local repositories.
 *
 * ## Account partitioning (schema v3)
 * [accountKey] (see [com.bobot.iptvapp.domain.util.accountKeyOf]) is part of the
 * composite primary key so that the cache is isolated per Xtream account.
 *
 * ## Index on [endMillis] (Task 11b carry-forward)
 * [com.bobot.iptvapp.data.local.dao.EpgDao.pruneOldPrograms] filters on
 * `endMillis < :beforeMillis`. Without an index, this query is a full table scan, which
 * degrades as the EPG cache grows (potentially hundreds of channels x dozens of
 * programmes each). The index makes pruning an indexed range scan instead.
 *
 * ## Migration policy
 * EPG cache — destructive fallback is acceptable. The next app launch
 * re-fetches and rebuilds the EPG cache from the Xtream Codes API.
 *
 * ## Schema version note (Task 11b)
 * This index was added while the database is still at `version = 1` and has never
 * shipped to a real user (greenfield project, no production installs to migrate). Per
 * the migration policy above, no `Migration` object or version bump is required for this
 * change — Room will simply generate the index as part of the initial schema. Once the
 * app has real users, any further schema change to this or any table MUST bump the
 * version and provide a proper `Migration` (or an explicit, documented destructive
 * fallback for cache-only tables).
 */
@Entity(
    tableName = "epg_programs",
    primaryKeys = ["accountKey", "channelId", "startMillis"],
    indices = [Index(value = ["endMillis"])],
)
data class EpgProgramEntity(
    val accountKey: String,
    val channelId: String,
    /** Programme start time as epoch milliseconds (UTC). Part of composite primary key. */
    val startMillis: Long,
    val title: String,
    val description: String?,
    /** Programme end time as epoch milliseconds (UTC). */
    val endMillis: Long,
)
