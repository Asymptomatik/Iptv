package com.bobot.iptvapp

import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.navigation.Onboarding
import com.bobot.iptvapp.navigation.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MainViewModel] (Task 16, Volet B).
 *
 * Follows the exact `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModelTest] (Task 15): [Dispatchers.setMain]
 * swaps in a [StandardTestDispatcher], and `testDispatcher.scheduler.runCurrent()` drains the
 * `init` block's one-shot `viewModelScope.launch` deterministically. [InMemoryCredentialsProvider]
 * is used as a real (non-mocked) test double — same pattern as `SettingsViewModelTest` /
 * `OnboardingViewModelTest` — since it is trivial to seed with a value or leave empty.
 */
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var credentialsProvider: InMemoryCredentialsProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        credentialsProvider = InMemoryCredentialsProvider()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startDestination is null before the credentials check completes`() {
        val viewModel = MainViewModel(credentialsProvider)

        // Deliberately not draining the scheduler yet — the one-shot check is still in flight.
        assertNull(viewModel.startDestination.value)
    }

    @Test
    fun `startDestination resolves to Profiles when credentials are already persisted`() {
        runTest(testDispatcher) {
            credentialsProvider.setCredentials(
                XtreamCredentials(baseUrl = "http://example.com:8080", username = "user", password = "pass"),
            )
        }

        val viewModel = MainViewModel(credentialsProvider)
        testDispatcher.scheduler.runCurrent()

        assertEquals(Profiles, viewModel.startDestination.value)
    }

    @Test
    fun `startDestination resolves to Onboarding when no credentials are persisted`() {
        val viewModel = MainViewModel(credentialsProvider)
        testDispatcher.scheduler.runCurrent()

        assertEquals(Onboarding, viewModel.startDestination.value)
    }
}
