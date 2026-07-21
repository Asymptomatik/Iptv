package com.bobot.iptvapp.domain.model

/** Metadata and transfer state shown for one offline VOD item. */
data class OfflineDownload(
    val downloadId: String,
    val contentType: DownloadContentType,
    val contentId: String,
    val title: String,
    val artworkUrl: String?,
    val streamUrl: String,
    val state: DownloadState,
    val bytesDownloaded: Long,
    val contentLength: Long,
    val updatedAtMillis: Long,
) {
    val progressPercent: Int?
        get() = contentLength.takeIf { it > 0 }?.let { length ->
            ((bytesDownloaded * 100) / length).toInt().coerceIn(0, 100)
        }
}

data class DownloadRequestData(
    val contentType: DownloadContentType,
    val contentId: String,
    val title: String,
    val artworkUrl: String?,
    val streamUrl: String,
)
