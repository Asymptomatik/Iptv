package com.bobot.iptvapp.data.repository

import com.bobot.iptvapp.data.local.dao.CatalogCacheDao
import com.bobot.iptvapp.data.local.dao.EpgDao
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Adversarial regression test for the second review's Finding #4: a stream-list fetch
 * ([CatalogRepositoryImpl.getMovies], representative of [CatalogRepositoryImpl.getLiveChannels]
 * and [CatalogRepositoryImpl.getSeriesList] — all three share the exact same shape) started
 * *before* a logout must not resurrect the previous account's data in the in-memory
 * `cachedAllMovies` memo, nor in Room, once it completes *after* [CatalogRepositoryImpl.invalidateCaches]
 * has already run.
 *
 * Reuses the [com.bobot.iptvapp.data.repository.CatalogRepositoryImpl.CategoryFetchState.generation]
 * counter that already guards the categories cache — [CatalogRepositoryImpl.invalidateCache] bumps
 * it for the same content type it clears, so a fetch that captured the generation before the bump
 * can tell its result is stale once it completes, without a new mechanism.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRepositoryStreamListRaceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testCoroutineScope = CoroutineScope(testDispatcher)
    private val credentialsProvider = InMemoryCredentialsProvider()
    private val accountA = XtreamCredentials("http://a.example:8080", "userA", "passA")

    private lateinit var dataSource: CatalogDataSource
    private lateinit var catalogCacheDao: CatalogCacheDao
    private lateinit var epgDao: EpgDao
    private lateinit var repository: CatalogRepositoryImpl

    @Before
    fun setUp() {
        dataSource = mockk()
        catalogCacheDao = mockk(relaxed = true)
        epgDao = mockk(relaxed = true)
        coEvery { catalogCacheDao.getAllMovies(any()) } returns emptyList()
        runBlocking { credentialsProvider.setCredentials(accountA) }
        repository = CatalogRepositoryImpl(
            dataSource = dataSource,
            catalogCacheDao = catalogCacheDao,
            epgDao = epgDao,
            ioDispatcher = testDispatcher,
            credentialsProvider = credentialsProvider,
            applicationScope = testCoroutineScope,
        )
    }

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

    @Test
    fun `a movies fetch started before logout does not resurrect the previous account's list afterwards`() =
        runTest(testDispatcher) {
            val staleGate = CompletableDeferred<Unit>()
            coEvery { dataSource.getMovies(null) } coAnswers {
                staleGate.await()
                listOf(buildMovie("stale"))
            }

            val results = mutableListOf<Resource<List<Movie>>>()
            val staleFetchJob = launch { repository.getMovies(null).toList(results) }
            runCurrent()

            // Logout: invalidates the memo and bumps the generation the stale fetch captured.
            credentialsProvider.clearCredentials()
            advanceUntilIdle()

            staleGate.complete(Unit)
            advanceUntilIdle()
            staleFetchJob.join()

            // The stale fetch's own collector still gets an answer...
            assertEquals(Resource.Success(listOf(buildMovie("stale"))), results.last())

            // ...but it must not have written the memo or Room. A fresh account's first read must
            // hit the network again, not silently serve the previous account's list.
            coVerify(exactly = 0) { catalogCacheDao.upsertMovies(any()) }

            credentialsProvider.setCredentials(XtreamCredentials("http://b.example:8080", "userB", "passB"))
            advanceUntilIdle()
            coEvery { dataSource.getMovies(null) } returns listOf(buildMovie("fresh"))

            val secondFetch = mutableListOf<Resource<List<Movie>>>()
            repository.getMovies(null).toList(secondFetch)

            assertEquals(
                "a fresh fetch after the stale one must not be served the resurrected list from memory",
                Resource.Success(listOf(buildMovie("fresh"))),
                secondFetch.last(),
            )
        }

    @Test
    fun `invalidateSessionCaches does not return while a write it let through is still landing in Room`() =
        runTest(testDispatcher) {
            // The check passes (generation still current) before the write below is gated, matching
            // a producer that committed to a Room write a moment before a concurrent logout bumps
            // the generation — see [CatalogRepositoryImpl.CategoryFetchState] "Publish
            // synchronization". The purge's later Room-clear step must not be able to run until this
            // write has actually landed, or the clear can complete before the write, leaving the
            // previous account's row behind.
            coEvery { dataSource.getMovies(null) } returns listOf(buildMovie("in-flight"))
            val writeGate = CompletableDeferred<Unit>()
            coEvery { catalogCacheDao.upsertMovies(any()) } coAnswers { writeGate.await() }

            val fetchResults = mutableListOf<Resource<List<Movie>>>()
            val fetchJob = launch { repository.getMovies(null).toList(fetchResults) }
            runCurrent()

            // The fetch has passed its generation check and is now parked writing to Room.
            val invalidateJob = launch { repository.invalidateSessionCaches() }
            // runCurrent(), not advanceUntilIdle(): the drain's own poll loop uses delay() as its
            // bounded-wait mechanism, so fast-forwarding virtual time here would race the drain's
            // timeout instead of genuinely observing it still parked.
            runCurrent()

            assertEquals(
                "the drain must not return while the write it let through has not landed yet",
                false,
                invalidateJob.isCompleted,
            )

            writeGate.complete(Unit)
            advanceUntilIdle()
            fetchJob.join()
            invalidateJob.join()

            assertEquals(true, invalidateJob.isCompleted)
        }
}
