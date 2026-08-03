package com.bobot.iptvapp.ui.screen.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.player.PlayerManager
import com.bobot.iptvapp.player.PlayerTrack
import com.bobot.iptvapp.player.PlayerTrackType
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlayerViewModel].
 *
 * ## Testing a `@HiltViewModel` — convention established here (Task 13, the first
 * ViewModel in this codebase)
 * [androidx.lifecycle.ViewModel.viewModelScope] runs on plain JVM (no Robolectric / Android
 * runtime needed) — it is backed by [androidx.lifecycle.viewmodel.internal.CloseableCoroutineScope]
 * with no Android framework dependency. To make it deterministic in tests:
 *  - [Dispatchers.setMain] swaps in a [StandardTestDispatcher] so `viewModelScope`'s
 *    `Dispatchers.Main.immediate` coroutines run against a controllable virtual-time scheduler;
 *  - [kotlinx.coroutines.test.TestCoroutineScheduler.runCurrent] (via
 *    [StandardTestDispatcher.scheduler]) drains only *currently ready* work — deliberately
 *    **not** `advanceUntilIdle()`, which would spin forever against [PlayerViewModel]'s
 *    infinite progress-polling ticker (`while (isActive) { delay(...) ; ... }`), since that
 *    loop always reschedules more (future) work and never goes idle.
 *
 * `androidx.media3.common.Player` is mocked directly (relaxed) — no Robolectric shadow is
 * needed since mockk never executes the interface's real method bodies.
 */
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var player: Player
    private lateinit var playerManager: PlayerManager
    private lateinit var playbackProgressRepository: PlaybackProgressRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        player = mockk(relaxed = true)
        playerManager = mockk()
        every { playerManager.player } returns player
        // Stubbed on the full 3-parameter signature: `prepare` has default values, so a
        // 2-argument `every` is routed through the `prepare$default` bridge and actually
        // records `prepare(any(), any(), eq(emptyList()))`. Any call carrying non-empty
        // external subtitles then matches no answer, and the resulting MockKException
        // escapes `runTest` to be blamed on whichever test happens to run next.
        every { playerManager.prepare(any(), any(), any()) } just Runs
        every { playerManager.release() } just Runs
        every { playerManager.getAudioTracks() } returns emptyList()
        every { playerManager.getSubtitleTracks() } returns emptyList()
        every { playerManager.selectAudioTrack(any()) } just Runs
        every { playerManager.selectSubtitleTrack(any()) } just Runs
        every { playerManager.disableSubtitles() } just Runs
        playbackProgressRepository = mockk()
        appPreferencesStore = mockk()
        catalogRepository = mockk()

        coEvery { playbackProgressRepository.upsertProgress(any()) } just Runs
        // Default: no external subtitles resolved unless a test overrides this stub — keeps
        // every pre-existing (MOVIE-content) test's `prepare(...)` call matching the
        // `externalSubtitles = emptyList()` default it was written against, since
        // `resolveExternalSubtitles` falls back to `emptyList()` on any `Resource.Error`.
        coEvery { catalogRepository.getMovieDetail(any()) } returns Resource.Error()

        viewModel = PlayerViewModel(
            playerManager = playerManager,
            playbackProgressRepository = playbackProgressRepository,
            appPreferencesStore = appPreferencesStore,
            catalogRepository = catalogRepository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── initialize / resume ──────────────────────────────────────────────────

    @Test
    fun `initialize prepares the player at the saved position when progress exists`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"
        coEvery {
            playbackProgressRepository.getProgress("profile-1", "42", ContentType.MOVIE)
        } returns PlaybackProgress(
            contentId = "42",
            contentType = ContentType.MOVIE,
            positionMillis = 30_000L,
            durationMillis = 100_000L,
            lastUpdatedMillis = 1L,
            profileId = "profile-1",
        )

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) {
            playerManager.prepare(streamUrl = "http://example.com:8080/movie/u/p/42.mp4", startPositionMs = 30_000L)
        }
        assertEquals(30_000L, viewModel.uiState.value.currentPositionMs)
    }

    @Test
    fun `initialize starts from zero when no progress record exists`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"
        coEvery {
            playbackProgressRepository.getProgress("profile-1", "77", ContentType.LIVE)
        } returns null

        viewModel.initialize("http://example.com:8080/live/u/p/77.ts", "77")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) {
            playerManager.prepare(streamUrl = "http://example.com:8080/live/u/p/77.ts", startPositionMs = 0L)
        }
    }

    @Test
    fun `initialize starts from zero when no active profile is set`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/movie/u/p/9.mp4", "9")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) {
            playerManager.prepare(streamUrl = "http://example.com:8080/movie/u/p/9.mp4", startPositionMs = 0L)
        }
        coVerify(exactly = 0) { playbackProgressRepository.getProgress(any(), any(), any()) }
    }

    @Test
    fun `initialize is idempotent — second call does not re-prepare the player`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/movie/u/p/9.mp4", "9")
        testDispatcher.scheduler.runCurrent()
        viewModel.initialize("http://example.com:8080/movie/u/p/9.mp4", "9")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.prepare(any(), any()) }
    }

    // ── onPlayerError / retry ─────────────────────────────────────────────────

    @Test
    fun `onPlayerError sets hasError to true and clears isBuffering`() {
        // Arrange: initialize so the listener is registered, then drive the error callback.
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } just Runs
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/live/u/p/77.ts", "77")
        testDispatcher.scheduler.runCurrent()

        val error = mockk<PlaybackException>(relaxed = true)
        listenerSlot.captured.onPlayerError(error)

        assertTrue("hasError should be true after onPlayerError", viewModel.uiState.value.hasError)
        assertFalse("isBuffering should be false after onPlayerError", viewModel.uiState.value.isBuffering)
    }

    @Test
    fun `retry clears hasError and re-prepares the player with the retained stream URL`() {
        // Arrange: initialise with a specific URL so the ViewModel retains it for retry.
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } just Runs
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        val url = "http://example.com:8080/movie/u/p/42.mp4"
        viewModel.initialize(url, "42")
        testDispatcher.scheduler.runCurrent()

        // Simulate an error so hasError = true.
        val error = mockk<PlaybackException>(relaxed = true)
        listenerSlot.captured.onPlayerError(error)
        assertTrue(viewModel.uiState.value.hasError)

        // Act: retry.
        viewModel.retry()

        // Assert: error cleared, isBuffering restored, prepare called a second time for same URL.
        assertFalse("hasError should be cleared by retry", viewModel.uiState.value.hasError)
        assertTrue("isBuffering should be true after retry", viewModel.uiState.value.isBuffering)
        // prepare is expected twice: once from initialize, once from retry.
        verify(exactly = 2) { playerManager.prepare(streamUrl = url, startPositionMs = 0L) }
    }

    // ── LIVE progress exclusion (Task 23 decision) ───────────────────────────────

    @Test
    fun `initialize does not query getProgress for LIVE content`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"

        viewModel.initialize("http://example.com:8080/live/u/p/77.ts", "77")
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { playbackProgressRepository.getProgress(any(), any(), any()) }
        verify(exactly = 1) {
            playerManager.prepare(streamUrl = "http://example.com:8080/live/u/p/77.ts", startPositionMs = 0L)
        }
    }

    @Test
    fun `saveProgress does not persist a record for LIVE content`() {
        // No `getProgress` stub is set up here on purpose — the Task 23 decision means it
        // must never be called for LIVE content (see `initialize does not query getProgress
        // for LIVE content` above); a strict mockk() call here would otherwise throw.
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"
        every { player.currentPosition } returns 12_345L
        every { player.duration } returns 0L

        viewModel.initialize("http://example.com:8080/live/u/p/77.ts", "77")
        testDispatcher.scheduler.runCurrent()

        // releasePlayer() calls saveProgress() as its final-save step.
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { playbackProgressRepository.upsertProgress(any()) }
    }

    // ── stall detection (application-level watchdog on continuous STATE_BUFFERING) ──────────

    @Test
    fun `continuous buffering beyond the stall timeout sets hasError and clears isBuffering`() {
        // Regression test for the real-world Xtream bug: a VOD stream that keeps delivering
        // bytes too slowly to ever reach STATE_READY (so `onPlayerError` never fires) must not
        // spin forever — after ~20s of continuous STATE_BUFFERING, the ViewModel should trip
        // the same error overlay `onPlayerError` uses.
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } just Runs
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        listenerSlot.captured.onPlaybackStateChanged(Player.STATE_BUFFERING)
        testDispatcher.scheduler.advanceTimeBy(20_000L)
        testDispatcher.scheduler.runCurrent()

        assertTrue("hasError should be true after a stall beyond the timeout", viewModel.uiState.value.hasError)
        assertFalse("isBuffering should be cleared once the stall is reported", viewModel.uiState.value.isBuffering)
    }

    @Test
    fun `buffering that resolves to STATE_READY before the stall timeout does not trigger hasError`() {
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } just Runs
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        listenerSlot.captured.onPlaybackStateChanged(Player.STATE_BUFFERING)
        testDispatcher.scheduler.advanceTimeBy(5_000L)
        testDispatcher.scheduler.runCurrent()
        listenerSlot.captured.onPlaybackStateChanged(Player.STATE_READY)

        // Advance well past the stall timeout — the watchdog should have been cancelled by the
        // STATE_READY transition above, so no false positive should fire.
        testDispatcher.scheduler.advanceTimeBy(20_000L)
        testDispatcher.scheduler.runCurrent()

        assertFalse("a brief/legitimate rebuffer must not trigger hasError", viewModel.uiState.value.hasError)
    }

    @Test
    fun `retry re-arms the stall watchdog even when the player stays in STATE_BUFFERING without a new Media3 callback`() {
        // Regression test for the Code Reviewer blocker on this feature: Media3 only invokes
        // `onPlaybackStateChanged` when the *integer* playback state value actually changes. If
        // the stream was already stuck in continuous STATE_BUFFERING when the first watchdog
        // fired `hasError = true` (this exact scenario), a fresh `prepare()` after `retry()` can
        // leave the player in STATE_BUFFERING with no detectable value change — so this test
        // deliberately does NOT simulate a second `onPlaybackStateChanged(STATE_BUFFERING)` call
        // after `retry()`, proving `retry()` itself must explicitly re-arm the watchdog rather
        // than relying on a future Media3 callback that may never come.
        //
        // This test fails without the `startStallDetection()` call in `retry()` (hasError would
        // stay false after the second timeout) and passes with it.
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } just Runs
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        // First attempt stalls and trips the watchdog.
        listenerSlot.captured.onPlaybackStateChanged(Player.STATE_BUFFERING)
        testDispatcher.scheduler.advanceTimeBy(20_000L)
        testDispatcher.scheduler.runCurrent()
        assertTrue("first stall should trigger hasError", viewModel.uiState.value.hasError)

        // User taps "Réessayer" — the stream stalls again in exactly the same STATE_BUFFERING
        // value, so no new Media3 callback is simulated here on purpose.
        viewModel.retry()
        testDispatcher.scheduler.advanceTimeBy(20_000L)
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            "retry must re-arm the watchdog even without a fresh Media3 callback, so a repeat " +
                "stall surfaces hasError again instead of spinning forever",
            viewModel.uiState.value.hasError,
        )
    }

    // ── togglePlayPause ───────────────────────────────────────────────────────

    @Test
    fun `togglePlayPause pauses when currently playing`() {
        every { player.isPlaying } returns true

        viewModel.togglePlayPause()

        verify(exactly = 1) { player.pause() }
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `togglePlayPause plays when currently paused`() {
        every { player.isPlaying } returns false

        viewModel.togglePlayPause()

        verify(exactly = 1) { player.play() }
        verify(exactly = 0) { player.pause() }
    }

    // ── seekTo clamping ───────────────────────────────────────────────────────

    @Test
    fun `seekTo clamps to the known duration upper bound`() {
        every { player.duration } returns 100_000L

        viewModel.seekTo(150_000L)

        verify(exactly = 1) { player.seekTo(100_000L) }
        assertEquals(100_000L, viewModel.uiState.value.currentPositionMs)
    }

    @Test
    fun `seekTo clamps negative positions to zero`() {
        every { player.duration } returns 100_000L

        viewModel.seekTo(-5_000L)

        verify(exactly = 1) { player.seekTo(0L) }
    }

    @Test
    fun `seekTo does not clamp to the upper bound when duration is unknown`() {
        // C.TIME_UNSET is a large negative sentinel; 0L covers both "unset" and "not yet known".
        every { player.duration } returns 0L

        viewModel.seekTo(500_000L)

        verify(exactly = 1) { player.seekTo(500_000L) }
    }

    // ── track selection (audio / subtitle) — Task 4 ──────────────────────────

    @Test
    fun `selectAudioTrack delegates to PlayerManager and refreshes uiState from the current track snapshots`() {
        val audioTracks = listOf(
            PlayerTrack(id = "audio-0", label = "Français", languageCode = "fra", isSelected = true, type = PlayerTrackType.AUDIO),
        )
        val subtitleTracks = listOf(
            PlayerTrack(id = "sub-0", label = "English", languageCode = "eng", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )
        every { playerManager.getAudioTracks() } returns audioTracks
        every { playerManager.getSubtitleTracks() } returns subtitleTracks

        viewModel.selectAudioTrack("audio-0")

        verify(exactly = 1) { playerManager.selectAudioTrack("audio-0") }
        assertEquals(audioTracks, viewModel.uiState.value.audioTracks)
        assertEquals(subtitleTracks, viewModel.uiState.value.subtitleTracks)
    }

    @Test
    fun `selectSubtitleTrack delegates to PlayerManager and refreshes uiState from the current track snapshots`() {
        val subtitleTracks = listOf(
            PlayerTrack(id = "sub-0", label = "English", languageCode = "eng", isSelected = true, type = PlayerTrackType.SUBTITLE),
        )
        every { playerManager.getAudioTracks() } returns emptyList()
        every { playerManager.getSubtitleTracks() } returns subtitleTracks

        viewModel.selectSubtitleTrack("sub-0")

        verify(exactly = 1) { playerManager.selectSubtitleTrack("sub-0") }
        assertEquals(subtitleTracks, viewModel.uiState.value.subtitleTracks)
    }

    @Test
    fun `disableSubtitles delegates to PlayerManager and refreshes uiState so no subtitle track is selected`() {
        val subtitleTracks = listOf(
            PlayerTrack(id = "sub-0", label = "English", languageCode = "eng", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )
        every { playerManager.getAudioTracks() } returns emptyList()
        every { playerManager.getSubtitleTracks() } returns subtitleTracks

        viewModel.disableSubtitles()

        verify(exactly = 1) { playerManager.disableSubtitles() }
        assertTrue(
            "no subtitle track should be selected once subtitles are disabled",
            viewModel.uiState.value.subtitleTracks.none { it.isSelected },
        )
    }

    @Test
    fun `onTracksChanged refreshes uiState audio and subtitle track lists`() {
        val listenerSlot = slot<Player.Listener>()
        every { player.addListener(capture(listenerSlot)) } just Runs
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        val audioTracks = listOf(
            PlayerTrack(id = "audio-0", label = "Français", languageCode = "fra", isSelected = true, type = PlayerTrackType.AUDIO),
        )
        val subtitleTracks = listOf(
            PlayerTrack(id = "sub-0", label = "English", languageCode = "eng", isSelected = true, type = PlayerTrackType.SUBTITLE),
        )
        every { playerManager.getAudioTracks() } returns audioTracks
        every { playerManager.getSubtitleTracks() } returns subtitleTracks

        listenerSlot.captured.onTracksChanged(mockk<Tracks>(relaxed = true))

        assertEquals(audioTracks, viewModel.uiState.value.audioTracks)
        assertEquals(subtitleTracks, viewModel.uiState.value.subtitleTracks)
    }

    @Test
    fun `selectAudioTrack is a safe no-op after releasePlayer`() {
        viewModel.releasePlayer()

        viewModel.selectAudioTrack("audio-0")

        verify(exactly = 0) { playerManager.selectAudioTrack(any()) }
        verify(exactly = 0) { playerManager.getAudioTracks() }
    }

    @Test
    fun `selectSubtitleTrack is a safe no-op after releasePlayer`() {
        viewModel.releasePlayer()

        viewModel.selectSubtitleTrack("sub-0")

        verify(exactly = 0) { playerManager.selectSubtitleTrack(any()) }
        verify(exactly = 0) { playerManager.getSubtitleTracks() }
    }

    @Test
    fun `disableSubtitles is a safe no-op after releasePlayer`() {
        viewModel.releasePlayer()

        viewModel.disableSubtitles()

        verify(exactly = 0) { playerManager.disableSubtitles() }
        verify(exactly = 0) { playerManager.getSubtitleTracks() }
    }

    // ── best-effort external subtitles (Task 4) ──────────────────────────────

    @Test
    fun `initialize resolves external subtitles from getMovieDetail for MOVIE content and passes them to prepare`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        val subtitles = listOf(ExternalSubtitle(url = "http://example.com/sub.srt", language = "fr"))
        coEvery { catalogRepository.getMovieDetail("42") } returns Resource.Success(sampleMovie(externalSubtitles = subtitles))

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) {
            playerManager.prepare(
                streamUrl = "http://example.com:8080/movie/u/p/42.mp4",
                startPositionMs = 0L,
                externalSubtitles = subtitles,
            )
        }
    }

    @Test
    fun `initialize falls back to empty external subtitles when getMovieDetail returns an error for MOVIE content`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        coEvery { catalogRepository.getMovieDetail("42") } returns Resource.Error(message = "boom")

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) {
            playerManager.prepare(
                streamUrl = "http://example.com:8080/movie/u/p/42.mp4",
                startPositionMs = 0L,
                externalSubtitles = emptyList(),
            )
        }
    }

    @Test
    fun `initialize falls back to empty external subtitles when getMovieDetail times out for MOVIE content`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        coEvery { catalogRepository.getMovieDetail("42") } coAnswers {
            delay(10_000L)
            Resource.Success(sampleMovie(externalSubtitles = listOf(ExternalSubtitle("http://example.com/late.srt", "en"))))
        }

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.advanceTimeBy(5_000L)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) {
            playerManager.prepare(
                streamUrl = "http://example.com:8080/movie/u/p/42.mp4",
                startPositionMs = 0L,
                externalSubtitles = emptyList(),
            )
        }
    }

    @Test
    fun `initialize does not call getMovieDetail for LIVE content and prepares with empty external subtitles`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/live/u/p/77.ts", "77")
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
        verify(exactly = 1) {
            playerManager.prepare(
                streamUrl = "http://example.com:8080/live/u/p/77.ts",
                startPositionMs = 0L,
                externalSubtitles = emptyList(),
            )
        }
    }

    @Test
    fun `initialize does not call getMovieDetail for SERIES content and prepares with empty external subtitles`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns null

        viewModel.initialize("http://example.com:8080/series/u/p/5.mp4", "5")
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { catalogRepository.getMovieDetail(any()) }
        verify(exactly = 1) {
            playerManager.prepare(
                streamUrl = "http://example.com:8080/series/u/p/5.mp4",
                startPositionMs = 0L,
                externalSubtitles = emptyList(),
            )
        }
    }

    // ── releasePlayer ─────────────────────────────────────────────────────────

    @Test
    fun `releasePlayer saves final progress and releases the PlayerManager`() {
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"
        coEvery {
            playbackProgressRepository.getProgress("profile-1", "42", ContentType.MOVIE)
        } returns null
        every { player.currentPosition } returns 12_345L
        every { player.duration } returns 100_000L

        val savedProgress = slot<PlaybackProgress>()
        coEvery { playbackProgressRepository.upsertProgress(capture(savedProgress)) } just Runs

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.release() }
        coVerify(exactly = 1) { playbackProgressRepository.upsertProgress(any()) }
        assertEquals("42", savedProgress.captured.contentId)
        assertEquals(ContentType.MOVIE, savedProgress.captured.contentType)
        assertEquals(12_345L, savedProgress.captured.positionMillis)
        assertEquals(100_000L, savedProgress.captured.durationMillis)
        assertEquals("profile-1", savedProgress.captured.profileId)
    }

    @Test
    fun `releasePlayer without a prior initialize does not attempt to save progress`() {
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.release() }
        coVerify(exactly = 0) { playbackProgressRepository.upsertProgress(any()) }
    }

    @Test
    fun `releasePlayer is idempotent — a second call does not re-save progress or re-release the player`() {
        // Regression test for the Task 13 review blocker: Navigation Compose clears the
        // destination's ViewModelStore (onCleared -> releasePlayer) around the same time the
        // composable leaves composition (DisposableEffect.onDispose -> releasePlayer), so a
        // second releasePlayer() call happens on the ordinary "back out of the player" path.
        // Before the fix, the second call re-read `player` (playerManager.player), which — on
        // a *real* ExoPlayerManager, now nulled out by the first release() — would lazily spin
        // up a brand-new, unprepared ExoPlayer reporting currentPosition = 0, and overwrite the
        // just-saved resume position with 0. Here, `player` is a single relaxed mock shared for
        // the whole test (not re-created on "release"), so this test instead proves the guard
        // at the PlayerViewModel level directly: upsertProgress/release must each fire exactly
        // once no matter how many times releasePlayer() is invoked.
        coEvery { appPreferencesStore.getActiveProfileId() } returns "profile-1"
        coEvery {
            playbackProgressRepository.getProgress("profile-1", "42", ContentType.MOVIE)
        } returns null
        every { player.currentPosition } returns 12_345L
        every { player.duration } returns 100_000L

        val savedProgress = slot<PlaybackProgress>()
        coEvery { playbackProgressRepository.upsertProgress(capture(savedProgress)) } just Runs

        viewModel.initialize("http://example.com:8080/movie/u/p/42.mp4", "42")
        testDispatcher.scheduler.runCurrent()

        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        // Simulate the position moving after release (e.g. a freshly-created, unprepared
        // player reporting 0) — the guard should prevent this second call from reading it at
        // all, since `saveProgress()`/`player` must not be touched again.
        every { player.currentPosition } returns 0L

        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.release() }
        coVerify(exactly = 1) { playbackProgressRepository.upsertProgress(any()) }
        assertEquals(12_345L, savedProgress.captured.positionMillis)
    }

    /** Minimal [Movie] fixture for [CatalogRepository.getMovieDetail] stubs — only
     *  [Movie.externalSubtitles] is exercised by the tests using this helper. */
    private fun sampleMovie(externalSubtitles: List<ExternalSubtitle> = emptyList()): Movie = Movie(
        id = "42",
        title = "Test Movie",
        posterUrl = null,
        plot = null,
        categoryId = "1",
        rating = null,
        year = null,
        addedMillis = null,
        durationMillis = null,
        containerExtension = "mp4",
        externalSubtitles = externalSubtitles,
    )
}
