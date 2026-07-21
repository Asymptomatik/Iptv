package com.bobot.iptvapp.download

import androidx.media3.exoplayer.offline.Download
import com.bobot.iptvapp.domain.model.DownloadState

/** Converts transient Media3 states to the stable states exposed to the UI. */
object DownloadStateMapper {
    fun fromMedia3(media3State: Int): DownloadState = when (media3State) {
        Download.STATE_QUEUED, Download.STATE_RESTARTING -> DownloadState.QUEUED
        Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
        Download.STATE_STOPPED -> DownloadState.PAUSED
        Download.STATE_COMPLETED -> DownloadState.COMPLETED
        Download.STATE_FAILED -> DownloadState.FAILED
        else -> DownloadState.FAILED
    }
}
