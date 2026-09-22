package com.bobot.iptvapp.domain.logout

/**
 * Where the install currently stands with respect to the purge described by [LogoutPurger].
 *
 * Published by [com.bobot.iptvapp.data.logout.LogoutCoordinator] rather than returned from a call,
 * because the purge outlives whoever asked for it: the screen that pressed "Se déconnecter" can be
 * destroyed halfway through, and the one that replaces it still has to learn how the purge ended.
 */
sealed interface LogoutPurgeState {

    /** No purge has run in this process, or the last one had nothing to do. */
    data object Idle : LogoutPurgeState

    /** A purge is walking its steps right now. Nothing may be enqueued behind it. */
    data object Running : LogoutPurgeState

    /** The last purge emptied every local store and cleared the marker. */
    data object Completed : LogoutPurgeState

    /**
     * The last purge stopped on [failure]. The pending marker is still set, so downloads stay
     * refused and the next attempt — a retry or the next launch's recovery — resumes it.
     */
    data class Failed(val failure: Throwable) : LogoutPurgeState
}
