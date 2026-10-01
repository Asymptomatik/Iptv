package com.bobot.iptvapp.data.logout

/**
 * Lets a local write that belongs to the signed-in account land only if that account is still the
 * one signed in, atomically with respect to the logout purge.
 *
 * Implemented by [LogoutCoordinator]. Kept as an interface so a store the purge itself empties
 * (see [com.bobot.iptvapp.data.remote.opensubtitles.OnlineSubtitleFileStore]) can depend on it
 * without a dependency cycle through the purger.
 */
interface SessionWriteGate {

    /**
     * Runs [block] unless a purge is running or owed, or one has completed since [generation] was
     * read from [LogoutCoordinator.sessionGeneration]; `null` comes back when it is refused.
     */
    suspend fun <T : Any> runInSession(generation: Int, block: suspend () -> T): T?
}
