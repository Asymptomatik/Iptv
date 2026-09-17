package com.bobot.iptvapp.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bobot.iptvapp.data.local.IptvDatabase
import com.bobot.iptvapp.data.local.entity.DownloadEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the logout-purge surface of [DownloadDao] using an in-memory
 * [IptvDatabase].
 *
 * The purge orchestration is covered on the JVM against fakes; what only a real database can
 * prove is that the `DELETE FROM downloads` and `SELECT COUNT(*) FROM downloads` statements
 * behind [DownloadDao.clearAll] and [DownloadDao.countAll] are valid and unscoped. They must be
 * unscoped: a logout that left another account's rows behind would leave
 * [DownloadEntity.streamUrl] — which carries the full Xtream URL, credentials included — readable
 * by whoever logs in next.
 *
 * ## Running
 * ```
 * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bobot.iptvapp.data.local.dao.DownloadDaoTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DownloadDaoTest {

    private lateinit var db: IptvDatabase
    private lateinit var downloadDao: DownloadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        downloadDao = db.downloadDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun download(downloadId: String, streamUrl: String = "http://a.example.com/$downloadId") =
        DownloadEntity(
            downloadId = downloadId,
            contentType = "MOVIE",
            contentId = downloadId,
            title = "Title $downloadId",
            artworkUrl = null,
            streamUrl = streamUrl,
            state = "COMPLETED",
            bytesDownloaded = 10L,
            contentLength = 10L,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

    @Test
    fun countAll_reports_zero_on_an_empty_table() = runTest {
        assertEquals(0, downloadDao.countAll())
    }

    @Test
    fun countAll_counts_every_row() = runTest {
        downloadDao.upsert(download("MOVIE:1"))
        downloadDao.upsert(download("MOVIE:2"))

        assertEquals(2, downloadDao.countAll())
    }

    @Test
    fun clearAll_removes_rows_from_every_account() = runTest {
        downloadDao.upsert(download("MOVIE:1", streamUrl = "http://a.example.com/u/accountA/1.mkv"))
        downloadDao.upsert(download("MOVIE:2", streamUrl = "http://b.example.com/u/accountB/2.mkv"))

        downloadDao.clearAll()

        assertEquals(0, downloadDao.countAll())
    }

    @Test
    fun clearAll_is_idempotent() = runTest {
        downloadDao.upsert(download("MOVIE:1"))

        downloadDao.clearAll()
        downloadDao.clearAll()

        assertEquals(0, downloadDao.countAll())
    }
}
