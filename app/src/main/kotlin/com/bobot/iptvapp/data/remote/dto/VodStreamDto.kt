package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a single VOD entry returned by `get_vod_streams`.
 *
 * This is the lightweight list-endpoint payload. Extended metadata (plot, cover art,
 * duration) is available only via `get_vod_info` → [VodInfoDto].
 *
 * Sample JSON:
 * ```json
 * {
 *   "num": 1,
 *   "name": "Inception",
 *   "stream_type": "movie",
 *   "stream_id": 54321,
 *   "stream_icon": "http://example.com/cover.jpg",
 *   "rating": "8.8",
 *   "rating_5based": 4.4,
 *   "added": "1620000000",
 *   "category_id": "12",
 *   "container_extension": "mkv",
 *   "custom_sid": "",
 *   "direct_source": ""
 * }
 * ```
 */
@Serializable
data class VodStreamDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("stream_id") val streamId: String,
    @SerialName("name") val name: String,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("stream_icon") val streamIcon: String? = null,
    /** Audience score or content rating; may be numeric string or classification code. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("rating") val rating: String? = null,
    /** Epoch seconds as string. Null when absent. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("added") val added: String? = null,
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String,
    /** File container/extension, e.g. "mkv", "mp4". Used for VOD URL construction. */
    @SerialName("container_extension") val containerExtension: String? = null,
    /** Plot / synopsis. Usually absent in the list payload; prefer [VodInfoDto.info]. */
    @SerialName("plot") val plot: String? = null,
)
