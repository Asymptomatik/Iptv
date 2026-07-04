package com.bobot.iptvapp.ui.screen.profiles

import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.domain.model.Profile
import com.bobot.iptvapp.domain.repository.FavoritesRepository
import com.bobot.iptvapp.domain.repository.PlaybackProgressRepository
import com.bobot.iptvapp.domain.repository.ProfileRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for [ProfilesViewModel].
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModelTest] (Task 15) and
 * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModelTest] (Task 14):
 * [Dispatchers.setMain] swaps in a [StandardTestDispatcher], and
 * `testDispatcher.scheduler.runCurrent()` (never `suspend`, always callable outside a `runTest`
 * block) deterministically drains pending `viewModelScope.launch` coroutines — including this
 * ViewModel's `init` block, whose reactive collector never itself completes (it just suspends
 * again waiting for the next [MutableStateFlow] emission), so `runCurrent()` alone is exactly
 * enough to observe every emission already pushed onto [profilesFlow] / [activeProfileIdFlow]
 * without needing `runTest`/virtual-time advancement anywhere in this file.
 *
 * [ProfileRepository], [AppPreferencesStore], [FavoritesRepository], and
 * [PlaybackProgressRepository] are all `mockk()` doubles (no in-memory fake exists for any of
 * them yet, unlike [com.bobot.iptvapp.data.source.InMemoryCredentialsProvider] used by the
 * Settings/Onboarding tests). [ProfileRepository.observeProfiles] and
 * [AppPreferencesStore.observeActiveProfileId] are stubbed to return the test's own
 * [profilesFlow] / [activeProfileIdFlow] [MutableStateFlow]s directly, so tests simulate the
 * repository's reactive updates by pushing new values onto those flows themselves, while
 * `createProfile` / `updateProfile` / `deleteProfile` / `setActiveProfileId` /
 * `clearFavorites` / `clearProgress` call arguments are asserted via `coVerify`.
 */
class ProfilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var profileRepository: ProfileRepository
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var favoritesRepository: FavoritesRepository
    private lateinit var playbackProgressRepository: PlaybackProgressRepository
    private lateinit var viewModel: ProfilesViewModel

    private lateinit var profilesFlow: MutableStateFlow<List<Profile>>
    private lateinit var activeProfileIdFlow: MutableStateFlow<String?>

    private val alice = Profile(id = "1", name = "Alice", avatarUrl = null)
    private val kids = Profile(id = "2", name = "Kids", avatarUrl = null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        profileRepository = mockk()
        appPreferencesStore = mockk()
        favoritesRepository = mockk()
        playbackProgressRepository = mockk()

        profilesFlow = MutableStateFlow(emptyList())
        activeProfileIdFlow = MutableStateFlow(null)

        every { profileRepository.observeProfiles() } returns profilesFlow
        every { appPreferencesStore.observeActiveProfileId() } returns activeProfileIdFlow
        coEvery { appPreferencesStore.setActiveProfileId(any()) } just Runs
        coEvery { favoritesRepository.clearFavorites(any()) } just Runs
        coEvery { playbackProgressRepository.clearProgress(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Creates [viewModel] and drains its `init` block's reactive collector. */
    private fun createViewModel() {
        viewModel = ProfilesViewModel(
            profileRepository = profileRepository,
            appPreferencesStore = appPreferencesStore,
            favoritesRepository = favoritesRepository,
            playbackProgressRepository = playbackProgressRepository,
        )
        testDispatcher.scheduler.runCurrent()
    }

    // ── Empty state (Task 16 brief requirement #2) ──────────────────────────

    @Test
    fun `init with no profiles forces the CREATE mode instead of an empty grid`() {
        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.CREATE, state.mode)
        assertTrue(state.profiles.isEmpty())
    }

    @Test
    fun `init with existing profiles shows the SELECTION grid with the active id populated`() {
        profilesFlow.value = listOf(alice, kids)
        activeProfileIdFlow.value = alice.id
        createViewModel()

        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertEquals(listOf(alice, kids), state.profiles)
        assertEquals(alice.id, state.activeProfileId)
    }

    // ── Manage-mode toggle & card click routing ──────────────────────────────

    @Test
    fun `onToggleManageMode flips isManageModeActive`() {
        profilesFlow.value = listOf(alice)
        createViewModel()

        assertFalse(viewModel.uiState.value.isManageModeActive)
        viewModel.onToggleManageMode()
        assertTrue(viewModel.uiState.value.isManageModeActive)
        viewModel.onToggleManageMode()
        assertFalse(viewModel.uiState.value.isManageModeActive)
    }

    @Test
    fun `onProfileCardClick selects the profile when manage mode is off`() {
        profilesFlow.value = listOf(alice)
        createViewModel()

        viewModel.onProfileCardClick(alice)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { appPreferencesStore.setActiveProfileId(alice.id) }
        assertTrue(viewModel.uiState.value.navigateToHome)
    }

    @Test
    fun `onProfileCardClick opens the edit form when manage mode is on`() {
        profilesFlow.value = listOf(alice)
        createViewModel()
        viewModel.onToggleManageMode()

        viewModel.onProfileCardClick(alice)

        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.EDIT, state.mode)
        assertEquals(alice.id, state.editingProfileId)
        assertEquals(alice.name, state.formName)
        coVerify(exactly = 0) { appPreferencesStore.setActiveProfileId(any()) }
    }

    // ── Selecting a profile directly ──────────────────────────────────────────

    @Test
    fun `onSelectProfile persists the active id then signals navigateToHome`() {
        profilesFlow.value = listOf(alice, kids)
        createViewModel()

        viewModel.onSelectProfile(kids)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { appPreferencesStore.setActiveProfileId(kids.id) }
        val state = viewModel.uiState.value
        assertEquals(kids.id, state.activeProfileId)
        assertTrue(state.navigateToHome)
    }

    // ── Add / cancel form navigation ─────────────────────────────────────────

    @Test
    fun `onAddProfileClick opens a blank creation form from the selection grid`() {
        profilesFlow.value = listOf(alice)
        createViewModel()

        viewModel.onAddProfileClick()

        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.CREATE, state.mode)
        assertEquals("", state.formName)
        assertNull(state.editingProfileId)
    }

    @Test
    fun `onCancelForm returns to SELECTION and clears the form`() {
        profilesFlow.value = listOf(alice)
        createViewModel()
        viewModel.onAddProfileClick()
        viewModel.onFormNameChange("Draft")

        viewModel.onCancelForm()

        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertEquals("", state.formName)
        assertNull(state.editingProfileId)
    }

    @Test
    fun `onFormNameChange updates the field and clears a prior error`() {
        createViewModel()
        viewModel.onSubmitCreate() // blank name -> sets an error to verify it gets cleared below

        viewModel.onFormNameChange("Bob")

        val state = viewModel.uiState.value
        assertEquals("Bob", state.formName)
        assertNull(state.errorMessage)
    }

    // ── onSubmitCreate — validation ───────────────────────────────────────────

    @Test
    fun `onSubmitCreate with a blank name shows an error and never calls createProfile`() {
        createViewModel()

        viewModel.onSubmitCreate()

        assertTrue(viewModel.uiState.value.errorMessage!!.isNotBlank())
        coVerify(exactly = 0) { profileRepository.createProfile(any(), any()) }
    }

    // ── onSubmitCreate — first profile ever (auto-select + navigate) ─────────

    @Test
    fun `onSubmitCreate auto-selects and navigates home when it is the very first profile`() {
        createViewModel() // profiles empty -> forced CREATE mode
        val created = Profile(id = "new-1", name = "Alice", avatarUrl = null)
        coEvery { profileRepository.createProfile("Alice", null) } returns created

        viewModel.onFormNameChange("Alice")
        viewModel.onSubmitCreate()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { profileRepository.createProfile("Alice", null) }
        coVerify(exactly = 1) { appPreferencesStore.setActiveProfileId(created.id) }
        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertEquals(created.id, state.activeProfileId)
        assertTrue(state.navigateToHome)
    }

    // ── onSubmitCreate — additional profile (no auto-select) ─────────────────

    @Test
    fun `onSubmitCreate returns to SELECTION without navigating when profiles already existed`() {
        profilesFlow.value = listOf(alice)
        activeProfileIdFlow.value = alice.id
        createViewModel()
        viewModel.onAddProfileClick()
        val created = Profile(id = "new-2", name = "Kids", avatarUrl = null)
        coEvery { profileRepository.createProfile("Kids", null) } returns created

        viewModel.onFormNameChange("Kids")
        viewModel.onSubmitCreate()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { profileRepository.createProfile("Kids", null) }
        coVerify(exactly = 0) { appPreferencesStore.setActiveProfileId(created.id) }
        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertFalse(state.navigateToHome)
        // The active profile is untouched — still Alice.
        assertEquals(alice.id, state.activeProfileId)
    }

    // ── onSubmitEdit ───────────────────────────────────────────────────────────

    @Test
    fun `onSubmitEdit with a blank name shows an error and never calls updateProfile`() {
        profilesFlow.value = listOf(alice)
        createViewModel()
        viewModel.onEditProfileClick(alice)

        viewModel.onFormNameChange("   ")
        viewModel.onSubmitEdit()

        assertTrue(viewModel.uiState.value.errorMessage!!.isNotBlank())
        coVerify(exactly = 0) { profileRepository.updateProfile(any()) }
    }

    @Test
    fun `onSubmitEdit renames the profile and returns to SELECTION`() {
        profilesFlow.value = listOf(alice)
        createViewModel()
        viewModel.onEditProfileClick(alice)
        coEvery { profileRepository.updateProfile(any()) } returns alice.copy(name = "Alicia")

        viewModel.onFormNameChange("Alicia")
        viewModel.onSubmitEdit()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { profileRepository.updateProfile(alice.copy(name = "Alicia")) }
        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertNull(state.editingProfileId)
    }

    // ── onDeleteProfile — last-profile guard ─────────────────────────────────

    @Test
    fun `onDeleteProfile refuses to delete the last remaining profile`() {
        profilesFlow.value = listOf(alice)
        activeProfileIdFlow.value = alice.id
        createViewModel()
        viewModel.onEditProfileClick(alice)

        viewModel.onDeleteProfile()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { profileRepository.deleteProfile(any()) }
        assertTrue(viewModel.uiState.value.errorMessage!!.isNotBlank())
    }

    // ── onDeleteProfile — deleting a non-active profile ──────────────────────

    @Test
    fun `onDeleteProfile of a non-active profile leaves the active id untouched`() {
        profilesFlow.value = listOf(alice, kids)
        activeProfileIdFlow.value = alice.id
        createViewModel()
        viewModel.onEditProfileClick(kids)
        coEvery { profileRepository.deleteProfile(kids.id) } just Runs

        viewModel.onDeleteProfile()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { profileRepository.deleteProfile(kids.id) }
        coVerify(exactly = 1) { favoritesRepository.clearFavorites(kids.id) }
        coVerify(exactly = 1) { playbackProgressRepository.clearProgress(kids.id) }
        coVerify(exactly = 0) { appPreferencesStore.setActiveProfileId(null) }
        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertEquals(alice.id, state.activeProfileId)
    }

    // ── onDeleteProfile — deleting the active profile ────────────────────────

    @Test
    fun `onDeleteProfile of the active profile clears the active id`() {
        profilesFlow.value = listOf(alice, kids)
        activeProfileIdFlow.value = alice.id
        createViewModel()
        viewModel.onEditProfileClick(alice)
        coEvery { profileRepository.deleteProfile(alice.id) } just Runs

        viewModel.onDeleteProfile()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { profileRepository.deleteProfile(alice.id) }
        coVerify(exactly = 1) { favoritesRepository.clearFavorites(alice.id) }
        coVerify(exactly = 1) { playbackProgressRepository.clearProgress(alice.id) }
        coVerify(exactly = 1) { appPreferencesStore.setActiveProfileId(null) }
        val state = viewModel.uiState.value
        assertEquals(ProfilesMode.SELECTION, state.mode)
        assertNull(state.activeProfileId)
    }
}
