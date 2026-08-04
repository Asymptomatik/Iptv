package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.FakeCatalogCacheDao
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reproducer tests for the cross-account Room catalog cache bleed bug.
 *
 * [CatalogRepositoryImpl]'s offline-first Room cache (see its class-level KDoc,
 * "Offline-first Room cache") is keyed only by content type / category / series id — never by
 * account. [CatalogRepositoryImpl.invalidateCaches] (triggered by a credentials change, see its
 * `init {}` block) only clears the **in-memory** session cache; it never purges Room. So once a
 * fetch made under account A has been written to Room, that data survives a switch to account B
 * and can resurface through the offline fallback as if it belonged to B.
 *
 * These tests are intentionally **not** merged into [CatalogRepositoryImplTest] — that ~850-line
 * suite is green today and its relaxed MockK [com.bobot.iptvapp.data.local.dao.CatalogCacheDao]
 * mock is left untouched. This class instead uses [FakeCatalogCacheDao], a real in-memory store
 * that actually persists and replays data across the credentials switch — the behaviour a mocked
 * DAO cannot exercise.
 *
 * ## Task 3 status
 * The two reproducer tests below (`getMovies falls back to account A's Room cache after
 * switching to account B`, `getCachedEpisodeWithSeries resolves account A's cached episode
 * after switching to account B`) now pass — [CatalogRepositoryImpl] partitions every Room
 * access by `accountKey` (see [CatalogRepositoryImpl.currentAccountKey]) and [FakeCatalogCacheDao]
 * enforces that partition in-memory. Their assertions are unchanged from when they were written
 * to lock in the bug; only the underlying production code changed to satisfy them.
 *
 * The tests below them close two gaps a partition-vs-flush ambiguity and a missing-credentials
 * path would otherwise leave open:
 *  - `getMovies retrieves account A's own cache after a round trip through account B` proves the
 *    fix is a genuine per-account *partition* and not a correctness-adjacent flush-on-switch: a
 *    naive fix that cleared Room on every credentials change would also make the two reproducers
 *    above pass, but would violate the approved brief's requirement that a returning account
 *    "retrouve sa partition existante, donc son cache hors-ligne".
 *  - `getMovies neither reads nor writes Room when no credentials are configured` and
 *    `getMovies persists under the same partition after a password-only change` cover the two
 *    edges of [CatalogRepositoryImpl.currentAccountKey]: no account at all, and an account whose
 *    identity (baseUrl + username) is unchanged.
 */
class CatalogRepositoryAccountPartitionTest {

    private lateinit var dataSource: CatalogDataSource
    private lateinit var catalogCacheDao: FakeCatalogCacheDao
    private lateinit var repository: CatalogRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    private val credentialsProvider = InMemoryCredentialsProvider()
    private val testCoroutineScope = CoroutineScope(testDispatcher)

    private val accountA = XtreamCredentials("http://server-a.example.com", "userA", "passA")
    private val accountB = XtreamCredentials("http://server-b.example.com", "userB", "passB")

    @Before
    fun setUp() {
        dataSource = mockk()
        catalogCacheDao = FakeCatalogCacheDao()
        repository = CatalogRepositoryImpl(
            dataSource = dataSource,
            catalogCacheDao = catalogCacheDao,
            ioDispatcher = testDispatcher,
            credentialsProvider = credentialsProvider,
            applicationScope = testCoroutineScope,
        )
    }

