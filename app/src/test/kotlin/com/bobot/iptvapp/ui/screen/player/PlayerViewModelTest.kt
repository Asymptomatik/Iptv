package com.bobot.iptvapp.ui.screen.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.player.PlayerManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
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
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        player = mockk(relaxed = true)
        playerManager = mockk()
        every { playerManager.player } returns player
        every { playerManager.prepare(any(), any()) } just Runs
        every { playerManager.release() } just Runs
        playbackProgressRepository = mockk()
        appPreferencesStore = mockk()

        coEvery { playbackProgressRepository.upsertProgress(any()) } just Runs

        viewModel = PlayerViewModel(
            playerManager = playerManager,
            playbackProgressRepository = playbackProgressRepository,
            appPreferencesStore = appPreferencesStore,
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
}
