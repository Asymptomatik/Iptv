package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.FakeCatalogCacheDao
import com.bobot.iptvapp.data.source.CatalogDataSource
import com.bobot.iptvapp.data.source.InMemoryCredentialsProvider
import com.bobot.iptvapp.domain.model.Episode
import com.bobot.iptvapp.domain.model.Movie
import com.bobot.iptvapp.domain.model.Season
import com.bobot.iptvapp.domain.model.Series
import com.bobot.iptvapp.domain.model.XtreamCredentials
import com.bobot.iptvapp.domain.util.Resource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
 * At the time this class is introduced, both tests below are **expected to fail** — proving the
 * bug exists — because the current [CatalogRepositoryImpl] has no notion of a per-account cache
 * partition. A follow-up task closes that gap; these tests are the reproduction this class of bug
 * is verified against.
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

    // ── getCachedEpisodeWithSeries — cross-account Room fallback bleed ───────

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

    // ── Fixtures ───────────────────────────────────────────────────────────

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
