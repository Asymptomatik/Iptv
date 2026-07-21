package com.bobot.iptvapp.data.repository

import androidx.media3.common.util.UnstableApi
import com.bobot.iptvapp.data.local.dao.DownloadDao
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.data.local.mapper.toDomain
import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.DownloadRequestId
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.repository.DownloadRepository
import com.bobot.iptvapp.download.IptvDownloadService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@UnstableApi
class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao,
    private val downloadService: IptvDownloadService.Commander,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {
    override fun observeDownloads(): Flow<List<OfflineDownload>> = downloadDao.observeAll()
        .map { downloads -> downloads.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    override fun observeDownload(downloadId: String): Flow<OfflineDownload?> = downloadDao.observe(downloadId)
        .map { it?.toDomain() }
        .flowOn(ioDispatcher)

    override suspend fun enqueue(request: DownloadRequestData): String = withContext(ioDispatcher) {
        val downloadId = DownloadRequestId.create(request.contentType, request.contentId)
        val now = System.currentTimeMillis()
        downloadDao.upsert(
            DownloadEntity(
                downloadId = downloadId,
                contentType = request.contentType.name,
                contentId = request.contentId,
                title = request.title,
                artworkUrl = request.artworkUrl,
                streamUrl = request.streamUrl,
                state = DownloadState.QUEUED.name,
                bytesDownloaded = 0L,
                contentLength = DownloadEntity.UNKNOWN_CONTENT_LENGTH,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        downloadService.enqueue(downloadId, request.streamUrl)
        downloadId
    }

    override suspend fun pause(downloadId: String) = withContext(ioDispatcher) {
        downloadService.pause(downloadId)
    }

    override suspend fun resume(downloadId: String) = withContext(ioDispatcher) {
        downloadService.resume(downloadId)
    }

    override suspend fun remove(downloadId: String) = withContext(ioDispatcher) {
        downloadService.remove(downloadId)
    }
}
