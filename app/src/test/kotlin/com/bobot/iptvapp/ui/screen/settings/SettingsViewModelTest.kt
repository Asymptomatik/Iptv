package com.bobot.iptvapp.ui.screen.settings

import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var credentialsProvider: InMemoryCredentialsProvider
    private lateinit var viewModel: SettingsViewModel

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

        verify(exactly = 1) { catalogRepository.invalidateCache(ContentType.MOVIE) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.SERIES) }
        verify(exactly = 0) { catalogRepository.invalidateCache(ContentType.LIVE) }
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
    fun `onLogout clears credentials and flips isLoggedOut`() = runTest(testDispatcher) {
        seedCredentials(existingCredentials)
        createViewModel()

        viewModel.onLogout()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isLoggedOut)
        assertNull(credentialsProvider.getCredentials())
    }
}
