package com.bobot.iptvapp.data.local.dao

import com.bobot.iptvapp.data.local.entity.DownloadEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [DownloadDao] test double, mirroring [FakeCatalogCacheDao]'s conventions.
 *
 * Rows live in a [MutableStateFlow] so [observeAll] / [observe] emit on every write, which is what
 * the UI-facing tests need. [failOnClear] simulates an I/O failure on [clearAll] so the logout
 * orchestrator's "a failed step keeps the pending marker" behaviour can be exercised without
 * mocking the DAO. [gateGet] models a caller — typically
 * [com.bobot.iptvapp.download.DownloadTracker] — suspended between its `get` and its `upsert`.
 */
class FakeDownloadDao : DownloadDao {

    private val rows = MutableStateFlow<Map<String, DownloadEntity>>(emptyMap())

    /** When non-null, [clearAll] throws it instead of deleting. */
    var failOnClear: Throwable? = null

    /** Number of times [clearAll] actually ran, for idempotence assertions. */
    var clearAllCount: Int = 0
        private set

    /** When set, [get] suspends on it before returning — modelling a read stalled mid-flight. */
    var gateGet: CompletableDeferred<Unit>? = null

    val currentRows: List<DownloadEntity> get() = rows.value.values.toList()

    override suspend fun upsert(download: DownloadEntity) {
        rows.value = rows.value + (download.downloadId to download)
    }

    override suspend fun get(downloadId: String): DownloadEntity? {
        gateGet?.await()
        return rows.value[downloadId]
    }

    override fun observe(downloadId: String): Flow<DownloadEntity?> = rows.map { it[downloadId] }

    override fun observeAll(): Flow<List<DownloadEntity>> =
        rows.map { it.values.sortedByDescending(DownloadEntity::updatedAtMillis) }

    override suspend fun delete(downloadId: String) {
        rows.value = rows.value - downloadId
    }

    override suspend fun clearAll() {
        failOnClear?.let { throw it }
        clearAllCount++
        rows.value = emptyMap()
    }

    override suspend fun countAll(): Int = rows.value.size
}
