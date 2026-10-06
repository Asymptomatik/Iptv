package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.data.local.dao.FakeCatalogCacheDao
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.domain.util.accountKeyOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * How [CatalogRepositoryImpl.getSeriesDetail]'s Room write-through and the series **list** writer
 * ([CatalogRepositoryImpl.getSeriesList]) share a `series` row.
 *
 * The list owns title, year and category (the Series "Nouveautés" order and category rows read
 * them back on a cold start); the detail only refreshes cover, plot and rating. Each test pins one
 * interleaving with [FakeCatalogCacheDao.seriesInterleaving]: the list write is run right after the
 * detail path's first series-table access, i.e. exactly where a read-then-write merge would act on
 * a stale snapshot.
 *
 * What this proves is the *repository's* contract — it never merges from a row it read earlier.
 * It does not prove Room's transaction is atomic: that lives in the instrumented
 * `CatalogCacheDaoSeriesDetailTest`, against a real database.
 */
class CatalogRepositorySeriesDetailWriteTest {

    private lateinit var dataSource: CatalogDataSource
    private lateinit var catalogCacheDao: FakeCatalogCacheDao
    private lateinit var repository: CatalogRepositoryImpl

    private val testDispatcher = StandardTestDispatcher()
    private val credentialsProvider = InMemoryCredentialsProvider()
    private val account = XtreamCredentials("http://server.example.com", "user", "pass")
    private val accountKey = accountKeyOf(account).value

    @Before
    fun setUp() {
        dataSource = mockk()
        catalogCacheDao = FakeCatalogCacheDao()
        repository = CatalogRepositoryImpl(
            dataSource = dataSource,
            catalogCacheDao = catalogCacheDao,
            epgDao = mockk<EpgDao>(relaxed = true),
            ioDispatcher = testDispatcher,
            credentialsProvider = credentialsProvider,
            applicationScope = CoroutineScope(testDispatcher),
        )
    }

    private suspend fun signIn() {
        credentialsProvider.setCredentials(account)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    /** The real list writer: a category list fetch persisting its rows to Room. */
    private suspend fun listWrites(categoryId: String, listed: Series) {
        coEvery { dataSource.getSeriesList(categoryId) } returns listOf(listed)
        assertEquals(Resource.Success(listOf(listed)), repository.getSeriesList(categoryId).last())
    }

    private val detail = Series(
        id = "s1",
        title = "Detail Title",
        coverUrl = "http://example.com/detail.jpg",
        plot = "Detail plot",
        categoryId = "",
        rating = "8.1",
        year = 2019,
    )

    private val newListing = Series(
        id = "s1",
        title = "Listed (2024)",
        coverUrl = "http://example.com/list.jpg",
        plot = null,
        categoryId = "newcat",
        rating = null,
        year = 2024,
    )

    @Test
    fun `a list write landing after the detail looked at an older row keeps the new listing`() =
        runTest(testDispatcher) {
            signIn()
            catalogCacheDao.upsertSeries(
                listOf(SeriesEntity(accountKey, "s1", "Listed (2008)", null, null, "oldcat", null, 2008)),
            )
            coEvery { dataSource.getSeriesInfo("s1") } returns detail
            catalogCacheDao.seriesInterleaving = { listWrites("newcat", newListing) }

            val result = repository.getSeriesDetail("s1")

            assertEquals(Resource.Success(detail), result)
            assertEquals(
                SeriesEntity(accountKey, "s1", "Listed (2024)", "http://example.com/detail.jpg", "Detail plot", "newcat", "8.1", 2024),
                catalogCacheDao.getSeriesById(accountKey, "s1"),
            )
        }

    @Test
    fun `a list insert landing after the detail found no row keeps the listing's category`() =
        runTest(testDispatcher) {
            signIn()
            coEvery { dataSource.getSeriesInfo("s1") } returns detail
            catalogCacheDao.seriesInterleaving = { listWrites("newcat", newListing) }

            val result = repository.getSeriesDetail("s1")

            assertEquals(Resource.Success(detail), result)
            assertEquals(
                SeriesEntity(accountKey, "s1", "Listed (2024)", "http://example.com/detail.jpg", "Detail plot", "newcat", "8.1", 2024),
                catalogCacheDao.getSeriesById(accountKey, "s1"),
            )
        }

    @Test
    fun `a series never listed is cached with the detail's own fields`() =
        runTest(testDispatcher) {
            signIn()
            coEvery { dataSource.getSeriesInfo("s1") } returns detail

            repository.getSeriesDetail("s1")

            assertEquals(
                SeriesEntity(accountKey, "s1", "Detail Title", "http://example.com/detail.jpg", "Detail plot", "", "8.1", 2019),
                catalogCacheDao.getSeriesById(accountKey, "s1"),
            )
        }

    @Test
    fun `a list write after the detail write wins on every list-owned field`() =
        runTest(testDispatcher) {
            signIn()
            coEvery { dataSource.getSeriesInfo("s1") } returns detail

            repository.getSeriesDetail("s1")
            listWrites("newcat", newListing)

            assertEquals(
                SeriesEntity(accountKey, "s1", "Listed (2024)", "http://example.com/list.jpg", null, "newcat", null, 2024),
                catalogCacheDao.getSeriesById(accountKey, "s1"),
            )
        }
}
