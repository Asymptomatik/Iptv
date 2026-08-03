package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.entity.ChannelEntity
import com.bobot.iptvapp.data.local.entity.EpisodeEntity
import com.bobot.iptvapp.data.local.entity.SeriesEntity
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.CatalogException
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.Category
import com.bobot.iptvapp.domain.model.Channel
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.EpgProgram
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CatalogRepositoryImpl].
 *
 * Uses a MockK [CatalogDataSource], a MockK [CatalogCacheDao] (relaxed by default — most
 * tests don't care about the offline-first Room cache side-effects; dedicated tests below
 * stub it explicitly to verify write-through and fallback behaviour), an
 * [InMemoryCredentialsProvider] (lightweight test double — no DataStore I/O), and a
 * [StandardTestDispatcher] to:
 *  - verify the Loading → Success emission sequence for Flow methods,
 *  - verify that [CatalogException] subtypes are mapped to [Resource.Error],
 *  - verify that the session cache avoids redundant data source calls,
 *  - verify that [invalidateCaches] clears all cached lists,
 *  - verify that suspend detail methods return correct [Resource] values,
 *  - verify that [authenticate] delegates correctly and maps Result to Resource,
 *  - verify that successful fetches persist to the Room catalog cache (Task 11b),
 *  - verify that a data source failure falls back to the Room cache when available,
 *    otherwise still emits [Resource.Error] (Task 11b).
 */
class CatalogRepositoryImplTest {

    private lateinit var dataSource: CatalogDataSource
    private lateinit var catalogCacheDao: CatalogCacheDao
    private lateinit var repository: CatalogRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    // InMemoryCredentialsProvider used as a controllable test double. Its
    // observeCredentials() returns a StateFlow so the init{} observer in
    // CatalogRepositoryImpl subscribes cleanly without any I/O.
    private val credentialsProvider = InMemoryCredentialsProvider()

    // CoroutineScope backed by testDispatcher so the credentials-observer coroutine
    // launched in CatalogRepositoryImpl.init{} runs within the test dispatcher.
    private val testCoroutineScope = CoroutineScope(testDispatcher)

    @Before
    fun setUp() {
        dataSource = mockk()
        // Relaxed: unstubbed writes (upsertX) silently no-op (return Unit) instead of
        // throwing, since most tests in this file are not concerned with the
        // offline-first Room cache write-through behaviour.
        catalogCacheDao = mockk(relaxed = true)
        // Explicit default stubs for every Room-cache *read* method — deterministically
        // "cache empty" — so that, unless a test overrides them, a data-source failure
        // falls through to Resource.Error exactly like before this cache was wired in.
        // (Relying on MockK's relaxed-mock defaulting for Flow-returning methods would be
        // implementation-defined; explicit stubs keep this deterministic.)
        every { catalogCacheDao.observeCategoriesByType(any()) } returns flowOf(emptyList())
        every { catalogCacheDao.observeAllChannels() } returns flowOf(emptyList())
        every { catalogCacheDao.observeChannelsByCategory(any()) } returns flowOf(emptyList())
        every { catalogCacheDao.observeAllMovies() } returns flowOf(emptyList())
        every { catalogCacheDao.observeMoviesByCategory(any()) } returns flowOf(emptyList())
        every { catalogCacheDao.observeAllSeries() } returns flowOf(emptyList())
        every { catalogCacheDao.observeSeriesByCategory(any()) } returns flowOf(emptyList())
        // A fresh repository instance per test ensures no cached state bleeds between tests.
        repository = CatalogRepositoryImpl(
            dataSource = dataSource,
            catalogCacheDao = catalogCacheDao,
            ioDispatcher = testDispatcher,
            credentialsProvider = credentialsProvider,
            applicationScope = testCoroutineScope,
        )
    }

    // ── observeLiveCategories — success path ─────────────────────────────────

