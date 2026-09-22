package com.bobot.iptvapp

import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.logout.LogoutPurgeStack
import com.bobot.iptvapp.navigation.Onboarding
import com.bobot.iptvapp.navigation.Profiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Unit tests for [MainViewModel] (Task 16, Volet B; extended by the logout purge's review finding
 * M2).
 *
 * Follows the `viewModelScope` testing convention established by
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModelTest]: [Dispatchers.setMain] swaps in a
 * [StandardTestDispatcher] and `advanceUntilIdle()` drains the `init` block deterministically.
 *
 * The purge underneath is the real one ([LogoutPurgeStack]), not a stub, because what M2 is about
 * is a *race*: the start destination used to be resolved from a credentials read that could happen
 * before, during or after the purge that deletes those very credentials. Only a real purge with a
 * step the test can hold open shows which one won.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)

    private val stack = LogoutPurgeStack(testDispatcher)

    private val coordinator = LogoutCoordinator(
        logoutPurger = stack.purger,
        markerStore = stack.markerStore,
        applicationScope = applicationScope,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        applicationScope.cancel()
    }

    private fun createViewModel() = MainViewModel(
        credentialsProvider = stack.credentials,
        logoutPurgeMarkerStore = stack.markerStore,
        logoutCoordinator = coordinator,
    )

    // ── Nominal routing ───────────────────────────────────────────────────────

    @Test
    fun `the graph is not composed before the check completes`() {
        val viewModel = createViewModel()

        // Deliberately not draining the scheduler yet — the one-shot check is still in flight.
        assertEquals(StartupState.Resolving, viewModel.startupState.value)
    }

    @Test
    fun `a healthy signed-in install resolves to Profiles`() = runTest(testDispatcher) {
        stack.credentials.setCredentials(stack.accountA)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(StartupState.Ready(Profiles), viewModel.startupState.value)
    }

    @Test
    fun `an install with no credentials resolves to Onboarding`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(StartupState.Ready(Onboarding), viewModel.startupState.value)
    }

    // ── M2: recovery is resolved before any destination ───────────────────────

    @Test
    fun `a purge interrupted by the last run is finished before any destination is resolved`() =
        runTest(testDispatcher) {
            // Process died mid-purge: the marker survived, and so did the credentials, because
            // clearing them is the purge's last step.
            stack.seedSignedInAccountWithDownloads()
            stack.markerStore.markPurgePending()

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(
                "the account must never have been routed back into",
                StartupState.Ready(Onboarding),
                viewModel.startupState.value,
            )
            assertNull(stack.credentials.getCredentials())
            assertEquals(emptyList<Any>(), stack.downloadDao.currentRows)
        }

    @Test
    fun `the app stays on the loading surface while the owed purge is still running`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.markerStore.markPurgePending()
            val gate = CompletableDeferred<Unit>()
            stack.gateFirstMedia3Removal = gate

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(
                "resolving a destination now would enter a session about to be deleted",
                StartupState.Resolving,
                viewModel.startupState.value,
            )

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(StartupState.Ready(Onboarding), viewModel.startupState.value)
        }

    @Test
    fun `a purge that cannot finish blocks the app instead of entering an authenticated route`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.markerStore.markPurgePending()
            stack.downloadDao.failOnClear = IllegalStateException("disque plein")

            val viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.startupState.value
            assertTrue(
                "expected RecoveryFailed, got $state",
                state is StartupState.RecoveryFailed,
            )
            assertTrue(
                "the message must be actionable French: ${(state as StartupState.RecoveryFailed).message}",
                state.message.contains("déconnexion"),
            )
            assertTrue("the marker must stay set", stack.markerStore.pending)
        }

    @Test
    fun `retrying a failed recovery resolves the destination once the purge goes through`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.markerStore.markPurgePending()
            stack.downloadDao.failOnClear = IllegalStateException("disque plein")

            val viewModel = createViewModel()
            advanceUntilIdle()
            assertTrue(viewModel.startupState.value is StartupState.RecoveryFailed)

            stack.downloadDao.failOnClear = null
            viewModel.onRetryRecovery()
            advanceUntilIdle()

            assertEquals(StartupState.Ready(Onboarding), viewModel.startupState.value)
            assertFalse(stack.markerStore.pending)
        }

    @Test
    fun `the legacy no-credentials-but-leftover-downloads install is cleaned before routing`() =
        runTest(testDispatcher) {
            // Logged out before the purge existed: no credentials, no marker, rows still there.
            stack.downloadDao.upsert(stack.downloadRow("movie-1"))

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(StartupState.Ready(Onboarding), viewModel.startupState.value)
            assertEquals(emptyList<Any>(), stack.downloadDao.currentRows)
        }

    // ── H4: the logout terminal is not lost when no screen is left to observe it ──

    @Test
    fun `a logout that completes after bootstrap routes the app back to onboarding even with nothing else observing`() =
        runTest(testDispatcher) {
            stack.credentials.setCredentials(stack.accountA)
            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(StartupState.Ready(Profiles), viewModel.startupState.value)

            // Models a logout requested by a screen destroyed before the purge settles: nothing
            // downstream of the coordinator is watching for the outcome except this ViewModel,
            // which — unlike a screen — is never recreated mid-session.
            launch { coordinator.logOut() }
            advanceUntilIdle()

            assertEquals(
                "the root must route back to onboarding on its own once the purge completes, " +
                    "even though no screen ever asked to be told",
                StartupState.Ready(Onboarding),
                viewModel.startupState.value,
            )
        }

    @Test
    fun `a logout completing while bootstrap is still resolving does not race the bootstrap's own decision`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.markerStore.markPurgePending()
            val gate = CompletableDeferred<Unit>()
            stack.gateFirstMedia3Removal = gate

            val viewModel = createViewModel()
            advanceUntilIdle()
            assertEquals(StartupState.Resolving, viewModel.startupState.value)

            gate.complete(Unit)
            advanceUntilIdle()

            // The bootstrap's own recovery emptied the account: this must settle on Onboarding
            // through the normal credentials-read path, not be short-circuited mid-resolution by
            // the same Completed transition the new always-on collector also observes.
            assertEquals(StartupState.Ready(Onboarding), viewModel.startupState.value)
        }
}
