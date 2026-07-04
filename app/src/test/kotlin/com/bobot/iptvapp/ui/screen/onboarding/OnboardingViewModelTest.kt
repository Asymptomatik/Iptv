package com.bobot.iptvapp.ui.screen.onboarding

import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.repository.CatalogRepository
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
 * Unit tests for [OnboardingViewModel].
 *
 * Follows the `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.player.PlayerViewModelTest] (Task 13): [Dispatchers.setMain]
 * swaps in a [StandardTestDispatcher], and [StandardTestDispatcher.scheduler]'s `runCurrent()`
 * drains pending coroutines deterministically.
 *
 * Unlike [com.bobot.iptvapp.ui.screen.player.PlayerViewModelTest] — which never awaits a
 * `suspend` call directly from the test body — several tests below assert on
 * [InMemoryCredentialsProvider.getCredentials], itself a `suspend fun`. Calling a `suspend`
 * function requires a coroutine scope, so those test bodies are wrapped in
 * `runTest(testDispatcher)` (same `StandardTestDispatcher` used for `Dispatchers.setMain`,
 * matching the pattern established by `CatalogRepositoryImplTest`/`ProfileRepositoryImplTest`);
 * `testDispatcher.scheduler.runCurrent()` is still called explicitly inside to drain the
 * `viewModelScope.launch` before asserting.
 *
 * [InMemoryCredentialsProvider] is used as a real (non-mocked) test double — same pattern as
 * `CatalogRepositoryImplTest` — so persistence side effects (`setCredentials` /
 * `clearCredentials`) can be asserted directly instead of via mock verification.
 */
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var catalogRepository: CatalogRepository
    private lateinit var credentialsProvider: InMemoryCredentialsProvider
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        catalogRepository = mockk()
        credentialsProvider = InMemoryCredentialsProvider()

        viewModel = OnboardingViewModel(
            catalogRepository = catalogRepository,
            credentialsProvider = credentialsProvider,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fillValidForm() {
        viewModel.onServerUrlChange("http://example.com:8080")
        viewModel.onUsernameChange("user")
        viewModel.onPasswordChange("pass")
    }

    // ── Field updates ─────────────────────────────────────────────────────────

    @Test
    fun `field changes update state and clear a prior error message`() {
        viewModel.onServerUrlChange("http://a.com")
        viewModel.onUsernameChange("u")
        viewModel.onPasswordChange("p")

        val state = viewModel.uiState.value
        assertEquals("http://a.com", state.serverUrl)
        assertEquals("u", state.username)
        assertEquals("p", state.password)
        assertNull(state.errorMessage)
    }

    @Test
    fun `onTogglePasswordVisibility flips isPasswordVisible`() {
        assertFalse(viewModel.uiState.value.isPasswordVisible)

        viewModel.onTogglePasswordVisibility()
        assertTrue(viewModel.uiState.value.isPasswordVisible)

        viewModel.onTogglePasswordVisibility()
        assertFalse(viewModel.uiState.value.isPasswordVisible)
    }

    // ── onSubmit — client-side validation ────────────────────────────────────

    @Test
    fun `onSubmit with blank fields shows an error and never calls authenticate`() {
        viewModel.onSubmit()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isAuthenticated)
        assertTrue(state.errorMessage!!.isNotBlank())
        coVerify(exactly = 0) { catalogRepository.authenticate() }
    }

    // ── onSubmit — success path ───────────────────────────────────────────────

    @Test
    fun `onSubmit persists credentials and flips isAuthenticated on success`() = runTest(testDispatcher) {
        coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)
        fillValidForm()

        viewModel.onSubmit()
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(
            XtreamCredentials(baseUrl = "http://example.com:8080", username = "user", password = "pass"),
            credentialsProvider.getCredentials(),
        )
    }

    @Test
    fun `onSubmit trims server url and username but not password`() = runTest(testDispatcher) {
        coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)
        viewModel.onServerUrlChange("  http://example.com:8080  ")
        viewModel.onUsernameChange("  user  ")
        viewModel.onPasswordChange("  pass  ")

        viewModel.onSubmit()
        testDispatcher.scheduler.runCurrent()

        assertEquals(
            XtreamCredentials(baseUrl = "http://example.com:8080", username = "user", password = "  pass  "),
            credentialsProvider.getCredentials(),
        )
    }

    // ── onSubmit — failure path ───────────────────────────────────────────────

    @Test
    fun `onSubmit rolls back credentials and shows an authentication message on AuthenticationFailed`() =
        runTest(testDispatcher) {
            coEvery { catalogRepository.authenticate() } returns
                Resource.Error(throwable = CatalogException.AuthenticationFailed())
            fillValidForm()

            viewModel.onSubmit()
            testDispatcher.scheduler.runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isAuthenticated)
            assertFalse(state.isLoading)
            assertTrue(state.errorMessage!!.contains("Identifiants"))
            assertNull(credentialsProvider.getCredentials())
            // Form remains editable / not cleared.
            assertEquals("http://example.com:8080", state.serverUrl)
            assertEquals("user", state.username)
            assertEquals("pass", state.password)
        }

    @Test
    fun `onSubmit shows a network message on NetworkError`() = runTest(testDispatcher) {
        coEvery { catalogRepository.authenticate() } returns
            Resource.Error(throwable = CatalogException.NetworkError("boom"))
        fillValidForm()

        viewModel.onSubmit()
        testDispatcher.scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isAuthenticated)
        assertTrue(state.errorMessage!!.contains("serveur"))
        assertNull(credentialsProvider.getCredentials())
    }

    @Test
    fun `onSubmit falls back to the Resource error message for unrecognised throwables`() {
        coEvery { catalogRepository.authenticate() } returns
            Resource.Error(throwable = RuntimeException("weird failure"))
        fillValidForm()

        viewModel.onSubmit()
        testDispatcher.scheduler.runCurrent()

        assertEquals("weird failure", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `onSubmit is a no-op while a request is already in flight`() {
        coEvery { catalogRepository.authenticate() } returns Resource.Success(Unit)
        fillValidForm()

        viewModel.onSubmit()
        // Do not drain the scheduler yet — isLoading should now be true.
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.onSubmit()
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { catalogRepository.authenticate() }
    }
}
