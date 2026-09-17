package com.bobot.iptvapp.download.purge

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.DownloadManager
import com.bobot.iptvapp.di.DownloadModule
import com.bobot.iptvapp.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Media3DownloadIndexGateway] over the singleton [DownloadManager].
 *
 * ## Two different threads, on purpose
 * [DownloadManager]'s mutating methods must run on the thread that built it — Hilt first resolves
 * the singleton while `IptvApplication`'s fields are injected, so that is the main thread, and
 * [removeAllDownloads] hops there. Reading the index is the opposite: it is a SQLite query and has
 * no business on the main thread, so [countIndexedDownloads] goes to [ioDispatcher].
 *
 * Going through [DownloadManager] rather than `DownloadService.sendRemoveAllDownloads` is also
 * deliberate. The service route is fire-and-forget and, in the background, subject to the
 * foreground-start restrictions this app would hit exactly when a purge is being resumed at
 * startup. The manager is the same singleton the service drives anyway.
 */
@Singleton
@UnstableApi
class LiveMedia3DownloadIndexGateway @Inject constructor(
    private val downloadManager: DownloadManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : Media3DownloadIndexGateway {

    override suspend fun removeAllDownloads() {
        withContext(Dispatchers.Main) { downloadManager.removeAllDownloads() }
    }

    override suspend fun countIndexedDownloads(): Int = withContext(ioDispatcher) {
        // No state filter: a completed download still owns an index row and its bytes, and the
        // purge is not done until those are gone too.
        downloadManager.downloadIndex.getDownloads().use { it.count }
    }
}

/**
 * [Media3CacheGateway] over the live [Cache] singleton.
 *
 * Eviction goes key by key through this very instance. The tempting shortcut — deleting
 * `filesDir/downloads` — corrupts a running `SimpleCache`: the singleton's in-memory index keeps
 * announcing spans whose files are gone, and the directory can then be refused on the next open.
 */
@Singleton
@UnstableApi
class LiveMedia3CacheGateway @Inject constructor(
    @DownloadModule.DownloadCache private val cache: Cache,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : Media3CacheGateway {

    override suspend fun cachedKeys(): Set<String> = withContext(ioDispatcher) { cache.keys }

    override suspend fun removeResource(key: String) = withContext(ioDispatcher) {
        cache.removeResource(key)
    }
}
