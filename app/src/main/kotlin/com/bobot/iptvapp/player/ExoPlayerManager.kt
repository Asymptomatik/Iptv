package com.bobot.iptvapp.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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

    override fun prepare(streamUrl: String, startPositionMs: Long) {
        val mediaSource = mediaSourceFactory.create(streamUrl)
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

    private fun requirePlayer(): ExoPlayer = exoPlayer ?: createPlayer().also { exoPlayer = it }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderersFactory).build()
    }
}
