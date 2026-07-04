package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level response from `get_vod_info?vod_id=X`.
 *
 * The response wraps extended metadata in `info` and stream identification data
 * in `movie_data`.
 *
 * Sample JSON (condensed):
 * ```json
 * {
 *   "info": {
 *     "title": "Inception",
 *     "year": "2010",
 *     "plot": "A skilled thief ...",
 *     "cover_big": "http://example.com/cover.jpg",
 *     "duration_secs": 8880,
 *     "rating": "8.8",
 *     "genre": "Action, Adventure",
 *     "releasedate": "2010-07-16"
 *   },
 *   "movie_data": {
 *     "stream_id": 54321,
 *     "category_id": "12",
 *     "container_extension": "mkv"
 *   }
 * }
 * ```
 */
@Serializable
data class VodInfoDto(
    @SerialName("info") val info: VodInfoDetailDto,
    @SerialName("movie_data") val movieData: VodMovieDataDto? = null,
)

/**
 * Extended metadata block inside a [VodInfoDto] response.
 *
 * Prefer [coverBig] over [movieImage] for artwork; fall back to [movieImage] when
 * [coverBig] is absent. Both fields can be blank strings on some servers.
 * [durationSecs] is an integer count of seconds; mappers multiply by 1000 for millis.
 */
@Serializable
data class VodInfoDetailDto(
    @SerialName("name") val name: String? = null,
    @SerialName("title") val title: String? = null,
    /** Release year as string (e.g. "2010") or null. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("year") val year: String? = null,
    /** Epoch seconds of when the VOD was added to the server. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("added") val added: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    /** Large cover / poster art URL. Preferred over [movieImage]. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("cover_big") val coverBig: String? = null,
    /** Alternative poster URL. Use as fallback when [coverBig] is absent. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("description") val description: String? = null,
    /** Total duration in seconds. Multiply by 1000 in mappers to get millis. */
    @SerialName("duration_secs") val durationSecs: Int? = null,
    /** Human-readable duration string, e.g. "02:28:00". Not used by mappers. */
    @SerialName("duration") val duration: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("rating") val rating: String? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("director") val director: String? = null,
    @SerialName("cast") val cast: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("tmdb_id") val tmdbId: String? = null,
    /** ISO date string, e.g. "2010-07-16". Year extracted by mappers. */
    @SerialName("releasedate") val releaseDate: String? = null,
)

/**
 * Stream identification block inside a [VodInfoDto] response.
 *
 * Contains the [streamId] and [containerExtension] needed to build playback URLs.
 * [categoryId] here is the authoritative source over the one in [VodInfoDetailDto].
 */
@Serializable
data class VodMovieDataDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("stream_id") val streamId: String,
    @SerialName("name") val name: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("added") val added: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
)
