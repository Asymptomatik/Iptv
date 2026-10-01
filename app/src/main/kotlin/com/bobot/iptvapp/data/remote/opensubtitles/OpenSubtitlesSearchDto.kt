package com.bobot.iptvapp.data.remote.opensubtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for `GET /subtitles` of the OpenSubtitles REST API v1, limited to the fields the app reads.
// Shape checked against the official reference ("Search for subtitles", opensubtitles.stoplight.io,
// 2026-09-23). Everything is nullable or defaulted: the payload is user-contributed data.

@Serializable
data class SubtitleSearchResponseDto(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
    val page: Int = 0,
    val data: List<SubtitleDataDto> = emptyList(),
)

@Serializable
data class SubtitleDataDto(
    val id: String? = null,
    val type: String? = null,
    val attributes: SubtitleAttributesDto? = null,
)

@Serializable
data class SubtitleAttributesDto(
    @SerialName("subtitle_id") val subtitleId: String? = null,
    val language: String? = null,
    @SerialName("download_count") val downloadCount: Int = 0,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    @SerialName("machine_translated") val machineTranslated: Boolean = false,
    @SerialName("ai_translated") val aiTranslated: Boolean = false,
    @SerialName("from_trusted") val fromTrusted: Boolean = false,
    val release: String? = null,
    @SerialName("feature_details") val featureDetails: SubtitleFeatureDetailsDto? = null,
    val files: List<SubtitleFileDto> = emptyList(),
)

@Serializable
data class SubtitleFeatureDetailsDto(
    val title: String? = null,
    val year: Int? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("parent_title") val parentTitle: String? = null,
)

@Serializable
data class SubtitleFileDto(
    @SerialName("file_id") val fileId: Long? = null,
    @SerialName("file_name") val fileName: String? = null,
)
