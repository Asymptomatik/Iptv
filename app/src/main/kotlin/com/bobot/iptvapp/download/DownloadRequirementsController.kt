package com.bobot.iptvapp.download

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import com.bobot.iptvapp.data.preferences.AppPreferencesStore
import com.bobot.iptvapp.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the singleton [DownloadManager]'s network [Requirements] in sync with the
 * user's Wi-Fi only downloads preference.
 *
 * Collects [AppPreferencesStore.observeWifiOnlyDownloads] for the lifetime of the
 * application and applies the corresponding [Requirements] on every change, so that
 * queued and in-progress downloads only proceed on an unmetered network when the
 * preference is enabled.
 */
@Singleton
@UnstableApi
class DownloadRequirementsController @Inject constructor(
    private val downloadManager: DownloadManager,
    private val appPreferencesStore: AppPreferencesStore,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        scope.launch {
            appPreferencesStore.observeWifiOnlyDownloads().collect { wifiOnly ->
                downloadManager.setRequirements(requirementsFor(wifiOnly))
            }
        }
    }

    companion object {
        /**
         * Pure mapping from the Wi-Fi only preference to the [Requirements] applied to
         * the [DownloadManager]. Extracted so it can be unit-tested on the JVM without
         * an Android runtime.
         */
        fun requirementsFor(wifiOnly: Boolean): Requirements =
            if (wifiOnly) {
                Requirements(Requirements.NETWORK_UNMETERED)
            } else {
                Requirements(Requirements.NETWORK)
            }
    }
}
