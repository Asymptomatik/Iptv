package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.EpgProgramEntity
import com.bobot.iptvapp.domain.model.EpgProgram

/**
 * Mapping functions between [EpgProgramEntity] (Room cache) and [EpgProgram] (domain).
 *
 * These mappers are used by the offline-first catalog cache integration layer.
 * See [com.bobot.iptvapp.data.local.dao.EpgDao] for the underlying DAO.
 */

/** Maps an [EpgProgramEntity] to the domain [EpgProgram]. */
fun EpgProgramEntity.toDomain(): EpgProgram = EpgProgram(
    channelId = channelId,
    title = title,
    description = description,
    startMillis = startMillis,
    endMillis = endMillis,
)

/**
 * Maps a domain [EpgProgram] to the [EpgProgramEntity] for Room cache persistence.
 *
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun EpgProgram.toEntity(accountKey: String): EpgProgramEntity = EpgProgramEntity(
    accountKey = accountKey,
    channelId = channelId,
    title = title,
    description = description,
    startMillis = startMillis,
    endMillis = endMillis,
)

/** Convenience extension to map a list of [EpgProgramEntity]s. */
fun List<EpgProgramEntity>.toDomain(): List<EpgProgram> = map { it.toDomain() }

/** Convenience extension to map a list of [EpgProgram]s. */
fun List<EpgProgram>.toEntity(accountKey: String): List<EpgProgramEntity> = map { it.toEntity(accountKey) }
