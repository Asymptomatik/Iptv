package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.domain.model.Channel

/**
 * Mapping functions between [ChannelEntity] (Room cache) and [Channel] (domain).
 *
 * These mappers are used by the offline-first catalog cache integration layer.
 * See [com.bobot.iptvapp.data.local.dao.CatalogCacheDao] for the underlying DAO.
 */

/** Maps a [ChannelEntity] to the domain [Channel]. */
fun ChannelEntity.toDomain(): Channel = Channel(
    id = id,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
)

/** Maps a domain [Channel] to the [ChannelEntity] for Room cache persistence. */
fun Channel.toEntity(): ChannelEntity = ChannelEntity(
    id = id,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
)

/** Convenience extension to map a list of [ChannelEntity]s. */
fun List<ChannelEntity>.toDomain(): List<Channel> = map { it.toDomain() }

/** Convenience extension to map a list of [Channel]s. */
fun List<Channel>.toEntity(): List<ChannelEntity> = map { it.toEntity() }
