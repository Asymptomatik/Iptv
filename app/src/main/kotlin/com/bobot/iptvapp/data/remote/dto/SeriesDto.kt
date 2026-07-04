package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for a single series entry returned by `get_series` (the list endpoint).
 *
 * This is the lightweight metadata payload. Season and episode data is only
 * available via `get_series_info` → [SeriesInfoDto].
 *
 * Sample JSON (condensed):
 * ```json
 * {
 *   "series_id": "101",
 *   "name": "Breaking Bad",
 *   "cover": "http://example.com/cover.jpg",
 *   "plot": "A high school chemistry teacher ...",
 *   "cast": "Bryan Cranston ...",
 *   "genre": "Drama",
 *   "releaseDate": "2008",
 *   "rating": "9.5",
 *   "category_id": "3"
 * }
 * ```
 *
 * [seriesId] uses [FlexibleStringSerializer] because it may be an integer or string.
 * [releaseDate] may be a year string ("2008"), a full ISO date ("2008-01-20"), or null.
 */
@Serializable
data class SeriesDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("series_id") val seriesId: String,
    @SerialName("name") val name: String,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("cast") val cast: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("genre") val genre: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("releaseDate") val releaseDate: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("last_modified") val lastModified: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("rating") val rating: String? = null,
    @SerialName("rating_5based") val rating5Based: Float? = null,
    /**
     * Category this series belongs to. Nullable because some servers omit it
     * when `get_series` is called without a `category_id` filter.
     */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("episode_run_time") val episodeRunTime: String? = null,
)
