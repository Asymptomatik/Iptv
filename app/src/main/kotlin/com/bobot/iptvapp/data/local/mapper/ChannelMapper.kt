package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.util.AccountKey
import com.bobot.iptvapp.domain.util.BouquetSeparator

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

/**
 * Maps a domain [Channel] to the [ChannelEntity] for Room cache persistence.
 *
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun Channel.toEntity(accountKey: AccountKey): ChannelEntity = ChannelEntity(
    accountKey = accountKey.value,
    id = id,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
)

/**
 * Maps a list of [ChannelEntity]s, dropping bouquet separators (see [BouquetSeparator]).
 *
 * The network mapper already filters them out, so nothing written after QA finding Y2 was fixed
 * can contain one. This second pass is for the caches written *before* that: they survive an app
 * update untouched, and a stale-cache read is exactly the path that would put a separator back on
 * screen — including as the Accueil hero — until the next successful refresh.
 */
fun List<ChannelEntity>.toDomain(): List<Channel> =
    filterNot { BouquetSeparator.matches(it.name) }.map { it.toDomain() }

/** Convenience extension to map a list of [Channel]s. */
fun List<Channel>.toEntity(accountKey: AccountKey): List<ChannelEntity> = map { it.toEntity(accountKey) }
