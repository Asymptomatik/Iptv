package com.bobot.iptvapp.data.logout

/**
 * Commits the two pieces of state that decide whether the install is signed in — the credentials
 * and the pending-purge marker — in a single write.
 *
 * ## Why they cannot be two calls
 * Both live in the same "iptv_prefs" DataStore, but
 * [com.bobot.iptvapp.data.preferences.DataStoreCredentialsProvider.clearCredentials] and
 * [com.bobot.iptvapp.data.preferences.LogoutPurgeMarkerStore.clearPurgePending] are two separate
 * transactions, and a logout that ran them in sequence had a window between them where the
 * credentials were already gone while the marker was still set. A failure landing in that window
 * left the app in a state no screen can describe honestly: the purge reports an error and Réglages
 * tells the user they are still signed in, while the account has in fact just been erased —
 * including the credentials needed to sign back in and retry.
 *
 * Folding both into one `DataStore.edit {}` removes the window: the block either commits and the
 * logout is complete on every axis, or it does not and *nothing* changed, which is precisely the
 * state "you are still signed in, retry" describes. That is also why this runs last, after the
 * residue check: until then, the credentials are what makes a retry possible.
 */
interface LogoutFinalizer {

    /**
     * Atomically removes the credentials and the pending marker.
     *
     * Throws whatever the underlying store throws — the caller turns that into a failed purge with
     * the marker left set, which is accurate because the failed write changed nothing.
     */
    suspend fun finalizeLogout()
}
