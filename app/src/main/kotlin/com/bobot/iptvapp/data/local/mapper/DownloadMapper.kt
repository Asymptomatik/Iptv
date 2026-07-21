package com.bobot.iptvapp.data.local.mapper

import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.domain.model.DownloadContentType
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.OfflineDownload

fun DownloadEntity.toDomain(): OfflineDownload = OfflineDownload(
    downloadId = downloadId,
    contentType = DownloadContentType.valueOf(contentType),
    contentId = contentId,
    title = title,
    artworkUrl = artworkUrl,
    streamUrl = streamUrl,
    state = DownloadState.valueOf(state),
    bytesDownloaded = bytesDownloaded,
    contentLength = contentLength,
    updatedAtMillis = updatedAtMillis,
)
