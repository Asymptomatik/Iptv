package com.bobot.iptvapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * UI index for a VOD download managed by Media3.
 *
 * The bytes and resumable transfer state stay in Media3's own database/cache. This table stores
 * the user-facing metadata needed to render a download even while the service is not running.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    /** Stable Media3 [androidx.media3.exoplayer.offline.DownloadRequest.id]. */
    @PrimaryKey val downloadId: String,
    /** [com.bobot.iptvapp.domain.model.DownloadContentType] enum name. */
    val contentType: String,
    val contentId: String,
    val title: String,
    val artworkUrl: String?,
    /** Original stream URI, used to resolve a cache-backed local playback source. */
    val streamUrl: String,
    /** [com.bobot.iptvapp.domain.model.DownloadState] enum name. */
    val state: String,
    val bytesDownloaded: Long,
    val contentLength: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    companion object {
        const val UNKNOWN_CONTENT_LENGTH = -1L
    }
}
