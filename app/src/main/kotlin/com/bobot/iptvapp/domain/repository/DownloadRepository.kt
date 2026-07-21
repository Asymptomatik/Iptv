package com.bobot.iptvapp.domain.repository

import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.OfflineDownload
import kotlinx.coroutines.flow.Flow

/** Queue and reactive UI index for VOD downloads. */
interface DownloadRepository {
    fun observeDownloads(): Flow<List<OfflineDownload>>
    fun observeDownload(downloadId: String): Flow<OfflineDownload?>
    suspend fun enqueue(request: DownloadRequestData): String
    suspend fun pause(downloadId: String)
    suspend fun resume(downloadId: String)
    suspend fun remove(downloadId: String)
}
