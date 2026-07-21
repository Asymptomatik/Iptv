package com.bobot.iptvapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

/** Reactive local index for downloads managed by Media3. */
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
}
