package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.data.local.dao.FakeCatalogCacheDao
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity.Companion.SCOPE_ALL
import com.bobot.iptvapp.data.local.entity.CatalogSyncEntity.Companion.SCOPE_CATEGORIES
import com.bobot.iptvapp.data.local.mapper.toEntity
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.util.Resource
import com.bobot.iptvapp.domain.util.accountKeyOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Room catalog cache being read on the **happy path** (schema v4), not only as the
 * network-failure fallback that [CatalogRepositoryAccountPartitionTest] and
 * [CatalogRepositoryImplTest] already cover.
 *
 * ## The behaviour being locked in
 * Home and Search load a catalog one category at a time (the OOM fix documented on both
 * ViewModels), so `getMovies(categoryId)` — not `getMovies(null)` — is the call that actually
 * runs, dozens of times per tab. Before this, that call had no cache at all in the repository:
 * the `cachedAll*` memos only ever hold the *unfiltered* lists, and Room was consulted from the
 * `catch` blocks alone. Every process restart therefore replayed the full, minute-long catalog
 * fetch against a Room database that already held every row.
 *
 * The tests below assert the two halves of the fix that a screenshot cannot: that a fresh slice
 * reaches the caller **without the data source being called at all** (`coVerify(exactly = 0)`),
 * and that freshness genuinely expires, so the cache is a cache and not a one-way snapshot the
 * user would be stuck with until they found the reload button in Réglages.
 *
 * ## Why a fake DAO and not a mock
 * [FakeCatalogCacheDao] is a real in-memory store, so a test can write rows the way production
 * does and read them back through the same queries. Its `markSyncedAgo` helper is what makes the
 * TTL testable without a clock abstraction: the repository asks how old a slice is, and the fake
 * decides the answer.
 */
class CatalogRepositoryReadThroughTest {

    private lateinit var dataSource: CatalogDataSource
    private lateinit var catalogCacheDao: FakeCatalogCacheDao
    private lateinit var epgDao: EpgDao
    private lateinit var repository: CatalogRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    private val credentialsProvider = InMemoryCredentialsProvider()
    private val testCoroutineScope = CoroutineScope(testDispatcher)

    private val credentials = XtreamCredentials("http://server.example.com", "user", "pass")
    private val accountKey = accountKeyOf(credentials)

    /** Comfortably inside the repository's 24 h TTL. */
    private val recently = 60L * 60 * 1000

    /** Comfortably past it. */
    private val longAgo = 48L * 60 * 60 * 1000

    @Before
    fun setUp() {
        dataSource = mockk()
        catalogCacheDao = FakeCatalogCacheDao()
        epgDao = mockk(relaxed = true)
        repository = CatalogRepositoryImpl(
            dataSource = dataSource,
            catalogCacheDao = catalogCacheDao,
            epgDao = epgDao,
            ioDispatcher = testDispatcher,
            credentialsProvider = credentialsProvider,
            applicationScope = testCoroutineScope,
        )
    }

    // ── Per-category reads: the path the catalog screens actually use ─────────

