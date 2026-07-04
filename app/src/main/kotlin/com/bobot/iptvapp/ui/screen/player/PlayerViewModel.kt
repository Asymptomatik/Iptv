package com.bobot.iptvapp.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as ExoCommonPlayer
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state consumed by [PlayerScreen].
 *
 * @property isPlaying       Mirrors [ExoCommonPlayer.isPlaying] (kept as UI state rather than
 *                           read directly from the player on every recomposition, since Media3
 *                           only *pushes* this via [ExoCommonPlayer.Listener.onIsPlayingChanged]).
 * @property isBuffering     True while [ExoCommonPlayer.getPlaybackState] reports
 *                           [ExoCommonPlayer.STATE_BUFFERING] — drives a loading indicator.
 *                           Always `false` while [hasError] is true.
 * @property hasError        True when [ExoCommonPlayer.Listener.onPlayerError] fires — signals
 *                           [PlayerScreen] to show the French error overlay ("Impossible de lire
 *                           le flux.") and hide the buffering spinner. Cleared to `false` by
 *                           [PlayerViewModel.retry].
 * @property currentPositionMs Last polled playback position, in milliseconds.
 * @property durationMs      Last polled content duration, in milliseconds. `0L` while unknown
 *                           (e.g. live streams, or before the player has prepared metadata).
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val hasError: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Hilt ViewModel driving [PlayerScreen] — the first `@HiltViewModel` in this codebase
 * (Task 13). No prior ViewModel existed to establish a convention from; the pattern used
 * here (and recommended for future screens) is:
 *  - `@HiltViewModel` + `@Inject constructor` taking only `domain.repository` /
 *    `player` / `data.preferences` collaborators (never Android Views, `NavHostController`,
 *    or Compose types) so the ViewModel stays unit-testable on the plain JVM;
 *  - navigation arguments (here: `streamUrl`, `streamId`) are **not** read from
 *    `SavedStateHandle` — they are passed explicitly into [initialize] by the composable,
 *    mirroring how [com.bobot.iptvapp.ui.screen.DetailPlaceholderScreen] already receives
 *    `contentType` / `contentId` as plain constructor-style parameters extracted once in
 *    `AppNavGraph` via `toRoute<Player>()`. This keeps the "screens receive lambdas/params,
 *    `AppNavGraph` owns all `NavHostController`/route decoding" convention intact and avoids
 *    ever needing to import `com.bobot.iptvapp.navigation.Player` in this file.
 *  - a single `StateFlow<PlayerUiState>` exposes everything the Composable needs to render;
 *    imperative playback actions (seek, play/pause) are plain public functions.
 *
 * ## `Player` naming collision (Task 12 review carry-forward)
 * [ExoCommonPlayer] aliases `androidx.media3.common.Player`. This file never actually needs
 * `com.bobot.iptvapp.navigation.Player` (the route) — see above — so the two `Player` symbols
 * are not in fact co-imported here today. The alias is kept anyway, defensively, so that a
 * future change (e.g. reading `streamUrl`/`streamId` via `SavedStateHandle.toRoute<Player>()`
 * directly in this ViewModel instead of via [initialize] parameters) cannot silently
 * reintroduce the ambiguity the Task 12 reviewer flagged. See [PlayerScreen] for the same
 * convention applied on the Composable side, where it protects an actually-adjacent import.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val appPreferencesStore: AppPreferencesStore,
) : ViewModel() {

    private companion object {
        /** UI position/duration polling cadence — smooth enough for a progress bar. */
        const val POSITION_TICK_INTERVAL_MS = 500L

        /**
         * Progress persistence cadence. The brief asks for "every ~5-10s, or on pause/exit" —
         * 7s sits in the middle of that range.
         */
        const val PROGRESS_SAVE_INTERVAL_MS = 7_000L

        /** Step applied by [seekForward] / [seekBackward] and by D-pad seek nudges. */
        const val SEEK_STEP_MS = 10_000L
    }

    /** The shared [ExoCommonPlayer] instance to attach to Media3's `PlayerView`. */
    val player: ExoCommonPlayer
        get() = playerManager.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var contentId: String? = null
    private var contentType: ContentType? = null
    private var activeProfileId: String? = null

    /** Retained after [initialize] so that [retry] can re-prepare the same stream. */
    private var streamUrl: String? = null

    /**
     * Resume position resolved in [initialize] and retained so that [retry] passes it back
     * to [PlayerManager.prepare], ensuring the user resumes from the same offset after an
     * error rather than restarting from the beginning.
     */
    private var startPositionMs: Long = 0L

    private var initialized = false
    private var released = false
    private var progressTickerJob: Job? = null

    private val playerListener = object : ExoCommonPlayer.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            // Persist immediately on pause, in addition to the periodic ticker save —
            // covers the "sauvegarder ... à la mise en pause" requirement precisely,
            // rather than waiting for the next 7s tick.
            if (!isPlaying) {
                saveProgress()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == ExoCommonPlayer.STATE_BUFFERING,
                    durationMs = safeDuration(),
                )
            }
        }

        /**
         * Called by Media3 whenever the player transitions to the error state (e.g.
         * [com.google.android.exoplayer2.upstream.UnknownHostException] for an unreachable
         * stream URL). Sets [PlayerUiState.hasError] to `true` and clears the buffering
         * spinner so [PlayerScreen] can show the French error overlay ("Impossible de lire
         * le flux.") with Réessayer / Retour actions instead of a silent black screen.
         */
        override fun onPlayerError(error: PlaybackException) {
            _uiState.update { it.copy(hasError = true, isBuffering = false) }
        }
    }

    /**
     * Prepares playback for [url] / [streamId], resuming from any previously saved position
     * for the active profile. Safe to call multiple times (e.g. recomposition after a
     * configuration change) — only the first call has an effect, matching [PlayerManager]'s
     * "prepare once per screen visit" contract.
     *
     * The resolved [url] and start position are retained internally so that [retry] can
     * re-prepare the same stream without requiring the caller to pass them again.
     */
    fun initialize(streamUrl: String, streamId: String) {
        if (initialized) return
        initialized = true

        this.streamUrl = streamUrl
        contentId = streamId
        contentType = resolveContentTypeFromUrl(streamUrl)

        player.addListener(playerListener)

        viewModelScope.launch {
            val profileId = appPreferencesStore.getActiveProfileId()
            activeProfileId = profileId

            val resolvedType = contentType ?: ContentType.MOVIE

            // Task 23 (optional cleanup, see `saveProgress` KDoc for the full decision):
            // ContentType.LIVE is now never persisted by `saveProgress`, so `getProgress`
            // would always return null for it going forward — skip the Room query entirely
            // rather than looking up data that will never exist.
            val resolvedStartPosition = if (profileId != null && resolvedType != ContentType.LIVE) {
                playbackProgressRepository.getProgress(
                    profileId = profileId,
                    contentId = streamId,
                    contentType = resolvedType,
                )?.positionMillis ?: 0L
            } else {
                // No active profile (should not normally happen once profile selection —
                // Task 16 — is wired, but guarded here so playback still works standalone),
                // or LIVE content (see above).
                0L
            }

            startPositionMs = resolvedStartPosition
            playerManager.prepare(streamUrl = streamUrl, startPositionMs = resolvedStartPosition)
            _uiState.update { it.copy(currentPositionMs = resolvedStartPosition) }
            startProgressTicker()
        }
    }

    /**
     * Clears the error state and re-prepares the retained stream from the last known resume
     * position. Intended to be wired to the "Réessayer" button on the error overlay in
     * [PlayerScreen].
     *
     * ## Guard notes
     * Unlike [initialize], this function is deliberately **not** gated by [initialized] — an
     * error may occur at any point after initialization, and re-preparing must still work. The
     * [released] guard is checked so that a race between user-initiated retry and
     * [releasePlayer] cannot call into a released [PlayerManager]. If [streamUrl] is null
     * (retry called before [initialize] ran — should not happen in normal flow), the call is
     * silently ignored.
     */
    fun retry() {
        if (released) return
        val url = streamUrl ?: return

        _uiState.update { it.copy(hasError = false, isBuffering = true) }
        playerManager.prepare(streamUrl = url, startPositionMs = startPositionMs)
    }

    /** Toggles play/pause — wired to the Composable's central play/pause control. */
    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Seeks forward by [SEEK_STEP_MS], clamped to the known content duration. */
    fun seekForward() {
        seekTo(player.currentPosition + SEEK_STEP_MS)
    }

    /** Seeks backward by [SEEK_STEP_MS], clamped to zero. */
    fun seekBackward() {
        seekTo(player.currentPosition - SEEK_STEP_MS)
    }

    /**
     * Seeks to an absolute [positionMs], clamped to `[0, duration]` when the duration is
     * known. Used both by the progress bar (drag-to-seek) and by [seekForward] / [seekBackward].
     */
    fun seekTo(positionMs: Long) {
        val duration = player.duration
        val upperBound = if (duration > 0) duration else Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, upperBound)
        player.seekTo(clamped)
        _uiState.update { it.copy(currentPositionMs = clamped) }
    }

    /**
     * Releases the underlying player and performs a final progress save. Called from
     * [PlayerScreen]'s `DisposableEffect.onDispose` — see [PlayerManager] KDoc for the
     * lifecycle contract this fulfils.
     *
     * ## Idempotency (Task 13 review fix)
     * Navigation Compose clears the destination's `ViewModelStore` (triggering [onCleared])
     * around the same time the composable leaves composition (triggering `PlayerScreen`'s
     * `DisposableEffect.onDispose`), so this is called **twice** on the ordinary "press back
     * out of the player" path. Without a guard, the second call would re-enter
     * [ExoPlayerManager][com.bobot.iptvapp.player.ExoPlayerManager] through the [player]
     * getter (`playerManager.player` → `requirePlayer()`), which — because
     * [PlayerManager] is `@Singleton`-scoped and its backing field was just nulled out by the
     * first call's [PlayerManager.release] — silently **creates a brand-new, unprepared
     * `ExoPlayer` instance**. That fresh player reports `currentPosition = 0`, so the second
     * [saveProgress] call would overwrite the correct resume position (just persisted by the
     * first call) with `0` under the same profile/content composite key. The `released` flag
     * makes every call after the first a no-op, mirroring the [initialized] guard on
     * [initialize].
     */
    fun releasePlayer() {
        if (released) return
        released = true

        saveProgress()
        progressTickerJob?.cancel()
        player.removeListener(playerListener)
        playerManager.release()
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = viewModelScope.launch {
            var msSinceLastSave = 0L
            while (isActive) {
                delay(POSITION_TICK_INTERVAL_MS)

                val isPlayingNow = player.isPlaying
                _uiState.update {
                    it.copy(
                        currentPositionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = safeDuration(),
                    )
                }

                if (isPlayingNow) {
                    msSinceLastSave += POSITION_TICK_INTERVAL_MS
                    if (msSinceLastSave >= PROGRESS_SAVE_INTERVAL_MS) {
                        msSinceLastSave = 0L
                        saveProgress()
                    }
                }
            }
        }
    }

    /**
     * Persists the current playback position under the active profile/content composite key.
     *
     * ## LIVE exclusion (Task 23 decision)
     * [ContentType.LIVE] is deliberately excluded from playback-progress persistence — this
     * was an explicitly deferred decision since Task 16/18 ("Exclure ContentType.LIVE de la
     * sauvegarde de progression de lecture — à trancher explicitement à la Tâche 23"), now
     * resolved here: resuming a live broadcast at a stale saved position provides no real
     * value — by the time a user returns, the live stream has moved on, and this app plays
     * live channels via a direct URL with no time-shift/catch-up/seek-back support. Persisting
     * a "position" for live content is therefore meaningless and would only pollute the
     * Continue Watching row (see [com.bobot.iptvapp.ui.screen.home.HomeViewModel]) with
     * useless rows. See also [initialize]'s matching skip of the `getProgress` lookup for LIVE.
     */
    private fun saveProgress() {
        val profileId = activeProfileId ?: return
        val id = contentId ?: return
        val type = contentType ?: return

        if (type == ContentType.LIVE) return

        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = safeDuration()

        viewModelScope.launch {
            playbackProgressRepository.upsertProgress(
                PlaybackProgress(
                    contentId = id,
                    contentType = type,
                    positionMillis = position,
                    durationMillis = duration,
                    lastUpdatedMillis = System.currentTimeMillis(),
                    profileId = profileId,
                ),
            )
        }
    }

    /** [ExoCommonPlayer.getDuration] reports `C.TIME_UNSET` (a large negative Long) when
     *  unknown (e.g. live streams, or before metadata loads) — clamp that to `0L` so callers
     *  never need to special-case the sentinel value. */
    private fun safeDuration(): Long = player.duration.coerceAtLeast(0L)
}

