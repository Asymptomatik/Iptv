package com.bobot.iptvapp.data.preferences

/**
 * Persists the "a logout purge is owed" flag across process death.
 *
 * The flag is written *before* anything destructive happens and cleared only once every step has
 * succeeded, so the window where the app has half-deleted an account's data is always a window in
 * which the marker is set. Anything that reads it — startup recovery, the enqueue guard — can
 * therefore treat "marker present" as "this install is not in a clean state yet".
 *
 * Kept out of [AppPreferencesStore] on purpose: that interface is user-facing settings the user
 * chose, this is internal recovery bookkeeping the user never sees.
 */
interface LogoutPurgeMarkerStore {

    /** `true` when a purge was started and has not been confirmed complete. */
    suspend fun isPurgePending(): Boolean

    /** Records that a purge is owed. Idempotent. */
    suspend fun markPurgePending()

    /** Records that the purge completed in full. Idempotent. */
    suspend fun clearPurgePending()
}