    @Test
    fun `getMovies serves a fresh Room slice without calling the data source`() =
        runTest(testDispatcher) {
            signIn()
            val cachedMovie = buildMovie("m1")
            cacheMovies(cachedMovie)
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID, recently)

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(cachedMovie)), awaitItem())
                awaitComplete()
            }

            // The whole point: not "the request succeeded from cache", but that no request left.
            coVerify(exactly = 0) { dataSource.getMovies(any()) }
        }

    @Test
    fun `getMovies refetches once the Room slice has expired`() =
        runTest(testDispatcher) {
            signIn()
            cacheMovies(buildMovie("stale"))
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID, longAgo)

            val fresh = buildMovie("fresh")
            coEvery { dataSource.getMovies(CATEGORY_ID) } returns listOf(fresh)

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(fresh)), awaitItem())
                awaitComplete()
            }

            coVerify(exactly = 1) { dataSource.getMovies(CATEGORY_ID) }
        }

    @Test
    fun `getMovies refetches when rows were cached without a sync marker`() =
        runTest(testDispatcher) {
            signIn()
            // A cache written before schema v4: the rows are there, nothing says when. Serving
            // them would pin the catalog to a snapshot of unknown age, so it must refetch.
            cacheMovies(buildMovie("undated"))

            val fresh = buildMovie("fresh")
            coEvery { dataSource.getMovies(CATEGORY_ID) } returns listOf(fresh)

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(fresh)), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `getMovies refetches when the marker is fresh but the rows are gone`() =
        runTest(testDispatcher) {
            signIn()
            // Marker without rows — the inverse of the case above. An empty slice is not a
            // legitimate answer here: it would leave the category blank for a full TTL.
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID, recently)

            val fresh = buildMovie("fresh")
            coEvery { dataSource.getMovies(CATEGORY_ID) } returns listOf(fresh)

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(fresh)), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `a successful per-category fetch stamps the slice as synced`() =
        runTest(testDispatcher) {
            signIn()
            coEvery { dataSource.getMovies(CATEGORY_ID) } returns listOf(buildMovie("m1"))

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertTrue(awaitItem() is Resource.Success)
                awaitComplete()
            }

            assertNotNull(
                "Without a marker the next open would refetch, which is the bug this fixes.",
                catalogCacheDao.syncedAtMillisOrNull(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID),
            )
        }

    @Test
    fun `a failed fetch leaves the slice unmarked`() =
        runTest(testDispatcher) {
            signIn()
            coEvery { dataSource.getMovies(CATEGORY_ID) } throws RuntimeException("network down")

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertTrue(awaitItem() is Resource.Error)
                awaitComplete()
            }

            assertNull(
                "A failure must not buy a TTL's worth of silence on an empty category.",
                catalogCacheDao.syncedAtMillisOrNull(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID),
            )
        }

    @Test
    fun `no credentials means Room is never consulted`() =
        runTest(testDispatcher) {
            // No signIn(): rows and marker exist under an account nobody is signed into.
            cacheMovies(buildMovie("m1"))
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID, recently)

            val fresh = buildMovie("fresh")
            coEvery { dataSource.getMovies(CATEGORY_ID) } returns listOf(fresh)

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(fresh)), awaitItem())
                awaitComplete()
            }
        }

    // ── Unfiltered reads ─────────────────────────────────────────────────────

    @Test
    fun `a fresh unfiltered slice is promoted to the session cache`() =
        runTest(testDispatcher) {
            signIn()
            val inCategory = buildMovie("m1", categoryId = CATEGORY_ID)
            val elsewhere = buildMovie("m2", categoryId = "other")
            cacheMovies(inCategory, elsewhere)
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, SCOPE_ALL, recently)

            repository.getMovies(null).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(inCategory, elsewhere)), awaitItem())
                awaitComplete()
            }

            // Promoted, so the per-category read below is answered from memory — without it, a
            // category whose own marker is absent would go to the network despite the full list
            // having just been resolved.
            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(inCategory)), awaitItem())
                awaitComplete()
            }

            coVerify(exactly = 0) { dataSource.getMovies(any()) }
        }

    // ── Categories ───────────────────────────────────────────────────────────

    @Test
    fun `observeVodCategories serves a fresh Room slice without calling the data source`() =
        runTest(testDispatcher) {
            signIn()
            val category = Category(id = CATEGORY_ID, name = "Action", type = ContentType.MOVIE)
            catalogCacheDao.upsertCategories(listOf(category).toEntity(accountKey))
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, SCOPE_CATEGORIES, recently)

            repository.observeVodCategories().test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(category)), awaitItem())
                awaitComplete()
            }

            // LoadCategoryScopedCatalogUseCase blocks on this terminal value before it can fetch a
            // single category, so a request here would put the network back in front of an
            // otherwise fully cached tab load.
            coVerify(exactly = 0) { dataSource.getVodCategories() }
        }

    // ── Explicit reload ──────────────────────────────────────────────────────

    @Test
    fun `invalidatePersistentCache sends the next read back to the data source`() =
        runTest(testDispatcher) {
            signIn()
            cacheMovies(buildMovie("cached"))
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.MOVIE.name, CATEGORY_ID, recently)

            repository.invalidatePersistentCache(ContentType.MOVIE)

            val fresh = buildMovie("fresh")
            coEvery { dataSource.getMovies(CATEGORY_ID) } returns listOf(fresh)

            repository.getMovies(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(fresh)), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `invalidatePersistentCache leaves other content types alone`() =
        runTest(testDispatcher) {
            signIn()
            val cachedChannel = buildChannel("c1")
            catalogCacheDao.upsertChannels(listOf(cachedChannel).toEntity(accountKey))
            catalogCacheDao.markSyncedAgo(accountKey.value, ContentType.LIVE.name, CATEGORY_ID, recently)

            repository.invalidatePersistentCache(ContentType.MOVIE)

            repository.getLiveChannels(CATEGORY_ID).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(listOf(cachedChannel)), awaitItem())
                awaitComplete()
            }

            coVerify(exactly = 0) { dataSource.getLiveChannels(any()) }
        }

    // ── Single-channel lookup ────────────────────────────────────────────────

    @Test
    fun `getCachedChannel resolves a channel without the unfiltered bouquet`() =
        runTest(testDispatcher) {
            signIn()
            val channel = buildChannel("c1")
            catalogCacheDao.upsertChannels(listOf(channel, buildChannel("c2")).toEntity(accountKey))

            assertEquals(channel, repository.getCachedChannel("c1"))
            coVerify(exactly = 0) { dataSource.getLiveChannels(any()) }
        }

    @Test
    fun `getCachedChannel returns null when the channel is not cached`() =
        runTest(testDispatcher) {
            signIn()
            assertNull(repository.getCachedChannel("absent"))
        }

    @Test
    fun `getCachedChannel returns null when no credentials are configured`() =
        runTest(testDispatcher) {
            catalogCacheDao.upsertChannels(listOf(buildChannel("c1")).toEntity(accountKey))
            assertNull(repository.getCachedChannel("c1"))
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Establishes the account *before* any content is fetched, so the credentials observer's
     * `drop(1)` skips this emission — matching how a restored session behaves in production.
     */
    private suspend fun signIn() {
        credentialsProvider.setCredentials(credentials)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private suspend fun cacheMovies(vararg movies: Movie) {
        catalogCacheDao.upsertMovies(movies.toList().toEntity(accountKey))
    }

    private fun buildMovie(id: String, categoryId: String = CATEGORY_ID) = Movie(
        id = id,
        title = "Movie $id",
        posterUrl = null,
        plot = null,
        categoryId = categoryId,
        rating = null,
        year = null,
        addedMillis = null,
        durationMillis = null,
        containerExtension = null,
    )

    private fun buildChannel(id: String) = Channel(
        id = id,
        name = "Channel $id",
        logoUrl = null,
        categoryId = CATEGORY_ID,
        epgChannelId = null,
    )

    private companion object {
        const val CATEGORY_ID = "cat1"
    }
}
