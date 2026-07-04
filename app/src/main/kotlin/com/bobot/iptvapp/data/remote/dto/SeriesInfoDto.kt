package com.bobot.iptvapp.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level response from `get_series_info?series_id=X`.
 *
 * Contains three parts:
 * - [info]: extended series metadata
 * - [seasons]: ordered list of season records
 * - [episodes]: a map keyed by season number string to a list of episodes in that season
 *
 * The [episodes] map uses string keys such as "1", "2", … matching [SeasonDto.seasonNumber].
 *
 * Sample JSON (condensed):
 * ```json
 * {
 *   "info": { "name": "Breaking Bad", "series_id": 101, ... },
 *   "seasons": [ { "season_number": 1, "name": "Season 1", ... } ],
 *   "episodes": {
 *     "1": [ { "id": "67890", "episode_num": 1, "title": "Pilot", ... } ]
 *   }
 * }
 * ```
 */
@Serializable
data class SeriesInfoDto(
    @SerialName("info") val info: SeriesInfoDetailDto,
    @SerialName("seasons") val seasons: List<SeasonDto> = emptyList(),
    /**
     * Map from season-number string key (e.g. "1") to the list of episodes in that
     * season. Keyed by string because JSON object keys are always strings.
     */
    @SerialName("episodes") val episodes: Map<String, List<EpisodeDto>> = emptyMap(),
)

/**
 * Extended series metadata inside a [SeriesInfoDto] response.
 *
 * [seriesId] mirrors the `series_id` query parameter but may not always be present;
 * callers should pass the known [series_id] as a fallback in mappers.
 */
@Serializable
data class SeriesInfoDetailDto(
    @SerialName("name") val name: String? = null,
    @SerialName("title") val title: String? = null,
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
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("category_name") val categoryName: String? = null,
    /** The numeric series ID echoed back in the detail payload. May be int or string. */
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("series_id") val seriesId: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("year") val year: String? = null,
)

/**
 * A single season record inside a [SeriesInfoDto.seasons] list.
 *
 * [seasonNumber] is the primary key used to join with the [SeriesInfoDto.episodes] map.
 * Prefer [coverBig] over [cover] for artwork.
 */
@Serializable
data class SeasonDto(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("name") val name: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("cover") val cover: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("cover_big") val coverBig: String? = null,
    @SerialName("overview") val overview: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("id") val id: String? = null,
)

/**
 * A single episode record inside a [SeriesInfoDto.episodes] season list.
 *
 * [id] is the episode stream ID used to build the playback URL.
 * [episodeNum] is the display position within its season; it is read as String
 * via [FlexibleStringSerializer] because some servers return it as an integer.
 * [season] is the denormalised season number echoed on the episode itself.
 */
@Serializable
data class EpisodeDto(
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("id") val id: String,
    /** Episode position within the season. May be an integer or quoted string in JSON. */
    @Serializable(with = FlexibleStringSerializer::class)
    @SerialName("episode_num") val episodeNum: String,
    @SerialName("title") val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = null,
    @SerialName("info") val info: EpisodeInfoDto? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("added") val added: String? = null,
    /** Season number echoed on the episode (integer). Null when absent. */
    @SerialName("season") val season: Int? = null,
    @SerialName("direct_source") val directSource: String? = null,
)

/**
 * Episode extended metadata nested inside [EpisodeDto.info].
 *
 * [durationSecs] is seconds; mappers multiply by 1000 for millis.
 * [movieImage] is a still-frame thumbnail URL.
 */
@Serializable
data class EpisodeInfoDto(
    @SerialName("duration_secs") val durationSecs: Int? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("plot") val plot: String? = null,
    @Serializable(with = NullableFlexibleStringSerializer::class)
    @SerialName("movie_image") val movieImage: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
)
