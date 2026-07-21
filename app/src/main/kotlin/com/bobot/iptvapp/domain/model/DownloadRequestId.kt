package com.bobot.iptvapp.domain.model

/**
 * Downloadable VOD content types.
 *
 * Series themselves are containers and cannot be downloaded; each selected episode uses
 * [EPISODE]. Live streams are deliberately excluded from the offline-download scope.
 */
enum class DownloadContentType {
    MOVIE,
    EPISODE,
}

/**
 * Stable Media3 download-request identifiers.
 *
 * Keeping the type in the identifier prevents collisions between a movie and an episode that
 * happen to share an Xtream source id. The same value is later used as the Room primary key and
 * Media3 [androidx.media3.exoplayer.offline.DownloadRequest.id].
 */
object DownloadRequestId {

    fun create(contentType: DownloadContentType, sourceId: String): String {
        require(sourceId.isNotBlank()) { "A download source id must not be blank." }
        return "${contentType.name}:$sourceId"
    }
}
