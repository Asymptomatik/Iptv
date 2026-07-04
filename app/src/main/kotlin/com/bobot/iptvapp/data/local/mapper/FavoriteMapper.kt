package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.FavoriteEntity
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.FavoriteItem

/**
 * Mapping functions between [FavoriteEntity] (Room) and [FavoriteItem] (domain).
 *
 * [FavoriteEntity.contentType] is stored as the [ContentType] enum name (a plain String,
 * e.g. `"MOVIE"`). These mappers handle the String ↔ enum conversion.
 *
 * Note: [FavoritesRepositoryImpl][com.bobot.iptvapp.data.repository.FavoritesRepositoryImpl]
 * constructs [FavoriteEntity] directly when toggling on (using `System.currentTimeMillis()`
 * as [FavoriteEntity.addedAt]), so [FavoriteItem.toEntity] is primarily useful for tests
 * and future use-cases that reconstruct favorites from domain objects.
 */

/** Maps a [FavoriteEntity] to the domain [FavoriteItem]. */
fun FavoriteEntity.toDomain(): FavoriteItem = FavoriteItem(
    profileId = profileId,
    contentId = contentId,
    contentType = ContentType.valueOf(contentType),
    addedAt = addedAt,
)

/** Maps a domain [FavoriteItem] to the [FavoriteEntity] for Room persistence. */
fun FavoriteItem.toEntity(): FavoriteEntity = FavoriteEntity(
    profileId = profileId,
    contentId = contentId,
    contentType = contentType.name,
    addedAt = addedAt,
)
