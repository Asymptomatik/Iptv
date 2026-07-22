package com.bobot.iptvapp.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.util.LanguageLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Default [PlayerManager] implementation, backed by Media3 `ExoPlayer`.
 *
 * ## Renderer / decoder configuration
 * [DefaultRenderersFactory] is configured with
 * [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON] rather than `PREFER`:
 * - `ON` registers extension (software) renderers as a **fallback**, tried only after the
 *   platform's hardware/software decoders fail to handle a given format. This matches the
 *   brief's requirement exactly: "activer le decoder FFmpeg en fallback quand le decoder
 *   matériel/logiciel standard ne supporte pas le codec".
 * - `PREFER` would route *every* matching codec through the extension decoder first,
 *   including mainstream formats (H.264/AAC) that the platform already decodes in
 *   hardware — needlessly forcing software decoding, hurting battery life and
 *   performance/thermals, especially relevant on Android TV set-top boxes.
 *
 * `ON` is therefore the correct choice: it only pays the software-decode cost for the
 * exotic codecs (e.g. certain AC3/EAC3/DTS audio tracks, or unusual video codecs) that
 * IPTV/Xtream feeds occasionally use and that hardware decoders on some devices reject.
 *
 * ## FFmpeg extension status (Task 12)
 * As documented in [PlayerManager] and ADR-004, the `media3-exoplayer-ffmpeg` artifact is
 * **not on the classpath** in this build — it is not published on Maven Central / Google
 * Maven and was intentionally *not* added as a dependency (see `app/build.gradle.kts` and
 * `gradle/libs.versions.toml` comments). [DefaultRenderersFactory] with
 * `EXTENSION_RENDERER_MODE_ON` is still configured now so that once the extension is built
 * locally and added to the classpath, [DefaultRenderersFactory]'s reflection-based lookup
 * (`androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer`) picks it up automatically —
 * **no code change will be required in this class**. Until then, this setting is a no-op:
 * only the platform's built-in renderers are available.
 *
 * ## Scope
 * Bound `@Singleton` (see `PlayerModule`) — one [PlayerManager] for the app's process
 * lifetime, matching every other singleton-scoped collaborator in this codebase. Only one
 * screen plays video at a time, so a single shared [ExoPlayer] instance (created lazily,
 * torn down via [release]) avoids the added complexity of a narrower Hilt scope tied to
 * the player screen's lifecycle.
 */
class ExoPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaSourceFactory: IptvMediaSourceFactory,
) : PlayerManager {

    private var exoPlayer: ExoPlayer? = null

    // `Player` (the framework-neutral interface exposed by [PlayerManager]) does not
    // declare `setMediaSource(MediaSource)` — that method only exists on the concrete
    // `ExoPlayer` interface. [requirePlayer] therefore returns the concrete type for
    // internal use (building/attaching the MediaSource in [prepare]), while the public
    // [player] property upcasts it to `Player` for external consumers (Task 13's
    // `PlayerScreen`), preserving the decoupling documented in [PlayerManager].
    override val player: Player
        get() = requirePlayer()

    override fun prepare(streamUrl: String, startPositionMs: Long, externalSubtitles: List<ExternalSubtitle>) {
        val mediaSource = mediaSourceFactory.create(streamUrl, externalSubtitles)
        requirePlayer().apply {
            setMediaSource(mediaSource)
            if (startPositionMs > 0L) {
                seekTo(startPositionMs)
            }
            prepare()
            playWhenReady = true
        }
    }

    override fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // ── Track exposure & selection (Task 2) ─────────────────────────────────
    //
    // Every method below reads a fresh `Player.getCurrentTracks()` snapshot rather than
    // caching it, so callers never see stale data even if they hold on to a `PlayerManager`
    // reference across track changes (e.g. after `onTracksChanged`). `TrackGroup`/`Format`
    // instances are Media3-internal and not exposed outside this class — only the
    // framework-neutral [PlayerTrack] is, per [PlayerManager]'s decoupling philosophy.
    //
    // ## Track identity
    // [PlayerTrack.id] is derived from `Format.id` when the container provides one (HLS
    // renditions and DASH adaptation sets commonly do), which is stable across snapshots for
    // the lifetime of a given stream. When absent, it falls back to a position-based id
    // (`"audio-<groupIndex>-<trackIndex>"`) scoped to tracks of the same type, which is only
    // stable as long as the track list itself doesn't change shape — an acceptable trade-off
    // given [PlayerManager.getAudioTracks]/[PlayerManager.getSubtitleTracks]'s documented
    // snapshot contract. [selectAudioTrack]/[selectSubtitleTrack] re-derive this same id for
    // every candidate track against the *current* snapshot before selecting, so a mismatch
    // (stale id) is simply treated as "not found" — a safe no-op — rather than selecting the
    // wrong track.

    override fun getAudioTracks(): List<PlayerTrack> =
        tracksOfType(C.TRACK_TYPE_AUDIO, PlayerTrackType.AUDIO)

    override fun getSubtitleTracks(): List<PlayerTrack> =
        tracksOfType(C.TRACK_TYPE_TEXT, PlayerTrackType.SUBTITLE)

    override fun selectAudioTrack(trackId: String) {
        selectTrack(trackId, C.TRACK_TYPE_AUDIO, PlayerTrackType.AUDIO)
    }

    override fun selectSubtitleTrack(trackId: String) {
        selectTrack(trackId, C.TRACK_TYPE_TEXT, PlayerTrackType.SUBTITLE)
    }

    override fun disableSubtitles() {
        val player = requirePlayer()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** The [Tracks.Group]s of [trackType] in the player's current [Tracks] snapshot. */
    private fun typeGroups(trackType: Int): List<Tracks.Group> =
        requirePlayer().currentTracks.groups.filter { it.type == trackType }

    /**
     * One `(groupIndex, trackIndex)` location in a [Tracks] snapshot of a given track type,
     * paired with its owning [Tracks.Group] and resolved [Format].
     *
     * Single place where the `(group, trackIndex)` walk shared by [tracksOfType] and
     * [findTrackLocation] is defined, so the id-derivation inputs ([trackId]'s `groupIndex`/
     * `trackIndex` arguments) can never drift between the two call sites.
     */
    private data class IndexedFormat(
        val groupIndex: Int,
        val trackIndex: Int,
        val group: Tracks.Group,
        val format: Format,
    )

    /** Every [IndexedFormat] of [trackType] in the current [Tracks] snapshot, in group order. */
    private fun indexedFormats(trackType: Int): List<IndexedFormat> =
        typeGroups(trackType).flatMapIndexed { groupIndex, group ->
            (0 until group.length).map { trackIndex ->
                IndexedFormat(groupIndex, trackIndex, group, group.getTrackFormat(trackIndex))
            }
        }

    /** Builds the [PlayerTrack] list for [trackType], labelled/identified per the class KDoc. */
    private fun tracksOfType(trackType: Int, playerTrackType: PlayerTrackType): List<PlayerTrack> =
        indexedFormats(trackType).map { indexed ->
            PlayerTrack(
                id = trackId(indexed.format, playerTrackType, indexed.groupIndex, indexed.trackIndex),
                label = trackLabel(indexed.format, playerTrackType, indexed.trackIndex),
                languageCode = indexed.format.language,
                isSelected = indexed.group.isTrackSelected(indexed.trackIndex),
                type = playerTrackType,
            )
        }

    /**
     * Applies [trackId] as the sole selection for [trackType], leaving playback untouched
     * (safe no-op) when [trackId] cannot be found in the current [Tracks] snapshot.
     */
    private fun selectTrack(trackId: String, trackType: Int, playerTrackType: PlayerTrackType) {
        val (mediaTrackGroup, trackIndex) =
            findTrackLocation(trackId, trackType, playerTrackType) ?: return

        val player = requirePlayer()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(mediaTrackGroup, trackIndex))
            .build()
    }

    /** Finds the `(TrackGroup, trackIndex)` matching [trackId] in the current snapshot, or `null`. */
    private fun findTrackLocation(
        trackId: String,
        trackType: Int,
        playerTrackType: PlayerTrackType,
    ): Pair<TrackGroup, Int>? =
        indexedFormats(trackType)
            .firstOrNull { indexed ->
                trackId(indexed.format, playerTrackType, indexed.groupIndex, indexed.trackIndex) == trackId
            }
            ?.let { it.group.mediaTrackGroup to it.trackIndex }

    /** See "Track identity" in the class KDoc above. */
    private fun trackId(format: Format, playerTrackType: PlayerTrackType, groupIndex: Int, trackIndex: Int): String {
        val formatId = format.id
        if (!formatId.isNullOrBlank()) return formatId
        val typePrefix = playerTrackType.name.lowercase()
        return "$typePrefix-$groupIndex-$trackIndex"
    }

    /**
     * Human-readable label for [format]: the container-provided label when present, else a
     * language display name resolved via [LanguageLabel], else a generic positional fallback
     * (e.g. "Audio 2") — never blank.
     *
     * There is deliberately no third "raw language code" branch between the two above: since
     * [LanguageLabel.forCode] already returns `null` only for a `null`/blank/`"und"`
     * ("undetermined") [Format.language], the only way to reach the positional fallback is
     * exactly one of those three — so a track tagged `"und"` (or with no language at all) and no
     * container label correctly gets the generic label (e.g. "Audio 2"), never the raw string
     * `"und"` (a track with an actually-resolvable — even if unmappable/garbage — language code
     * still gets [LanguageLabel.forCode]'s own raw-code fallback, which is the same string one
     * would have gotten from a redundant third branch here anyway).
     */
    private fun trackLabel(format: Format, playerTrackType: PlayerTrackType, trackIndex: Int): String {
        val containerLabel = format.label
        if (!containerLabel.isNullOrBlank()) return containerLabel

        val languageLabel = LanguageLabel.forCode(format.language)
        if (!languageLabel.isNullOrBlank()) return languageLabel

        val typeName = when (playerTrackType) {
            PlayerTrackType.AUDIO -> "Audio"
            PlayerTrackType.SUBTITLE -> "Subtitle"
        }
        return "$typeName ${trackIndex + 1}"
    }

    private fun requirePlayer(): ExoPlayer = exoPlayer ?: createPlayer().also { exoPlayer = it }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderersFactory).build()
    }
}
