package com.bobot.iptvapp.data.logout

import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.domain.logout.LogoutPurgeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogoutCoordinator] — the review's H1 (a purge owned by `viewModelScope`) and M5
 * (two purges with nothing between them).
 *
 * ## What is actually exercised
 * The coordinator sits on top of the *real* [DefaultLogoutPurger] and its real sub-purgers (see
 * [LogoutPurgeStack]), and every test drives two independent callers against it. What is asserted
 * is the interleaving those callers produce in [LogoutPurgeStack.journal] and the state the stores
 * end up in — not that a method was called. A coordinator that "looks" single-flight but lets the
 * second caller through would produce a different journal and fail here.
 *
 * [StandardTestDispatcher] is shared by the test body, the stand-in application scope and the
 * stand-in screen scope, so `advanceUntilIdle()` drains all three in one deterministic order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogoutCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Stands in for the `@ApplicationScope` singleton scope: nothing in a test cancels it. */
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)

    private val stack = LogoutPurgeStack(testDispatcher)

    private val coordinator = LogoutCoordinator(
        logoutPurger = stack.purger,
        markerStore = stack.markerStore,
        applicationScope = applicationScope,
    )

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    // ── H1: the purge does not belong to the screen that asked for it ─────────

    @Test
    fun `a purge outlives the screen scope that started it`() = runTest(testDispatcher) {
        stack.seedSignedInAccountWithDownloads()
        val gate = CompletableDeferred<Unit>()
        stack.gateFirstMedia3Removal = gate

        // The Settings screen asks for the logout…
        val screenScope = CoroutineScope(SupervisorJob() + testDispatcher)
        screenScope.launch { coordinator.logOut() }
        advanceUntilIdle()
        assertEquals(
            "the purge must have reached its first blocking step",
            listOf("mark-pending", "stop-playback", "invalidate-memos", "media3-index:enter"),
            stack.journal,
        )

        // …then the user presses back and the ViewModel is destroyed mid-purge.
        screenScope.cancel()
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertNull("the credentials must still have been cleared", stack.credentials.getCredentials())
        assertEquals(emptyList<DownloadEntity>(), stack.downloadDao.currentRows)
        assertFalse("the marker must not stay pending", stack.markerStore.pending)
    }

    @Test
    fun `an abandoned purge still publishes its outcome for the next screen`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            val gate = CompletableDeferred<Unit>()
            stack.gateFirstMedia3Removal = gate

            val screenScope = CoroutineScope(SupervisorJob() + testDispatcher)
            screenScope.launch { coordinator.logOut() }
            advanceUntilIdle()
            assertEquals(LogoutPurgeState.Running, coordinator.state.value)

            screenScope.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(LogoutPurgeState.Completed, coordinator.state.value)
        }

    @Test
    fun `a failed purge is published as Failed and leaves the marker set`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            stack.downloadDao.failOnClear = IllegalStateException("disque plein")

            // A failure is an outcome, not something the caller has to catch: it is published.
            val thrown = runCatching { coordinator.logOut() }.exceptionOrNull()
            advanceUntilIdle()

            assertNull("logOut must not throw at its caller: $thrown", thrown)
            assertTrue(
                "expected Failed, got ${coordinator.state.value}",
                coordinator.state.value is LogoutPurgeState.Failed,
            )
            assertTrue(stack.markerStore.pending)
        }

    // ── M5: single-flight ─────────────────────────────────────────────────────

    @Test
    fun `a recovery cannot clear the marker while a user logout is still running`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            val gate = CompletableDeferred<Unit>()
            stack.gateFirstMedia3Removal = gate

            launch { coordinator.logOut() }
            advanceUntilIdle()
            val journalWhileGated = stack.journal.toList()

            // Startup recovery arrives while the logout is parked on its longest step.
            launch { coordinator.recoverIfNeeded() }
            advanceUntilIdle()

            assertEquals(
                "the recovery must not have run a single step behind the logout's back",
                journalWhileGated,
                stack.journal,
            )

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "mark-pending",
                    "stop-playback",
                    "invalidate-memos",
                    "media3-index:enter",
                    "media3-index:exit",
                    "media3-cache",
                    "room-downloads",
                    "room-catalog-epg",
                    "finalize",
                ),
                stack.journal,
            )
        }

    @Test
    fun `a second logout joins the first attempt instead of running its own`() = runTest(testDispatcher) {
        stack.seedSignedInAccountWithDownloads()
        val gate = CompletableDeferred<Unit>()
        stack.gateFirstMedia3Removal = gate

        val first = launch { coordinator.logOut() }
        advanceUntilIdle()

        val second = launch { coordinator.logOut() }
        advanceUntilIdle()

        assertEquals(
            "the marker must be marked once and the logout finalized not at all while the first purge runs",
            1,
            stack.journal.count { it == "mark-pending" },
        )
        assertEquals(0, stack.journal.count { it == "finalize" })

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()

        // A second call arriving while the first is in flight must join that same attempt and
        // its exact outcome, not queue behind it and replay its own — see the class KDoc "Why
        // single-flight is about the result, not just mutual exclusion".
        assertEquals(
            "the second call must have joined the first attempt, not started a second one",
            1,
            stack.journal.count { it == "mark-pending" },
        )
        assertEquals(1, stack.journal.count { it == "finalize" })
        assertEquals("finalize", stack.journal.last())
        assertFalse(stack.markerStore.pending)
        assertEquals(LogoutPurgeState.Completed, coordinator.state.value)
    }

    @Test
    fun `a recovery arriving while a logout is still in flight shares its outcome instead of publishing its own`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()
            val gate = CompletableDeferred<Unit>()
            stack.gateFirstMedia3Removal = gate

            val logoutJob = launch { coordinator.logOut() }
            advanceUntilIdle()

            // A recovery arrives while the logout attempt is still in flight, not queued behind
            // it — with nothing of its own left to do once it joins that attempt's outcome.
            val recoveryJob = launch { coordinator.recoverIfNeeded() }
            advanceUntilIdle()

            gate.complete(Unit)
            advanceUntilIdle()
            logoutJob.join()
            recoveryJob.join()

            assertEquals(
                "the recovery joining an in-flight logout must not run its own attempt behind it",
                1,
                stack.journal.count { it == "mark-pending" },
            )
            assertEquals(1, stack.journal.count { it == "finalize" })
            assertEquals(
                "the shared outcome must be the logout's Completed, never downgraded to Idle by " +
                    "the joining recovery finding nothing left to do on its own",
                LogoutPurgeState.Completed,
                coordinator.state.value,
            )
        }

    // ── Recovery with nothing owed ────────────────────────────────────────────

    @Test
    fun `a recovery with nothing owed leaves the published state untouched`() =
        runTest(testDispatcher) {
            stack.seedSignedInAccountWithDownloads()

            coordinator.recoverIfNeeded()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), stack.journal)
            assertEquals(LogoutPurgeState.Idle, coordinator.state.value)
        }
}
