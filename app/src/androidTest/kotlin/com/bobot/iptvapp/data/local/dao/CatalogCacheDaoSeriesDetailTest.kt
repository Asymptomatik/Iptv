package com.bobot.iptvapp.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bobot.iptvapp.data.local.IptvDatabase
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [CatalogCacheDao.upsertSeriesDetail] against a real in-memory
 * [IptvDatabase]: the SQL merge and the Room transaction the JVM fake can only model.
 *
 * ## Running
 * ```
 * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bobot.iptvapp.data.local.dao.CatalogCacheDaoSeriesDetailTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class CatalogCacheDaoSeriesDetailTest {

    private lateinit var db: IptvDatabase
    private lateinit var dao: CatalogCacheDao

    @Before
    fun setUp() {
        // No allowMainThreadQueries(): the concurrency test needs Room's real threading.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IptvDatabase::class.java).build()
        dao = db.catalogCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun listed(id: String, account: String = "acc") =
        SeriesEntity(account, id, "Listed (2024)", "list.jpg", null, "newcat", null, 2024)

    private fun detail(id: String, account: String = "acc") =
        SeriesEntity(account, id, "Detail Title", "detail.jpg", "Detail plot", "", "8.1", 2019)

    @Test
    fun detailWriteKeepsTheListedTitleYearAndCategory() = runTest {
        dao.upsertSeries(listOf(listed("s1")))

        dao.upsertSeriesDetail(detail("s1"))

        assertEquals(
            SeriesEntity("acc", "s1", "Listed (2024)", "detail.jpg", "Detail plot", "newcat", "8.1", 2024),
            dao.getSeriesById("acc", "s1"),
        )
    }

    @Test
    fun detailWriteInsertsASeriesNeverListed() = runTest {
        dao.upsertSeriesDetail(detail("s1"))

        assertEquals(detail("s1"), dao.getSeriesById("acc", "s1"))
    }

    @Test
    fun listWriteAfterTheDetailWinsOnEveryListedField() = runTest {
        dao.upsertSeriesDetail(detail("s1"))

        dao.upsertSeries(listOf(listed("s1")))

        assertEquals(listed("s1"), dao.getSeriesById("acc", "s1"))
    }

    @Test
    fun detailWriteOnlyTouchesItsOwnAccount() = runTest {
        dao.upsertSeries(listOf(listed("s1", account = "other")))

        dao.upsertSeriesDetail(detail("s1"))

        assertEquals(listed("s1", account = "other"), dao.getSeriesById("other", "s1"))
        assertEquals(detail("s1"), dao.getSeriesById("acc", "s1"))
    }

    @Test
    fun concurrentListAndDetailWritesNeverLoseTheListedFields() = runBlocking {
        // Whatever order Room serializes them in, a series written by both its list and its detail
        // call ends up with the list's title, year and category.
        val ids = (1..200).map { "s$it" }

        ids.flatMap { id ->
            listOf(
                async(Dispatchers.IO) { dao.upsertSeriesDetail(detail(id)) },
                async(Dispatchers.IO) { dao.upsertSeries(listOf(listed(id))) },
            )
        }.awaitAll()

        ids.forEach { id ->
            val row = dao.getSeriesById("acc", id)!!
            assertEquals(id, Triple("Listed (2024)", 2024, "newcat"), Triple(row.title, row.year, row.categoryId))
        }
    }
}
