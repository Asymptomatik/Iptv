package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reactive local index for downloads managed by Media3.
 *
 * ## Global purge surface
 * [clearAll] and [countAll] are deliberately unparameterised. They back the full-logout purge
 * (see [com.bobot.iptvapp.data.logout.LocalCachePurger]), which is global by design: rows here
 * carry a [DownloadEntity.streamUrl] holding the full Xtream URL — credentials included — so
 * nothing from a previous account may survive a logout, whichever account wrote it.
 */
@Dao
interface DownloadDao {

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun get(downloadId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    fun observe(downloadId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("DELETE FROM downloads WHERE downloadId = :downloadId")
    suspend fun delete(downloadId: String)

    /** Deletes every row, across every account. Idempotent — deleting from an empty table is a no-op. */
    @Query("DELETE FROM downloads")
    suspend fun clearAll()

    /** Number of rows currently indexed, across every account. Backs logout-residue detection. */
    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun countAll(): Int
}
