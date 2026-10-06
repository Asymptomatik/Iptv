package com.bobot.iptvapp.ui.screen.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.bobot.iptvapp.data.logout.FakeDownloadedSubtitlePurger
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.preferences.FakeLogoutPurgeMarkerStore
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesClient
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesDownloader
import com.bobot.iptvapp.domain.logout.LogoutPurger
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.player.PlayerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A player screen left open through a logout: once the purge has started, nothing this screen still
 * does — a retry, a tick, a save already on its way, a button, a Media3 callback, leaving — may play
 * the previous account's Xtream URL again, rebuild the player the purge stopped, or write a position
 * for the previous profile. Nor may it touch the next account's playback afterwards.
 *
 * The purge is a real [LogoutCoordinator] over a purger whose first step, like the real one's, stops
 * playback. [PlayerManager.player] is read the way `ExoPlayerManager` serves it: the first read after
 * that stop builds a brand-new player, which is journalled as `"player recreated"`. Everything the
 * screen does to the player manager and to the progress table is journalled in [events], so each
 * test asserts what happened *after* the purge, and in which order.
 */
class PlayerViewModelSessionRevocationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var applicationScope: CoroutineScope
    private lateinit var player: Player
    private lateinit var playerManager: PlayerManager
    private lateinit var progressRepository: PlaybackProgressRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var client: OpenSubtitlesClient
    private lateinit var downloader: OpenSubtitlesDownloader
    private lateinit var markerStore: FakeLogoutPurgeMarkerStore
    private lateinit var coordinator: LogoutCoordinator
    private lateinit var viewModel: PlayerViewModel

    private val listenerSlot = slot<Player.Listener>()
    private val events = mutableListOf<String>()

    /** Set by the purge's first step, as `ExoPlayerManager.stopActivePlayback` releases the player. */
    private var playbackStopped = false

    /** What the ViewModel handed to [PlayerManager.setSideLoadedSubtitleErrorListener] last. */
    private var subtitleErrorListener: ((String) -> Unit)? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)

        player = mockk(relaxed = true)
        every { player.addListener(capture(listenerSlot)) } answers { }
        every { player.isPlaying } returns true
        every { player.currentPosition } returns POSITION_MS
        every { player.duration } returns DURATION_MS
        every { player.isCurrentMediaItemLive } returns false

        playerManager = mockk()
        every { playerManager.player } answers {
            if (playbackStopped) events += "player recreated"
            player
        }
        every { playerManager.prepare(any(), any(), any()) } answers { events += "prepare" }
        every { playerManager.release() } answers { events += "release" }
        every { playerManager.getAudioTracks() } answers { events += "read tracks"; emptyList() }
        every { playerManager.getSubtitleTracks() } answers { events += "read tracks"; emptyList() }
        every { playerManager.selectAudioTrack(any()) } answers { events += "select audio" }
        every { playerManager.selectSubtitleTrack(any()) } answers { events += "select subtitle" }
        every { playerManager.disableSubtitles() } answers { events += "disable subtitles" }
        every { playerManager.setSideLoadedSubtitleErrorListener(any()) } answers { subtitleErrorListener = firstArg() }

        progressRepository = mockk()
        coEvery { progressRepository.getProgress(any(), any(), any()) } returns null
        coEvery { progressRepository.upsertProgress(any()) } answers {
            events += "upsert ${firstArg<PlaybackProgress>().profileId}"
        }
        appPreferencesStore = mockk()
        coEvery { appPreferencesStore.getActiveProfileId() } returns OLD_PROFILE
        catalogRepository = mockk()
        coEvery { catalogRepository.getMovieDetail(any()) } returns Resource.Error()
        client = mockk()
        coEvery { client.search(any()) } answers {
            events += "search"
            OnlineSubtitleSearchResult.Found(listOf(FRENCH))
        }
        downloader = mockk()
        coEvery { downloader.download(any(), any(), any()) } answers {
            events += "download"
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.SESSION_ENDED)
        }

        markerStore = FakeLogoutPurgeMarkerStore()
        useCoordinator(LogoutCoordinator(stoppingPurger(), markerStore, applicationScope))
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    /** A purge whose first step stops playback, then waits on [gate] when one is given. */
    private fun stoppingPurger(gate: CompletableDeferred<Unit>? = null) = object : LogoutPurger {
        override suspend fun logOut() {
            events += "purge"
            playbackStopped = true
            gate?.await()
        }

        override suspend fun recoverIfNeeded(): Boolean = false
    }

    private fun useCoordinator(logoutCoordinator: LogoutCoordinator) {
        coordinator = logoutCoordinator
        viewModel = newViewModel()
    }

    private fun newViewModel() = PlayerViewModel(
        playerManager = playerManager,
        playbackProgressRepository = progressRepository,
        appPreferencesStore = appPreferencesStore,
        catalogRepository = catalogRepository,
        openSubtitlesClient = client,
        openSubtitlesDownloader = downloader,
        logoutCoordinator = coordinator,
        downloadedSubtitlePurger = FakeDownloadedSubtitlePurger(),
    )

    private fun startMovie() {
        viewModel.initialize(MOVIE_URL, "42", MOVIE_CONTEXT)
        testDispatcher.scheduler.runCurrent()
    }

    private fun logOutNow() {
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
    }

    private val listener get() = listenerSlot.captured

    /** What happened from the purge on — everything before it is the old session's own business. */
    private fun eventsFromPurge(): List<String> = events.drop(events.indexOf("purge"))

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `retry after a completed logout prepares nothing and rebuilds no player`() {
        startMovie()
        listener.onPlayerError(mockk<PlaybackException>(relaxed = true))
        logOutNow()

        viewModel.retry()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `retry while a purge is running prepares nothing, even once the purge ends`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(stoppingPurger(purgeGate), markerStore, applicationScope))
        startMovie()
        listener.onPlayerError(mockk<PlaybackException>(relaxed = true))
        logOutNow() // stopped playback, holds the purge lock until purgeGate

        viewModel.retry()
        testDispatcher.scheduler.runCurrent()
        purgeGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `a logout starting right before the retry prepare waits for it, so its purge comes after`() {
        // Unconfined: the logout launched from inside the retry runs as far as it can right there,
        // exactly like a purge thread grabbing the lock the instant it is free.
        val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCoordinator(LogoutCoordinator(stoppingPurger(), markerStore, logoutScope))
        startMovie()
        listener.onPlayerError(mockk<PlaybackException>(relaxed = true))
        var logoutArmed = true
        every { playerManager.prepare(any(), any(), any()) } answers {
            if (logoutArmed) {
                logoutArmed = false
                logoutScope.launch { coordinator.logOut() }
            }
            events += "prepare"
        }
        events.clear()

        viewModel.retry()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("prepare", "purge"), events)
        logoutScope.cancel()
    }

    // ── Progress ticker ────────────────────────────────────────────────────────

    @Test
    fun `the ticker stops reading the player and saving once a logout stopped playback`() {
        startMovie()
        logOutNow()

        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `ticks while a purge is still running neither rebuild the player nor save`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(stoppingPurger(purgeGate), markerStore, applicationScope))
        startMovie()
        logOutNow()

        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()
        purgeGate.complete(Unit)
        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    // ── A save already on its way ──────────────────────────────────────────────

    @Test
    fun `a save in flight when the logout starts lands before its purge, never after`() {
        startMovie()
        val upsertGate = CompletableDeferred<Unit>()
        coEvery { progressRepository.upsertProgress(any()) } coAnswers {
            upsertGate.await()
            events += "upsert ${firstArg<PlaybackProgress>().profileId}"
        }
        listener.onIsPlayingChanged(false) // pause: saves right away
        testDispatcher.scheduler.runCurrent()
        events.clear()

        logOutNow()
        upsertGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        // The purge — which empties the progress table — comes after the write, so it deletes it.
        assertEquals(listOf("upsert $OLD_PROFILE", "purge"), events)
    }

    @Test
    fun `a save read before the logout but reaching the table after its purge is dropped`() {
        // Unconfined: the whole logout runs right where it is launched, between the save reading
        // the position and its coroutine getting to write it.
        val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCoordinator(LogoutCoordinator(stoppingPurger(), markerStore, logoutScope))
        startMovie()
        events.clear()

        listener.onIsPlayingChanged(false) // pause save, read while still in session…
        logoutScope.launch { coordinator.logOut() } // …and the purge completes before it writes
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), events)
        logoutScope.cancel()
    }

    // ── Getter, controls and callbacks ─────────────────────────────────────────

    @Test
    fun `after a logout the screen's getter, controls and callbacks never rebuild the player`() {
        startMovie()
        logOutNow()

        assertNull(viewModel.player)
        viewModel.togglePlayPause()
        viewModel.seekForward()
        viewModel.seekBackward()
        viewModel.seekTo(5_000L)
        viewModel.selectAudioTrack("audio-1")
        viewModel.selectSubtitleTrack("sub-1")
        viewModel.disableSubtitles()
        listener.onPlaybackStateChanged(Player.STATE_IDLE)
        listener.onTracksChanged(Tracks.EMPTY)
        listener.onIsPlayingChanged(false)
        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `a live channel ending after a logout shows nothing and its retry reopens nothing`() {
        viewModel.initialize(LIVE_URL, "77")
        testDispatcher.scheduler.runCurrent()
        logOutNow()

        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `a live channel that ended before a logout is not reopened by a retry after it`() {
        viewModel.initialize(LIVE_URL, "77")
        testDispatcher.scheduler.runCurrent()
        listener.onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue(viewModel.uiState.value.hasError)
        logOutNow()

        viewModel.retry()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `leaving after a logout neither saves for the old profile nor rebuilds the player`() {
        startMovie()
        logOutNow()

        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `a pick on the old screen after a logout is refused before anything is downloaded`() {
        startMovie()
        logOutNow()

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
        assertEquals(LOGOUT_MESSAGE, viewModel.uiState.value.onlineSubtitles.applyError)
    }

    // ── Online search ──────────────────────────────────────────────────────────

    @Test
    fun `opening or retrying the search after a logout sends the title nowhere`() {
        startMovie()
        logOutNow()

        viewModel.openOnlineSubtitleSearch()
        viewModel.retryOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
        assertEquals(OnlineSubtitleSearchState.Idle, viewModel.uiState.value.onlineSubtitles.search)
    }

    @Test
    fun `opening the search while a purge is running sends nothing, even once it ends`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(stoppingPurger(purgeGate), markerStore, applicationScope))
        startMovie()
        logOutNow()

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        purgeGate.complete(Unit)
        viewModel.retryOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
    }

    @Test
    fun `a search asked right before the logout but not yet sent is never sent`() {
        // Unconfined: the whole logout runs between the button and the search coroutine's start.
        val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCoordinator(LogoutCoordinator(stoppingPurger(), markerStore, logoutScope))
        startMovie()

        viewModel.openOnlineSubtitleSearch()
        logoutScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("purge"), eventsFromPurge())
        assertTrue(viewModel.uiState.value.onlineSubtitles.search !is OnlineSubtitleSearchState.Results)
        logoutScope.cancel()
    }

    @Test
    fun `a search answered after the logout never shows, even from a client deaf to cancellation`() {
        val answer = CompletableDeferred<Unit>()
        coEvery { client.search(any()) } coAnswers {
            events += "search"
            withContext(NonCancellable) { answer.await() }
            OnlineSubtitleSearchResult.Found(listOf(FRENCH))
        }
        startMovie()
        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        logOutNow()
        answer.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.onlineSubtitles.search !is OnlineSubtitleSearchState.Results)
        assertEquals(listOf("purge"), eventsFromPurge())
    }

    // ── A screen created before initialize: its session is the one it was created in ─

    /** Everything an old route can still do once [PlayerViewModel.initialize] finally runs. */
    private fun initializeAndUse(screen: PlayerViewModel) {
        screen.initialize(MOVIE_URL, "42", MOVIE_CONTEXT)
        testDispatcher.scheduler.runCurrent()
        assertNull(screen.player)
        screen.openOnlineSubtitleSearch()
        screen.retry()
        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()
    }

    private fun assertNothingAfterPurge() {
        assertEquals(listOf("purge"), eventsFromPurge())
        coVerify(exactly = 0) { client.search(any()) }
        coVerify(exactly = 0) { progressRepository.upsertProgress(any()) }
        coVerify(exactly = 0) { appPreferencesStore.getActiveProfileId() }
    }

    @Test
    fun `a screen created before a completed logout and initialized after it plays nothing`() {
        val screen = newViewModel()
        logOutNow()

        initializeAndUse(screen)

        assertNothingAfterPurge()
    }

    @Test
    fun `a screen created before a running logout and initialized during it plays nothing`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(stoppingPurger(purgeGate), markerStore, applicationScope))
        val screen = newViewModel()
        logOutNow()

        initializeAndUse(screen)
        purgeGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        screen.retry()
        screen.openOnlineSubtitleSearch()
        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()

        assertNothingAfterPurge()
    }

    @Test
    fun `a screen created while a purge runs plays nothing, during it or after it`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(stoppingPurger(purgeGate), markerStore, applicationScope))
        logOutNow()
        val screen = newViewModel()

        initializeAndUse(screen)
        purgeGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        screen.retry()
        screen.openOnlineSubtitleSearch()
        testDispatcher.scheduler.advanceTimeBy(30_000L)
        testDispatcher.scheduler.runCurrent()

        assertNothingAfterPurge()
    }

    @Test
    fun `a screen created after a failed purge, still owed, plays nothing`() {
        val failingPurger = object : LogoutPurger {
            override suspend fun logOut() {
                markerStore.markPurgePending()
                events += "purge"
                playbackStopped = true
                throw IllegalStateException("disk full")
            }

            override suspend fun recoverIfNeeded(): Boolean = false
        }
        useCoordinator(LogoutCoordinator(failingPurger, markerStore, applicationScope))
        logOutNow()
        val screen = newViewModel()

        initializeAndUse(screen)

        assertNothingAfterPurge()
    }

    @Test
    fun `without a logout, a screen created then initialized plays, searches and saves`() {
        val screen = newViewModel()
        screen.initialize(MOVIE_URL, "42", MOVIE_CONTEXT)
        testDispatcher.scheduler.runCurrent()
        assertNotNull(screen.player)
        screen.openOnlineSubtitleSearch()
        testDispatcher.scheduler.advanceTimeBy(7_500L)
        testDispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("prepare", "search", "upsert $OLD_PROFILE"),
            events.filterNot { it == "read tracks" },
        )
        assertEquals(
            OnlineSubtitleSearchState.Results(listOf(FRENCH)),
            screen.uiState.value.onlineSubtitles.search,
        )
    }

    // ── The next account opens its own player afterwards ───────────────────────

    @Test
    fun `the old screen never touches the next account's playback`() {
        startMovie()
        listener.onPlayerError(mockk<PlaybackException>(relaxed = true))
        val oldScreen = viewModel
        val oldListener = listener
        logOutNow()

        // The next account signs in and opens a player: a fresh player, a fresh screen.
        playbackStopped = false
        coEvery { appPreferencesStore.getActiveProfileId() } returns NEXT_PROFILE
        val nextScreen = newViewModel()
        nextScreen.initialize(NEXT_MOVIE_URL, "7")
        testDispatcher.scheduler.runCurrent()
        events.clear()

        // Everything the old screen can still do.
        assertNull(oldScreen.player)
        oldScreen.retry()
        oldScreen.togglePlayPause()
        oldScreen.seekTo(5_000L)
        oldScreen.selectAudioTrack("audio-1")
        oldListener.onIsPlayingChanged(false)
        oldScreen.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        assertEquals(emptyList<String>(), events)
        coVerify(exactly = 0) { progressRepository.upsertProgress(match { it.profileId == OLD_PROFILE }) }
        // The next account's own screen still plays, and saves under its own profile.
        assertNotNull(nextScreen.player)
        testDispatcher.scheduler.advanceTimeBy(7_500L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("upsert $NEXT_PROFILE"), events)
        nextScreen.releasePlayer()
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("upsert $NEXT_PROFILE", "release", "upsert $NEXT_PROFILE"), events)
    }

    // ── No logout: everything still works ──────────────────────────────────────

    @Test
    fun `without a logout, retry, the ticker and leaving all still play and save`() {
        startMovie()
        assertNotNull(viewModel.player)
        testDispatcher.scheduler.advanceTimeBy(7_500L)
        testDispatcher.scheduler.runCurrent()
        listener.onPlayerError(mockk<PlaybackException>(relaxed = true))
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("prepare", "upsert $OLD_PROFILE", "prepare", "release", "upsert $OLD_PROFILE"),
            events.filterNot { it == "read tracks" },
        )
        // A PlayerView re-bound after leaving must get no player, not a fresh one.
        assertNull(viewModel.player)
    }

    private companion object {
        const val MOVIE_URL = "http://example.com:8080/movie/old-user/old-pass/42.mp4"
        const val LIVE_URL = "http://example.com:8080/live/old-user/old-pass/77.ts"
        const val NEXT_MOVIE_URL = "http://example.com:8080/movie/next-user/next-pass/7.mp4"
        const val OLD_PROFILE = "old-profile"
        const val NEXT_PROFILE = "next-profile"
        const val POSITION_MS = 42_000L
        const val DURATION_MS = 100_000L
        val MOVIE_CONTEXT = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Up", year = 2009)
        val FRENCH = OnlineSubtitle(fileId = 1001L, language = "fr", release = "Up.2009.1080p")
    }
}
