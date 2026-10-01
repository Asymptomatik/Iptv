package com.bobot.iptvapp.data.remote.opensubtitles

import android.util.Log
import com.bobot.iptvapp.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deletes, at application start, the subtitle files a previous process left behind — see
 * [OnlineSubtitleFileStore.sweepOrphans]. Without it they would wait for the next subtitle download
 * or the next logout, which may never come. The sweep runs in the background: a process killed
 * before it ends simply leaves the job to the next start.
 *
 * Follows [com.bobot.iptvapp.download.LogoutPurgeRecoveryController]'s shape: a `@Singleton` held by
 * an `@Inject` field on [com.bobot.iptvapp.IptvApplication] so Hilt actually instantiates it.
 */
@Singleton
class OnlineSubtitleOrphanSweeper @Inject constructor(
    fileStore: OnlineSubtitleFileStore,
    @ApplicationScope applicationScope: CoroutineScope,
) {
    init {
        applicationScope.launch {
            // No path in the log: the next subtitle save retries the sweep anyway.
            if (!fileStore.sweepOrphans()) Log.w(TAG, "Orphaned subtitles remain; the next save retries")
        }
    }

    private companion object {
        const val TAG = "OnlineSubtitleSweeper"
    }
}
