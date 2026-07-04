package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a content category returned by:
 *   - get_live_categories
 *   - get_vod_categories
 *   - get_series_categories
 *
 * Sample JSON:
 * ```json
 * {"category_id":"7","category_name":"News","parent_id":0}
 * ```
 *
 * [categoryId] may be returned as an integer (e.g. `7`) or a quoted string
 * (`"7"`) depending on the server; [FlexibleStringSerializer] handles both.
 */
@Serializable
data class CategoryDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String,
    @SerialName("category_name") val categoryName: String,
    @SerialName("parent_id") val parentId: Int? = null,
)
