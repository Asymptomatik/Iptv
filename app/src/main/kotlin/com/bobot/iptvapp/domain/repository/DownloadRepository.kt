package com.bobot.iptvapp.domain.repository

import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.OfflineDownload
import kotlinx.coroutines.flow.Flow

/** Queue and reactive UI index for VOD downloads. */
interface DownloadRepository {
    fun observeDownloads(): Flow<List<OfflineDownload>>
    fun observeDownload(downloadId: String): Flow<OfflineDownload?>
    /**
     * Queues [request] for download and returns its download id, or `null` when the request was
     * refused because a logout purge is still pending.
     *
     * Refusing rather than throwing is deliberate: the call sites launch this from a
     * `viewModelScope` without a `try`/`catch`, so an exception would take the process down over a
     * transient state the user cannot act on. Nothing is written and no command is sent, which is
     * the guarantee that matters — a purge in flight must not have new rows appearing behind it.
     */
    suspend fun enqueue(request: DownloadRequestData): String?

    /**
     * Pauses [downloadId], returning whether the command was sent.
     *
     * `false` means the same thing it does for [enqueue]: a logout purge is running or owed, and
     * nothing was sent. A pause is a write to Media3's index like any other, and one applied behind
     * a purge that has already walked past leaves an entry the purge will never look at again.
     */
    suspend fun pause(downloadId: String): Boolean

    /**
     * Resumes [downloadId], returning whether the command was sent.
     *
     * The most destructive of the three to let through mid-purge: resuming restarts a transfer,
     * which re-creates both an index entry and the cache spans
     * [com.bobot.iptvapp.download.purge.DownloadStoragePurger.evictCachedResources] has just
     * emptied — the previous account's bytes written back to disk by the very session being signed
     * out of.
     */
    suspend fun resume(downloadId: String): Boolean

    /**
     * Removes [downloadId] and its bytes.
     *
     * Deliberately *not* guarded, unlike the three above, and deliberately still `Unit`: a removal
     * can only ever delete state the purge also wants gone, so refusing it during a purge would
     * block the one action that helps. It is idempotent against the purge's own
     * `removeAllDownloads`.
     */
    suspend fun remove(downloadId: String)
}