    @Test
    fun `observeLiveCategories emits Loading then Success`() = runTest(testDispatcher) {
        val categories = listOf(Category("1", "News", ContentType.LIVE))
        coEvery { dataSource.getLiveCategories() } returns categories

        repository.observeLiveCategories().test {
            assertEquals(Resource.Loading, awaitItem())
            assertEquals(Resource.Success(categories), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeLiveCategories emits empty Success list when source returns empty`() =
        runTest(testDispatcher) {
            coEvery { dataSource.getLiveCategories() } returns emptyList()

            repository.observeLiveCategories().test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(emptyList<Category>()), awaitItem())
                awaitComplete()
            }
        }

    // ── observeLiveCategories — concurrent first fetches ─────────────────────

    @Test
    fun `two concurrent first collections of observeLiveCategories share a single data source fetch`() =
        runTest(testDispatcher) {
            // The Flow is cold, so concurrent collectors all read the in-memory memo as null. Home
            // makes this the normal case, not a rare race: loading one catalog tab collects its
            // categories Flow twice at once (buildRowsFlow observes it, LoadCategoryScopedCatalogUseCase
            // awaits its first terminal value), which used to double every categories request.
            val categories = listOf(Category("1", "News", ContentType.LIVE))
            val releaseFetch = CompletableDeferred<Unit>()
            var fetches = 0
            coEvery { dataSource.getLiveCategories() } coAnswers {
                fetches++
                // Keeps the first fetch in flight so the second collector genuinely overlaps it,
                // instead of arriving after the memo was already filled.
                releaseFetch.await()
                categories
            }

            val first = async { repository.observeLiveCategories().toList() }
            val second = async { repository.observeLiveCategories().toList() }
            runCurrent()
            releaseFetch.complete(Unit)

            val expected = listOf(Resource.Loading, Resource.Success(categories))
            assertEquals(expected, first.await())
            // The waiter is served the winner's result rather than refetching.
            assertEquals(expected, second.await())
            assertEquals(1, fetches)
        }

    // ── observeLiveCategories — error paths ──────────────────────────────────

    @Test
    fun `observeLiveCategories emits Loading then Error on NetworkError`() =
        runTest(testDispatcher) {
            val exception = CatalogException.NetworkError("Connection refused")
            coEvery { dataSource.getLiveCategories() } throws exception

            repository.observeLiveCategories().test {
                assertEquals(Resource.Loading, awaitItem())
                val error = awaitItem() as Resource.Error
                assertEquals(exception, error.throwable)
                awaitComplete()
            }
        }

    @Test
    fun `observeLiveCategories emits Loading then Error on AuthenticationFailed`() =
        runTest(testDispatcher) {
            val exception = CatalogException.AuthenticationFailed()
            coEvery { dataSource.getLiveCategories() } throws exception

            repository.observeLiveCategories().test {
                assertEquals(Resource.Loading, awaitItem())
                val error = awaitItem() as Resource.Error
                assertTrue(error.throwable is CatalogException.AuthenticationFailed)
                awaitComplete()
            }
        }

    // ── observeVodCategories / observeSeriesCategories ───────────────────────

    @Test
    fun `observeVodCategories emits Loading then Success`() = runTest(testDispatcher) {
        val categories = listOf(Category("1001", "Action", ContentType.MOVIE))
        coEvery { dataSource.getVodCategories() } returns categories

        repository.observeVodCategories().test {
            assertEquals(Resource.Loading, awaitItem())
            assertEquals(Resource.Success(categories), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `observeSeriesCategories emits Loading then Error on generic exception`() =
        runTest(testDispatcher) {
            coEvery { dataSource.getSeriesCategories() } throws RuntimeException("Timeout")

            repository.observeSeriesCategories().test {
                assertEquals(Resource.Loading, awaitItem())
                val error = awaitItem() as Resource.Error
                assertNotNull(error.throwable)
                awaitComplete()
            }
        }

    // ── getLiveChannels ───────────────────────────────────────────────────────

    @Test
    fun `getLiveChannels with null categoryId emits Loading then Success`() =
        runTest(testDispatcher) {
            val channels = listOf(
                Channel("101", "BBC", "http://logo.url", "1", "bbc.world"),
            )
            coEvery { dataSource.getLiveChannels(null) } returns channels

            repository.getLiveChannels(null).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(channels), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `getLiveChannels with categoryId derives result from cache when full list is cached`() =
        runTest(testDispatcher) {
            val allChannels = listOf(
                Channel("101", "BBC", null, "cat1", "bbc.world"),
                Channel("201", "ESPN", null, "cat2", "espn"),
            )
            coEvery { dataSource.getLiveChannels(null) } returns allChannels

            // First collect with null — populates cache
            repository.getLiveChannels(null).test {
                awaitItem() // Loading
                awaitItem() // Success with all channels
                awaitComplete()
            }

            // Second collect with categoryId — derives from cache, no new data source call
            repository.getLiveChannels("cat1").test {
                awaitItem() // Loading
                val result = awaitItem() as Resource.Success
                assertEquals(1, result.data.size)
                assertEquals("101", result.data[0].id)
                awaitComplete()
            }

            // The data source should have been called only once — the null (unfiltered) call.
            // The categoryId="cat1" call was served from cache without touching the data source.
            coVerify(exactly = 1) { dataSource.getLiveChannels(null) }
        }

    // ── Offline-first Room cache (Task 11b) ──────────────────────────────────

    @Test
    fun `getLiveChannels persists fetched channels to the Room catalog cache on success`() =
        runTest(testDispatcher) {
            val channels = listOf(Channel("101", "BBC", null, "cat1", "bbc.world"))
            coEvery { dataSource.getLiveChannels(null) } returns channels
            coEvery { catalogCacheDao.upsertChannels(any()) } returns Unit

            repository.getLiveChannels(null).test {
                awaitItem() // Loading
                awaitItem() // Success
                awaitComplete()
            }

            coVerify(exactly = 1) {
                catalogCacheDao.upsertChannels(
                    match { it.size == 1 && it[0].id == "101" },
                )
            }
        }

    @Test
    fun `getLiveChannels falls back to the Room cache when the data source fails and cache has data`() =
        runTest(testDispatcher) {
            val cachedEntities = listOf(
                ChannelEntity(id = "101", name = "BBC", logoUrl = null, categoryId = "cat1", epgChannelId = "bbc.world"),
            )
            coEvery { dataSource.getLiveChannels(null) } throws CatalogException.NetworkError("Offline")
            every { catalogCacheDao.observeAllChannels() } returns flowOf(cachedEntities)

            repository.getLiveChannels(null).test {
                awaitItem() // Loading
                val result = awaitItem() as Resource.Success
                assertEquals(1, result.data.size)
                assertEquals("101", result.data[0].id)
                awaitComplete()
            }
        }

    @Test
    fun `getLiveChannels emits Error when the data source fails and the Room cache is empty`() =
        runTest(testDispatcher) {
            val exception = CatalogException.NetworkError("Offline")
            coEvery { dataSource.getLiveChannels(null) } throws exception
            every { catalogCacheDao.observeAllChannels() } returns flowOf(emptyList())

            repository.getLiveChannels(null).test {
                awaitItem() // Loading
                val error = awaitItem() as Resource.Error
                assertEquals(exception, error.throwable)
                awaitComplete()
            }
        }

    // ── getMovies ─────────────────────────────────────────────────────────────

    @Test
    fun `getMovies emits Loading then Error when data source throws`() =
        runTest(testDispatcher) {
            coEvery { dataSource.getMovies(null) } throws CatalogException.NetworkError("Timeout")

            repository.getMovies(null).test {
                assertEquals(Resource.Loading, awaitItem())
                assertTrue(awaitItem() is Resource.Error)
                awaitComplete()
            }
        }

    // ── getSeriesList ─────────────────────────────────────────────────────────

    @Test
    fun `getSeriesList emits Loading then Success with empty list`() =
        runTest(testDispatcher) {
            coEvery { dataSource.getSeriesList(null) } returns emptyList()

            repository.getSeriesList(null).test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(emptyList<Series>()), awaitItem())
                awaitComplete()
            }
        }

    // ── Session cache — second collect avoids re-fetch ────────────────────────

    @Test
    fun `observeLiveCategories does not re-fetch on second collect`() =
        runTest(testDispatcher) {
            val categories = listOf(Category("1", "Sports", ContentType.LIVE))
            coEvery { dataSource.getLiveCategories() } returns categories

            // First collection
            repository.observeLiveCategories().test {
                awaitItem() // Loading
                awaitItem() // Success
                awaitComplete()
            }

            // Second collection — should hit cache and not call data source again
            repository.observeLiveCategories().test {
                awaitItem() // Loading (emitted before cache check — by design)
                assertEquals(Resource.Success(categories), awaitItem())
                awaitComplete()
            }

            coVerify(exactly = 1) { dataSource.getLiveCategories() }
        }

    // ── invalidateCaches ──────────────────────────────────────────────────────

    @Test
    fun `invalidateCaches clears all session caches so next collect re-fetches`() =
        runTest(testDispatcher) {
            val categories = listOf(Category("1", "Sports", ContentType.LIVE))
            coEvery { dataSource.getLiveCategories() } returns categories

            // Populate cache
            repository.observeLiveCategories().test {
                awaitItem(); awaitItem(); awaitComplete()
            }

            // Invalidate
            repository.invalidateCaches()

            // Next collect must hit data source again
            repository.observeLiveCategories().test {
                awaitItem() // Loading
                assertEquals(Resource.Success(categories), awaitItem())
                awaitComplete()
            }

            // Data source called twice — once before invalidation, once after
            coVerify(exactly = 2) { dataSource.getLiveCategories() }
        }

    @Test
    fun `cache is invalidated when credentials change`() = runTest(testDispatcher) {
        val categories = listOf(Category("1", "Sports", ContentType.LIVE))
        coEvery { dataSource.getLiveCategories() } returns categories

        // Populate cache
        repository.observeLiveCategories().test {
            awaitItem(); awaitItem(); awaitComplete()
        }

        // Simulate credentials change (triggers invalidateCaches via the observer in init{})
        credentialsProvider.setCredentials(
            XtreamCredentials("http://new-server.com:8080", "user2", "pass2"),
        )
        // Allow the init{} coroutine to process the credential-change emission
        testDispatcher.scheduler.advanceUntilIdle()

        // Cache was cleared — next collect must go to the data source again
        repository.observeLiveCategories().test {
            awaitItem() // Loading
            assertEquals(Resource.Success(categories), awaitItem())
            awaitComplete()
        }

        // Data source called twice — the second time after cache invalidation
        coVerify(exactly = 2) { dataSource.getLiveCategories() }
    }

    // ── invalidateCache(type) — targeted invalidation ────────────────────────

    /**
     * Populates the categories + list session cache for all three [ContentType]s, then
     * re-collects every one of them and returns the fresh [Resource.Success] payload for each —
     * used both to seed the caches under test and, after invalidation, to prove which caches
     * were actually cleared via `coVerify` call counts on [dataSource].
     */
    private suspend fun setUpAllThreeContentTypeCaches() {
        val liveCategories = listOf(Category("1", "News", ContentType.LIVE))
        val vodCategories = listOf(Category("1001", "Action", ContentType.MOVIE))
        val seriesCategories = listOf(Category("2001", "Drama", ContentType.SERIES))
        val channels = listOf(Channel("101", "BBC", null, "cat1", "bbc.world"))
        val movies = listOf(buildMovie("m1"))
        val series = listOf(buildSeries("s1"))

        coEvery { dataSource.getLiveCategories() } returns liveCategories
        coEvery { dataSource.getVodCategories() } returns vodCategories
        coEvery { dataSource.getSeriesCategories() } returns seriesCategories
        coEvery { dataSource.getLiveChannels(null) } returns channels
        coEvery { dataSource.getMovies(null) } returns movies
        coEvery { dataSource.getSeriesList(null) } returns series

        repository.observeLiveCategories().test { awaitItem(); awaitItem(); awaitComplete() }
        repository.observeVodCategories().test { awaitItem(); awaitItem(); awaitComplete() }
        repository.observeSeriesCategories().test { awaitItem(); awaitItem(); awaitComplete() }
        repository.getLiveChannels(null).test { awaitItem(); awaitItem(); awaitComplete() }
        repository.getMovies(null).test { awaitItem(); awaitItem(); awaitComplete() }
        repository.getSeriesList(null).test { awaitItem(); awaitItem(); awaitComplete() }
    }

    @Test
    fun `invalidateCache LIVE clears only the LIVE list and categories cache`() =
        runTest(testDispatcher) {
            setUpAllThreeContentTypeCaches()

            repository.invalidateCache(ContentType.LIVE)

            // Re-collecting the invalidated type must hit the data source again.
            repository.observeLiveCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getLiveChannels(null).test { awaitItem(); awaitItem(); awaitComplete() }
            // Re-collecting the two untouched types must still be served from cache.
            repository.observeVodCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getMovies(null).test { awaitItem(); awaitItem(); awaitComplete() }
            repository.observeSeriesCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getSeriesList(null).test { awaitItem(); awaitItem(); awaitComplete() }

            coVerify(exactly = 2) { dataSource.getLiveCategories() }
            coVerify(exactly = 2) { dataSource.getLiveChannels(null) }
            coVerify(exactly = 1) { dataSource.getVodCategories() }
            coVerify(exactly = 1) { dataSource.getMovies(null) }
            coVerify(exactly = 1) { dataSource.getSeriesCategories() }
            coVerify(exactly = 1) { dataSource.getSeriesList(null) }
        }

    @Test
    fun `invalidateCache MOVIE clears only the MOVIE list and categories cache`() =
        runTest(testDispatcher) {
            setUpAllThreeContentTypeCaches()

            repository.invalidateCache(ContentType.MOVIE)

            repository.observeVodCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getMovies(null).test { awaitItem(); awaitItem(); awaitComplete() }
            repository.observeLiveCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getLiveChannels(null).test { awaitItem(); awaitItem(); awaitComplete() }
            repository.observeSeriesCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getSeriesList(null).test { awaitItem(); awaitItem(); awaitComplete() }

            coVerify(exactly = 2) { dataSource.getVodCategories() }
            coVerify(exactly = 2) { dataSource.getMovies(null) }
            coVerify(exactly = 1) { dataSource.getLiveCategories() }
            coVerify(exactly = 1) { dataSource.getLiveChannels(null) }
            coVerify(exactly = 1) { dataSource.getSeriesCategories() }
            coVerify(exactly = 1) { dataSource.getSeriesList(null) }
        }

    @Test
    fun `invalidateCache SERIES clears only the SERIES list and categories cache`() =
        runTest(testDispatcher) {
            setUpAllThreeContentTypeCaches()

            repository.invalidateCache(ContentType.SERIES)

            repository.observeSeriesCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getSeriesList(null).test { awaitItem(); awaitItem(); awaitComplete() }
            repository.observeLiveCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getLiveChannels(null).test { awaitItem(); awaitItem(); awaitComplete() }
            repository.observeVodCategories().test { awaitItem(); awaitItem(); awaitComplete() }
            repository.getMovies(null).test { awaitItem(); awaitItem(); awaitComplete() }

            coVerify(exactly = 2) { dataSource.getSeriesCategories() }
            coVerify(exactly = 2) { dataSource.getSeriesList(null) }
            coVerify(exactly = 1) { dataSource.getLiveCategories() }
            coVerify(exactly = 1) { dataSource.getLiveChannels(null) }
            coVerify(exactly = 1) { dataSource.getVodCategories() }
            coVerify(exactly = 1) { dataSource.getMovies(null) }
        }

    @Test
    fun `invalidateCache called twice in a row on an already-empty cache does not throw`() =
        runTest(testDispatcher) {
            // Cache was never populated for this repository instance — invalidating it is a
            // safe no-op, and calling it again immediately after must not crash either.
            repository.invalidateCache(ContentType.LIVE)
            repository.invalidateCache(ContentType.LIVE)

            val categories = listOf(Category("1", "News", ContentType.LIVE))
            coEvery { dataSource.getLiveCategories() } returns categories

            // The cache still behaves normally afterwards — a single data source call.
            repository.observeLiveCategories().test {
                awaitItem() // Loading
                assertEquals(Resource.Success(categories), awaitItem())
                awaitComplete()
            }

            coVerify(exactly = 1) { dataSource.getLiveCategories() }
        }

    @Test
    fun `invalidateCache called twice in a row on a populated cache clears it exactly once`() =
        runTest(testDispatcher) {
            val categories = listOf(Category("1001", "Action", ContentType.MOVIE))
            coEvery { dataSource.getVodCategories() } returns categories

            // Populate the cache.
            repository.observeVodCategories().test { awaitItem(); awaitItem(); awaitComplete() }

            // Two consecutive invalidations must not crash and must not double-fetch afterwards.
            repository.invalidateCache(ContentType.MOVIE)
            repository.invalidateCache(ContentType.MOVIE)

            repository.observeVodCategories().test {
                awaitItem() // Loading
                assertEquals(Resource.Success(categories), awaitItem())
                awaitComplete()
            }

            // Called once before invalidation, once after — the second invalidateCache() call
            // was a no-op on an already-cleared cache.
            coVerify(exactly = 2) { dataSource.getVodCategories() }
        }

    // ── getMovieDetail ────────────────────────────────────────────────────────

    @Test
    fun `getMovieDetail returns Success when data source returns movie`() =
        runTest(testDispatcher) {
            val movie = buildMovie("m1")
            coEvery { dataSource.getMovieInfo("m1") } returns movie

            val result = repository.getMovieDetail("m1")
            assertEquals(Resource.Success(movie), result)
        }

    @Test
    fun `getMovieDetail returns Error when NotFound is thrown`() = runTest(testDispatcher) {
        val exception = CatalogException.NotFound("m999")
        coEvery { dataSource.getMovieInfo("m999") } throws exception

        val result = repository.getMovieDetail("m999")
        assertTrue(result is Resource.Error)
        assertEquals(exception, (result as Resource.Error).throwable)
    }

    @Test
    fun `getMovieDetail returns Error wrapping generic exception`() = runTest(testDispatcher) {
        coEvery { dataSource.getMovieInfo("m1") } throws RuntimeException("Server error")

        val result = repository.getMovieDetail("m1")
        assertTrue(result is Resource.Error)
        assertNotNull((result as Resource.Error).throwable)
    }

    // ── getSeriesDetail ───────────────────────────────────────────────────────

    @Test
    fun `getSeriesDetail returns Success with populated series`() = runTest(testDispatcher) {
        val series = buildSeries("s1")
        coEvery { dataSource.getSeriesInfo("s1") } returns series

        val result = repository.getSeriesDetail("s1")
        assertEquals(Resource.Success(series), result)
    }

    @Test
    fun `getSeriesDetail returns Error when NotFound is thrown`() = runTest(testDispatcher) {
        coEvery { dataSource.getSeriesInfo("s999") } throws CatalogException.NotFound("s999")

        val result = repository.getSeriesDetail("s999")
        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).throwable is CatalogException.NotFound)
    }

    // ── getSeriesDetail — Room cache write-through (Task 11b/25 carry-forward) ──

    @Test
    fun `getSeriesDetail persists series, seasons and episodes to the Room cache on success`() =
        runTest(testDispatcher) {
            val series = buildSeriesWithEpisodes("s1")
            coEvery { dataSource.getSeriesInfo("s1") } returns series

            val result = repository.getSeriesDetail("s1")

            assertEquals(Resource.Success(series), result)
            coVerify(exactly = 1) {
                catalogCacheDao.upsertSeries(match { it.size == 1 && it[0].id == "s1" })
            }
            coVerify(exactly = 1) {
                catalogCacheDao.upsertSeasons(
                    match { seasons -> seasons.size == 1 && seasons[0].seriesId == "s1" && seasons[0].seasonNumber == 1 },
                )
            }
            coVerify(exactly = 1) {
                catalogCacheDao.upsertEpisodes(
                    match { episodes ->
                        episodes.size == 2 &&
                            episodes.all { it.seriesId == "s1" } &&
                            episodes.map { it.id } == listOf("s1-e1", "s1-e2")
                    },
                )
            }
        }

    @Test
    fun `getSeriesDetail still returns Success when the Room cache write throws`() =
        runTest(testDispatcher) {
            val series = buildSeriesWithEpisodes("s1")
            coEvery { dataSource.getSeriesInfo("s1") } returns series
            coEvery { catalogCacheDao.upsertSeries(any()) } throws RuntimeException("Disk full")

            val result = repository.getSeriesDetail("s1")

            // Best-effort cache write — a Room failure must not downgrade the successful
            // data source fetch to Resource.Error (persistQuietly contract).
            assertEquals(Resource.Success(series), result)
        }

    // ── getCachedEpisodeWithSeries (Task 25) ─────────────────────────────────

    @Test
    fun `getCachedEpisodeWithSeries returns the mapped Series and Episode pair when both are cached`() =
        runTest(testDispatcher) {
            val episodeEntity = buildEpisodeEntity("e1", seriesId = "s1")
            val seriesEntity = buildSeriesEntity("s1")
            coEvery { catalogCacheDao.getEpisodeById("e1") } returns episodeEntity
            coEvery { catalogCacheDao.getSeriesById("s1") } returns seriesEntity

            val result = repository.getCachedEpisodeWithSeries("e1")

            assertNotNull(result)
            val (resolvedSeries, resolvedEpisode) = result!!
            assertEquals("s1", resolvedSeries.id)
            assertEquals("Series s1", resolvedSeries.title)
            assertEquals("e1", resolvedEpisode.id)
            assertEquals(1, resolvedEpisode.episodeNumber)
        }

    @Test
    fun `getCachedEpisodeWithSeries returns null when the episode is not cached`() =
        runTest(testDispatcher) {
            coEvery { catalogCacheDao.getEpisodeById("missing") } returns null

            val result = repository.getCachedEpisodeWithSeries("missing")

            assertEquals(null, result)
            coVerify(exactly = 0) { catalogCacheDao.getSeriesById(any()) }
        }

    @Test
    fun `getCachedEpisodeWithSeries returns null when the parent series is not cached`() =
        runTest(testDispatcher) {
            val episodeEntity = buildEpisodeEntity("e1", seriesId = "s1")
            coEvery { catalogCacheDao.getEpisodeById("e1") } returns episodeEntity
            coEvery { catalogCacheDao.getSeriesById("s1") } returns null

            val result = repository.getCachedEpisodeWithSeries("e1")

            assertEquals(null, result)
        }

    @Test
    fun `getCachedEpisodeWithSeries returns null instead of throwing when a Room read fails`() =
        runTest(testDispatcher) {
            coEvery { catalogCacheDao.getEpisodeById("e1") } throws RuntimeException("Disk error")

            val result = repository.getCachedEpisodeWithSeries("e1")

            assertEquals(null, result)
        }

    // ── getEpg ────────────────────────────────────────────────────────────────

    @Test
    fun `getEpg returns Success with EPG list`() = runTest(testDispatcher) {
        val programs = listOf(
            EpgProgram("ch1", "News", null, 1_000L, 2_000L),
        )
        coEvery { dataSource.getShortEpg("ch1", null) } returns programs

        val result = repository.getEpg("ch1")
        assertEquals(Resource.Success(programs), result)
    }

    @Test
    fun `getEpg returns empty Success for channel with no EPG`() = runTest(testDispatcher) {
        coEvery { dataSource.getShortEpg("ch-no-epg", 4) } returns emptyList()

        val result = repository.getEpg("ch-no-epg", limit = 4)
        assertEquals(Resource.Success(emptyList<EpgProgram>()), result)
    }

    @Test
    fun `getEpg returns Error on NetworkError`() = runTest(testDispatcher) {
        coEvery { dataSource.getShortEpg("ch1", null) } throws CatalogException.NetworkError("Offline")

        val result = repository.getEpg("ch1")
        assertTrue(result is Resource.Error)
    }

    // ── authenticate ──────────────────────────────────────────────────────────

    @Test
    fun `authenticate returns Success when data source returns Result success`() =
        runTest(testDispatcher) {
            coEvery { dataSource.authenticate() } returns Result.success(Unit)

            val result = repository.authenticate()
            assertEquals(Resource.Success(Unit), result)
        }

    @Test
    fun `authenticate returns Error when data source returns Result failure`() =
        runTest(testDispatcher) {
            val exception = CatalogException.AuthenticationFailed()
            coEvery { dataSource.authenticate() } returns Result.failure(exception)

            val result = repository.authenticate()
            assertTrue(result is Resource.Error)
            assertEquals(exception, (result as Resource.Error).throwable)
        }

    @Test
    fun `authenticate returns Error when data source throws`() = runTest(testDispatcher) {
        coEvery { dataSource.authenticate() } throws RuntimeException("Crash")

        val result = repository.authenticate()
        assertTrue(result is Resource.Error)
    }

    // ── Dispatcher testability ────────────────────────────────────────────────

    @Test
    fun `injected dispatcher controls execution — StandardTestDispatcher advances on demand`() =
        runTest(testDispatcher) {
            coEvery { dataSource.getLiveCategories() } returns emptyList()

            // StandardTestDispatcher requires explicit advancement; runTest handles this.
            // Collecting via Turbine advances the dispatcher automatically.
            repository.observeLiveCategories().test {
                assertEquals(Resource.Loading, awaitItem())
                assertEquals(Resource.Success(emptyList<Category>()), awaitItem())
                awaitComplete()
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildMovie(id: String) = Movie(
        id = id,
        title = "Movie $id",
        posterUrl = null,
        plot = null,
        categoryId = "cat1",
        rating = null,
        year = null,
        addedMillis = null,
        durationMillis = null,
        containerExtension = null,
    )

    private fun buildSeries(id: String) = Series(
        id = id,
        title = "Series $id",
        coverUrl = null,
        plot = null,
        categoryId = "cat1",
        rating = null,
        year = null,
        seasons = emptyList(),
    )

    /** A [Series] with one populated season containing two episodes (detail-view shape). */
    private fun buildSeriesWithEpisodes(id: String): Series {
        val episodes = listOf(
            buildEpisode(id = "$id-e1", episodeNumber = 1),
            buildEpisode(id = "$id-e2", episodeNumber = 2),
        )
        val season = Season(seasonNumber = 1, name = "Season 1", coverUrl = null, episodes = episodes)
        return buildSeries(id).copy(seasons = listOf(season))
    }

    private fun buildEpisode(id: String, episodeNumber: Int, seasonNumber: Int = 1) = Episode(
        id = id,
        title = "Episode $id",
        episodeNumber = episodeNumber,
        seasonNumber = seasonNumber,
        plot = null,
        durationMillis = null,
        containerExtension = "mkv",
        coverUrl = null,
    )

    private fun buildEpisodeEntity(id: String, seriesId: String, seasonNumber: Int = 1) = EpisodeEntity(
        id = id,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        title = "Episode $id",
        episodeNumber = 1,
        plot = null,
        durationMillis = null,
        containerExtension = "mkv",
        coverUrl = null,
    )

    private fun buildSeriesEntity(id: String) = SeriesEntity(
        id = id,
        title = "Series $id",
        coverUrl = null,
        plot = null,
        categoryId = "cat1",
        rating = null,
        year = null,
    )
}
