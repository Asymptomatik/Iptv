package com.bobot.iptvapp.ui.screen.livedetail

import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.source.CredentialsProvider
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LiveDetailViewModel].
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailViewModelTest] /
 * [com.bobot.iptvapp.ui.screen.seriesdetail.SeriesDetailViewModelTest]: [Dispatchers.setMain] swaps
 * in a [StandardTestDispatcher], and `testDispatcher.scheduler.runCurrent()` deterministically
 * drains the `viewModelScope.launch` coroutine started by [LiveDetailViewModel.initialize] /
 * [LiveDetailViewModel.onRetry] / [LiveDetailViewModel.onToggleFavorite] — including the
 * never-completing `isFavorite` Flow collector, which just suspends again waiting for the next
 * [MutableStateFlow] emission.
 *
 * Unlike the movie/series ViewModels, [CatalogRepository.getLiveChannels] is stubbed with
 * [flowOf] (a cold, terminating Flow: one or more emissions then completion) rather than a
 * [MutableStateFlow], since [LiveDetailViewModel] collects it exactly like a one-shot fetch — see
 * [LiveDetailViewModel] KDoc "Resolving the channel".
 */
class LiveDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var credentialsProvider: CredentialsProvider
    private lateinit var viewModel: LiveDetailViewModel

    private lateinit var isFavoriteFlow: MutableStateFlow<Boolean>

    private val profileId = "p1"
    private val channelId = "101"

    private val credentials = XtreamCredentials(
        baseUrl = "http://example.com:8080",
        username = "alice",
        password = "secret",
    )

    private val channelWithEpg = Channel(
        id = channelId,
        name = "BBC World News",
        logoUrl = "http://example.com/bbc.png",
        categoryId = "1",
        epgChannelId = "bbc.world",
    )

    private val channelWithoutEpg = Channel(
        id = "302",
        name = "Netflix Channel",
        logoUrl = "http://example.com/netflix.png",
        categoryId = "3",
        epgChannelId = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        catalogRepository = mockk()
        favoritesRepository = mockk()
        appPreferencesStore = mockk()
        credentialsProvider = mockk()

        isFavoriteFlow = MutableStateFlow(false)

        coEvery { appPreferencesStore.getActiveProfileId() } returns profileId
        coEvery { credentialsProvider.getCredentials() } returns credentials
        // Cache miss by default, so every test below still exercises the unfiltered-list
        // fallback it was written against. The cache *hit* has its own test.
        coEvery { catalogRepository.getCachedChannel(any()) } returns null
        every { favoritesRepository.isFavorite(any(), any(), any()) } returns isFavoriteFlow
        coEvery { favoritesRepository.toggleFavorite(any(), any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = LiveDetailViewModel(
            catalogRepository = catalogRepository,
            favoritesRepository = favoritesRepository,
            appPreferencesStore = appPreferencesStore,
            credentialsProvider = credentialsProvider,
        )
    }

    private fun initialize(id: String = channelId) {
        createViewModel()
        viewModel.initialize(id)
        testDispatcher.scheduler.runCurrent()
    }

    private fun stubChannels(vararg channels: Channel) {
        every { catalogRepository.getLiveChannels(categoryId = null) } returns
            flowOf(Resource.Success(channels.toList()))
    }

    private fun epgProgram(
        title: String,
        startMillis: Long,
        endMillis: Long,
        description: String? = null,
    ) = EpgProgram(
        channelId = "bbc.world",
        title = title,
        description = description,
        startMillis = startMillis,
        endMillis = endMillis,
    )

    // ── Successful load ───────────────────────────────────────────────────────

    @Test
    fun `initialize resolves the channel from the live channels list and builds the stream URL`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(channelWithEpg, state.channel)
        assertEquals("http://example.com:8080/live/alice/secret/101.ts", state.streamUrl)
    }

    @Test
    fun `initialize resolves the channel from the cache without downloading the whole bouquet`() {
        coEvery { catalogRepository.getCachedChannel(channelId) } returns channelWithEpg
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())

        initialize()

        val state = viewModel.uiState.value
        assertEquals(channelWithEpg, state.channel)
        assertEquals("http://example.com:8080/live/alice/secret/101.ts", state.streamUrl)
        // `getLiveChannels(null)` is the unfiltered bouquet — the single heaviest call the API
        // offers, and one the catalog screens never make, so it is never cached either. Opening a
        // channel used to pay for it in full, every time.
        verify(exactly = 0) { catalogRepository.getLiveChannels(any()) }
    }

    @Test
    fun `a cached channel still gets its EPG and favorite state`() {
        coEvery { catalogRepository.getCachedChannel(channelId) } returns channelWithEpg
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())

        initialize()

        // A channel read from Room is not a lesser one: both dependent loads must still run.
        coVerify(exactly = 1) { catalogRepository.getEpg("bbc.world") }
        verify(exactly = 1) { favoritesRepository.isFavorite(profileId, channelId, ContentType.LIVE) }
    }

    @Test
    fun `initialize is idempotent and only collects the channels flow once`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())

        initialize()
        viewModel.initialize(channelId)
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { catalogRepository.getLiveChannels(categoryId = null) }
    }

    @Test
    fun `initialize leaves the stream URL null when no credentials are configured`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())
        coEvery { credentialsProvider.getCredentials() } returns null

        initialize()

        assertNull(viewModel.uiState.value.streamUrl)
    }

    @Test
    fun `initialize surfaces an error when no channel matches the requested id`() {
        stubChannels(channelWithEpg)

        initialize(id = "does-not-exist")

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Chaîne introuvable.", state.errorMessage)
        assertNull(state.channel)
    }

    @Test
    fun `initialize surfaces the error message when the channels fetch fails`() {
        every { catalogRepository.getLiveChannels(categoryId = null) } returns
            flowOf(Resource.Error(message = "Hors ligne"))

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Hors ligne", state.errorMessage)
        assertNull(state.channel)
    }

    // ── EPG ────────────────────────────────────────────────────────────────────

    @Test
    fun `initialize splits EPG programs into current and upcoming around now`() {
        stubChannels(channelWithEpg)
        val now = System.currentTimeMillis()
        val previous = epgProgram("Previous", now - 7_200_000L, now - 3_600_000L)
        val current = epgProgram("Now Playing", now - 1_000L, now + 3_600_000L, description = "Synopsis")
        val next = epgProgram("Next", now + 3_600_000L, now + 7_200_000L)
        val nextNext = epgProgram("Next Next", now + 7_200_000L, now + 10_800_000L)
        coEvery { catalogRepository.getEpg("bbc.world") } returns
            Resource.Success(listOf(next, previous, nextNext, current))

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isEpgLoading)
        assertEquals(current, state.currentProgram)
        assertEquals(listOf(next, nextNext), state.upcomingPrograms)
        assertNull(state.epgMessage)
    }

    @Test
    fun `initialize shows the no-EPG message when the channel has no epgChannelId`() {
        stubChannels(channelWithoutEpg)

        initialize(id = channelWithoutEpg.id)

        val state = viewModel.uiState.value
        assertFalse(state.isEpgLoading)
        assertNull(state.currentProgram)
        assertTrue(state.upcomingPrograms.isEmpty())
        assertEquals("Aucun programme disponible", state.epgMessage)
        coVerify(exactly = 0) { catalogRepository.getEpg(any(), any()) }
    }

    @Test
    fun `initialize shows the no-EPG message when the EPG fetch returns an empty list`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isEpgLoading)
        assertNull(state.currentProgram)
        assertEquals("Aucun programme disponible", state.epgMessage)
    }

    @Test
    fun `initialize shows the no-EPG message when the EPG fetch fails`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Error(message = "Erreur EPG")

        initialize()

        val state = viewModel.uiState.value
        assertFalse(state.isEpgLoading)
        assertNull(state.currentProgram)
        assertEquals("Aucun programme disponible", state.epgMessage)
    }

    // ── Favorites ──────────────────────────────────────────────────────────────

    @Test
    fun `initialize reflects the current favorite state and reacts to later changes`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())
        isFavoriteFlow.value = true

        initialize()

        assertTrue(viewModel.uiState.value.isFavorite)

        isFavoriteFlow.value = false
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.isFavorite)
    }

    @Test
    fun `onToggleFavorite delegates to the repository for the active profile and channel at LIVE scope`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())
        initialize()

        viewModel.onToggleFavorite()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { favoritesRepository.toggleFavorite(profileId, channelId, ContentType.LIVE) }
    }

    @Test
    fun `onToggleFavorite is a no-op when no profile is active`() {
        stubChannels(channelWithEpg)
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())
        coEvery { appPreferencesStore.getActiveProfileId() } returns null
        initialize()

        viewModel.onToggleFavorite()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { favoritesRepository.toggleFavorite(any(), any(), any()) }
    }

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    fun `onRetry re-resolves the same channel`() {
        every { catalogRepository.getLiveChannels(categoryId = null) } returnsMany listOf(
            flowOf(Resource.Error(message = "Hors ligne")),
            flowOf(Resource.Success(listOf(channelWithEpg))),
        )
        coEvery { catalogRepository.getEpg("bbc.world") } returns Resource.Success(emptyList())
        initialize()
        assertEquals("Hors ligne", viewModel.uiState.value.errorMessage)

        viewModel.onRetry()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 2) { catalogRepository.getLiveChannels(categoryId = null) }
        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertEquals(channelWithEpg, state.channel)
    }
}