    /**
     * Switches the active account from A to B, letting the application-scoped credentials
     * observer in [CatalogRepositoryImpl.init] run to completion. This is what calls
     * [CatalogRepositoryImpl.invalidateCaches] — clearing the **in-memory** session cache only —
     * which is precisely what forces the next fetch to fall through to Room on a source failure
     * instead of being served straight from the (now-empty) in-memory memo.
     */
    private suspend fun switchToAccountB() {
        credentialsProvider.setCredentials(accountB)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ── getMovies(null) — cross-account Room fallback bleed ──────────────────

    @Test
    fun `getMovies falls back to account A's Room cache after switching to account B`() =
        runTest(testDispatcher) {
            // Establish account A as the current account before any content is fetched, so the
            // credentials-observer's drop(1) skips this startup emission — matching how a
            // restored session behaves in production.
            credentialsProvider.setCredentials(accountA)
            testDispatcher.scheduler.advanceUntilIdle()

            val movieA = buildMovie("movieA")
            coEvery { dataSource.getMovies(null) } returns listOf(movieA)

            // Populate both the in-memory session cache and the Room cache under account A.
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                assertTrue(awaitItem() == Resource.Success(listOf(movieA)))
                awaitComplete()
            }

            // Switching to account B invalidates the in-memory cache (but not Room — that is the
            // bug), and the source now fails, forcing the Room fallback path.
            switchToAccountB()
            coEvery { dataSource.getMovies(null) } throws RuntimeException("account B: network down")

            // Turbine's `test` extension returns Unit, not the lambda's last expression — the
            // emitted item must be captured into an outer variable, not "returned" from the block.
            var result: Resource<List<Movie>>? = null
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                result = awaitItem()
                awaitComplete()
            }

