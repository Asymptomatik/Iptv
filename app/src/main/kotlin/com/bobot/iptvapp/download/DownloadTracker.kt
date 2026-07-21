package com.bobot.iptvapp.download

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.bobot.iptvapp.data.local.dao.DownloadDao
import com.bobot.iptvapp.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Projects Media3 transfer callbacks into the Room index consumed by the UI. */
@Singleton
@UnstableApi
class DownloadTracker @Inject constructor(
    private val downloadDao: DownloadDao,
    downloadManager: DownloadManager,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                update(download)
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                scope.launch { downloadDao.delete(download.request.id) }
            }
        })
    }

    private fun update(download: Download) {
        scope.launch {
            val existing = downloadDao.get(download.request.id) ?: return@launch
            downloadDao.upsert(
                existing.copy(
                    state = DownloadStateMapper.fromMedia3(download.state).name,
                    bytesDownloaded = download.bytesDownloaded,
                    contentLength = download.contentLength,
                    updatedAtMillis = download.updateTimeMs,
                ),
            )
        }
    }
}
