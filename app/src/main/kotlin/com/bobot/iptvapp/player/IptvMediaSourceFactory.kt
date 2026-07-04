package com.bobot.iptvapp.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Builds a Media3 [MediaSource] for a given stream URL, picking the correct
 * `MediaSource.Factory` implementation for the "VLC-like" formats required by the brief:
 *
 * - [StreamMediaType.HLS] → [HlsMediaSource] — required for adaptive `.m3u8` live playlists;
 *   [androidx.media3.exoplayer.source.DefaultMediaSourceFactory] would also detect HLS by
 *   extension, but building the source explicitly keeps the type→source mapping obvious
 *   and avoids relying on sniffing when the extension resolution is ambiguous.
 * - [StreamMediaType.MPEG_TS] / [StreamMediaType.MP4] → [ProgressiveMediaSource] — both are
 *   direct-play, non-chunked containers (Xtream Codes live `.ts` and VOD `.mp4`).
 * - [StreamMediaType.OTHER] → falls back to a plain [ProgressiveMediaSource] as well; VOD
 *   containers other than `.mp4` (e.g. `.mkv`, `.avi`) are still progressive downloads, and
 *   Media3's `MimeTypeResolver`/extractor sniffing (via [androidx.media3.extractor.DefaultExtractorsFactory]
 *   used internally by [ProgressiveMediaSource.Factory]) will pick the right extractor.
 *
 * ## Networking
 * Uses the app's shared [OkHttpClient] (see `NetworkModule`) via [OkHttpDataSource.Factory]
 * so that player HTTP traffic shares the same timeouts/logging configuration as the
 * Retrofit-based Xtream Codes client, instead of instantiating a second, differently
 * configured HTTP stack.
 */
class IptvMediaSourceFactory @Inject constructor(
    okHttpClient: OkHttpClient,
) {

    private val dataSourceFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)

    /**
     * Creates a [MediaSource] for [streamUrl], resolving its [StreamMediaType] via
     * [StreamTypeResolver] and applying an explicit MIME type hint on the built
     * [MediaItem] so the correct extractor/parser is selected even when the server
     * response omits or misreports `Content-Type`.
     */
    fun create(streamUrl: String): MediaSource {
        val mediaType = StreamTypeResolver.resolve(streamUrl)
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(mediaType.toMimeTypeHint())
            .build()

        return when (mediaType) {
            StreamMediaType.HLS ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            StreamMediaType.MPEG_TS, StreamMediaType.MP4, StreamMediaType.OTHER ->
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    /** Maps a [StreamMediaType] to a Media3 MIME type hint, or `null` to let Media3 sniff it. */
    private fun StreamMediaType.toMimeTypeHint(): String? = when (this) {
        StreamMediaType.HLS -> MimeTypes.APPLICATION_M3U8
        StreamMediaType.MPEG_TS -> MimeTypes.VIDEO_MP2T
        StreamMediaType.MP4 -> MimeTypes.VIDEO_MP4
        StreamMediaType.OTHER -> null
    }
}
