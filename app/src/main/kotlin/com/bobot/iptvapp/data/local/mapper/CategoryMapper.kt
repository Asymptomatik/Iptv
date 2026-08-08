package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.CategoryEntity
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.util.AccountKey

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

/**
 * Maps a domain [Category] to the [CategoryEntity] for Room cache persistence.
 *
 * @param accountKey The owning account's cache partition key (see
 *   [com.bobot.iptvapp.domain.util.accountKeyOf]), part of the entity's composite
 *   primary key.
 */
fun Category.toEntity(accountKey: AccountKey): CategoryEntity = CategoryEntity(
    accountKey = accountKey.value,
    id = id,
    name = name,
    contentType = type,
)

/** Convenience extension to map a list of [CategoryEntity]s. */
fun List<CategoryEntity>.toDomain(): List<Category> = map { it.toDomain() }

/** Convenience extension to map a list of [Category]s. */
fun List<Category>.toEntity(accountKey: AccountKey): List<CategoryEntity> = map { it.toEntity(accountKey) }
