package com.bobot.iptvapp.player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import okhttp3.OkHttpClient
import com.bobot.iptvapp.di.DownloadModule.DownloadCache
import com.bobot.iptvapp.di.OnlineSubtitleDirectory
import com.bobot.iptvapp.di.StreamingHttpClient
import java.io.File
import java.io.IOException
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
 * Uses the [StreamingHttpClient]-qualified [OkHttpClient] (see `NetworkModule`) via
 * [OkHttpDataSource.Factory], so player traffic keeps the API client's timeouts and shares its
 * connection pool instead of standing up a second HTTP stack — but *without* its body-logging
 * interceptor, which would buffer each whole film into the heap. See
 * [com.bobot.iptvapp.di.NetworkModule.provideStreamingOkHttpClient].
 *
 * ## External subtitle side-loading (Task 3)
 * [create] optionally side-loads [ExternalSubtitle]s (e.g. Xtream `get_vod_info`'s best-effort
 * `.srt` URL) alongside the main stream.
 *
 * ### Why [MergingMediaSource] + a subtitle [ProgressiveMediaSource], not `MediaItem.setSubtitleConfigurations`
 * The Media3 developer guide's side-loading recipe (`MediaItem.Builder().setSubtitleConfigurations(...)`
 * then `player.setMediaItem(...)`) only works because `ExoPlayer`'s *default* `MediaSource.Factory`
 * is [androidx.media3.exoplayer.source.DefaultMediaSourceFactory], whose own
 * `createMediaSource(MediaItem)` reads `mediaItem.localConfiguration.subtitleConfigurations`
 * and wraps the underlying source in a [MergingMediaSource] together with one source per
 * subtitle. [HlsMediaSource.Factory] and [ProgressiveMediaSource.Factory] — the two factories
 * this class builds directly, bypassing `DefaultMediaSourceFactory` (see the class KDoc above) —
 * do **not** contain that logic themselves; a `MediaItem.subtitleConfigurations` list passed to
 * either of them directly would be silently ignored (no error, no track). So this class
 * replicates `DefaultMediaSourceFactory`'s merging step explicitly: build the video
 * [MediaSource] as before, then, only when [ExternalSubtitle]s are supplied, wrap it in a
 * [MergingMediaSource] with one subtitle source per usable entry.
 *
 * ### Why the subtitle is parsed during extraction
 * Media3 1.4.1's default `TextRenderer` only accepts parsed cues (`application/x-media3-cues`)
 * unless legacy decoding is enabled player-wide; a raw `.srt`/`.vtt` sample — what a
 * `SingleSampleMediaSource` emits — fails its state check as soon as the track is selected.
 * Each subtitle is therefore a [ProgressiveMediaSource] whose only extractor is a
 * [SubtitleExtractor] fed by [DefaultSubtitleParserFactory], exactly what
 * `DefaultMediaSourceFactory` 1.4.1 builds on its default (parse-during-extraction) path. A
 * subtitle whose format no parser supports is skipped rather than handed to the renderer raw.
 *
 * ### Failures
 * [create] never lets a *mapping/construction*-time problem (blank/malformed URL, unsupported
 * format, a `file:` outside [onlineSubtitleDirectory], an unexpected exception building one
 * subtitle source) abort building the video source — see [buildSubtitleMediaSources]. A *load*
 * failure (404, timeout, purged file, …) is however handled by [ProgressiveMediaSource]'s default
 * load-error policy, like any `DefaultMediaSourceFactory` side-loaded subtitle: it is retried,
 * then surfaces as a player error — unlike the former `SingleSampleMediaSource`, which turned it
 * into an end-of-stream.
 *
 * Except for a subtitle carrying an [ExternalSubtitle.trackId] when [create] is given
 * `onSubtitleLoadError`: a player error cannot tell which source failed, so each failed attempt
 * to load or parse that subtitle is reported to the callback instead, on the main thread, and it
 * is retried forever rather than escalated ([SideLoadedSubtitleErrorPolicy]). The track stays
 * stalled or silent until the caller re-prepares without it; the video is never failed for it.
 */
