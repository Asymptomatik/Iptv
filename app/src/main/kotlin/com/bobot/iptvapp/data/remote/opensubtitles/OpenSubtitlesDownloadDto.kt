package com.bobot.iptvapp.data.remote.opensubtitles

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for `POST /download` of the OpenSubtitles REST API v1, limited to the fields the app reads.
// Shape checked against the official reference ("Download", opensubtitles.stoplight.io,
// 2026-09-23). The 406 quota answer shares the success shape, minus `link`.

@Serializable
data class DownloadRequestDto(
    @SerialName("file_id") val fileId: Long,
)

@Serializable
data class DownloadResponseDto(
    val link: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    val requests: Int? = null,
    val remaining: Int? = null,
    val message: String? = null,
    @SerialName("reset_time_utc") val resetTimeUtc: String? = null,
)
