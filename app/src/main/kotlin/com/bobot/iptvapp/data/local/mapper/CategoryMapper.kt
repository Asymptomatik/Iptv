package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.domain.model.Category

/**
 * Mapping functions between [CategoryEntity] (Room cache) and [Category] (domain).
 *
 * [CategoryEntity.contentType] is stored as a [com.bobot.iptvapp.domain.model.ContentType]
 * enum value via [com.bobot.iptvapp.data.local.Converters] — Room handles the conversion
 * automatically, so no explicit enum/String conversion is needed here.
 *
 * These mappers are used by the offline-first catalog cache integration layer.
 * See [com.bobot.iptvapp.data.local.dao.CatalogCacheDao] for the underlying DAO.
 */

/** Maps a [CategoryEntity] to the domain [Category]. */
fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    type = contentType,
)

/** Maps a domain [Category] to the [CategoryEntity] for Room cache persistence. */
fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    contentType = type,
)

/** Convenience extension to map a list of [CategoryEntity]s. */
fun List<CategoryEntity>.toDomain(): List<Category> = map { it.toDomain() }

/** Convenience extension to map a list of [Category]s. */
fun List<Category>.toEntity(): List<CategoryEntity> = map { it.toEntity() }
