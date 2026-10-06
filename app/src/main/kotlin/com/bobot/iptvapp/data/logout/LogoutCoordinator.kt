package com.bobot.iptvapp.data.logout

import com.bobot.iptvapp.data.preferences.LogoutPurgeMarkerStore
import com.bobot.iptvapp.di.ApplicationScope
import com.bobot.iptvapp.domain.logout.LogoutPurgeState
import com.bobot.iptvapp.domain.logout.LogoutPurger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place from which a logout purge is ever started, and the one lock everything that could
 * race it takes.
 *
 * ## Why the purge does not belong to its caller
 * It used to run in `SettingsViewModel.viewModelScope`. Pressing back while the purge was waiting
 * the Media3 index out cancelled it mid-sequence, and since the marker is only cleared by the *last*
 * step, the install was left half-purged: pending marker set, credentials intact, every new download
 * silently refused, and no error anywhere — the one interruption neither the error path nor the
 * startup recovery caught. Here the work is launched in the process-lifetime
 * [ApplicationScope][com.bobot.iptvapp.di.ApplicationScope] instead, so a caller going away only
 * stops the *waiting*, never the purge.
 *
 * ## Why callers observe rather than catch
 * [logOut] and [recoverIfNeeded] suspend until the purge settles but never throw its failure at the
 * caller — the caller may not be alive to hear it. The outcome is published on [state], which a
 * freshly recreated screen reads on subscription, and which stays [LogoutPurgeState.Failed] for as
 * long as the install owes a purge.
 *
 * ## Why enqueue takes the same lock
 * [runUnlessPurgeOwed] is not a courtesy guard, it is the other half of the purge's mutual
 * exclusion. Checking the marker and then writing is a check-then-act: a request that read "no purge
 * pending" could be suspended at exactly the wrong moment and land its Room row and its Media3
 * command *behind* steps the purge had already run. Holding the purge's own [Mutex] across the whole
 * check-write-command sequence is what makes that impossible, and using [Mutex.tryLock] rather than
 * waiting means a request arriving mid-purge is refused immediately instead of blocking a UI thread
 * for the length of a purge.
 *
 * ## Why single-flight is about the result, not just mutual exclusion
 * [purgeLock] alone stops two attempts from *overlapping*, but a caller that merely waits for the
 * lock and then runs its own attempt afterwards is still a second, independent state transition.
 * A recovery queued behind a user logout is the concrete failure: the logout finishes and publishes
 * [LogoutPurgeState.Completed], the recovery then acquires the lock, finds nothing left owed, and
 * publishes [LogoutPurgeState.Idle] — overwriting the very outcome a freshly recreated screen needed
 * to see. [joinSharedAttempt] closes this by making every caller that arrives while an attempt is
 * running await that *same* attempt and observe its *exact* outcome, instead of starting its own.
 *
 * ## Why a credentials write is refused on a stale generation, not committed or rolled back
 * [runUnlessPurgeOwed] only excludes the instant a sign-in writes its tentative credentials; the
 * network `authenticate()` call after it is not, and cannot be, held under [purgeLock] — that would
 * block a real logout behind however long the network call takes. A purge can therefore start and
 * finish while a sign-in is mid-`authenticate()`, and by the time it resolves, acting on its result
 * is no longer safe either way: committing success would announce a session the purge has already
 * wiped, and rolling back a failure could write over — or clear — credentials a purge finished
 * settling in the meantime. [sessionGeneration] is bumped exactly once per purge that actually
 * purges something; callers capture it in the same [runUnlessPurgeOwed] block as their tentative
 * write and compare it again once `authenticate()` returns, refusing to touch the store at all if
 * it no longer matches. See
 * [com.bobot.iptvapp.ui.screen.onboarding.OnboardingViewModel.onSubmit] and
 * [com.bobot.iptvapp.ui.screen.settings.SettingsViewModel.onSaveCredentials].
 */