class IptvMediaSourceFactory @Inject constructor(
    @StreamingHttpClient okHttpClient: OkHttpClient,
    @DownloadCache downloadCache: Cache,
    @OnlineSubtitleDirectory private val onlineSubtitleDirectory: File,
) {

    private val upstreamDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
    private val dataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    private val subtitleParserFactory = DefaultSubtitleParserFactory()

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
     *
     * [onSubtitleLoadError] is told the [ExternalSubtitle.trackId] of a subtitle that failed to
     * load or parse — see the class KDoc's "Failures" section.
     */
    fun create(
        streamUrl: String,
        externalSubtitles: List<ExternalSubtitle> = emptyList(),
        onSubtitleLoadError: ((trackId: String) -> Unit)? = null,
    ): MediaSource {
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

        val subtitleSources = buildSubtitleMediaSources(externalSubtitles, onSubtitleLoadError)
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
     * Maps [externalSubtitles] to subtitle [MediaSource]s, skipping (never throwing for) any
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
     *   than silently building a source that could never load);
     * - no [DefaultSubtitleParserFactory] parser supports its format, or it is a `file:` outside
     *   [onlineSubtitleDirectory] (both skipped silently, see [toSubtitleMediaSourceOrNull]);
     * - building its source throws for any other reason.
     */
    private fun buildSubtitleMediaSources(
        externalSubtitles: List<ExternalSubtitle>,
        onSubtitleLoadError: ((trackId: String) -> Unit)?,
    ): List<MediaSource> =
        externalSubtitles.mapNotNull { subtitle ->
            runCatching { subtitle.toSubtitleMediaSourceOrNull(onSubtitleLoadError) }
                .onFailure {
                    // Neither the URL nor the message: an Xtream subtitle URL carries the account's
                    // credentials in its path, and exception messages routinely quote the URL.
                    Log.w(TAG, "Skipping unusable external subtitle (${it.javaClass.simpleName})")
                }
                .getOrNull()
        }

    /**
     * Builds a single subtitle [ProgressiveMediaSource] for [this] subtitle, parsing it into cues
     * as it is extracted (see the class KDoc), or `null` when its URL is blank, fails the [Uri]
     * parsing guard, names a format no parser supports, or is a refused `file:` path.
     *
     * The track's [Format.selectionFlags] is deliberately left unset (`0`) — never
     * [C.SELECTION_FLAG_DEFAULT] — so an external subtitle is *selectable* (via
     * [PlayerManager.getSubtitleTracks]/[PlayerManager.selectSubtitleTrack], Task 2) but never
     * auto-selected ahead of the user's/embedded-track's own default, per the design requirement
     * that embedded track selection stays the user's choice.
     */
    private fun ExternalSubtitle.toSubtitleMediaSourceOrNull(
        onSubtitleLoadError: ((trackId: String) -> Unit)?,
    ): MediaSource? {
        if (url.isBlank()) return null
        val uri = Uri.parse(url).takeIf { !it.scheme.isNullOrBlank() } ?: return null

        // A set id becomes the track's Format.id, hence its PlayerTrack.id (see ExoPlayerManager).
        val format = Format.Builder()
            .setId(trackId)
            .setSampleMimeType(SubtitleMimeTypeResolver.resolve(url))
            .setLanguage(language)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()
        // A format no parser handles would reach the TextRenderer raw and fail there: skip it.
        if (!subtitleParserFactory.supportsFormat(format)) return null

        val subtitleDataSource = subtitleDataSourceFactory(uri) ?: return null
        val extractorsFactory = ExtractorsFactory {
            arrayOf(SubtitleExtractor(subtitleParserFactory.create(format), format))
        }
        val sourceFactory = ProgressiveMediaSource.Factory(subtitleDataSource, extractorsFactory)
        val reportedTrackId = trackId
        // The policy never gives up on its own, so it is only set when someone re-prepares.
        if (reportedTrackId == null || onSubtitleLoadError == null) {
            return sourceFactory.createMediaSource(MediaItem.Builder().setUri(uri).build())
        }
        sourceFactory.setLoadErrorHandlingPolicy(SideLoadedSubtitleErrorPolicy)
        return sourceFactory.createMediaSource(MediaItem.Builder().setUri(uri).build()).apply {
            addEventListener(
                Handler(Looper.getMainLooper()),
                SubtitleLoadErrorReporter(reportedTrackId, onSubtitleLoadError),
            )
        }
    }

    /**
     * Retries a failing side-loaded subtitle every few seconds, forever: never fatal (a parse
     * error included) and never enough attempts to throw, so it can never become a player error
     * — which would be indistinguishable from a video one. See the class KDoc's "Failures".
     */
    private object SideLoadedSubtitleErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long = SUBTITLE_RETRY_DELAY_MS
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
    }

    /** Hands [trackId] to [onError] for every failed load of the one source it listens to. */
    private class SubtitleLoadErrorReporter(
        private val trackId: String,
        private val onError: (trackId: String) -> Unit,
    ) : MediaSourceEventListener {
        override fun onLoadError(
            windowIndex: Int,
            mediaPeriodId: MediaSource.MediaPeriodId?,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            // The type only: the path and the message would name the file.
            Log.w(TAG, "Side-loaded subtitle failed to load (${error.javaClass.simpleName})")
            onError(trackId)
        }
    }

    /**
     * The [DataSource.Factory] loading the subtitle at [uri], or `null` when it must not be loaded.
     *
     * The network stack ([OkHttpDataSource]) cannot open `file:`, so a subtitle downloaded from
     * OpenSubtitles is read through a [FileDataSource] — but only from [onlineSubtitleDirectory]:
     * any other `file:` path (another app-private file, `..` escapes) is refused rather than read.
     */
    internal fun subtitleDataSourceFactory(uri: Uri): DataSource.Factory? {
        if (!uri.scheme.equals("file", ignoreCase = true)) return dataSourceFactory
        val path = uri.path ?: return null
        val file = File(path).canonicalFile
        return FileDataSource.Factory().takeIf { file.parentFile == onlineSubtitleDirectory.canonicalFile }
    }

    private companion object {
        const val TAG = "IptvMediaSourceFactory"
        const val SUBTITLE_RETRY_DELAY_MS = 5_000L
    }
}
