package com.bobot.iptvapp.data.preferences

/** In-memory [LogoutPurgeMarkerStore] test double. */
class FakeLogoutPurgeMarkerStore(initiallyPending: Boolean = false) : LogoutPurgeMarkerStore {

    var pending: Boolean = initiallyPending
        private set

    /** When non-null, [markPurgePending] throws it. */
    var failOnMark: Throwable? = null

    override suspend fun isPurgePending(): Boolean = pending

    override suspend fun markPurgePending() {
        failOnMark?.let { throw it }
        pending = true
    }

    override suspend fun clearPurgePending() {
        pending = false
    }
}