@Singleton
class LogoutCoordinator @Inject constructor(
    private val logoutPurger: LogoutPurger,
    private val markerStore: LogoutPurgeMarkerStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : SessionWriteGate {

    /**
     * Held for the *entire* duration of a purge, and briefly by every operation that must not
     * interleave with one. Single ownership is the point: two purges cannot overlap, so one can
     * never clear the pending marker while the other is still walking its steps.
     */
    private val purgeLock = Mutex()

    /** Guards [inFlightAttempt] only — a short critical section, never held for a whole purge. */
    private val attemptLock = Mutex()

    /** The attempt every concurrent caller is currently joining, or `null` when none is running. */
    private var inFlightAttempt: CompletableDeferred<Unit>? = null

    private val _state = MutableStateFlow<LogoutPurgeState>(LogoutPurgeState.Idle)

    /** Last known outcome of a purge in this process. See the class KDoc for why it is published. */
    val state: StateFlow<LogoutPurgeState> = _state.asStateFlow()

    private val _sessionGeneration = AtomicInteger(0)

    /**
     * Bumped exactly once every time a purge actually purges something. See the class KDoc "Why a
     * credentials write is refused on a stale generation, not committed or rolled back".
     */
    val sessionGeneration: Int get() = _sessionGeneration.get()

    private val _purgeAttempts = AtomicInteger(0)

    /**
     * Bumped as every purge attempt *starts*, under [purgeLock] and before its first step stops
     * playback — whatever the attempt then does or how it ends. [sessionGeneration] only moves once a
     * purge completes, too late for a player screen left open through the logout: by then the purge
     * has already released the player, and a screen still reading it would build a fresh one. That
     * stop runs on the main thread after this bump, so main-thread code reading an unchanged value
     * knows playback has not been stopped under it — see
     * [PlayerViewModel][com.bobot.iptvapp.ui.screen.player.PlayerViewModel].
     *
     * A recovery with nothing owed bumps it as well: it only runs at launch, before any player.
     */
    val purgeAttempts: Int get() = _purgeAttempts.get()

    /**
     * Runs a user-requested logout and suspends until it has settled, without ever cancelling it:
     * cancelling the caller only abandons the wait. The result is on [state]; nothing is thrown.
     *
     * A second call arriving while a purge runs joins that same attempt instead of racing or
     * queuing behind it — see [joinSharedAttempt].
     */
    suspend fun logOut() {
        joinSharedAttempt { logoutPurger.logOut(); true }
    }

    /**
     * Finishes the purge this install still owes, if any, and suspends until it has settled. Does
     * nothing, and leaves [state] alone, when nothing is owed and no other attempt is in flight to
     * join.
     */
    suspend fun recoverIfNeeded() {
        joinSharedAttempt { logoutPurger.recoverIfNeeded() }
    }

    /**
     * Fire-and-forget [recoverIfNeeded], for [com.bobot.iptvapp.download.LogoutPurgeRecoveryController]:
     * the process can be started by the download service alone, with no UI to wait on the result.
     */
    fun startRecovery() {
        applicationScope.launch { joinSharedAttempt { logoutPurger.recoverIfNeeded() } }
    }

    /**
     * Runs [block] under [purgeLock] unless a purge is running or owed, in which case it is refused
     * and `null` comes back. See the class KDoc for why the lock, and not just the marker.
     */
    suspend fun <T : Any> runUnlessPurgeOwed(block: suspend () -> T): T? {
        if (!purgeLock.tryLock()) return null
        try {
            if (markerStore.isPurgePending()) return null
            return block()
        } finally {
            purgeLock.unlock()
        }
    }

    /**
     * [runUnlessPurgeOwed], plus a refusal when a purge has completed since [generation] was read:
     * a write started for the previous account must not land after its purge — nor be purged by
     * nobody, since the next purge only comes with the next logout.
     */
    override suspend fun <T : Any> runInSession(generation: Int, block: suspend () -> T): T? {
        if (!purgeLock.tryLock()) return null
        try {
            if (_sessionGeneration.get() != generation || markerStore.isPurgePending()) return null
            return block()
        } finally {
            purgeLock.unlock()
        }
    }

    /**
     * [runInSession], but waiting for [purgeLock] rather than being refused while it is held: for a
     * short local write that must not be lost to a pick or an enqueue holding the lock for a few
     * milliseconds. A purge it queued behind still refuses it — that purge moved the generation, or
     * left one owed. Never for anything slow: [block] runs under the lock, holding up any logout.
     */
    suspend fun <T : Any> awaitInSession(generation: Int, block: suspend () -> T): T? =
        purgeLock.withLock {
            if (_sessionGeneration.get() != generation || markerStore.isPurgePending()) null else block()
        }

    /**
     * Joins the single attempt in flight, starting one on [applicationScope] when none is running.
     * This — not [purgeLock] alone — is what makes [logOut] and [recoverIfNeeded] single-flight in
     * *outcome*: every caller that arrives while [inFlightAttempt] is set suspends on that exact
     * [CompletableDeferred] and shares whatever it settles on, instead of running [purge] again once
     * the lock frees up. See the class KDoc "Why single-flight is about the result, not just mutual
     * exclusion".
     *
     * The check-or-start decision and the eventual clearing of [inFlightAttempt] both happen under
     * [attemptLock], and the clear only ever removes the reference it is still pointing at ([fresh]
     * itself) — never a newer attempt a caller may already have started in the gap between this
     * attempt finishing its work and reacquiring [attemptLock] to clean up.
     */
    private suspend fun joinSharedAttempt(purge: suspend () -> Boolean) {
        val attempt = attemptLock.withLock {
            inFlightAttempt ?: CompletableDeferred<Unit>().also { fresh ->
                inFlightAttempt = fresh
                applicationScope.launch {
                    try {
                        runExclusively(purge)
                    } finally {
                        // NonCancellable: this cleanup must still run even if applicationScope
                        // itself is going down — otherwise every future caller would see a stale
                        // inFlightAttempt and hang joining a Deferred nothing ever completes.
                        withContext(NonCancellable) {
                            attemptLock.withLock { if (inFlightAttempt === fresh) inFlightAttempt = null }
                        }
                        fresh.complete(Unit)
                    }
                }
            }
        }
        attempt.join()
    }

    /**
     * @param purge returns whether it actually purged anything — a recovery with nothing owed must
     *   not announce a completed logout to a screen that never asked for one.
     */
    private suspend fun runExclusively(purge: suspend () -> Boolean) {
        purgeLock.withLock {
            _purgeAttempts.incrementAndGet()
            _state.value = LogoutPurgeState.Running
            try {
                val purged = purge()
                _state.value =
                    if (purged) LogoutPurgeState.Completed else LogoutPurgeState.Idle
                if (purged) _sessionGeneration.incrementAndGet()
            } catch (cancellation: CancellationException) {
                // Only reachable if the application scope itself goes down, i.e. the process is
                // ending. Nothing was "wrong", and reporting a failure would be a lie, so the
                // state goes back to unknown and the marker — still set — drives the next launch.
                _state.value = LogoutPurgeState.Idle
                throw cancellation
            } catch (failure: Exception) {
                _state.value = LogoutPurgeState.Failed(failure)
            }
        }
    }
}
