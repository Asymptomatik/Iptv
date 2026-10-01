package com.bobot.iptvapp.ui.screen.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.Player as ExoCommonPlayer
import com.bobot.iptvapp.data.logout.DownloadedSubtitlePurger
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesClient
import com.bobot.iptvapp.data.remote.opensubtitles.OnlineSubtitleVisit
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesDownloader
import com.bobot.iptvapp.domain.logout.LogoutPurgeState
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.player.PlayerManager
import com.bobot.iptvapp.player.PlayerTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * @property isLive         `true` when the stream has no seekable content window: a live channel,
 *                           or any media item Media3 reports as live. Everything time-related in
 *                           [PlayerScreen] keys off this — see [PlayerViewModel.isLiveStream] for
 *                           why the flag is needed at all (QA finding N5).
 * @property audioTracks     Snapshot of the current stream's audio tracks (from
 *                           [PlayerManager.getAudioTracks]), refreshed on every Media3
 *                           [ExoCommonPlayer.Listener.onTracksChanged] callback and immediately
 *                           after [PlayerViewModel.selectAudioTrack] (see [PlayerViewModel]'s
 *                           `refreshTracks`). Empty before a stream is prepared, or when the
 *                           stream exposes no alternative audio track. Exactly one entry has
 *                           [PlayerTrack.isSelected] `true` once a stream is prepared — Media3
 *                           always applies some audio track when at least one exists.
 * @property subtitleTracks  Snapshot of the current stream's subtitle tracks — embedded plus any
 *                           usable best-effort external subtitle (see [PlayerManager.prepare]'s
 *                           `externalSubtitles` parameter) — from [PlayerManager.getSubtitleTracks],
 *                           refreshed the same way as [audioTracks]. Unlike audio, this list may
 *                           have **no** entry with [PlayerTrack.isSelected] `true`: that state
 *                           *is* "subtitles disabled" — deliberately no separate
 *                           `subtitlesEnabled` flag is kept here, since [PlayerManager.disableSubtitles]
 *                           clears every subtitle track's selection, making "disabled" and "no
 *                           selected entry in this list" the exact same state Media3 itself
 *                           exposes. Consumers can derive "disabled" as
 *                           `subtitleTracks.none { it.isSelected }`.
 * @property onlineSubtitles The OpenSubtitles search and pick — see [OnlineSubtitlesUiState].
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val hasError: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLive: Boolean = false,
    val audioTracks: List<PlayerTrack> = emptyList(),
    val subtitleTracks: List<PlayerTrack> = emptyList(),
    val onlineSubtitles: OnlineSubtitlesUiState = OnlineSubtitlesUiState(),
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
    private val catalogRepository: CatalogRepository,
    private val openSubtitlesClient: OpenSubtitlesClient,
    private val openSubtitlesDownloader: OpenSubtitlesDownloader,
    private val logoutCoordinator: LogoutCoordinator,
    private val downloadedSubtitlePurger: DownloadedSubtitlePurger,
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

        /**
         * Maximum time the player is allowed to remain continuously in
         * [ExoCommonPlayer.STATE_BUFFERING] before this ViewModel treats it as an application
         * stall rather than legitimate buffering, and surfaces the existing error overlay.
         *
         * ## Why this exists
         * The shared `OkHttpClient` (see `NetworkModule`) already has explicit connect/read/write
         * timeouts, but those only guard *HTTP request* stalls. A real VOD stream can keep
         * delivering bytes — just too slowly/intermittently to ever satisfy ExoPlayer's minimum
         * rebuffer threshold — in which case the player never reaches [ExoCommonPlayer.STATE_READY]
         * and never calls [ExoCommonPlayer.Listener.onPlayerError] either, so neither the OkHttp
         * timeouts nor the existing `onPlayerError` handler ever fire. Without this timer, the
         * user is stuck on an infinite loading spinner. 20s is generous enough to absorb normal
         * VOD start-up buffering and mid-playback rebuffers (e.g. a brief network hiccup or a
         * seek) while still failing fast enough to be actionable via "Réessayer".
         *
         * ## LIVE streams are intentionally included
         * Unlike [saveProgress]'s LIVE exclusion, this timer applies to every [ContentType],
         * including LIVE: a live channel that never starts is exactly as broken from the user's
         * perspective as a movie that never starts, and should surface the same error overlay
         * rather than spin forever. Live-edge/start-up latency for a healthy stream is expected
         * to resolve in a few seconds, well under this threshold, so no live-specific grace
         * period is needed.
         */
        const val STALL_DETECTION_TIMEOUT_MS = 20_000L

        /**
         * Upper bound on the best-effort external-subtitle metadata fetch performed by
         * [resolveExternalSubtitles] as part of [initialize], via
         * [CatalogRepository.getMovieDetail]. Chosen well under [STALL_DETECTION_TIMEOUT_MS]
         * (20s) so that even a worst-case timeout here can never itself be responsible for
         * tripping the stall watchdog, while still being generous enough to absorb a normal
         * `get_vod_info` round-trip. On timeout, [resolveExternalSubtitles] falls back to
         * `emptyList()` and playback proceeds with only the stream's embedded tracks — see its
         * KDoc for the full best-effort contract.
         */
        const val EXTERNAL_SUBTITLES_TIMEOUT_MS = 4_000L

        /** Prefix of the track id given to a subtitle picked online — see [selectOnlineSubtitle]. */
        const val ONLINE_TRACK_ID_PREFIX = "online-subtitle-"
    }

    /**
     * The shared [ExoCommonPlayer] instance to attach to Media3's `PlayerView` — `null` once this
     * screen is released or its session revoked (see [inSession]): reading [PlayerManager.player]
     * then would build a fresh player behind the logout purge, or hand over the next account's.
     */
    val player: ExoCommonPlayer?
        get() = if (!released && inSession()) activePlayer else null

    /** [PlayerManager.player], for this class's own reads — only ever once [inSession] allowed them. */
    private val activePlayer: ExoCommonPlayer
        get() = playerManager.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var contentId: String? = null
    private var contentType: ContentType? = null
    private var activeProfileId: String? = null

    /** Retained after [initialize] so that [retry] can re-prepare the same stream. */
    private var streamUrl: String? = null

    /**
     * Resume position resolved in [initialize], moved on by every later prepare, and passed back
     * to [PlayerManager.prepare] by [retry] when the player no longer holds the media to read
     * the reached position from — so an error never restarts the video from the beginning.
     */
    private var startPositionMs: Long = 0L

    /**
     * Best-effort external subtitles resolved in [initialize] (see [resolveExternalSubtitles])
     * and retained so that [retry] re-prepares with the same side-loaded tracks instead of
     * silently dropping them back to [PlayerManager.prepare]'s `emptyList()` default.
     */
    private var resolvedExternalSubtitles: List<ExternalSubtitle> = emptyList()

    /**
     * Metadata for an online subtitle search, as handed over by the route in [initialize].
     * Always `null` for live streams and for routes that carried none.
     */
    var subtitleSearchContext: SubtitleSearchContext? = null
        private set

    /**
     * The subtitle picked online, as last handed to [PlayerManager.prepare] after
     * [resolvedExternalSubtitles] — kept so that [retry] re-prepares with it too. At most one: a
     * new pick replaces it rather than stacking tracks.
     */
    private var onlineExternalSubtitle: ExternalSubtitle? = null

    /**
     * Id of the online track to switch on as soon as a tracks snapshot carries it — see
     * [switchToPendingOnlineTrack]. Cleared once switched, or when the user picks a track by hand.
     */
    private var pendingOnlineTrackId: String? = null

    /**
     * Whether the user's subtitle choice is still [onlineExternalSubtitle]: set by a pick, cleared
     * by any other track or by switching subtitles off. [retry] switches the track back on only then.
     */
    private var onlineTrackChosen = false

    /** Numbers each pick and retry of one, so its track id can never match a snapshot of an earlier prepare. */
    private var onlinePickCount = 0

    private var onlineSearchJob: Job? = null
    private var onlineApplyJob: Job? = null

    /** The re-prepare without a failed online track — see [onSideLoadedSubtitleError]. */
    private var onlineFallbackJob: Job? = null

    /**
     * Counts every [PlayerManager.prepare] of this visit, so a fallback queued for one media can
     * tell a retry or a new pick has replaced it since.
     */
    private var preparedMedia = 0

    /** Owns the files this visit's picks download; [releasePlayer] closes it, deleting them. */
    private val onlineSubtitleVisit = OnlineSubtitleVisit()

    /**
     * [initialize]'s start-up, up to the first prepare. A pick waits for it (see
     * [applyOnlineSubtitle]); leaving the player cancels it.
     */
    private var startUpJob: Job? = null

    /**
     * The session this visit plays, searches and saves for: [LogoutCoordinator.purgeAttempts] and
     * [LogoutCoordinator.sessionGeneration] as they were when the route created this ViewModel —
     * the attempts first, so a purge landing between the two reads revokes this visit. Never read
     * again: a logout between construction and [initialize] must revoke the route, not hand it the
     * next session. See [inSession].
     */
    private val sessionPurgeAttempts = logoutCoordinator.purgeAttempts
    private val sessionGeneration = logoutCoordinator.sessionGeneration

    private var retryJob: Job? = null

    private var initialized = false
    private var released = false
    private var progressTickerJob: Job? = null

    /**
     * Cancelable "application stall" watchdog — see [STALL_DETECTION_TIMEOUT_MS] KDoc for why
     * this exists. Started by [onPlaybackStateChanged] whenever the player enters
     * [ExoCommonPlayer.STATE_BUFFERING], and cancelled as soon as it leaves that state (in
     * particular on [ExoCommonPlayer.STATE_READY], so a brief/legitimate rebuffer never trips
     * it). Mirrors [progressTickerJob]'s cancelable-`Job` pattern rather than introducing a new
     * timer mechanism (no `Handler`/`java.util.Timer`).
     */
    private var stallDetectionJob: Job? = null

    // Media3 may still call back once a logout purge has stopped the player: every callback that
    // reads the player or saves goes through [inSession], directly or via [saveProgress] /
    // [refreshTracks].
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
            if (!inSession()) return
            _uiState.update {
                it.copy(
                    isBuffering = playbackState == ExoCommonPlayer.STATE_BUFFERING,
                    durationMs = safeDuration(),
                    isLive = isLiveStream(),
                )
            }

            // Stall watchdog: (re)start the timer on every entry into STATE_BUFFERING, and
            // cancel it on any other state (STATE_READY in particular — see
            // `startStallDetection`/`cancelStallDetection` KDoc for the full contract).
            if (playbackState == ExoCommonPlayer.STATE_BUFFERING) {
                startStallDetection()
            } else {
                cancelStallDetection()
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
            cancelStallDetection()
            _uiState.update { it.copy(hasError = true, isBuffering = false) }
        }

        /**
         * Media3's push signal that the current [Tracks] snapshot changed — fires once tracks
         * become known after [PlayerManager.prepare], and again after any track-selection
         * override is applied (e.g. via [selectAudioTrack] / [selectSubtitleTrack] /
         * [disableSubtitles], or a mid-stream HLS rendition change). [refreshTracks] re-reads
         * [PlayerManager.getAudioTracks] / [PlayerManager.getSubtitleTracks] so
         * [PlayerUiState.audioTracks] / [PlayerUiState.subtitleTracks] stay in sync without this
         * ViewModel needing to inspect [tracks] itself.
         */
        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks()
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
    fun initialize(
        streamUrl: String,
        streamId: String,
        subtitleSearchContext: SubtitleSearchContext? = null,
    ) {
        if (initialized) return
        initialized = true
        // A logout since this route was created: nothing is retained — no stream to retry, no
        // title to search, no profile to save for — and the player is never read, since reading it
        // behind the purge's stop would build a fresh one.
        if (!inSession()) return

        this.streamUrl = streamUrl
        contentId = streamId
        contentType = resolveContentTypeFromUrl(streamUrl)
        this.subtitleSearchContext = subtitleSearchContext.takeIf { contentType != ContentType.LIVE }
        updateOnline { it.copy(isAvailable = this.subtitleSearchContext != null) }

        activePlayer.addListener(playerListener)
        playerManager.setSideLoadedSubtitleErrorListener { onSideLoadedSubtitleError(it) }

        startUpJob = viewModelScope.launch {
            val profileId = appPreferencesStore.getActiveProfileId()

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

            // Best-effort external-subtitle resolution (Task 4) — must complete (or time out)
            // before `prepare()` below, since `prepare()` is the only place these can be
            // side-loaded (Task 3's `PlayerManager.prepare` contract). See
            // `resolveExternalSubtitles` KDoc for the MOVIE-only gating and timeout rationale.
            val externalSubtitles = resolveExternalSubtitles(resolvedType, streamId)

            // Same gate as a pick (see `applyOnlineSubtitle`), for the same reason: the waits
            // above run outside the purge's lock, so a logout can stop playback and purge while
            // the screen stays open. Checked and prepared in one go — a purge completed before is
            // refused, one starting after stops this playback. [activeProfileId] is only set in
            // here, so a refused start-up never saves a position for the purged profile either.
            logoutCoordinator.runInSession(sessionGeneration) {
                if (!inSession()) return@runInSession
                activeProfileId = profileId
                resolvedExternalSubtitles = externalSubtitles
                startPositionMs = resolvedStartPosition
                preparedMedia++
                playerManager.prepare(
                    streamUrl = streamUrl,
                    startPositionMs = resolvedStartPosition,
                    externalSubtitles = externalSubtitles,
                )
                _uiState.update { it.copy(currentPositionMs = resolvedStartPosition) }
                startProgressTicker()
            }
        }
    }

    /**
     * Clears the error state and re-prepares the retained stream where playback had reached (or
     * the last known resume position), paused if it was, the online track switched back on if it
     * was still the user's choice. Intended to be wired to the "Réessayer" button on the error
     * overlay in [PlayerScreen].
     *
     * ## Guard notes
     * Unlike [initialize], this function is deliberately **not** gated by [initialized] — an
     * error may occur at any point after initialization, and re-preparing must still work. The
     * [released] guard is checked so that a race between user-initiated retry and
     * [releasePlayer] cannot call into a released [PlayerManager]. If [streamUrl] is null
     * (retry called before [initialize] ran — should not happen in normal flow), the call is
     * silently ignored.
     *
     * ## Logout purge
     * The error overlay stays up through a logout, and the stream URL carries the previous
     * account's Xtream credentials. So the re-prepare goes through the same gate as [initialize]'s,
     * for the same session: checked and prepared in one go under the purge's lock — a purge
     * completed or started before is refused, one starting after stops this playback.
     */
    fun retry() {
        if (released) return
        val url = streamUrl ?: return
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            logoutCoordinator.runInSession(sessionGeneration) {
                if (!released && inSession()) reprepare(url)
            }
        }
    }

    private fun reprepare(url: String) {
        // Cancel any stall watchdog left over from the previous attempt so it cannot fire
        // against the new attempt's playback (e.g. right after this retry succeeds and starts
        // playing normally) — see `stallDetectionJob` KDoc.
        cancelStallDetection()
        _uiState.update { it.copy(hasError = false, isBuffering = true) }

        // Explicitly (re)arm the watchdog here rather than relying solely on the next
        // `onPlaybackStateChanged(STATE_BUFFERING)` callback: Media3 only invokes that listener
        // when the *integer* playback state actually changes. If the stream was already stuck
        // in continuous STATE_BUFFERING when the previous watchdog fired `hasError = true` —
        // exactly the scenario this feature fixes — a fresh `prepare()` call below can leave the
        // player in STATE_BUFFERING with no detectable value change, so the callback never
        // refires and a purely callback-driven watchdog would never be armed again, silently
        // reproducing the original infinite-spinner bug after "Réessayer". The `isActive` guard
        // in `startStallDetection` (plus `hasError` having just been reset to `false` above)
        // makes this call safe even if the Media3 callback *does* also fire independently —
        // no duplicate timer is created.
        startStallDetection()

        // Where playback had reached, not where it last started — as long as the player still
        // holds this media. A live stream always restarts at its live edge.
        if (contentType != ContentType.LIVE && activePlayer.currentMediaItem != null) {
            startPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
        }
        val keepPlaying = activePlayer.playWhenReady
        _uiState.update { it.copy(currentPositionMs = startPositionMs) }

        // Media3 ties a selection to the track groups it was made on, and the retried media has
        // new ones: the online track the user had on is switched on again, under a fresh id so
        // that only the retried media's snapshot can match it (see `switchToPendingOnlineTrack`).
        onlineExternalSubtitle?.let { online ->
            onlinePickCount++
            val trackId = "$ONLINE_TRACK_ID_PREFIX$onlinePickCount"
            onlineExternalSubtitle = online.copy(trackId = trackId)
            pendingOnlineTrackId = trackId.takeIf { onlineTrackChosen }
        }

        // Re-passes the retained external subtitles (see `currentExternalSubtitles`) rather than
        // relying on `prepare`'s `emptyList()` default, so a retry does not silently drop an
        // already-resolved best-effort track, nor one picked online.
        preparedMedia++
        playerManager.prepare(
            streamUrl = url,
            startPositionMs = startPositionMs,
            externalSubtitles = currentExternalSubtitles(),
        )
        // `prepare` always resumes playback; a video the user had paused stays paused.
        if (!keepPlaying) activePlayer.pause()
    }

    // ─── Online subtitles (OpenSubtitles) ────────────────────────────────────────

    /**
     * Opens the search panel, searching unless results (or a search) are already there. A no-op
     * when [OnlineSubtitlesUiState.isAvailable] is `false` — in particular for any live channel.
     *
     * ## Logout purge
     * Also a no-op once this visit's session is revoked (see [inSession]): the search sends the
     * previous account's title to OpenSubtitles. A search already on its way is not published —
     * see [startOnlineSearch].
     */
    fun openOnlineSubtitleSearch() {
        if (released || subtitleSearchContext == null || !inSession()) return
        updateOnline { it.copy(isPanelOpen = true) }
        val search = _uiState.value.onlineSubtitles.search
        if (search is OnlineSubtitleSearchState.Idle || search is OnlineSubtitleSearchState.Failed) {
            startOnlineSearch()
        }
    }

    /** Searches again, dropping whatever the previous search would still answer. */
    fun retryOnlineSubtitleSearch() {
        if (released || subtitleSearchContext == null || !inSession()) return
        updateOnline { it.copy(isPanelOpen = true) }
        startOnlineSearch()
    }

    /**
     * Closes the panel. A search still running is cancelled and forgotten; results already shown
     * are kept for the next opening. A pick already made keeps going — the user asked for it.
     */
    fun closeOnlineSubtitleSearch() {
        val searching = onlineSearchJob?.isActive == true
        onlineSearchJob?.cancel()
        onlineSearchJob = null
        updateOnline {
            it.copy(
                isPanelOpen = false,
                search = if (searching) OnlineSubtitleSearchState.Idle else it.search,
            )
        }
    }

    /**
     * Downloads [subtitle] and re-prepares the stream with it, the Xtream subtitles kept, at the
     * position playback has reached *when the file arrives* — the download takes seconds, and
     * resuming from where the user clicked would replay them. Play/pause is carried over, and
     * the new track is switched on once Media3 reports it (see [switchToPendingOnlineTrack]).
     *
     * A newer pick supersedes this one; a failure only sets
     * [OnlineSubtitlesUiState.applyError] — playback is never stopped for it.
     *
     * ## Logout purge
     * The file lands in the directory the logout purge empties, so a pick must never write
     * behind a purge. The download cannot run under the purge's lock (it would block a logout
     * behind the network), so the pick uses the same generation check as sign-in does (see
     * [LogoutCoordinator]): it is refused outright while a purge is running or owed, the file
     * store writes only inside [LogoutCoordinator.runInSession] for that generation (a file
     * written before a purge is the purge's to delete, none can land after it), and the file is
     * played only under that same gate. The file belongs to [onlineSubtitleVisit]: kept for any
     * retry or later pick, deleted when the player is left — never a purge of the directory, which
     * may hold another player's or, after a logout, the next account's subtitles.
     */
    fun selectOnlineSubtitle(subtitle: OnlineSubtitle) {
        if (released || subtitleSearchContext == null) return
        onlineApplyJob?.cancel()
        updateOnline { it.copy(applyingFileId = subtitle.fileId, applyError = null) }
        onlineApplyJob = viewModelScope.launch { applyOnlineSubtitle(subtitle) }
    }

    fun dismissOnlineSubtitleError() {
        updateOnline { it.copy(applyError = null) }
    }

    /** Toggles play/pause — wired to the Composable's central play/pause control. */
    fun togglePlayPause() {
        val player = player ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /**
     * Seeks forward by [SEEK_STEP_MS], clamped to the known content duration.
     * A no-op on a live stream — see [seekTo].
     */
    fun seekForward() {
        val player = player ?: return
        seekTo(player.currentPosition + SEEK_STEP_MS)
    }

    /** Seeks backward by [SEEK_STEP_MS], clamped to zero. */
    fun seekBackward() {
        val player = player ?: return
        seekTo(player.currentPosition - SEEK_STEP_MS)
    }

    /**
     * Seeks to an absolute [positionMs], clamped to `[0, duration]` when the duration is
     * known. Used both by the progress bar (drag-to-seek) and by [seekForward] / [seekBackward].
     *
     * A no-op on a live stream (QA finding N5): a live source reports no duration, so the
     * clamp below would collapse every seek onto `0L` and yank the viewer back to the start
     * of the buffer. The UI hides the seek affordances when [PlayerUiState.isLive], but the
     * D-pad left/right keys reach this method directly, so the guard belongs here too.
     */
    fun seekTo(positionMs: Long) {
        val player = player ?: return
        if (isLiveStream()) return
        val duration = player.duration
        val upperBound = if (duration > 0) duration else Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, upperBound)
        player.seekTo(clamped)
        _uiState.update { it.copy(currentPositionMs = clamped) }
    }

    /**
     * Selects the audio track identified by [trackId] (as reported by
     * [PlayerUiState.audioTracks]) — delegates to [PlayerManager.selectAudioTrack], which is
     * itself a safe no-op for a stale/unknown [trackId]. Immediately calls [refreshTracks] so
     * [PlayerUiState.audioTracks] reflects the new [PlayerTrack.isSelected] without waiting on
     * Media3's own `onTracksChanged` callback (which also fires for this change — see
     * [refreshTracks] KDoc for why the double refresh is harmless).
     *
     * A safe no-op once the player has been [releasePlayer]d, mirroring [retry]'s `released`
     * guard.
     */
    fun selectAudioTrack(trackId: String) {
        if (released || !inSession()) return
        playerManager.selectAudioTrack(trackId)
        refreshTracks()
    }

    /**
     * Selects the subtitle track identified by [trackId] (as reported by
     * [PlayerUiState.subtitleTracks]) — see [selectAudioTrack] for the refresh/guard contract,
     * which this mirrors exactly.
     */
    fun selectSubtitleTrack(trackId: String) {
        if (released || !inSession()) return
        pendingOnlineTrackId = null
        onlineTrackChosen = trackId == onlineExternalSubtitle?.trackId
        playerManager.selectSubtitleTrack(trackId)
        refreshTracks()
    }

    /**
     * Disables subtitle rendering entirely — see [PlayerManager.disableSubtitles] and
     * [PlayerUiState.subtitleTracks] KDoc for how "disabled" is represented (no track in that
     * list has [PlayerTrack.isSelected] `true`). See [selectAudioTrack] for the refresh/guard
     * contract, which this mirrors exactly.
     */
    fun disableSubtitles() {
        if (released || !inSession()) return
        pendingOnlineTrackId = null
        onlineTrackChosen = false
        playerManager.disableSubtitles()
        refreshTracks()
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
     *
     * ## After a logout
     * A purge that has started already stopped this playback (its first step): nothing is saved
     * and the player is not read again. One that has completed may have been followed by the next
     * account's own player, which is not this screen's to release.
     *
     * ## Downloaded subtitles
     * The files this visit's picks downloaded are deleted, and a download still finishing deletes
     * its own — see [OnlineSubtitleVisit]. Those files only: never another player's, nor, after a
     * logout, the next account's.
     */
    fun releasePlayer() {
        if (released) return
        released = true

        saveProgress()
        progressTickerJob?.cancel()
        // Leaving the player drops any online search, pick or retry still in flight: nothing may
        // re-prepare a released player, and a late result has nowhere to show.
        startUpJob?.cancel()
        onlineSearchJob?.cancel()
        onlineApplyJob?.cancel()
        onlineFallbackJob?.cancel()
        retryJob?.cancel()
        cancelStallDetection()
        if (inSession()) activePlayer.removeListener(playerListener)
        if (logoutCoordinator.sessionGeneration == sessionGeneration) playerManager.release()
        onlineSubtitleVisit.close()
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    // ─── Internal ────────────────────────────────────────────────────────────────

    /**
     * Re-reads [PlayerManager.getAudioTracks] / [PlayerManager.getSubtitleTracks] and pushes the
     * fresh snapshot into [PlayerUiState.audioTracks] / [PlayerUiState.subtitleTracks].
     *
     * Called from two places, both safe to combine:
     *  - [playerListener]'s `onTracksChanged` — Media3's own push signal, the source of truth;
     *  - immediately after [selectAudioTrack] / [selectSubtitleTrack] / [disableSubtitles] — so
     *    the UI never shows a stale [PlayerTrack.isSelected] for the brief window before Media3's
     *    callback also fires. Both call sites converge on the same idempotent read-and-copy, so
     *    the guaranteed extra `onTracksChanged` firing afterwards is a harmless no-op refresh
     *    rather than a duplicate source of truth.
     *
     * A no-op once the player has been [releasePlayer]d — [PlayerManager] must not be touched
     * after [PlayerManager.release] (mirrors every other post-release guard in this class).
     */
    private fun refreshTracks() {
        if (released || !inSession()) return
        _uiState.update {
            it.copy(
                audioTracks = playerManager.getAudioTracks(),
                subtitleTracks = playerManager.getSubtitleTracks(),
            )
        }
        switchToPendingOnlineTrack()
    }

    /**
     * Switches on the online track [selectOnlineSubtitle] prepared, once the fresh snapshot in
     * [PlayerUiState.subtitleTracks] carries its id. Right after `prepare`, Media3 still reports
     * the old media's tracks (or none) — selecting then would hit a stale id and silently do
     * nothing — so this waits for the track to actually show up, then forgets it: later track
     * updates never override what the user picks next.
     */
    private fun switchToPendingOnlineTrack() {
        val trackId = pendingOnlineTrackId ?: return
        if (_uiState.value.subtitleTracks.none { it.id == trackId }) return
        pendingOnlineTrackId = null
        playerManager.selectSubtitleTrack(trackId)
        _uiState.update { it.copy(subtitleTracks = playerManager.getSubtitleTracks()) }
    }

    private fun currentExternalSubtitles(): List<ExternalSubtitle> =
        resolvedExternalSubtitles + listOfNotNull(onlineExternalSubtitle)

    private fun updateOnline(transform: (OnlineSubtitlesUiState) -> OnlineSubtitlesUiState) {
        _uiState.update { it.copy(onlineSubtitles = transform(it.onlineSubtitles)) }
    }

    private fun startOnlineSearch() {
        val context = subtitleSearchContext ?: return
        onlineSearchJob?.cancel()
        updateOnline { it.copy(search = OnlineSubtitleSearchState.Loading) }
        onlineSearchJob = viewModelScope.launch {
            // Checked again once dispatched: a logout may have run since the button was pressed.
            if (!inSession()) return@launch
            val state = when (val result = openSubtitlesClient.search(context)) {
                is OnlineSubtitleSearchResult.Found -> OnlineSubtitleSearchState.Results(result.subtitles)
                OnlineSubtitleSearchResult.NoResults -> OnlineSubtitleSearchState.Empty
                is OnlineSubtitleSearchResult.Failed -> result.toSearchState()
            }
            // A cancelled search can still resume here if its answer was already on its way;
            // only the search that is still the current one, for a session still signed in, may
            // publish — whether or not the client honoured the cancellation.
            if (isActive && !released && inSession()) updateOnline { it.copy(search = state) }
        }
    }

    private suspend fun applyOnlineSubtitle(subtitle: OnlineSubtitle) {
        // This visit's session, not whichever is current: a screen left open through a completed
        // logout would otherwise download — and play the previous account's stream — for the next.
        val generation = sessionGeneration
        val stillInSession = logoutCoordinator.runUnlessPurgeOwed {
            logoutCoordinator.sessionGeneration == generation && inSession()
        }
        if (stillInSession != true) {
            failPick(LOGOUT_MESSAGE)
            return
        }

        when (val result = openSubtitlesDownloader.download(subtitle, generation, onlineSubtitleVisit)) {
            is OnlineSubtitleDownloadResult.Failed -> failPick(result.toMessage())
            is OnlineSubtitleDownloadResult.Downloaded -> {
                // The search is offered before the first prepare: a pick landing earlier would
                // play from 0 and then be replaced by that prepare, without its track. Waiting
                // (outside the purge's lock) makes it re-prepare on top, at the resume point.
                startUpJob?.join()
                // Checked and played in one go under the purge's lock: a purge either completed
                // before (refused) or starts after, and then its first step stops this playback.
                // A check released before `prepare` would let a purge stop the player in between
                // and this pick start the previous account's stream again behind it.
                val played = logoutCoordinator.runInSession(generation) {
                    if (!released && inSession()) playWithOnlineSubtitle(subtitle, result.subtitle)
                }
                if (played == null) failPick(LOGOUT_MESSAGE)
            }
        }
    }

    private fun playWithOnlineSubtitle(subtitle: OnlineSubtitle, file: ExternalSubtitle) {
        val url = streamUrl ?: return
        onlinePickCount++
        val trackId = "$ONLINE_TRACK_ID_PREFIX$onlinePickCount"
        onlineExternalSubtitle = file.copy(trackId = trackId)
        pendingOnlineTrackId = trackId
        onlineTrackChosen = true
        updateOnline {
            it.copy(applyingFileId = null, appliedFileId = subtitle.fileId, applyError = null)
        }
        reprepareInPlace(url)
    }

    /**
     * Replaces the playing media with [url] and [currentExternalSubtitles], where the video had
     * reached and paused if it was.
     */
    private fun reprepareInPlace(url: String) {
        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val keepPlaying = activePlayer.playWhenReady
        // Retained like `initialize`'s resume point, so a later `retry` resumes here, not there.
        startPositionMs = position

        cancelStallDetection()
        _uiState.update { it.copy(hasError = false, isBuffering = true, currentPositionMs = position) }
        // Same reason as in `retry`: a fresh prepare may leave STATE_BUFFERING unchanged.
        startStallDetection()
        preparedMedia++
        playerManager.prepare(
            streamUrl = url,
            startPositionMs = position,
            externalSubtitles = currentExternalSubtitles(),
        )
        // `prepare` always resumes playback; a video the user had paused stays paused.
        if (!keepPlaying) activePlayer.pause()
    }

    /**
     * The player could not load or parse side-loaded track [trackId]. Only the online track still
     * applied is acted on — every failed attempt is reported, and so are tracks a later pick
     * replaced. It is forgotten at once, so nothing brings it back (a pending switch, a [retry]),
     * then the video is re-prepared once without it: Media3 keeps a failing source stalled rather
     * than failing playback, see [PlayerManager.setSideLoadedSubtitleErrorListener].
     *
     * No re-prepare if the media has been replaced since (a retry or pick already dropped the
     * track), or behind the error overlay — its [retry] will. Same session gate as a pick.
     */
    private fun onSideLoadedSubtitleError(trackId: String) {
        if (released || !inSession()) return
        if (trackId != onlineExternalSubtitle?.trackId) return
        onlineExternalSubtitle = null
        pendingOnlineTrackId = null
        onlineTrackChosen = false
        updateOnline { it.copy(appliedFileId = null, applyError = ONLINE_TRACK_FAILED_MESSAGE) }

        val url = streamUrl ?: return
        val failedIn = preparedMedia
        onlineFallbackJob?.cancel()
        onlineFallbackJob = viewModelScope.launch {
            logoutCoordinator.runInSession(sessionGeneration) {
                if (released || !inSession()) return@runInSession
                if (preparedMedia != failedIn || _uiState.value.hasError) return@runInSession
                reprepareInPlace(url)
            }
        }
    }

    private fun failPick(message: String) {
        updateOnline { it.copy(applyingFileId = null, applyError = message) }
    }

    /**
     * Best-effort resolution of [ExternalSubtitle]s for [initialize] to pass into
     * [PlayerManager.prepare] (Task 3's side-loading contract).
     *
     * ## MOVIE-only gating
     * External subtitles are only ever advertised by Xtream's `get_vod_info` response (see
     * [com.bobot.iptvapp.domain.model.Movie.externalSubtitles] KDoc) — there is no equivalent
     * detail call for LIVE channels or SERIES episodes, so [CatalogRepository.getMovieDetail] is
     * only invoked when [contentType] is [ContentType.MOVIE]; every other type short-circuits to
     * `emptyList()` without making a network call.
     *
     * ## Best-effort contract (approved brief: "never blocks or crashes playback")
     *  - [Resource.Error] from [CatalogRepository.getMovieDetail] falls back to `emptyList()`;
     *  - any other thrown exception is also caught and falls back to `emptyList()` — a second
     *    line of defence so a detail-call failure can never propagate out of this function and
     *    abort [initialize]'s coroutine before it reaches [PlayerManager.prepare]. Coroutine
     *    [CancellationException] is deliberately re-thrown rather than swallowed, so cancelling
     *    [initialize]'s coroutine (e.g. the screen is left before playback starts) still
     *    cancels cleanly instead of being misreported as "no external subtitles";
     *  - [EXTERNAL_SUBTITLES_TIMEOUT_MS] bounds the wait via [withTimeoutOrNull] — see that
     *    constant's KDoc for the exact rationale — so a slow/hanging `get_vod_info` call can
     *    never indefinitely delay the start of playback; timing out also falls back to
     *    `emptyList()`, and the movie plays with only its embedded tracks.
     */
    private suspend fun resolveExternalSubtitles(
        contentType: ContentType,
        streamId: String,
    ): List<ExternalSubtitle> {
        if (contentType != ContentType.MOVIE) return emptyList()

        return try {
            withTimeoutOrNull(EXTERNAL_SUBTITLES_TIMEOUT_MS) {
                when (val result = catalogRepository.getMovieDetail(streamId)) {
                    is Resource.Success -> result.data.externalSubtitles
                    is Resource.Error -> emptyList()
                    Resource.Loading -> emptyList()
                }
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = viewModelScope.launch {
            var msSinceLastSave = 0L
            while (isActive) {
                delay(POSITION_TICK_INTERVAL_MS)
                // A logout purge has stopped this playback: reading the player would build a new
                // one, and this session never comes back — see [inSession].
                if (!inSession()) break

                val isPlayingNow = activePlayer.isPlaying
                _uiState.update {
                    it.copy(
                        currentPositionMs = activePlayer.currentPosition.coerceAtLeast(0L),
                        durationMs = safeDuration(),
                        isLive = isLiveStream(),
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
     * Starts (or leaves running, if already active) the [STALL_DETECTION_TIMEOUT_MS] watchdog
     * for the current [ExoCommonPlayer.STATE_BUFFERING] episode. Does nothing if the player has
     * already been [released], or if [PlayerUiState.hasError] is already `true` (avoids a
     * double-trigger — the error overlay is already showing).
     *
     * If the buffering episode is still ongoing once the delay elapses, [PlayerUiState.hasError]
     * is set to `true` and [PlayerUiState.isBuffering] to `false`, which surfaces the existing
     * error overlay exactly as [onPlayerError] does. Cancelled by [cancelStallDetection] as soon
     * as the player leaves [ExoCommonPlayer.STATE_BUFFERING] (in particular on
     * [ExoCommonPlayer.STATE_READY]), so a legitimate/brief rebuffer never trips it.
     */
    private fun startStallDetection() {
        if (released || _uiState.value.hasError) return
        if (stallDetectionJob?.isActive == true) return

        stallDetectionJob = viewModelScope.launch {
            delay(STALL_DETECTION_TIMEOUT_MS)
            if (!released) {
                _uiState.update { it.copy(hasError = true, isBuffering = false) }
            }
        }
    }

    /** Cancels the in-flight stall watchdog, if any — see [startStallDetection]. */
    private fun cancelStallDetection() {
        stallDetectionJob?.cancel()
        stallDetectionJob = null
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
     *
     * ## Logout purge
     * Nothing is read once this visit's session is revoked (see [inSession]). A position read
     * before is written under the purge's lock, for this visit's session only: a purge either
     * comes after the write — and deletes it with the rest of the table — or refuses it. Without
     * that, a write still on its way would land after the purge, and nothing would ever delete it.
     * [LogoutCoordinator.awaitInSession] rather than `runInSession`, so the save is not lost to a
     * pick holding the lock for a few milliseconds.
     */
    private fun saveProgress() {
        val profileId = activeProfileId ?: return
        val id = contentId ?: return
        val type = contentType ?: return

        if (type == ContentType.LIVE) return
        if (!inSession()) return

        val position = activePlayer.currentPosition.coerceAtLeast(0L)
        val duration = safeDuration()
        val generation = sessionGeneration

        viewModelScope.launch {
            logoutCoordinator.awaitInSession(generation) {
                if (!inSession()) return@awaitInSession
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
    }

    /**
     * Whether this visit's session is still the signed-in one: no logout purge has started since
     * this ViewModel was created, none has completed since, and none is running or left owed.
     * Once one has, it has stopped this playback — or is about to, its first step — and
     * [PlayerManager.player] would hand back a fresh player, or later the next account's. Never
     * true again after that: whatever the purge's outcome, this screen neither plays, reads the
     * player, searches nor saves any more.
     *
     * The generation and [LogoutCoordinator.state] cover a route created *during* a purge, whose
     * attempts snapshot already includes it: running or failed (still owed), it must not touch the
     * player the purge stopped; completed, the generation has moved past it.
     *
     * Holds nothing, so it is enough on its own only for main-thread work that does not play: the
     * purge's stop runs on the main thread after [LogoutCoordinator.purgeAttempts] moves, so a read
     * here that still sees it unchanged comes before that stop. Anything that prepares or writes
     * checks it again under the purge's lock.
     */
    private fun inSession(): Boolean =
        logoutCoordinator.purgeAttempts == sessionPurgeAttempts &&
            logoutCoordinator.sessionGeneration == sessionGeneration &&
            logoutCoordinator.state.value.let { it !is LogoutPurgeState.Running && it !is LogoutPurgeState.Failed }

    /** [ExoCommonPlayer.getDuration] reports `C.TIME_UNSET` (a large negative Long) when
     *  unknown (e.g. live streams, or before metadata loads) — clamp that to `0L` so callers
     *  never need to special-case the sentinel value. */
    private fun safeDuration(): Long = activePlayer.duration.coerceAtLeast(0L)

    /** Whether the current stream is live — a live channel has no seekable timeline, so the
     *  UI drops the progress bar, the two time labels and the seek buttons (QA finding N5).
     *
     *  Two sources, because neither alone is enough: [contentType] is resolved from the stream
     *  URL and is authoritative for Xtream live channels even before playback starts, while
     *  [ExoCommonPlayer.isCurrentMediaItemLive] also catches VOD URLs that a provider actually
     *  serves as a live HLS window. */
    private fun isLiveStream(): Boolean =
        contentType == ContentType.LIVE || activePlayer.isCurrentMediaItemLive
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
