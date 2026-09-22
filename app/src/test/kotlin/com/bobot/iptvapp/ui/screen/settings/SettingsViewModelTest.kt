package com.bobot.iptvapp.ui.screen.settings

import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.data.preferences.FakeLogoutPurgeMarkerStore
import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.logout.FakeLogoutPurger
import com.bobot.iptvapp.domain.logout.LogoutPurgeException
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.ui.util.CREDENTIALS_REFUSED_MESSAGE
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel].
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModelTest] (Task 14):
 * [Dispatchers.setMain] swaps in a [StandardTestDispatcher] shared by every test, and
 * `testDispatcher.scheduler.runCurrent()` (never `suspend`, always callable outside a
 * `runTest` block) deterministically drains pending `viewModelScope.launch` coroutines.
 *
 * [seedCredentials] is a plain `suspend` helper (not itself a `runTest` wrapper) so it can be
 * called either:
 *  - directly inside a single, already-open `runTest(testDispatcher) { ... }` block (tests that
 *    also assert on other suspend calls, e.g. [InMemoryCredentialsProvider.getCredentials]), or
 *  - via its own single, standalone `runTest(testDispatcher) { seedCredentials(...) }` call for
 *    tests that otherwise never touch a suspend function directly.
 * Deliberately avoided: nesting one `runTest(testDispatcher) { ... }` call inside another —
 * `runTest` is a top-level coroutine test builder, and this codebase's established convention
 * (see `OnboardingViewModelTest`) always keeps it single-level per call site.
 *
 * [InMemoryCredentialsProvider] is used as a real (non-mocked) test double so persistence side
 * effects — including the "restore previous credentials on failure" behaviour unique to this
 * ViewModel — can be asserted directly instead of via mock verification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var credentialsProvider: InMemoryCredentialsProvider
    private lateinit var appPreferencesStore: AppPreferencesStore
    private lateinit var logoutPurger: FakeLogoutPurger
    private lateinit var viewModel: SettingsViewModel

    /**
     * Stands in for the `@ApplicationScope` singleton scope. It is deliberately *not* the test's
     * own scope: the point of the coordinator is that the purge does not belong to whoever asked
     * for it, and a purge parented to the caller would make that impossible to observe.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private val markerStore = FakeLogoutPurgeMarkerStore()

    /**
     * Built on first use so a test can install a gated [FakeLogoutPurger] beforehand, and shared by
     * every [SettingsViewModel] the test creates — one process has exactly one coordinator, which
     * is what lets a recreated screen still learn how a purge it did not start ended.
     */
    private val logoutCoordinator: LogoutCoordinator by lazy {
        LogoutCoordinator(
            logoutPurger = logoutPurger,
            markerStore = markerStore,
            applicationScope = applicationScope,
        )
    }

    private val existingCredentials = XtreamCredentials(
        baseUrl = "http://old.example.com:8080",
        username = "olduser",
        password = "oldpass",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        catalogRepository = mockk()
        credentialsProvider = InMemoryCredentialsProvider()
        appPreferencesStore = mockk()
        logoutPurger = FakeLogoutPurger()
        every { appPreferencesStore.observeWifiOnlyDownloads() } returns flowOf(false)
        coEvery { appPreferencesStore.setWifiOnlyDownloads(any()) } just Runs
        coEvery { catalogRepository.invalidatePersistentCache(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        applicationScope.cancel()
    }

    /** Plain suspend helper — see class KDoc for how call sites wrap this in `runTest`. */
    private suspend fun seedCredentials(credentials: XtreamCredentials?) {
        if (credentials != null) {
            credentialsProvider.setCredentials(credentials)
        }
    }

    /** Creates [viewModel] and drains its `init` block's `viewModelScope.launch`. */
    private fun createViewModel() {
        viewModel = SettingsViewModel(
            catalogRepository = catalogRepository,
            credentialsProvider = credentialsProvider,
            appPreferencesStore = appPreferencesStore,
            logoutCoordinator = logoutCoordinator,
        )
        testDispatcher.scheduler.runCurrent()
    }

    // ── init pre-fill ─────────────────────────────────────────────────────────

    @Test
    fun `init pre-fills server url and username but leaves password blank`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()

        val state = viewModel.uiState.value
        assertEquals("http://old.example.com:8080", state.serverUrl)
        assertEquals("olduser", state.username)
        assertEquals("", state.password)
    }

    @Test
    fun `init with no stored credentials leaves all fields blank`() {
        createViewModel()

        val state = viewModel.uiState.value
        assertEquals("", state.serverUrl)
        assertEquals("", state.username)
        assertEquals("", state.password)
    }

    // ── Field updates ─────────────────────────────────────────────────────────

    @Test
    fun `field changes update state and clear prior messages`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()
        every { catalogRepository.invalidateCache(ContentType.MOVIE) } just Runs
        viewModel.onReloadMovies() // sets an infoMessage to verify it gets cleared below

        viewModel.onServerUrlChange("http://a.com")
        viewModel.onUsernameChange("u")
        viewModel.onPasswordChange("p")

        val state = viewModel.uiState.value
        assertEquals("http://a.com", state.serverUrl)
        assertEquals("u", state.username)
        assertEquals("p", state.password)
        assertNull(state.errorMessage)
        assertNull(state.infoMessage)
    }

    @Test
    fun `onTogglePasswordVisibility flips isPasswordVisible`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()

        assertFalse(viewModel.uiState.value.isPasswordVisible)
        viewModel.onTogglePasswordVisibility()
        assertTrue(viewModel.uiState.value.isPasswordVisible)
    }

    // ── onSaveCredentials — client-side validation ───────────────────────────

    @Test
    fun `onSaveCredentials with blank url or username shows an error and never calls authenticate`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()

        viewModel.onServerUrlChange("")
        viewModel.onSaveCredentials()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage!!.isNotBlank())
        coVerify(exactly = 0) { catalogRepository.authenticate() }
    }

    @Test
    fun `onSaveCredentials with blank password and no prior credentials shows a validation error`() {
        createViewModel()

        viewModel.onServerUrlChange("http://example.com:8080")
        viewModel.onUsernameChange("user")
        // Password left blank and there is no previously stored password to fall back to.

        viewModel.onSaveCredentials()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage!!.isNotBlank())
        coVerify(exactly = 0) { catalogRepository.authenticate() }
    }

    // ── onSaveCredentials — a purge still owed ───────────────────────────────

    @Test
    fun `saving credentials while a purge is still owed is refused rather than persisted`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            // Réglages is where a logout is started, so it is also where a save is likeliest to
            // arrive on top of one that has not finished.
            markerStore.markPurgePending()
            coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)

            viewModel.onServerUrlChange("http://new.example.com:8080")
            viewModel.onUsernameChange("newuser")
            viewModel.onPasswordChange("newpass")

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.uiState.value
            assertFalse("the form must not stay stuck spinning on a refusal", state.isLoading)
            assertEquals(CREDENTIALS_REFUSED_MESSAGE, state.errorMessage)
            assertEquals(
                "the store the purge is about to finalize must still hold only the old account",
                existingCredentials,
                credentialsProvider.getCredentials(),
            )
            coVerify(exactly = 0) { catalogRepository.authenticate() }
        }

    // ── onSaveCredentials — success path ─────────────────────────────────────

    @Test
    fun `onSaveCredentials with blank password reuses the previous password on success`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)

            viewModel.onServerUrlChange("http://new.example.com:8080")
            viewModel.onUsernameChange("newuser")
            // Password field intentionally left blank.

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.errorMessage)
            assertTrue(state.infoMessage!!.isNotBlank())
            assertEquals("", state.password)
            assertEquals(
                XtreamCredentials(
                    baseUrl = "http://new.example.com:8080",
                    username = "newuser",
                    password = "oldpass",
                ),
                credentialsProvider.getCredentials(),
            )
        }

    @Test
    fun `onSaveCredentials with an explicit password overrides the previous one on success`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)

            viewModel.onServerUrlChange("http://new.example.com:8080")
            viewModel.onUsernameChange("newuser")
            viewModel.onPasswordChange("newpass")

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                XtreamCredentials(
                    baseUrl = "http://new.example.com:8080",
                    username = "newuser",
                    password = "newpass",
                ),
                credentialsProvider.getCredentials(),
            )
        }

    // ── onSaveCredentials — failure path (the key behavioural difference) ───

    @Test
    fun `onSaveCredentials restores the previous working credentials on failure instead of clearing them`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            coEvery { catalogRepository.authenticate() } returns
                Resource.Error(throwable = CatalogException.AuthenticationFailed())

            viewModel.onServerUrlChange("http://bad.example.com:8080")
            viewModel.onUsernameChange("baduser")
            viewModel.onPasswordChange("badpass")

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertTrue(state.errorMessage!!.contains("Identifiants"))
            // The old, working credentials are restored — never cleared.
            assertEquals(existingCredentials, credentialsProvider.getCredentials())
            // The form remains filled in / editable for a retry (nothing is cleared).
            assertEquals("http://bad.example.com:8080", state.serverUrl)
            assertEquals("baduser", state.username)
            assertEquals("badpass", state.password)
        }

    @Test
    fun `onSaveCredentials shows a network message on NetworkError and still restores previous credentials`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            coEvery { catalogRepository.authenticate() } returns
                Resource.Error(throwable = CatalogException.NetworkError("boom"))

            viewModel.onServerUrlChange("http://bad.example.com:8080")
            viewModel.onUsernameChange("baduser")
            viewModel.onPasswordChange("badpass")

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.uiState.value
            assertTrue(state.errorMessage!!.contains("serveur"))
            assertEquals(existingCredentials, credentialsProvider.getCredentials())
        }

    @Test
    fun `onSaveCredentials is a no-op while a request is already in flight`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()
        coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)

        viewModel.onUsernameChange("newuser")
        viewModel.onPasswordChange("newpass")

        viewModel.onSaveCredentials()
        // Do not drain the scheduler yet — isLoading should now be true.
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.onSaveCredentials()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { catalogRepository.authenticate() }
    }

    // ── onReloadMovies / onReloadSeries / onReloadChannels ──────────────────────

    @Test
    fun `onReloadMovies invalidates only the MOVIE cache and shows the movies confirmation message`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()
        every { catalogRepository.invalidateCache(any()) } just Runs

        viewModel.onReloadMovies()
        testDispatcher.scheduler.runCurrent()

        verify(exactly = 1) { catalogRepository.invalidateCache(ContentType.MOVIE) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.SERIES) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.LIVE) }
        // Both halves, or the button does nothing: since schema v4 the repository answers reads
        // from Room whenever the slice is fresh, so clearing the in-memory cache alone would send
        // the next read straight back to the same cached rows.
        coVerify(exactly = 1) { catalogRepository.invalidatePersistentCache(ContentType.MOVIE) }
        coVerify(exactly = 0) { catalogRepository.invalidatePersistentCache(ContentType.SERIES) }
        coVerify(exactly = 0) { catalogRepository.invalidatePersistentCache(ContentType.LIVE) }
        val state = viewModel.uiState.value
        assertEquals("Films rechargés.", state.infoMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun `onReloadSeries invalidates only the SERIES cache and shows the series confirmation message`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()
        every { catalogRepository.invalidateCache(any()) } just Runs

        viewModel.onReloadSeries()

        verify(exactly = 1) { catalogRepository.invalidateCache(ContentType.SERIES) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.MOVIE) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.LIVE) }
        val state = viewModel.uiState.value
        assertEquals("Séries rechargées.", state.infoMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun `onReloadChannels invalidates only the LIVE cache and shows the channels confirmation message`() {
        runTest(testDispatcher) { seedCredentials(existingCredentials) }
        createViewModel()
        every { catalogRepository.invalidateCache(any()) } just Runs

        viewModel.onReloadChannels()

        verify(exactly = 1) { catalogRepository.invalidateCache(ContentType.LIVE) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.MOVIE) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.SERIES) }
        val state = viewModel.uiState.value
        assertEquals("Chaînes rechargées.", state.infoMessage)
        assertNull(state.errorMessage)
    }

    // ── onLogout ──────────────────────────────────────────────────────────────

    @Test
    fun `onLogout flips isLoggedOut once the purge succeeds`() = runTest(testDispatcher) {
        // Clearing the credentials themselves is no longer this ViewModel's job — it is the last
        // step of the purge, asserted in DefaultLogoutPurgerTest. What is asserted here is that
        // the navigation signal is emitted only on a purge that came back clean.
        seedCredentials(existingCredentials)
        createViewModel()

        viewModel.onLogout()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLoggedOut)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    // ── onLogout: purge delegation (slice 7) ──────────────────────────────────

    @Test
    fun `onLogout delegates the whole teardown to the logout purger`() = runTest(testDispatcher) {
        seedCredentials(existingCredentials)
        createViewModel()

        viewModel.onLogout()
        advanceUntilIdle()

        assertEquals(1, logoutPurger.logOutCount)
    }

    @Test
    fun `onLogout does not clear the credentials behind the purger's back`() = runTest(testDispatcher) {
        // The purge clears credentials *last*, after the local stores are empty. A ViewModel that
        // also cleared them directly would defeat that ordering, so with a purger that clears
        // nothing the credentials must still be there.
        seedCredentials(existingCredentials)
        createViewModel()

        viewModel.onLogout()
        advanceUntilIdle()

        assertEquals(existingCredentials, credentialsProvider.getCredentials())
    }

    @Test
    fun `a failed purge keeps the user logged in and shows a retryable French error`() = runTest(testDispatcher) {
        seedCredentials(existingCredentials)
        createViewModel()
        logoutPurger.failOnceWith = LogoutPurgeException("Échec du vidage du cache.")

        viewModel.onLogout()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoggedOut)
        assertFalse(state.isLoading)
        assertEquals(SettingsMessageSection.ACTIONS, state.messageSection)
        val error = state.errorMessage
        assertTrue("expected a French, retryable error, got: $error", error != null && error.contains("réessayer"))
        assertEquals(existingCredentials, credentialsProvider.getCredentials())
    }

    @Test
    fun `retrying after a failed purge logs the user out`() = runTest(testDispatcher) {
        seedCredentials(existingCredentials)
        createViewModel()
        logoutPurger.failOnceWith = LogoutPurgeException("Échec transitoire.")

        viewModel.onLogout()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoggedOut)

        viewModel.onLogout()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedOut)
        assertNull(state.errorMessage)
        assertEquals(2, logoutPurger.logOutCount)
    }

    @Test
    fun `the loading state is held for as long as the purge runs`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        logoutPurger = FakeLogoutPurger(gate = gate)
        seedCredentials(existingCredentials)
        createViewModel()

        viewModel.onLogout()
        advanceUntilIdle()
        assertTrue("the purge is still running", viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isLoggedOut)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isLoggedOut)
    }

    @Test
    fun `confirming logout closes the confirmation dialog`() = runTest(testDispatcher) {
        seedCredentials(existingCredentials)
        createViewModel()
        viewModel.onLogoutRequested()

        viewModel.onLogout()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLogoutConfirmationVisible)
    }

    // ── onLogout: the purge is not this screen's to own (review finding H1) ───

    @Test
    fun `a Settings screen that replaces the one which asked still learns the purge succeeded`() =
        runTest(testDispatcher) {
            // The user presses "Se déconnecter", then navigates away while the purge is running.
            // The ViewModel that asked is gone; the one that takes its place must not be left
            // believing the user is still signed in.
            val gate = CompletableDeferred<Unit>()
            logoutPurger = FakeLogoutPurger(gate = gate)
            seedCredentials(existingCredentials)
            createViewModel()
            viewModel.onLogout()
            advanceUntilIdle()

            createViewModel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isLoggedOut)
        }

    @Test
    fun `a Settings screen opened while a purge runs shows it as in progress`() =
        runTest(testDispatcher) {
            val gate = CompletableDeferred<Unit>()
            logoutPurger = FakeLogoutPurger(gate = gate)
            seedCredentials(existingCredentials)
            createViewModel()
            viewModel.onLogout()
            advanceUntilIdle()

            createViewModel()

            assertTrue(
                "a purge is running, so the screen's actions must stay disabled",
                viewModel.uiState.value.isLoading,
            )
            gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `a Settings screen opened after a consumed logout is not sent to onboarding again`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            viewModel.onLogout()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isLoggedOut)

            // The user signs in again and comes back to Settings. The previous logout is history.
            createViewModel()

            assertFalse(viewModel.uiState.value.isLoggedOut)
        }

    // ── onSaveCredentials — H5: a purge finishing mid-authenticate() must not be committed or
    //    rolled back over ─────────────────────────────────────────────────────

    @Test
    fun `a purge that completes while authenticate is in flight refuses the success instead of announcing it`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            val gate = CompletableDeferred<Unit>()
            coEvery { catalogRepository.authenticate() } coAnswers {
                gate.await()
                Resource.Success(Unit)
            }

            viewModel.onServerUrlChange("http://new.example.com:8080")
            viewModel.onUsernameChange("newuser")
            viewModel.onPasswordChange("newpass")

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()
            // The tentative write landed and authenticate() is now parked on the gate.
            assertTrue(viewModel.uiState.value.isLoading)

            // A purge starts and finishes entirely while this attempt is still mid-authenticate() —
            // models a logout started from this very screen settling in the exact window
            // runUnlessPurgeOwed cannot cover, since it only excludes the tentative write.
            applicationScope.launch { logoutCoordinator.logOut() }
            testDispatcher.scheduler.runCurrent()

            gate.complete(Unit)
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.uiState.value
            assertFalse(
                "a session the purge already superseded must never be announced as saved",
                state.infoMessage != null,
            )
            assertFalse(state.isLoading)
            assertEquals(CREDENTIALS_REFUSED_MESSAGE, state.errorMessage)
        }

    @Test
    fun `a purge that completes while authenticate is in flight refuses the rollback instead of overwriting a settled session`() =
        runTest(testDispatcher) {
            seedCredentials(existingCredentials)
            createViewModel()
            val gate = CompletableDeferred<Unit>()
            coEvery { catalogRepository.authenticate() } coAnswers {
                gate.await()
                Resource.Error(throwable = CatalogException.AuthenticationFailed())
            }

            viewModel.onServerUrlChange("http://bad.example.com:8080")
            viewModel.onUsernameChange("baduser")
            viewModel.onPasswordChange("badpass")

            viewModel.onSaveCredentials()
            testDispatcher.scheduler.runCurrent()
            val tentativeWrite = credentialsProvider.getCredentials()
            assertEquals(
                XtreamCredentials(baseUrl = "http://bad.example.com:8080", username = "baduser", password = "badpass"),
                tentativeWrite,
            )

            applicationScope.launch { logoutCoordinator.logOut() }
            testDispatcher.scheduler.runCurrent()

            gate.complete(Unit)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "a stale attempt's failure must not overwrite credentials a purge already " +
                    "settled — the store must be left exactly as the purge (not this attempt) left it",
                tentativeWrite,
                credentialsProvider.getCredentials(),
            )
            assertEquals(CREDENTIALS_REFUSED_MESSAGE, viewModel.uiState.value.errorMessage)
        }

    // ── confirmation copy (slice 8) ───────────────────────────────────────────

    @Test
    fun `the logout confirmation announces that local downloads are deleted`() {
        // Acceptance criterion 10. The old copy promised the opposite ("téléchargements sont
        // conservés"), which is now a lie the user cannot undo.
        assertTrue(
            "the confirmation must name downloads: $LOGOUT_CONFIRMATION_MESSAGE",
            LOGOUT_CONFIRMATION_MESSAGE.contains("téléchargements"),
        )
        assertTrue(
            "the confirmation must announce deletion: $LOGOUT_CONFIRMATION_MESSAGE",
            LOGOUT_CONFIRMATION_MESSAGE.contains("supprim"),
        )
        // Profiles and favorites genuinely do survive, so "conservés" on its own is fine; what
        // must be gone is the old promise that *downloads* survive.
        val downloadsSentence = LOGOUT_CONFIRMATION_MESSAGE.split(". ")
            .single { it.contains("téléchargements") }
        assertTrue(
            "the sentence about downloads must announce deletion, not survival: $downloadsSentence",
            downloadsSentence.contains("supprim") && !downloadsSentence.contains("conserv"),
        )
    }
}
