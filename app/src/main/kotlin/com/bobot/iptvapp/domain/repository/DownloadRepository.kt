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
    suspend fun pause(downloadId: String)
    suspend fun resume(downloadId: String)
    suspend fun remove(downloadId: String)
}
