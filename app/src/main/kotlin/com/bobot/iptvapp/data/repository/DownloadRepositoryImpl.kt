package com.bobot.iptvapp.data.repository

import androidx.media3.common.util.UnstableApi
import com.bobot.iptvapp.data.local.dao.DownloadDao
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import com.bobot.iptvapp.data.local.mapper.toDomain
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.di.IoDispatcher
import com.bobot.iptvapp.domain.model.DownloadRequestData
import com.bobot.iptvapp.domain.model.DownloadRequestId
import com.bobot.iptvapp.domain.model.DownloadState
import com.bobot.iptvapp.domain.model.OfflineDownload
import com.bobot.iptvapp.domain.repository.DownloadRepository
import com.bobot.iptvapp.download.DownloadCommander
import com.bobot.iptvapp.download.purge.Media3DownloadIndexGateway
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * @param logoutCoordinator Holds every [enqueue] under the same [LogoutCoordinator.purgeLock] a
 *   purge runs under (see [LogoutCoordinator.runUnlessPurgeOwed]). A bare marker check followed by
 *   a Room write is a check-then-act: a request that read "no purge pending" could still land its
 *   row and its Media3 command behind steps a purge had already run. The lock makes the two
 *   mutually exclusive instead.
 * @param media3IndexGateway Polled after [DownloadCommander.enqueue] to confirm Media3 has
 *   observably filed the download before this call returns — see [awaitObservableAcceptance].
 */
@UnstableApi
class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao,
    private val downloadService: DownloadCommander,
    private val logoutCoordinator: LogoutCoordinator,
    private val media3IndexGateway: Media3DownloadIndexGateway,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DownloadRepository {
    override fun observeDownloads(): Flow<List<OfflineDownload>> = downloadDao.observeAll()
        .map { downloads -> downloads.map { it.toDomain() } }
        .flowOn(ioDispatcher)

    override fun observeDownload(downloadId: String): Flow<OfflineDownload?> = downloadDao.observe(downloadId)
        .map { it?.toDomain() }
        .flowOn(ioDispatcher)

    override suspend fun enqueue(request: DownloadRequestData): String? = withContext(ioDispatcher) {
        val downloadId = DownloadRequestId.create(request.contentType, request.contentId)

        val accepted = logoutCoordinator.runUnlessPurgeOwed {
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
            val observablyAccepted = awaitObservableAcceptance(downloadId)
            if (!observablyAccepted) {
                downloadDao.delete(downloadId)
                cancelStillLandingAdd(downloadId)
            }
            observablyAccepted
        }

        if (accepted != true) return@withContext null
        downloadId
    }

    /**
     * Polls [media3IndexGateway] until [downloadId] is observably filed, or [AWAIT_ACCEPTANCE_TIMEOUT_MILLIS]
     * elapses. Called from inside [LogoutCoordinator.runUnlessPurgeOwed]'s block, so a concurrent
     * purge cannot declare the index empty until this either lands the download or gives up.
     */
    private suspend fun awaitObservableAcceptance(downloadId: String): Boolean {
        var waitedMillis = 0L
        while (true) {
            if (media3IndexGateway.containsDownload(downloadId)) return true
            if (waitedMillis >= AWAIT_ACCEPTANCE_TIMEOUT_MILLIS) return false
            delay(AWAIT_ACCEPTANCE_POLL_MILLIS)
            waitedMillis += AWAIT_ACCEPTANCE_POLL_MILLIS
        }
    }

    /**
     * Compensates an add that has not observably landed within [AWAIT_ACCEPTANCE_TIMEOUT_MILLIS]:
     * still-in-flight does not mean abandoned, so a bare Room delete is not enough — the Intent
     * Media3 has not filed yet can still land afterwards. `DownloadService` dispatches intents
     * targeting the same download id in the order they were sent, so sending a matching `remove`
     * now guarantees it is processed after that add, whenever it eventually lands.
     *
     * Waits for the index to observably confirm the entry is gone before returning, so the caller —
     * still inside [LogoutCoordinator.runUnlessPurgeOwed]'s block — never releases the barrier while
     * a credentialed download could still appear from the compensated add.
     */
    private suspend fun cancelStillLandingAdd(downloadId: String) {
        downloadService.remove(downloadId)
        var waitedMillis = 0L
        while (media3IndexGateway.containsDownload(downloadId)) {
            if (waitedMillis >= AWAIT_CANCELLATION_TIMEOUT_MILLIS) return
            delay(AWAIT_ACCEPTANCE_POLL_MILLIS)
            waitedMillis += AWAIT_ACCEPTANCE_POLL_MILLIS
        }
    }

    override suspend fun pause(downloadId: String): Boolean = withContext(ioDispatcher) {
        logoutCoordinator.runUnlessPurgeOwed { downloadService.pause(downloadId); true } == true
    }

    override suspend fun resume(downloadId: String): Boolean = withContext(ioDispatcher) {
        logoutCoordinator.runUnlessPurgeOwed { downloadService.resume(downloadId); true } == true
    }

    override suspend fun remove(downloadId: String) = withContext(ioDispatcher) {
        downloadService.remove(downloadId)
    }

    private companion object {
        private const val AWAIT_ACCEPTANCE_TIMEOUT_MILLIS = 5_000L
        private const val AWAIT_ACCEPTANCE_POLL_MILLIS = 50L
        private const val AWAIT_CANCELLATION_TIMEOUT_MILLIS = 5_000L
    }
}
