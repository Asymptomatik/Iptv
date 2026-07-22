package com.bobot.iptvapp.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import okhttp3.OkHttpClient
import com.bobot.iptvapp.di.DownloadModule.DownloadCache
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
 *
 * ## External subtitle side-loading (Task 3)
 * [create] optionally side-loads [ExternalSubtitle]s (e.g. Xtream `get_vod_info`'s best-effort
 * `.srt` URL) alongside the main stream.
 *
 * ### Why [MergingMediaSource] + [SingleSampleMediaSource], not `MediaItem.setSubtitleConfigurations`
 * The Media3 developer guide's side-loading recipe (`MediaItem.Builder().setSubtitleConfigurations(...)`
 * then `player.setMediaItem(...)`) only works because `ExoPlayer`'s *default* `MediaSource.Factory`
 * is [androidx.media3.exoplayer.source.DefaultMediaSourceFactory], whose own
 * `createMediaSource(MediaItem)` reads `mediaItem.localConfiguration.subtitleConfigurations`
 * and wraps the underlying source in a [MergingMediaSource] together with one
 * [SingleSampleMediaSource] per subtitle (verified directly against the `media3` `1.4.1` tag's
 * `DefaultMediaSourceFactory.java` source). [HlsMediaSource.Factory] and
 * [ProgressiveMediaSource.Factory] — the two factories this class builds directly, bypassing
 * `DefaultMediaSourceFactory` (see the class KDoc above) — do **not** contain that logic
 * themselves; a `MediaItem.subtitleConfigurations` list passed to either of them directly would
 * be silently ignored (no error, no track). So this class replicates
 * `DefaultMediaSourceFactory`'s own merging step explicitly: build the video [MediaSource] as
 * before, then, only when [ExternalSubtitle]s are supplied, wrap it in a [MergingMediaSource]
 * with one [SingleSampleMediaSource] per usable subtitle — the exact mechanism
 * `DefaultMediaSourceFactory` itself uses, so behavior matches the Media3-idiomatic side-loading
 * path precisely, just wired manually for this factory's explicit-source-type design.
 *
 * ### Best-effort guarantee: a dead/404 subtitle URL never blocks or crashes playback
 * [SingleSampleMediaSource.Factory] defaults `treatLoadErrorsAsEndOfStream` to `true` (verified
 * against `SingleSampleMediaSource.java`'s `Factory` constructor at the `1.4.1` tag — this
 * factory does not override it) — a load failure (404, timeout, malformed file, …) is reported
 * to the sample stream as an end-of-stream signal rather than propagated as a fatal error via
 * `SampleStream.maybeThrowError()`. A failed external subtitle track therefore simply never
 * produces cues; it does not fail the [MergingMediaSource], the video track, or playback as a
 * whole. This is the same default `DefaultMediaSourceFactory` relies on for its own side-loaded
 * subtitles, so no additional configuration is needed here. Separately, [create] itself never
 * lets a *mapping/construction*-time problem (blank/malformed URL, an unexpected exception
 * building one subtitle's [MediaItem.SubtitleConfiguration]) abort building the video source
 * either — see [buildSubtitleMediaSources].
 */
class IptvMediaSourceFactory @Inject constructor(
    okHttpClient: OkHttpClient,
    @DownloadCache downloadCache: Cache,
) {

    private val upstreamDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
    private val dataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * Creates a [MediaSource] for [streamUrl], resolving its [StreamMediaType] via
     * [StreamTypeResolver] and applying an explicit MIME type hint on the built
     * [MediaItem] so the correct extractor/parser is selected even when the server
     * response omits or misreports `Content-Type`.
     *
     * When [externalSubtitles] is non-empty, usable entries (see [buildSubtitleMediaSources])
     * are side-loaded alongside the stream via a [MergingMediaSource] — see the class KDoc's
     * "External subtitle side-loading" section for why. When [externalSubtitles] is empty (the
     * default, and every existing caller today), the returned [MediaSource] is exactly what this
     * method returned before this parameter existed — no [MergingMediaSource] wrapping, no
     * behavior change.
     */
    fun create(streamUrl: String, externalSubtitles: List<ExternalSubtitle> = emptyList()): MediaSource {
        val mediaType = StreamTypeResolver.resolve(streamUrl)
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(mediaType.toMimeTypeHint())
            .build()

        val videoSource = when (mediaType) {
            StreamMediaType.HLS ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)

            StreamMediaType.MPEG_TS, StreamMediaType.MP4, StreamMediaType.OTHER ->
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        if (externalSubtitles.isEmpty()) return videoSource

        val subtitleSources = buildSubtitleMediaSources(externalSubtitles)
        if (subtitleSources.isEmpty()) return videoSource

        return MergingMediaSource(videoSource, *subtitleSources.toTypedArray())
    }

    /** Maps a [StreamMediaType] to a Media3 MIME type hint, or `null` to let Media3 sniff it. */
    private fun StreamMediaType.toMimeTypeHint(): String? = when (this) {
        StreamMediaType.HLS -> MimeTypes.APPLICATION_M3U8
        StreamMediaType.MPEG_TS -> MimeTypes.VIDEO_MP2T
        StreamMediaType.MP4 -> MimeTypes.VIDEO_MP4
        StreamMediaType.OTHER -> null
    }

    /**
     * Maps [externalSubtitles] to [SingleSampleMediaSource]s, skipping (never throwing for) any
     * entry that is unusable — this whole method is the "best-effort" boundary the class KDoc
     * promises: whatever happens per-entry, [create] always still gets a (possibly empty) list
     * back and falls back to the plain video source when it's empty, rather than propagating an
     * exception that would abort playback setup entirely.
     *
     * An entry is skipped, with a warning logged rather than a thrown exception, when:
     * - its URL is blank ([ExternalSubtitle.url] is documented as always non-blank, but this is
     *   still guarded defensively since the value ultimately originates from a remote server);
     * - its URL cannot be parsed into a [Uri] with a scheme (the "Uri.parse guard" from the
     *   task's design requirements — [Uri.parse] itself practically never throws, but a bare
     *   string with no scheme is not a usable subtitle location, so it is rejected here rather
     *   than silently building a [SingleSampleMediaSource] that could never load);
     * - building its [MediaItem.SubtitleConfiguration]/[SingleSampleMediaSource] throws for any
     *   other reason.
     */
    private fun buildSubtitleMediaSources(externalSubtitles: List<ExternalSubtitle>): List<MediaSource> =
        externalSubtitles.mapNotNull { subtitle ->
            runCatching { subtitle.toSubtitleMediaSourceOrNull() }
                .onFailure {
                    Log.w(TAG, "Skipping unusable external subtitle (url=${subtitle.url}): ${it.message}")
                }
                .getOrNull()
        }

    /**
     * Builds a single [SingleSampleMediaSource] for [this] subtitle, or `null` when its URL is
     * blank or fails the [Uri] parsing guard described in [buildSubtitleMediaSources]'s KDoc.
     *
     * [MediaItem.SubtitleConfiguration.selectionFlags] is deliberately left unset (`0`) — never
     * [C.SELECTION_FLAG_DEFAULT] — so an external subtitle is *selectable* (via
     * [PlayerManager.getSubtitleTracks]/[PlayerManager.selectSubtitleTrack], Task 2) but never
     * auto-selected ahead of the user's/embedded-track's own default, per the design requirement
     * that embedded track selection stays the user's choice.
     */
    private fun ExternalSubtitle.toSubtitleMediaSourceOrNull(): MediaSource? {
        if (url.isBlank()) return null
        val uri = Uri.parse(url).takeIf { !it.scheme.isNullOrBlank() } ?: return null

        val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(SubtitleMimeTypeResolver.resolve(url))
            .setLanguage(language)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()

        return SingleSampleMediaSource.Factory(dataSourceFactory)
            .createMediaSource(subtitleConfiguration, /* durationUs= */ C.TIME_UNSET)
    }

    private companion object {
        const val TAG = "IptvMediaSourceFactory"
    }
}
