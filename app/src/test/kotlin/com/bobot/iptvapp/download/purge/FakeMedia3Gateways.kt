package com.bobot.iptvapp.download.purge

/**
 * In-memory [Media3DownloadIndexGateway] modelling the one property that matters to the purge: a
 * removal request is asynchronous, so the index does not empty on the call that asks for it.
 *
 * [removalLatency] is how many [countIndexedDownloads] checks still report the old contents before
 * the removal "lands". `0` means the removal is visible immediately; a large value models an index
 * that never drains, which is the failure the purger must not mistake for success.
 */
class FakeMedia3DownloadIndexGateway(
    initialDownloadIds: Set<String> = emptySet(),
    private val removalLatency: Int = 0,
) : Media3DownloadIndexGateway {

    private var downloadIds: Set<String> = initialDownloadIds
    private var pendingRemoval: Set<String>? = null
    private var checksSinceRemoval = 0

    /** When non-null, [removeAllDownloads] throws it. */
    var failOnRemoveAll: Throwable? = null

    var removeAllCount: Int = 0
        private set

    /** Index contents as last observed — `emptySet()` once the removal has landed. */
    val currentDownloadIds: Set<String> get() = downloadIds

    override suspend fun removeAllDownloads() {
        failOnRemoveAll?.let { throw it }
        removeAllCount++
        pendingRemoval = emptySet()
        checksSinceRemoval = 0
    }

    override suspend fun countIndexedDownloads(): Int {
        pendingRemoval?.let { target ->
            if (checksSinceRemoval >= removalLatency) {
                downloadIds = target
                pendingRemoval = null
            } else {
                checksSinceRemoval++
            }
        }
        return downloadIds.size
    }
}

/** In-memory [Media3CacheGateway]; only ever evicts per key, like the real live-instance adapter. */
class FakeMedia3CacheGateway(initialKeys: Set<String> = emptySet()) : Media3CacheGateway {

    private val keys = initialKeys.toMutableSet()

    /** Every key passed to [removeResource], in call order, including repeats. */
    val removedKeys = mutableListOf<String>()

    override suspend fun cachedKeys(): Set<String> = keys.toSet()

    override suspend fun removeResource(key: String) {
        removedKeys += key
        keys -= key
    }
}
