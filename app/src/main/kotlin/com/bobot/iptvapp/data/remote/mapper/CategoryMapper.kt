package com.bobot.iptvapp.data.remote.mapper

import com.bobot.iptvapp.data.remote.dto.CategoryDto
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.ContentType

/**
 * Maps a [CategoryDto] (network DTO) to a [Category] domain model.
 *
 * The [ContentType] is not present in the DTO because all three category endpoints
 * (`get_live_categories`, `get_vod_categories`, `get_series_categories`) return
 * the same JSON shape. The caller must supply the correct [type] based on which
 * endpoint was called.
 *
 * @param type Content type this category belongs to; determines which endpoint owns it.
 */
fun CategoryDto.toDomain(type: ContentType): Category = Category(
    id = categoryId,
    name = categoryName,
    type = type,
)

/**
 * Convenience extension to map a list of [CategoryDto]s.
 */
fun List<CategoryDto>.toDomain(type: ContentType): List<Category> =
    map { it.toDomain(type) }