/**
 * Resolves the [ContentType] a stream belongs to directly from its URL path, e.g.
 * `.../live/{user}/{pass}/{id}.ts` → [ContentType.LIVE] (see
 * [com.bobot.iptvapp.data.remote.XtreamUrlBuilder] for the URL shapes this matches).
 *
 * Pure/framework-free on purpose (mirrors [com.bobot.iptvapp.player.StreamTypeResolver]) so
 * it is unit-testable on the plain JVM without mocking [PlayerViewModel]'s collaborators.
 *
 * ## Why URL inference instead of a navigation argument
 * [com.bobot.iptvapp.navigation.Player] (the route) only carries `streamUrl` and `streamId`
 * — it does not carry a content type. Adding one would mean changing the route's shape and
 * every caller that constructs it (currently only the Task 18/19 detail-screen placeholder),
 * which is out of scope for Task 13 ("seulement PlayerScreen et son ViewModel, plus le
 * rewiring minimal d'AppNavGraph.kt pour cette seule route"). Inferring the type from the
 * URL — which Xtream Codes' own predictable path shape makes reliable — avoids that
 * route-wide change while still giving [PlaybackProgress] the `contentType` its composite
 * key requires.
 *
 * Falls back to [ContentType.MOVIE] when neither `/live/` nor `/series/` appears in the URL
 * (i.e. the `/movie/` case, or any unrecognised shape) — VOD movies are the most common case
 * among the three when the pattern is ambiguous.
 */
internal fun resolveContentTypeFromUrl(streamUrl: String): ContentType = when {
    "/live/" in streamUrl -> ContentType.LIVE
    "/series/" in streamUrl -> ContentType.SERIES
    else -> ContentType.MOVIE
}
