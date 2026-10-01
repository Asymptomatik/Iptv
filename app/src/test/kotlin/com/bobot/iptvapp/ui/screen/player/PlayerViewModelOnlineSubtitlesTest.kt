package com.bobot.iptvapp.ui.screen.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.bobot.iptvapp.data.logout.FakeDownloadedSubtitlePurger
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.preferences.FakeLogoutPurgeMarkerStore
import com.bobot.iptvapp.data.remote.opensubtitles.OnlineSubtitleFileStore
import com.bobot.iptvapp.data.remote.opensubtitles.OnlineSubtitleVisit
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesClient
import com.bobot.iptvapp.data.remote.opensubtitles.OpenSubtitlesDownloader
import com.bobot.iptvapp.domain.logout.FakeLogoutPurger
import com.bobot.iptvapp.domain.logout.LogoutPurger
import com.bobot.iptvapp.domain.model.ExternalSubtitle
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.OnlineSubtitle
import com.bobot.iptvapp.domain.model.OnlineSubtitleDownloadResult
import com.bobot.iptvapp.domain.model.OnlineSubtitleSearchResult
import com.bobot.iptvapp.domain.model.PlaybackProgress
import com.bobot.iptvapp.domain.model.SubtitleSearchContext
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.player.ExoPlayerManager
import com.bobot.iptvapp.player.IptvMediaSourceFactory
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

/**
 * The online subtitle search and pick in [PlayerViewModel] — follows [PlayerViewModelTest]'s
 * `runCurrent()` convention (the progress ticker never goes idle).
 *
 * The OpenSubtitles client and downloader are mocked (no network, no key); the logout side is a
 * real [LogoutCoordinator] over fakes, because what the pick must respect is the coordinator's
 * actual refusal rule and session generation, not a stubbed answer.
 */
class PlayerViewModelOnlineSubtitlesTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var player: Player
    private lateinit var playerManager: PlayerManager
    private lateinit var client: OpenSubtitlesClient
    private lateinit var downloader: OpenSubtitlesDownloader
    private lateinit var applicationScope: CoroutineScope
    private lateinit var markerStore: FakeLogoutPurgeMarkerStore
    private lateinit var coordinator: LogoutCoordinator
    private lateinit var subtitlePurger: FakeDownloadedSubtitlePurger
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var progressRepository: PlaybackProgressRepository
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var viewModel: PlayerViewModel

    private val listenerSlot = slot<Player.Listener>()
    private val subtitleTracks = mutableListOf<PlayerTrack>()

    /** What the ViewModel handed to [PlayerManager.setSideLoadedSubtitleErrorListener] last. */
    private var subtitleErrorListener: ((String) -> Unit)? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        player = mockk(relaxed = true)
        every { player.addListener(capture(listenerSlot)) } just Runs
        every { player.currentPosition } returns 0L
        playerManager = mockk()
        every { playerManager.player } returns player
        every { playerManager.prepare(any(), any(), any()) } just Runs
        every { playerManager.release() } just Runs
        every { playerManager.getAudioTracks() } returns emptyList()
        every { playerManager.getSubtitleTracks() } answers { subtitleTracks.toList() }
        every { playerManager.selectSubtitleTrack(any()) } just Runs
        every { playerManager.disableSubtitles() } just Runs
        every { playerManager.setSideLoadedSubtitleErrorListener(any()) } answers { subtitleErrorListener = firstArg() }

        client = mockk()
        downloader = mockk()
        applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)
        markerStore = FakeLogoutPurgeMarkerStore()
        subtitlePurger = FakeDownloadedSubtitlePurger()
        useCoordinator(LogoutCoordinator(FakeLogoutPurger(), markerStore, applicationScope))
    }

    /** (Re)builds [viewModel] around [logoutCoordinator] — for the tests that need their own. */
    private fun useCoordinator(logoutCoordinator: LogoutCoordinator) {
        coordinator = logoutCoordinator
        appPreferencesStore = mockk()
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        progressRepository = mockk()
        coEvery { progressRepository.upsertProgress(any()) } just Runs
        catalogRepository = mockk()
        coEvery { catalogRepository.getMovieDetail(any()) } returns Resource.Success(movieWith(XTREAM_SUBTITLES))

        viewModel = PlayerViewModel(
            playerManager = playerManager,
            playbackProgressRepository = progressRepository,
            appPreferencesStore = appPreferencesStore,
            catalogRepository = catalogRepository,
            openSubtitlesClient = client,
            openSubtitlesDownloader = downloader,
            logoutCoordinator = coordinator,
            downloadedSubtitlePurger = subtitlePurger,
        )
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        Dispatchers.resetMain()
    }

    private val online get() = viewModel.uiState.value.onlineSubtitles

    private fun startMovie() {
        viewModel.initialize(MOVIE_URL, "42", MOVIE_CONTEXT)
        testDispatcher.scheduler.runCurrent()
    }

    private fun searchFinds(vararg subtitles: OnlineSubtitle) {
        coEvery { client.search(MOVIE_CONTEXT) } returns OnlineSubtitleSearchResult.Found(subtitles.toList())
    }

    private fun downloadGives(result: OnlineSubtitleDownloadResult, gate: CompletableDeferred<Unit>? = null) {
        coEvery { downloader.download(any(), any(), any()) } coAnswers {
            gate?.await()
            result
        }
    }

    /** The external subtitles handed to every `prepare`, in call order. */
    private fun preparedExternals(): List<List<ExternalSubtitle>> {
        val all = mutableListOf<List<ExternalSubtitle>>()
        verify { playerManager.prepare(any(), any(), capture(all)) }
        return all
    }

    private fun pushTracks(vararg tracks: PlayerTrack) {
        subtitleTracks.clear()
        subtitleTracks += tracks
        listenerSlot.captured.onTracksChanged(Tracks.EMPTY)
    }

    // ── Availability ───────────────────────────────────────────────────────────

    @Test
    fun `the search is offered for a movie or an episode with a context`() {
        startMovie()

        assertTrue(online.isAvailable)
    }

    @Test
    fun `a live channel never offers the search and opening it asks nothing`() {
        viewModel.initialize("http://example.com:8080/live/u/p/77.ts", "77", MOVIE_CONTEXT)
        testDispatcher.scheduler.runCurrent()

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertFalse(online.isAvailable)
        assertFalse(online.isPanelOpen)
        coVerify(exactly = 0) { client.search(any()) }
    }

    @Test
    fun `a route without metadata offers no search`() {
        viewModel.initialize(MOVIE_URL, "42")
        testDispatcher.scheduler.runCurrent()

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertFalse(online.isAvailable)
        coVerify(exactly = 0) { client.search(any()) }
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    @Test
    fun `opening the search shows loading, then the results to pick from by hand`() {
        startMovie()
        val gate = CompletableDeferred<Unit>()
        coEvery { client.search(MOVIE_CONTEXT) } coAnswers {
            gate.await()
            OnlineSubtitleSearchResult.Found(listOf(FRENCH, ENGLISH))
        }

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        assertTrue(online.isPanelOpen)
        assertEquals(OnlineSubtitleSearchState.Loading, online.search)

        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnlineSubtitleSearchState.Results(listOf(FRENCH, ENGLISH)), online.search)
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `no result shows the empty state`() {
        startMovie()
        coEvery { client.search(MOVIE_CONTEXT) } returns OnlineSubtitleSearchResult.NoResults

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnlineSubtitleSearchState.Empty, online.search)
    }

    @Test
    fun `search failures become French messages, retryable only when retrying can help`() {
        startMovie()
        val expectations = listOf(
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.MISSING_API_KEY) to
                OnlineSubtitleSearchState.Failed(MISSING_KEY_MESSAGE, canRetry = false),
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.UNAUTHORIZED, httpCode = 401) to
                OnlineSubtitleSearchState.Failed(KEY_REFUSED_MESSAGE, canRetry = false),
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.FORBIDDEN, httpCode = 403) to
                OnlineSubtitleSearchState.Failed(KEY_REFUSED_MESSAGE, canRetry = false),
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.RATE_LIMITED, retryAfterSeconds = 3, httpCode = 429) to
                OnlineSubtitleSearchState.Failed(rateLimitedMessage(3), canRetry = true),
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.NETWORK) to
                OnlineSubtitleSearchState.Failed(NETWORK_MESSAGE, canRetry = true),
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.TIMEOUT) to
                OnlineSubtitleSearchState.Failed(NETWORK_MESSAGE, canRetry = true),
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.INVALID_RESPONSE) to
                OnlineSubtitleSearchState.Failed(UNEXPECTED_MESSAGE, canRetry = true),
        )

        expectations.forEach { (failure, expected) ->
            coEvery { client.search(MOVIE_CONTEXT) } returns failure
            viewModel.retryOnlineSubtitleSearch()
            testDispatcher.scheduler.runCurrent()

            assertEquals("for ${failure.reason}", expected, online.search)
        }
        assertTrue("the rate-limit message names the delay", rateLimitedMessage(3).contains("3 s"))
        assertFalse(rateLimitedMessage(null).contains("null"))
        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `retry after a failure searches again and shows the results`() {
        startMovie()
        coEvery { client.search(MOVIE_CONTEXT) } returns
            OnlineSubtitleSearchResult.Failed(OnlineSubtitleSearchResult.Reason.NETWORK) andThen
            OnlineSubtitleSearchResult.Found(listOf(FRENCH))

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        viewModel.retryOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnlineSubtitleSearchState.Results(listOf(FRENCH)), online.search)
        coVerify(exactly = 2) { client.search(MOVIE_CONTEXT) }
    }

    @Test
    fun `reopening the panel keeps the results instead of searching again`() {
        startMovie()
        searchFinds(FRENCH)

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        viewModel.closeOnlineSubtitleSearch()
        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnlineSubtitleSearchState.Results(listOf(FRENCH)), online.search)
        coVerify(exactly = 1) { client.search(MOVIE_CONTEXT) }
    }

    @Test
    fun `a result arriving after the panel was closed is dropped`() {
        startMovie()
        val gate = CompletableDeferred<Unit>()
        coEvery { client.search(MOVIE_CONTEXT) } coAnswers {
            gate.await()
            OnlineSubtitleSearchResult.Found(listOf(FRENCH))
        }

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        viewModel.closeOnlineSubtitleSearch()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertFalse(online.isPanelOpen)
        assertEquals(OnlineSubtitleSearchState.Idle, online.search)
    }

    @Test
    fun `an older search finishing late never overwrites a newer one`() {
        startMovie()
        val slowGate = CompletableDeferred<Unit>()
        coEvery { client.search(MOVIE_CONTEXT) } coAnswers {
            slowGate.await()
            OnlineSubtitleSearchResult.Found(listOf(ENGLISH))
        } andThen OnlineSubtitleSearchResult.Found(listOf(FRENCH))

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        viewModel.retryOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        slowGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnlineSubtitleSearchState.Results(listOf(FRENCH)), online.search)
    }

    @Test
    fun `a search finishing after the player was left changes nothing`() {
        startMovie()
        val gate = CompletableDeferred<Unit>()
        coEvery { client.search(MOVIE_CONTEXT) } coAnswers {
            gate.await()
            OnlineSubtitleSearchResult.Found(listOf(FRENCH))
        }

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        viewModel.releasePlayer()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertFalse(online.search is OnlineSubtitleSearchState.Results)
    }

    // ── Pick ───────────────────────────────────────────────────────────────────

    @Test
    fun `a pick re-prepares at the position reached when the file arrives, with the Xtream subtitles kept`() {
        startMovie()
        val gate = CompletableDeferred<Unit>()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT), gate)
        every { player.currentPosition } returns 12_000L

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        assertEquals(FRENCH.fileId, online.applyingFileId)

        // Playback went on while the file downloaded: the real position is the later one.
        every { player.currentPosition } returns 47_000L
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        val externals = preparedExternals()
        assertEquals(2, externals.size)
        verify { playerManager.prepare(MOVIE_URL, 47_000L, any()) }
        val second = externals[1]
        assertEquals(XTREAM_SUBTITLES, second.dropLast(1))
        assertEquals(LOCAL_SRT.url, second.last().url)
        assertEquals(LOCAL_SRT.language, second.last().language)
        assertTrue("the online track must be identifiable", !second.last().trackId.isNullOrBlank())
        assertNull(online.applyingFileId)
        assertEquals(FRENCH.fileId, online.appliedFileId)
        assertNull(online.applyError)
    }

    @Test
    fun `a paused video stays paused after the pick, a playing one keeps playing`() {
        startMovie()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        every { player.playWhenReady } returns false
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        verify(exactly = 1) { player.pause() }

        every { player.playWhenReady } returns true
        viewModel.selectOnlineSubtitle(ENGLISH)
        testDispatcher.scheduler.runCurrent()
        verify(exactly = 1) { player.pause() }
        verify(exactly = 3) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `the online track is switched on once the new tracks list carries it, never from a stale one`() {
        startMovie()
        pushTracks(PlayerTrack("subtitle-2-0", "Anglais", "en", isSelected = true, type = PlayerTrackType.SUBTITLE))
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val onlineTrackId = preparedExternals().last().last().trackId!!

        // Stale snapshot (old media still reported) — nothing to select yet.
        pushTracks(PlayerTrack("subtitle-2-0", "Anglais", "en", isSelected = true, type = PlayerTrackType.SUBTITLE))
        verify(exactly = 0) { playerManager.selectSubtitleTrack(any()) }

        pushTracks(
            PlayerTrack("subtitle-2-0", "Anglais", "en", isSelected = false, type = PlayerTrackType.SUBTITLE),
            PlayerTrack(onlineTrackId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )
        verify(exactly = 1) { playerManager.selectSubtitleTrack(onlineTrackId) }

        // Further track updates never force it again over the user's own choice.
        pushTracks(
            PlayerTrack("subtitle-2-0", "Anglais", "en", isSelected = true, type = PlayerTrackType.SUBTITLE),
            PlayerTrack(onlineTrackId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )
        verify(exactly = 1) { playerManager.selectSubtitleTrack(onlineTrackId) }
    }

    // Once merged, Media3 (MergingMediaPeriod, 1.4.1) reports every track id as
    // "<child index>:<id>". This one runs the pick through the real ExoPlayerManager, on a snapshot
    // built that way, rather than on a mock that hands the requested id straight back.

    @Test
    fun `the online track is switched on from the merged snapshot Media3 really reports`() {
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        val listeners = mutableListOf<Player.Listener>()
        every { exoPlayer.addListener(capture(listeners)) } just Runs
        var parameters = TrackSelectionParameters.DEFAULT
        every { exoPlayer.trackSelectionParameters } answers { parameters }
        every { exoPlayer.trackSelectionParameters = any() } answers { parameters = firstArg() }
        var tracks = Tracks.EMPTY
        every { exoPlayer.currentTracks } answers { tracks }
        val requested = mutableListOf<List<ExternalSubtitle>>()
        val mediaSourceFactory = mockk<IptvMediaSourceFactory>(relaxed = true)
        every { mediaSourceFactory.create(any(), capture(requested), any()) } returns mockk(relaxed = true)
        val manager = ExoPlayerManager(mockk(relaxed = true), mediaSourceFactory)
        ExoPlayerManager::class.java.getDeclaredField("exoPlayer").apply { isAccessible = true }.set(manager, exoPlayer)
        playerManager = manager
        useCoordinator(coordinator)
        fun report(vararg groups: Tracks.Group) {
            tracks = Tracks(groups.toList())
            listeners.forEach { it.onTracksChanged(tracks) }
        }

        startMovie()
        val embedded = mergedTextGroup(child = 0, id = "3", language = "eng")
        report(embedded)
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val onlineTrackId = requested.last().last().trackId!!

        // Video, then the Xtream subtitle, then the online one: children 0, 1 and 2.
        val online = mergedTextGroup(child = 2, id = onlineTrackId, language = "fr")
        report(embedded, mergedTextGroup(child = 1, id = null, language = "en"), online)

        assertEquals(TrackSelectionOverride(online.mediaTrackGroup, 0), parameters.overrides[online.mediaTrackGroup])
        assertEquals(1, parameters.overrides.size)
        assertTrue(viewModel.uiState.value.subtitleTracks.any { it.id == onlineTrackId })
    }

    /** A text group as `MergingMediaPeriod` reports child [child]'s single track of id [id]. */
    private fun mergedTextGroup(child: Int, id: String?, language: String): Tracks.Group =
        Tracks.Group(
            TrackGroup(
                "$child:0",
                Format.Builder().setId("$child:${id.orEmpty()}").setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
                    .setLanguage(language).build(),
            ),
            /* adaptiveSupported= */ false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )

    @Test
    fun `picking the same subtitle twice gives the new track an id the old list cannot carry`() {
        startMovie()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val firstId = preparedExternals().last().last().trackId
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val secondId = preparedExternals().last().last().trackId

        assertTrue(firstId != secondId)
        // The previous online file is replaced, not stacked.
        assertEquals(XTREAM_SUBTITLES.size + 1, preparedExternals().last().size)
    }

    @Test
    fun `a manual track choice before the online track shows up cancels the automatic switch`() {
        startMovie()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val onlineTrackId = preparedExternals().last().last().trackId!!
        viewModel.disableSubtitles()
        pushTracks(PlayerTrack(onlineTrackId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))

        verify(exactly = 0) { playerManager.selectSubtitleTrack(onlineTrackId) }
    }

    @Test
    fun `download failures keep the video untouched and explain in French`() {
        startMovie()
        val expectations = mapOf(
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.MISSING_API_KEY) to MISSING_KEY_MESSAGE,
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.UNAUTHORIZED, httpCode = 401) to KEY_REFUSED_MESSAGE,
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.FORBIDDEN, httpCode = 403) to KEY_REFUSED_MESSAGE,
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.QUOTA_EXCEEDED, httpCode = 406) to QUOTA_MESSAGE,
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.RATE_LIMITED, httpCode = 429) to rateLimitedMessage(null),
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.NETWORK) to NETWORK_MESSAGE,
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.LINK_EXPIRED, httpCode = 410) to LINK_EXPIRED_MESSAGE,
            OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.UNSUPPORTED_FORMAT) to INVALID_FORMAT_MESSAGE,
        )

        expectations.forEach { (failure, message) ->
            downloadGives(failure)
            viewModel.selectOnlineSubtitle(FRENCH)
            testDispatcher.scheduler.runCurrent()

            assertEquals("for ${failure.reason}", message, online.applyError)
            assertNull(online.applyingFileId)
        }
        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
        verify(exactly = 0) { player.stop() }
        assertNull(online.appliedFileId)

        viewModel.dismissOnlineSubtitleError()
        assertNull(online.applyError)
    }

    @Test
    fun `the player's retry after a pick keeps the reached position and the online track`() {
        startMovie()
        every { player.currentPosition } returns 47_000L
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val pickedExternals = preparedExternals().last()

        listenerSlot.captured.onPlayerError(mockk(relaxed = true))
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 2) { playerManager.prepare(MOVIE_URL, 47_000L, any()) }
        val retried = preparedExternals().last()
        assertEquals(pickedExternals.dropLast(1), retried.dropLast(1))
        assertEquals(pickedExternals.last().url, retried.last().url)
        assertEquals(pickedExternals.last().language, retried.last().language)
    }

    // ── Retry with an online track ─────────────────────────────────────────────
    //
    // Media3 ties a selection to the track groups it was made on: the retried media has new
    // groups, so the online track the user had on must be switched on again, by the ViewModel.

    /** The retried prepare's online track id, after an error and [PlayerViewModel.retry]. */
    private fun failAndRetry(): String {
        listenerSlot.captured.onPlayerError(mockk(relaxed = true))
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()
        return preparedExternals().last().last().trackId!!
    }

    @Test
    fun `retry switches the online track the user had on back on, from the retried media's snapshot only`() {
        val pickedId = pickFrench()
        pushTracks(PlayerTrack(pickedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))
        verify(exactly = 1) { playerManager.selectSubtitleTrack(pickedId) }
        pushTracks(PlayerTrack(pickedId, "Français", "fr", isSelected = true, type = PlayerTrackType.SUBTITLE))

        val retriedId = failAndRetry()

        // The failed media's snapshot, still reported right after the prepare: nothing selected.
        pushTracks(PlayerTrack(pickedId, "Français", "fr", isSelected = true, type = PlayerTrackType.SUBTITLE))
        verify(exactly = 1) { playerManager.selectSubtitleTrack(any()) }

        pushTracks(
            PlayerTrack("subtitle-1-0", "Anglais", "en", isSelected = false, type = PlayerTrackType.SUBTITLE),
            PlayerTrack(retriedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )
        verify(exactly = 1) { playerManager.selectSubtitleTrack(retriedId) }

        // Later updates never force it over what the user picks next.
        pushTracks(
            PlayerTrack("subtitle-1-0", "Anglais", "en", isSelected = true, type = PlayerTrackType.SUBTITLE),
            PlayerTrack(retriedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )
        verify(exactly = 1) { playerManager.selectSubtitleTrack(retriedId) }
        verify(exactly = 2) { playerManager.selectSubtitleTrack(any()) }
        verify(exactly = 0) { playerManager.disableSubtitles() }
    }

    @Test
    fun `retry before the online track showed up still switches it on once the retried media carries it`() {
        pickFrench()

        val retriedId = failAndRetry()
        pushTracks(PlayerTrack(retriedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))

        verify(exactly = 1) { playerManager.selectSubtitleTrack(retriedId) }
    }

    @Test
    fun `retry keeps an online track the user had switched off available, but off`() {
        val pickedId = pickFrench()
        pushTracks(PlayerTrack(pickedId, "Français", "fr", isSelected = true, type = PlayerTrackType.SUBTITLE))
        viewModel.disableSubtitles()
        pushTracks(PlayerTrack(pickedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))

        val retriedId = failAndRetry()
        pushTracks(PlayerTrack(retriedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))

        assertEquals(LOCAL_SRT.url, preparedExternals().last().last().url)
        verify(exactly = 0) { playerManager.selectSubtitleTrack(retriedId) }
    }

    @Test
    fun `retry keeps another track the user switched to over the online one`() {
        val pickedId = pickFrench()
        pushTracks(
            PlayerTrack("subtitle-1-0", "Anglais", "en", isSelected = false, type = PlayerTrackType.SUBTITLE),
            PlayerTrack(pickedId, "Français", "fr", isSelected = true, type = PlayerTrackType.SUBTITLE),
        )
        viewModel.selectSubtitleTrack("subtitle-1-0")

        val retriedId = failAndRetry()
        pushTracks(
            PlayerTrack("subtitle-1-0", "Anglais", "en", isSelected = false, type = PlayerTrackType.SUBTITLE),
            PlayerTrack(retriedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE),
        )

        verify(exactly = 0) { playerManager.selectSubtitleTrack(retriedId) }
    }

    @Test
    fun `retry after the online track failed switches nothing on`() {
        val pickedId = pickFrench()
        onlineTrackFails(pickedId)

        listenerSlot.captured.onPlayerError(mockk(relaxed = true))
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()
        pushTracks(PlayerTrack(pickedId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))

        verify(exactly = 0) { playerManager.selectSubtitleTrack(any()) }
    }

    @Test
    fun `retry resumes where playback had reached since the pick, paused if it was`() {
        pickFrench() // prepared at 47 s
        every { player.currentPosition } returns 83_000L
        every { player.playWhenReady } returns false

        failAndRetry()

        verify { playerManager.prepare(MOVIE_URL, 83_000L, any()) }
        verify(exactly = 1) { player.pause() }
        assertEquals(83_000L, viewModel.uiState.value.currentPositionMs)
    }

    @Test
    fun `retry with no media left in the player resumes from the last known position, playing if it was`() {
        pickFrench() // prepared at 47 s
        every { player.currentMediaItem } returns null
        every { player.currentPosition } returns 0L

        failAndRetry()

        verify(exactly = 2) { playerManager.prepare(MOVIE_URL, 47_000L, any()) }
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `a newer pick supersedes one still downloading`() {
        startMovie()
        val slowGate = CompletableDeferred<Unit>()
        coEvery { downloader.download(FRENCH, any(), any()) } coAnswers {
            slowGate.await()
            OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT)
        }
        coEvery { downloader.download(ENGLISH, any(), any()) } returns OnlineSubtitleDownloadResult.Downloaded(LOCAL_EN_SRT)

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        viewModel.selectOnlineSubtitle(ENGLISH)
        testDispatcher.scheduler.runCurrent()
        slowGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(2, preparedExternals().size)
        assertEquals(LOCAL_EN_SRT.url, preparedExternals().last().last().url)
        assertEquals(ENGLISH.fileId, online.appliedFileId)
    }

    @Test
    fun `a download finishing after the player was left never re-prepares`() {
        startMovie()
        val gate = CompletableDeferred<Unit>()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT), gate)

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        viewModel.releasePlayer()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `a pick is refused while a logout purge is owed, before anything is downloaded`() {
        startMovie()
        applicationScope.launch { markerStore.markPurgePending() }
        testDispatcher.scheduler.runCurrent()

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
        assertEquals(LOGOUT_MESSAGE, online.applyError)
        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
    }

    // ── Online track failing in the player ─────────────────────────────────────
    //
    // Media3 reports a side-loaded file that cannot be read or parsed as a load error of that
    // source, not as a player error: the manager hands its track id to the listener the ViewModel
    // registered. Mocked here — the Media3 side is covered by ExoPlayerManagerTest and the
    // instrumented SideLoadedSubtitleFailureTest.

    /** Picks [FRENCH] at 47 s and returns the track id it was prepared with. */
    private fun pickFrench(): String {
        startMovie()
        every { player.currentPosition } returns 47_000L
        every { player.playWhenReady } returns true
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        return preparedExternals().last().last().trackId!!
    }

    private fun onlineTrackFails(trackId: String) {
        subtitleErrorListener!!.invoke(trackId)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `the player is told who to report a failing side-loaded subtitle to`() {
        startMovie()

        assertTrue(subtitleErrorListener != null)
    }

    @Test
    fun `a failing online track is dropped and the video re-prepared once without it, where it had reached`() {
        val trackId = pickFrench()
        every { player.currentPosition } returns 52_000L

        // Every failed attempt is reported: only the first may act.
        subtitleErrorListener!!.invoke(trackId)
        subtitleErrorListener!!.invoke(trackId)
        testDispatcher.scheduler.runCurrent()
        onlineTrackFails(trackId)

        verify(exactly = 3) { playerManager.prepare(any(), any(), any()) }
        verify { playerManager.prepare(MOVIE_URL, 52_000L, XTREAM_SUBTITLES) }
        verify(exactly = 0) { player.pause() }
        assertNull(online.appliedFileId)
        assertEquals(ONLINE_TRACK_FAILED_MESSAGE, online.applyError)
        assertFalse(viewModel.uiState.value.hasError)
    }

    @Test
    fun `a video paused when its online track fails stays paused`() {
        val trackId = pickFrench()
        every { player.playWhenReady } returns false

        onlineTrackFails(trackId)

        verify(exactly = 3) { playerManager.prepare(any(), any(), any()) }
        verify(exactly = 1) { player.pause() }
    }

    @Test
    fun `the failed track is never switched on from a snapshot of the media it failed in`() {
        val trackId = pickFrench()

        onlineTrackFails(trackId)
        pushTracks(PlayerTrack(trackId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))

        verify(exactly = 0) { playerManager.selectSubtitleTrack(trackId) }
    }

    @Test
    fun `retry after the fallback never brings the failed track back`() {
        val trackId = pickFrench()
        onlineTrackFails(trackId)

        listenerSlot.captured.onPlayerError(mockk(relaxed = true))
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()

        assertEquals(XTREAM_SUBTITLES, preparedExternals().last())
    }

    @Test
    fun `a failure of a track no longer applied, or of no online track, changes nothing`() {
        val frenchId = pickFrench()
        coEvery { downloader.download(ENGLISH, any(), any()) } returns OnlineSubtitleDownloadResult.Downloaded(LOCAL_EN_SRT)
        viewModel.selectOnlineSubtitle(ENGLISH)
        testDispatcher.scheduler.runCurrent()

        onlineTrackFails(frenchId)
        onlineTrackFails("subtitle-1-0")

        verify(exactly = 3) { playerManager.prepare(any(), any(), any()) }
        assertEquals(ENGLISH.fileId, online.appliedFileId)
        assertNull(online.applyError)
    }

    @Test
    fun `a video error with no subtitle failure keeps the former behaviour, online track included`() {
        pickFrench()

        listenerSlot.captured.onPlayerError(mockk(relaxed = true))
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.hasError)
        assertEquals(FRENCH.fileId, online.appliedFileId)
        assertNull(online.applyError)
        verify(exactly = 2) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `a track failing while the video is in error is dropped without re-preparing behind the overlay`() {
        val trackId = pickFrench()
        listenerSlot.captured.onPlayerError(mockk(relaxed = true))

        onlineTrackFails(trackId)

        verify(exactly = 2) { playerManager.prepare(any(), any(), any()) }
        assertTrue(viewModel.uiState.value.hasError)
        assertNull(online.appliedFileId)
        viewModel.retry()
        testDispatcher.scheduler.runCurrent()
        verify { playerManager.prepare(MOVIE_URL, 47_000L, XTREAM_SUBTITLES) }
    }

    @Test
    fun `a retry already on its way makes the fallback's own re-prepare unnecessary`() {
        val trackId = pickFrench()
        listenerSlot.captured.onPlayerError(mockk(relaxed = true))
        viewModel.retry() // queued, not run yet
        // The overlay is cleared by the retry itself only when it runs; the failure lands before.
        subtitleErrorListener!!.invoke(trackId)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 3) { playerManager.prepare(any(), any(), any()) }
        assertEquals(XTREAM_SUBTITLES, preparedExternals().last())
    }

    @Test
    fun `a new pick replacing the media before the fallback runs is not re-prepared a second time`() {
        val trackId = pickFrench()
        val gate = CompletableDeferred<Unit>()
        coEvery { downloader.download(ENGLISH, any(), any()) } coAnswers {
            gate.await()
            OnlineSubtitleDownloadResult.Downloaded(LOCAL_EN_SRT)
        }
        viewModel.selectOnlineSubtitle(ENGLISH)
        testDispatcher.scheduler.runCurrent()
        gate.complete(Unit) // the English pick resumes first…
        subtitleErrorListener!!.invoke(trackId) // …then the French track's failure lands
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 3) { playerManager.prepare(any(), any(), any()) }
        assertEquals(LOCAL_EN_SRT.url, preparedExternals().last().last().url)
        assertEquals(ENGLISH.fileId, online.appliedFileId)
    }

    @Test
    fun `a failure reported after the player was left re-prepares nothing`() {
        val trackId = pickFrench()
        viewModel.releasePlayer()

        onlineTrackFails(trackId)

        verify(exactly = 2) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `a failure whose fallback would land after a logout plays nothing`() {
        // Unconfined: the logout completes right where it is launched, between the failure and
        // the fallback it queued.
        val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCoordinator(LogoutCoordinator(FakeLogoutPurger(), markerStore, logoutScope))
        val trackId = pickFrench()

        subtitleErrorListener!!.invoke(trackId)
        logoutScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, coordinator.sessionGeneration)
        verify(exactly = 2) { playerManager.prepare(any(), any(), any()) }
        logoutScope.cancel()
    }

    @Test
    fun `a failure reported while a purge is running plays nothing, even once it ends`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(FakeLogoutPurger(purgeGate), markerStore, applicationScope))
        val trackId = pickFrench()
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()

        onlineTrackFails(trackId)
        purgeGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 2) { playerManager.prepare(any(), any(), any()) }
    }

    // ── Pick during start-up ───────────────────────────────────────────────────
    //
    // The search is offered as soon as `initialize` returns, while the resume point and the
    // Xtream subtitles are still being resolved: a pick can be downloaded before the first
    // prepare.

    private val profileGate = CompletableDeferred<Unit>()
    private val detailGate = CompletableDeferred<Unit>()

    /** Holds the start-up on the profile and the movie detail, resuming at [RESUME_MS]. */
    private fun startMovieHeldOnStartUp() {
        coEvery { appPreferencesStore.getActiveProfileId() } coAnswers {
            profileGate.await()
            "profile-1"
        }
        coEvery { progressRepository.getProgress("profile-1", "42", ContentType.MOVIE) } returns
            PlaybackProgress("42", ContentType.MOVIE, RESUME_MS, 5_400_000L, 0L, "profile-1")
        coEvery { catalogRepository.getMovieDetail("42") } coAnswers {
            detailGate.await()
            Resource.Success(movieWith(XTREAM_SUBTITLES))
        }
        // Like ExoPlayer: the position reported is the one the last prepare started from.
        var preparedAt = 0L
        every { playerManager.prepare(any(), any(), any()) } answers { preparedAt = secondArg() }
        every { player.currentPosition } answers { preparedAt }

        viewModel.initialize(MOVIE_URL, "42", MOVIE_CONTEXT)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a pick applied before the first prepare survives it, at the resume point`() {
        startMovieHeldOnStartUp()
        searchFinds(FRENCH)
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        viewModel.openOnlineSubtitleSearch()
        testDispatcher.scheduler.runCurrent()
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        profileGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        val positions = mutableListOf<Long>()
        verify { playerManager.prepare(any(), capture(positions), any()) }
        val last = preparedExternals().last()
        assertEquals(RESUME_MS, positions.last())
        assertEquals(XTREAM_SUBTITLES, last.dropLast(1))
        assertEquals(LOCAL_SRT.url, last.last().url)
        assertEquals(FRENCH.fileId, online.appliedFileId)
        assertNull(online.applyingFileId)

        val onlineTrackId = last.last().trackId!!
        pushTracks(PlayerTrack(onlineTrackId, "Français", "fr", isSelected = false, type = PlayerTrackType.SUBTITLE))
        verify(exactly = 1) { playerManager.selectSubtitleTrack(onlineTrackId) }
    }

    @Test
    fun `leaving the player during start-up prepares nothing, even with a pick waiting`() {
        startMovieHeldOnStartUp()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        viewModel.releasePlayer()
        profileGate.complete(Unit)
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { playerManager.prepare(any(), any(), any()) }
    }

    // ── Logout races ───────────────────────────────────────────────────────────
    //
    // The downloads below write the way the real file store does: only through
    // `runInSession`, with the generation the pick captured. The purge, like the real one,
    // empties the subtitle directory.

    /** A purge that journals itself in [events] and empties the subtitle directory. */
    private fun purgingLogoutPurger(events: MutableList<String> = mutableListOf()) = object : LogoutPurger {
        override suspend fun logOut() {
            events += "purge"
            subtitlePurger.purgeDownloadedSubtitles()
        }

        override suspend fun recoverIfNeeded(): Boolean = false
    }

    /** Writes [name] for [generation] as the file store does — `false` when the gate refuses it. */
    private suspend fun writeInSession(name: String, generation: Int): Boolean =
        coordinator.runInSession(generation) { subtitlePurger.files += name } != null

    /** The next account's own pick, written once the previous account's logout completed. */
    private fun nextAccountWrites(name: String) {
        applicationScope.launch { writeInSession(name, coordinator.sessionGeneration) }
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a write arriving after a completed logout is refused, so nothing lands and nothing plays`() {
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(), markerStore, applicationScope))
        startMovie()
        val gate = CompletableDeferred<Unit>()
        coEvery { downloader.download(FRENCH, any(), any()) } coAnswers {
            gate.await()
            if (writeInSession("1001.fr.srt", secondArg())) {
                OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT)
            } else {
                OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.SESSION_ENDED)
            }
        }

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
        assertTrue(subtitlePurger.files.isEmpty())
        assertNull(online.appliedFileId)
        assertEquals(LOGOUT_MESSAGE, online.applyError)
    }

    @Test
    fun `an old pick resuming after a logout is not played and never deletes the next account's file`() {
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(), markerStore, applicationScope))
        startMovie()
        val gate = CompletableDeferred<Unit>()
        coEvery { downloader.download(FRENCH, any(), any()) } coAnswers {
            writeInSession("1001.fr.srt", secondArg()) // lands before the logout…
            gate.await() // …and the pick is still on its way when the logout completes
            OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT)
        }

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        assertTrue("the purge owns the old account's file", subtitlePurger.files.isEmpty())
        nextAccountWrites("3003.en.srt")
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(setOf("3003.en.srt"), subtitlePurger.files)
        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
        assertNull(online.appliedFileId)
        assertEquals(LOGOUT_MESSAGE, online.applyError)
    }

    @Test
    fun `an old pick cancelled after a logout never deletes the next account's file`() {
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(), markerStore, applicationScope))
        startMovie()
        coEvery { downloader.download(FRENCH, any(), any()) } coAnswers {
            writeInSession("1001.fr.srt", secondArg())
            CompletableDeferred<Unit>().await() // never completes: the player is left meanwhile
            OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT)
        }

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        nextAccountWrites("3003.en.srt")
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()

        assertEquals(setOf("3003.en.srt"), subtitlePurger.files)
    }

    @Test
    fun `a logout starting right before the re-prepare waits for it, so its purge comes after`() {
        // Unconfined: the logout launched from inside the pick runs as far as it can right
        // there, exactly like a purge thread grabbing the lock the instant it is free.
        val events = mutableListOf<String>()
        val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(events), markerStore, logoutScope))
        startMovie()
        every { playerManager.prepare(any(), any(), any()) } answers { events += "prepare" }
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))
        var logoutArmed = true
        // Read by the pick after its session check, just before it prepares.
        every { player.currentPosition } answers {
            if (logoutArmed) {
                logoutArmed = false
                logoutScope.launch { coordinator.logOut() }
            }
            47_000L
        }

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()

        // The purge (whose first step stops playback) runs after the prepare, never before it.
        assertEquals(listOf("prepare", "purge"), events)
        assertEquals(1, coordinator.sessionGeneration)
        logoutScope.cancel()
    }

    @Test
    fun `a pick arriving while a purge is running is refused and plays nothing`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(FakeLogoutPurger(purgeGate), markerStore, applicationScope))
        startMovie()
        val downloadGate = CompletableDeferred<Unit>()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT), downloadGate)

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        applicationScope.launch { coordinator.logOut() } // holds the purge lock until purgeGate
        testDispatcher.scheduler.runCurrent()
        downloadGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
        assertEquals(LOGOUT_MESSAGE, online.applyError)
        purgeGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `without a logout, a normal pick leaves the downloaded file alone`() {
        startMovie()
        coEvery { downloader.download(FRENCH, any(), any()) } coAnswers {
            subtitlePurger.files += "1001.fr.srt"
            OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT)
        }

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()

        assertEquals(setOf("1001.fr.srt"), subtitlePurger.files)
        assertEquals(FRENCH.fileId, online.appliedFileId)
    }

    // ── Leaving the player ─────────────────────────────────────────────────────
    //
    // The downloads below write through a real file store on a temporary directory, gated by
    // [coordinator], for the visit the pick handed over: what is checked is what is on disk.

    @get:Rule
    val tmp = TemporaryFolder()

    private val subtitleDirectory by lazy { File(tmp.root, "online_subtitles") }

    private fun fileStore() = OnlineSubtitleFileStore(subtitleDirectory, Dispatchers.Unconfined, coordinator)

    private fun storedSubtitles(): List<String> = subtitleDirectory.list()?.toList().orEmpty()

    /**
     * Downloads write a real file for the visit they are handed. [held], when set, is awaited
     * first, out of reach of cancellation — like the blocking HTTP call a real download sits in.
     */
    private fun downloadsWriteFiles(held: CompletableDeferred<Unit>? = null) {
        coEvery { downloader.download(any(), any(), any()) } coAnswers {
            val subtitle = firstArg<OnlineSubtitle>()
            val generation = secondArg<Int>()
            val visit = thirdArg<OnlineSubtitleVisit>()
            withContext(NonCancellable) {
                held?.await()
                val file = fileStore().save(subtitle.fileId, subtitle.language, SRT_BYTES, generation, visit)
                if (file == null) {
                    OnlineSubtitleDownloadResult.Failed(OnlineSubtitleDownloadResult.Reason.SESSION_ENDED)
                } else {
                    OnlineSubtitleDownloadResult.Downloaded(ExternalSubtitle(file.toURI().toString(), subtitle.language))
                }
            }
        }
    }

    private fun lastPreparedOnlineFile(): File = File(URI(preparedExternals().last().last().url))

    @Test
    fun `leaving the player deletes the subtitle it downloaded, kept through a retry until then`() {
        startMovie()
        downloadsWriteFiles()
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val played = lastPreparedOnlineFile()

        viewModel.retry()
        testDispatcher.scheduler.runCurrent()
        assertEquals(played, lastPreparedOnlineFile())
        assertTrue(played.exists())

        viewModel.releasePlayer()

        assertFalse(played.exists())
        assertEquals(emptyList<String>(), storedSubtitles())
    }

    @Test
    fun `a download still running when the player is left leaves no file once it lands`() {
        startMovie()
        val held = CompletableDeferred<Unit>()
        downloadsWriteFiles(held)
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()

        viewModel.releasePlayer()
        held.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(emptyList<String>(), storedSubtitles())
        verify(exactly = 1) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `leaving one player never deletes the file another player downloaded for the same subtitle`() {
        downloadsWriteFiles()
        startMovie()
        val first = viewModel
        first.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        useCoordinator(coordinator) // the next player screen, opened before this one is cleared
        startMovie()
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        val theirs = lastPreparedOnlineFile()

        first.releasePlayer()

        assertEquals(listOf(theirs.name), storedSubtitles())
        assertArrayEquals(SRT_BYTES, theirs.readBytes())
    }

    @Test
    fun `leaving after a logout never deletes the next account's file, the purge took the old one`() {
        val purger = object : LogoutPurger {
            override suspend fun logOut() = fileStore().purgeDownloadedSubtitles()
            override suspend fun recoverIfNeeded(): Boolean = false
        }
        useCoordinator(LogoutCoordinator(purger, markerStore, applicationScope))
        startMovie()
        downloadsWriteFiles()
        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, storedSubtitles().size)

        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        assertEquals("the purge owns the old account's file", emptyList<String>(), storedSubtitles())
        var next: File? = null
        applicationScope.launch {
            next = fileStore().save(FRENCH.fileId, "fr", SRT_BYTES, coordinator.sessionGeneration, OnlineSubtitleVisit())
        }
        testDispatcher.scheduler.runCurrent()

        viewModel.releasePlayer()

        assertEquals(listOf(next!!.name), storedSubtitles())
    }

    // ── Logout during start-up ─────────────────────────────────────────────────
    //
    // The screen stays open: nothing releases the player, only the purge stops it. What start-up
    // may still do once its waits resume is decided by the session it started in.

    @Test
    fun `a logout while start-up waits on the profile and the detail prepares nothing for the old account`() {
        val events = mutableListOf<String>()
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(events), markerStore, applicationScope))
        startMovieHeldOnStartUp()

        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf("purge"), events)
        profileGate.complete(Unit)
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        testDispatcher.scheduler.advanceTimeBy(60_000L)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { playerManager.prepare(any(), any(), any()) }
        coVerify(exactly = 0) { progressRepository.upsertProgress(any()) }
        // Nor does leaving afterwards persist a position for the old profile.
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { progressRepository.upsertProgress(any()) }
    }

    @Test
    fun `a logout completing between the profile and the detail still prepares nothing`() {
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(), markerStore, applicationScope))
        startMovieHeldOnStartUp()

        profileGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { playerManager.prepare(any(), any(), any()) }
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { progressRepository.upsertProgress(any()) }
    }

    @Test
    fun `start-up reaching its prepare while a purge is running prepares nothing, even once it ends`() {
        val purgeGate = CompletableDeferred<Unit>()
        useCoordinator(LogoutCoordinator(FakeLogoutPurger(purgeGate), markerStore, applicationScope))
        startMovieHeldOnStartUp()

        applicationScope.launch { coordinator.logOut() } // holds the purge lock until purgeGate
        testDispatcher.scheduler.runCurrent()
        profileGate.complete(Unit)
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()
        purgeGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { playerManager.prepare(any(), any(), any()) }
    }

    @Test
    fun `a pick waiting on a start-up refused by a logout plays nothing either`() {
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(), markerStore, applicationScope))
        startMovieHeldOnStartUp()
        downloadGives(OnlineSubtitleDownloadResult.Downloaded(LOCAL_SRT))

        viewModel.selectOnlineSubtitle(FRENCH)
        testDispatcher.scheduler.runCurrent()
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        profileGate.complete(Unit)
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 0) { playerManager.prepare(any(), any(), any()) }
        assertEquals(LOGOUT_MESSAGE, online.applyError)
    }

    @Test
    fun `without a logout, a held start-up prepares at the resume point and saves progress`() {
        // A logout completed *before* this visit: the next account's player must still play.
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(), markerStore, applicationScope))
        applicationScope.launch { coordinator.logOut() }
        testDispatcher.scheduler.runCurrent()
        // The next visit's route is created after the logout — a screen created before it stays
        // revoked (see PlayerViewModelSessionRevocationTest).
        useCoordinator(coordinator)
        startMovieHeldOnStartUp()

        profileGate.complete(Unit)
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { playerManager.prepare(MOVIE_URL, RESUME_MS, XTREAM_SUBTITLES) }
        assertEquals(RESUME_MS, viewModel.uiState.value.currentPositionMs)
        viewModel.releasePlayer()
        testDispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { progressRepository.upsertProgress(match { it.profileId == "profile-1" }) }
    }

    @Test
    fun `a logout starting right before the start-up prepare waits for it, so its purge comes after`() {
        // Unconfined, as in the pick's version: the logout grabs the lock the instant it is free.
        val events = mutableListOf<String>()
        val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        useCoordinator(LogoutCoordinator(purgingLogoutPurger(events), markerStore, logoutScope))
        startMovieHeldOnStartUp()
        var logoutArmed = true
        every { playerManager.prepare(any(), any(), any()) } answers {
            if (logoutArmed) {
                logoutArmed = false
                logoutScope.launch { coordinator.logOut() }
            }
            events += "prepare"
        }

        profileGate.complete(Unit)
        detailGate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf("prepare", "purge"), events)
        assertEquals(1, coordinator.sessionGeneration)
        logoutScope.cancel()
    }

    private fun movieWith(externalSubtitles: List<ExternalSubtitle>): Movie = Movie(
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

    private companion object {
        const val MOVIE_URL = "http://example.com:8080/movie/u/p/42.mp4"
        const val RESUME_MS = 30_000L
        val MOVIE_CONTEXT = SubtitleSearchContext(SubtitleSearchContext.Kind.MOVIE, title = "Up", year = 2009)
        val XTREAM_SUBTITLES = listOf(ExternalSubtitle("http://example.com/subs/42.en.srt", "en"))
        val FRENCH = OnlineSubtitle(fileId = 1001L, language = "fr", release = "Up.2009.1080p")
        val ENGLISH = OnlineSubtitle(fileId = 2002L, language = "en", release = "Up.2009.720p")
        val LOCAL_SRT = ExternalSubtitle("file:/data/cache/online_subtitles/1001.fr.srt", "fr")
        val LOCAL_EN_SRT = ExternalSubtitle("file:/data/cache/online_subtitles/2002.en.srt", "en")
        val SRT_BYTES = "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n".toByteArray()
    }
}
