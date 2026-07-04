package com.bobot.iptvapp.player

import androidx.media3.common.Player

/**
 * Owns and configures the app's video player instance.
 *
 * This interface is intentionally decoupled from the concrete Media3 `ExoPlayer` class
 * (only [androidx.media3.common.Player] — the framework-neutral playback interface — is
 * exposed). Per the approved plan's Task 12 risk note, the FFmpeg decoder extension
 * (`androidx.media3:media3-exoplayer-ffmpeg`) is **not published** on Maven Central or
 * Google's Maven repository for any Media3 version (verified against
 * `dl.google.com`/`repo1.maven.org` while implementing this task) and must be built
 * locally from the media3 source tree. Keeping call sites (Task 13's `PlayerScreen` and
 * any future consumer) programmed against [PlayerManager] and [Player] rather than
 * `ExoPlayer` directly means that swapping, wrapping, or extending the underlying
 * renderer/decoder configuration later — e.g. once the FFmpeg extension is built and
 * published to `mavenLocal()` — requires no changes outside [ExoPlayerManager].
 *
 * See ADR-004 for the full rationale.
 *
 * ## Lifecycle
 * A single [PlayerManager] instance is shared for the app's lifetime (see `PlayerModule`
 * for scope). The underlying [Player] is created lazily on first [prepare] and released
 * via [release]; calling [prepare] again after [release] transparently creates a new
 * player instance. Consumers (Task 13's `PlayerScreen`) are expected to call [release]
 * when leaving the player screen (e.g. from a `DisposableEffect`) to free the decoder
 * and surface resources promptly, rather than waiting for process death.
 */
interface PlayerManager {

    /**
     * The current [Player] instance, creating it on first access. Attach this to a
     * Media3 `PlayerView` (Task 13) to render video and drive playback controls.
     */
    val player: Player

    /**
     * Prepares [player] to play [streamUrl].
     *
     * Builds the appropriate `MediaSource` for the stream's format (HLS / MPEG-TS / MP4 —
     * see [IptvMediaSourceFactory]), sets it on [player], seeks to [startPositionMs] when
     * greater than zero (resume support, wired by Task 13), and starts playback
     * (`playWhenReady = true`).
     *
     * @param streamUrl Direct-play or HLS URL resolved by the Xtream Codes client.
     * @param startPositionMs Position to resume from, in milliseconds. `0L` (default)
     *   starts from the beginning.
     */
    fun prepare(streamUrl: String, startPositionMs: Long = 0L)

    /**
     * Releases the current [Player] instance and its underlying decoder/renderer
     * resources. Safe to call multiple times or when no player has been created yet.
     * The next [prepare] call (or [player] access) creates a fresh instance.
     */
    fun release()
}
