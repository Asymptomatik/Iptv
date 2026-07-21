package com.bobot.iptvapp.download

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.bobot.iptvapp.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3 foreground service that keeps queued VOD transfers alive when the app is backgrounded.
 *
 * The service owns transfer execution only. [DownloadTracker] will project Media3's state into
 * Room for UI consumption in the following milestone.
 */
@UnstableApi
class IptvDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    NOTIFICATION_CHANNEL_ID,
    R.string.downloads_notification_channel_name,
    0,
) {

    override fun getDownloadManager(): DownloadManager =
        EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadManagerEntryPoint::class.java,
        ).downloadManager()

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = notificationHelper.buildProgressNotification(
        this,
        android.R.drawable.stat_sys_download,
        null,
        null,
        downloads,
        notMetRequirements,
    )

    private val notificationHelper by lazy {
        DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "offline_downloads"
    }

    /** Commands route through DownloadService so foreground execution is started when needed. */
    @Singleton
    class Commander @Inject constructor(@ApplicationContext private val context: Context) {
        fun enqueue(downloadId: String, streamUrl: String) {
            val request = DownloadRequest.Builder(downloadId, Uri.parse(streamUrl))
                .build()
            sendAddDownload(context, IptvDownloadService::class.java, request, true)
        }

        fun pause(downloadId: String) =
            sendSetStopReason(context, IptvDownloadService::class.java, downloadId, PAUSE_STOP_REASON, true)

        fun resume(downloadId: String) =
            sendSetStopReason(context, IptvDownloadService::class.java, downloadId, Download.STOP_REASON_NONE, true)

        fun remove(downloadId: String) =
            sendRemoveDownload(context, IptvDownloadService::class.java, downloadId, true)

        private companion object {
            const val PAUSE_STOP_REASON = 1
        }
    }
}

/** Bridges the non-Hilt Media3 service lifecycle to the app singleton graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadManagerEntryPoint {
    fun downloadManager(): DownloadManager
}
