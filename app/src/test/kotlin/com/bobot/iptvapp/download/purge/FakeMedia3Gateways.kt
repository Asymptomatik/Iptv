package com.bobot.iptvapp.download.purge

import kotlinx.coroutines.CompletableDeferred

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
    private val pendingAdds = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val invisibleUntilLanded = mutableSetOf<String>()
    private val cancelledPendingAdds = mutableSetOf<String>()

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

    /**
     * Registers [downloadId] as sent to Media3 but not yet landed. [containsDownload] suspends for
     * it until [gate] completes, modelling `DownloadService`'s intent-dispatch latency (see
     * [Media3DownloadIndexGateway.containsDownload]) in a way a test controls precisely, rather than
     * a bounded poll count. A `null` gate lands it immediately.
     */
    fun enqueueDownload(downloadId: String, gate: CompletableDeferred<Unit>? = null) {
        if (gate == null) {
            downloadIds = downloadIds + downloadId
        } else {
            pendingAdds[downloadId] = gate
        }
    }

    override suspend fun containsDownload(downloadId: String): Boolean {
        if (downloadId in invisibleUntilLanded) return false
        pendingAdds[downloadId]?.let { gate ->
            gate.await()
            pendingAdds.remove(downloadId)
            downloadIds = downloadIds + downloadId
        }
        return downloadId in downloadIds
    }

    /**
     * Registers [downloadId] as sent to Media3 but observably absent — unlike [enqueueDownload]'s
     * `gate`, [containsDownload] returns `false` immediately instead of suspending, modelling a
     * bounded poll that keeps missing an add still working its way through
     * `DownloadService`'s intent-dispatch queue, rather than a single indefinite wait.
     */
    fun enqueueDownloadPendingIndefinitely(downloadId: String) {
        invisibleUntilLanded += downloadId
    }

    /**
     * The delayed Intent for [downloadId] finally lands. If [cancelPendingAdd] was called for it
     * first, the add has no effect — `DownloadService` dispatches intents to the same download id
     * in the order sent, so a compensating remove sent after this add always lands after it.
     */
    fun landLateAdd(downloadId: String) {
        invisibleUntilLanded -= downloadId
        if (!cancelledPendingAdds.remove(downloadId)) {
            downloadIds = downloadIds + downloadId
        }
    }

    /**
     * Models a compensating remove sent while [downloadId]'s add is still in flight: per
     * `DownloadService`'s ordered intent dispatch, it is guaranteed to process after that add, so
     * the add's eventual [landLateAdd] must have no observable effect.
     */
    fun cancelPendingAdd(downloadId: String) {
        cancelledPendingAdds += downloadId
    }
}

/** In-memory [Media3CacheGateway]; only ever evicts per key, like the real live-instance adapter. */
class FakeMedia3CacheGateway(initialKeys: Set<String> = emptySet()) : Media3CacheGateway {

    private val keys = initialKeys.toMutableSet()

    /** Every key passed to [removeResource], in call order, including repeats. */
    val removedKeys = mutableListOf<String>()

    /**
     * Keys that appear *during* an eviction pass, keyed by the removal that triggers them.
     *
     * This is the window a single snapshot cannot see: the player's own writer, or a download
     * command Media3 had already accepted, can create a resource after `cachedKeys()` was read and
     * before the purge returns. Registering it against a specific removal makes the interleaving
     * exact instead of time-dependent. Each entry fires once.
     */
    private val appearOnRemoval = mutableMapOf<String, String>()

    /**
     * When set, this key is re-created on *every* removal — a producer the purge never manages to
     * get ahead of. Models the failure a bounded wait must report rather than spin on forever.
     */
    var reappearsAfterEveryRemoval: String? = null

    /**
     * How many of [reappearsAfterEveryRemoval]'s refills still happen. [Int.MAX_VALUE] (the
     * default) never stops, modelling a producer the purge can never get ahead of. A finite value
     * models a producer that stops at a specific removal, so a test can put the *last* refill at an
     * exact point in the bounded wait (e.g. the very last pass before timeout).
     */
    var reappearCountRemaining: Int = Int.MAX_VALUE

    /**
     * When set, this key is added the *second* time [cachedKeys] is read while the cache is empty —
     * i.e. strictly after a first read has already observed it empty, modelling the player's writer
     * creating a span in the gap between that read and a confirmation re-read. Fires once.
     */
    private var appearAfterFirstEmptyRead: String? = null
    private var emptyReadArmed = false

    override suspend fun cachedKeys(): Set<String> {
        if (keys.isEmpty() && appearAfterFirstEmptyRead != null) {
            if (!emptyReadArmed) {
                emptyReadArmed = true
            } else {
                keys += appearAfterFirstEmptyRead!!
                appearAfterFirstEmptyRead = null
                emptyReadArmed = false
            }
        }
        return keys.toSet()
    }

    override suspend fun removeResource(key: String) {
        removedKeys += key
        keys -= key
        appearOnRemoval.remove(key)?.let { keys += it }
        reappearsAfterEveryRemoval?.let {
            if (reappearCountRemaining > 0) {
                keys += it
                reappearCountRemaining--
            }
        }
    }

    /** Makes [appearing] show up in the cache the moment [duringRemovalOf] is evicted. See above. */
    fun appearDuringEvictionOf(duringRemovalOf: String, appearing: String) {
        appearOnRemoval[duringRemovalOf] = appearing
    }

    /** See [appearAfterFirstEmptyRead] above. */
    fun appearAfterFirstEmptyRead(key: String) {
        appearAfterFirstEmptyRead = key
    }

    /**
     * Puts a key back after an eviction, modelling a resource re-cached by a transfer that had not
     * finished releasing when the purge walked past.
     */
    fun restoreResource(key: String) {
        keys += key
    }
}
