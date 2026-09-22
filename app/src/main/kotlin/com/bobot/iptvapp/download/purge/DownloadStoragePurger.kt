package com.bobot.iptvapp.download.purge

/**
 * Releases the download storage Media3 owns: the download index and the bytes in the download
 * cache.
 *
 * Kept separate from [com.bobot.iptvapp.data.logout.LocalCachePurger] (which owns Room) because the
 * two have to happen in a fixed order — Media3 must have released a download before its Room row is
 * deleted, otherwise [com.bobot.iptvapp.download.DownloadTracker] can re-create the row from a
 * late callback and the purge would silently leave residue behind.
 */
interface DownloadStoragePurger {

    /**
     * Removes every Media3 download, active ones included, and only returns once the index is
     * observably empty.
     *
     * @throws com.bobot.iptvapp.domain.logout.LogoutPurgeException if the index still holds entries
     *   once the wait budget is spent — reporting success on a fire-and-forget command is the exact
     *   failure this method exists to prevent.
     */
    suspend fun removeAllDownloadsAndAwaitEmptyIndex()

    /** Evicts every cached resource through the live cache instance. Idempotent. */
    suspend fun evictCachedResources()

    /** `true` when the index or the cache still holds anything — backs logout-residue detection. */
    suspend fun hasStorageResidue(): Boolean
}
