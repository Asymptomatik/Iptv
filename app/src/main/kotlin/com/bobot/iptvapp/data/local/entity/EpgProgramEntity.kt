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
 * ## Schema version note
 * This index was originally added under Task 11b, back when the database was still at
 * `version = 1` and had never shipped to a real user, so no `Migration` object or version
 * bump was required at the time. That is no longer the case: the database is now at
 * `version = 3` (see [com.bobot.iptvapp.data.local.IptvDatabase]), and this index is part
 * of the exported schema snapshot at
 * `app/schemas/com.bobot.iptvapp.data.local.IptvDatabase/3.json`. It is created by
 * `DatabaseMigrations.MIGRATION_2_3`, alongside the `accountKey` partitioning of this
 * table. The database now also holds user tables (`profiles`, `favorites`,
 * `playback_progress`, `downloads`) that cannot be recreated from scratch without losing
 * user data, so the rule going forward is unconditional: any schema change to any table
 * MUST bump the database version and be accompanied by an explicit `Migration` object (or
 * a documented destructive fallback, but only where the migration policy in
 * [com.bobot.iptvapp.data.local.IptvDatabase] allows it for cache-only tables).
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
