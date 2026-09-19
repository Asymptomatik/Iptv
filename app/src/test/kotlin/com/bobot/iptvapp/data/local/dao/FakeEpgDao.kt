package com.bobot.iptvapp.data.local.dao

import com.bobot.iptvapp.data.local.entity.EpgProgramEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [EpgDao] test double, mirroring [FakeCatalogCacheDao]'s conventions.
 *
 * The existing repository tests drive [EpgDao] through a relaxed mock because they only ever
 * assert *that* [clearAll] was called. The logout purger tests assert the rows are actually gone,
 * which needs real storage.
 */
class FakeEpgDao : EpgDao {

    private val programs = mutableMapOf<Triple<String, String, Long>, EpgProgramEntity>()

    /** When non-null, [clearAll] throws it instead of deleting. */
    var failOnClear: Throwable? = null

    val currentPrograms: List<EpgProgramEntity> get() = programs.values.toList()

    override suspend fun upsert(programs: List<EpgProgramEntity>) {
        programs.forEach { this.programs[keyOf(it)] = it }
    }

    override fun observeByChannelId(accountKey: String, channelId: String): Flow<List<EpgProgramEntity>> =
        flowOf(programsOf(accountKey, channelId))

    override suspend fun getCurrentProgram(
        accountKey: String,
        channelId: String,
        nowMillis: Long,
    ): EpgProgramEntity? = programsOf(accountKey, channelId)
        .firstOrNull { nowMillis in it.startMillis until it.endMillis }

    override suspend fun pruneOldPrograms(accountKey: String, beforeMillis: Long) {
        programs.values
            .filter { it.accountKey == accountKey && it.endMillis < beforeMillis }
            .forEach { programs.remove(keyOf(it)) }
    }

    override suspend fun clearByChannelId(accountKey: String, channelId: String) {
        programsOf(accountKey, channelId).forEach { programs.remove(keyOf(it)) }
    }

    override suspend fun clearAll() {
        failOnClear?.let { throw it }
        programs.clear()
    }

    override suspend fun countAll(): Int = programs.size

    private fun programsOf(accountKey: String, channelId: String): List<EpgProgramEntity> =
        programs.values
            .filter { it.accountKey == accountKey && it.channelId == channelId }
            .sortedBy { it.startMillis }

    private fun keyOf(program: EpgProgramEntity) =
        Triple(program.accountKey, program.channelId, program.startMillis)
}
