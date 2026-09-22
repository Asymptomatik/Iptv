package com.bobot.iptvapp.domain.logout

import kotlinx.coroutines.CompletableDeferred

/**
 * Test double for [LogoutPurger] used by the ViewModel-level slices.
 *
 * The orchestrator's own behaviour is covered against real fakes in
 * [com.bobot.iptvapp.data.logout.DefaultLogoutPurgerTest]; here the point is only what the
 * ViewModel does with a purge that succeeds, fails, or is still running, so this double records
 * calls and lets the test decide the outcome.
 *
 * @param gate When set, [logOut] suspends on it. Lets a test observe the in-flight loading state
 *   before deciding how the purge ends.
 */
class FakeLogoutPurger(
    private val gate: CompletableDeferred<Unit>? = null,
) : LogoutPurger {

    var logOutCount: Int = 0
        private set

    var recoverCount: Int = 0
        private set

    /** Thrown by the next [logOut] call, then cleared — so a test can make a retry succeed. */
    var failOnceWith: Throwable? = null

    override suspend fun logOut() {
        logOutCount++
        gate?.await()
        failOnceWith?.let {
            failOnceWith = null
            throw it
        }
    }

    override suspend fun recoverIfNeeded(): Boolean {
        recoverCount++
        return false
    }
}