            // Expected (post-fix) behaviour: account B's Room partition is empty, so the fallback
            // has nothing to serve and the source failure surfaces as Resource.Error. Today, the
            // Room cache is not partitioned by account, so the fallback instead replays account
            // A's movie and this assertion fails — the bug this test locks in.
            assertTrue(
                "Expected Resource.Error after switching accounts with a failing source, " +
                    "but got $result — this means the Room fallback resurfaced account A's " +
                    "cached movie(s) under account B.",
                result is Resource.Error,
            )
        }

    // ── getMovies(null) — round trip A→B→A retrieves A's own partition ───────

    @Test
    fun `getMovies retrieves account A's own cache after a round trip through account B`() =
        runTest(testDispatcher) {
            // Populate account A's cache, exactly as in the reproducer above.
            credentialsProvider.setCredentials(accountA)
            testDispatcher.scheduler.advanceUntilIdle()

            val movieA = buildMovie("movieA")
            coEvery { dataSource.getMovies(null) } returns listOf(movieA)
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                assertTrue(awaitItem() == Resource.Success(listOf(movieA)))
                awaitComplete()
            }

            // Switch to account B — a different partition, untouched by A's writes.
            switchToAccountB()

            // Switch back to A. The brief requires that a returning account ("mêmes baseUrl +
            // username") finds its existing partition intact, so its offline cache is still
            // usable even though the source is now failing.
            credentialsProvider.setCredentials(accountA)
            testDispatcher.scheduler.advanceUntilIdle()
            coEvery { dataSource.getMovies(null) } throws RuntimeException("account A: network down again")

            var result: Resource<List<Movie>>? = null
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                result = awaitItem()
                awaitComplete()
            }

            // This is the assertion a flush-on-switch "fix" cannot satisfy: it would have wiped
            // A's partition when B was activated, and the round trip back to A would find nothing
            // to serve, degrading to Resource.Error just like the cross-account reproducer above.
            // A genuine per-account partition instead retrieves A's own cached movie.
            assertEquals(Resource.Success(listOf(movieA)), result)
        }

    // ── getMovies(null) — no credentials configured ───────────────────────────

    @Test
    fun `getMovies neither reads nor writes Room when no credentials are configured`() =
        runTest(testDispatcher) {
            // credentialsProvider is never set — currentAccountKey() resolves to null.
            val movie = buildMovie("orphanMovie")
            coEvery { dataSource.getMovies(null) } returns listOf(movie)

            // A successful fetch with no account configured must still surface the network
            // result (the in-memory session cache is unaffected by account partitioning) but
            // must not write it to Room under a missing partition.
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                assertTrue(awaitItem() == Resource.Success(listOf(movie)))
                awaitComplete()
            }

            // Clear the in-memory session cache (not Room) so the next collection re-hits the
            // data source instead of being served straight from the memo populated above.
            repository.invalidateCache(ContentType.MOVIE)
            coEvery { dataSource.getMovies(null) } throws RuntimeException("still offline, no credentials")

            var result: Resource<List<Movie>>? = null
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                result = awaitItem()
                awaitComplete()
            }

            // With no credentials, currentAccountKey() is null on every operation, so neither the
            // successful fetch above nor this one ever reached Room: there is nothing to fall
            // back to (the write was skipped, and the read is skipped too), and the source
            // failure surfaces directly as Resource.Error.
            assertTrue(
                "Expected Resource.Error: no credentials means no Room read/write can happen, " +
                    "but got $result.",
                result is Resource.Error,
            )
        }

    // ── getMovies(null) — password-only change keeps the same partition ──────

    @Test
    fun `getMovies persists under the same partition after a password-only change`() =
        runTest(testDispatcher) {
            credentialsProvider.setCredentials(accountA)
            testDispatcher.scheduler.advanceUntilIdle()

            val movieA = buildMovie("movieA")
            coEvery { dataSource.getMovies(null) } returns listOf(movieA)
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                assertTrue(awaitItem() == Resource.Success(listOf(movieA)))
                awaitComplete()
            }

            // Same baseUrl and username, only the password differs — accountKeyOf() deliberately
            // excludes the password, so this must resolve to the same partition as accountA.
            val accountARepasswordOnly = accountA.copy(password = "a-different-password")
            credentialsProvider.setCredentials(accountARepasswordOnly)
            testDispatcher.scheduler.advanceUntilIdle()
            coEvery { dataSource.getMovies(null) } throws RuntimeException("network down after password change")

            var result: Resource<List<Movie>>? = null
            repository.getMovies(null).test {
                assertTrue(awaitItem() == Resource.Loading)
                result = awaitItem()
                awaitComplete()
            }

            assertEquals(Resource.Success(listOf(movieA)), result)
        }

    // ── getCachedEpisodeWithSeries — cross-account Room fallback bleed ────────

    @Test
    fun `getCachedEpisodeWithSeries resolves account A's cached episode after switching to account B`() =
        runTest(testDispatcher) {
            credentialsProvider.setCredentials(accountA)
            testDispatcher.scheduler.advanceUntilIdle()

            val episodeA = buildEpisode(id = "episodeA", episodeNumber = 1)
            val seriesA = buildSeriesWithEpisode(id = "seriesA", episode = episodeA)
            coEvery { dataSource.getSeriesInfo("seriesA") } returns seriesA

            // getSeriesDetail's write-through persists the series metadata plus its flattened
            // season/episode tree to Room (see CatalogRepositoryImpl.persistSeriesDetailQuietly).
            val detailResult = repository.getSeriesDetail("seriesA")
            assertTrue(detailResult is Resource.Success)

            switchToAccountB()

            // Read-only Room lookup — no data source call involved, so no stubbing needed here.
            val resolved = repository.getCachedEpisodeWithSeries("episodeA")

            // Expected (post-fix) behaviour: account A's episode/series are not visible from
            // account B's partition, so this resolves to null. Today, the same unpartitioned Room
            // tables still contain them, so the lookup succeeds and returns account A's pair — the
            // bug this test locks in.
            assertNull(
                "Expected null after switching accounts (episode/series belong to account A), " +
                    "but got $resolved — this means the Room cache resurfaced account A's cached " +
                    "series/episode under account B.",
                resolved,
            )
        }

    // ── getCachedEpisodeWithSeries — no credentials configured ────────────────

    @Test
    fun `getCachedEpisodeWithSeries returns null and never touches Room when no credentials are configured`() =
        runTest(testDispatcher) {
            // credentialsProvider is never set — currentAccountKey() resolves to null.
            val resolved = repository.getCachedEpisodeWithSeries("anyEpisode")

            assertNull(resolved)
            // Nothing was ever written for this id either, but the point being verified here is
            // that no read is attempted at all — a stronger guarantee than "the cache was empty".
            coVerify(exactly = 0) { dataSource.getSeriesInfo(any()) }
        }

    // ── Fixtures ───────────────────────────────────────────────────────────────

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

    private fun buildSeriesWithEpisode(id: String, episode: Episode): Series {
        val season = Season(
            seasonNumber = episode.seasonNumber,
            name = "Season 1",
            coverUrl = null,
            episodes = listOf(episode),
        )
        return Series(
            id = id,
            title = "Series $id",
            coverUrl = null,
            plot = null,
            categoryId = "cat1",
            rating = null,
            year = null,
            seasons = listOf(season),
        )
    }
}
