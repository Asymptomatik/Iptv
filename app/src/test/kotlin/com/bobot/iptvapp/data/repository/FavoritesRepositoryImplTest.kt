package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.FavoriteDao
import com.bobot.iptvapp.data.local.entity.FavoriteEntity
import com.bobot.iptvapp.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FavoritesRepositoryImpl].
 *
 * Uses a MockK [FavoriteDao] and a [StandardTestDispatcher] to:
 *  - verify that [toggleFavorite] reads the current state via [FavoriteDao.isFavorite].first()
 *    and inserts when the item is not yet a favorite, or deletes when it already is,
 *  - verify that [observeFavorites] maps entities to domain [com.bobot.iptvapp.domain.model.FavoriteItem]s,
 *  - verify that [isFavorite] delegates to the DAO with the ContentType name converted to String,
 *  - verify that [clearFavorites] delegates to [FavoriteDao.clearByProfileId].
 */
class FavoritesRepositoryImplTest {

    private lateinit var favoriteDao: FavoriteDao
    private lateinit var repository: FavoritesRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        favoriteDao = mockk()
        repository = FavoritesRepositoryImpl(
            favoriteDao = favoriteDao,
            ioDispatcher = testDispatcher,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildEntity(
        profileId: String = "p1",
        contentId: String = "m1",
        contentType: String = ContentType.MOVIE.name,
        addedAt: Long = 1_000L,
    ) = FavoriteEntity(
        profileId = profileId,
        contentId = contentId,
        contentType = contentType,
        addedAt = addedAt,
    )

    // ── toggleFavorite ────────────────────────────────────────────────────────

    @Test
    fun `toggleFavorite inserts when item is not currently a favorite`() =
        runTest(testDispatcher) {
            every {
                favoriteDao.isFavorite("p1", "m1", ContentType.MOVIE.name)
            } returns flowOf(false)
            val entitySlot = slot<FavoriteEntity>()
            coEvery { favoriteDao.insert(capture(entitySlot)) } returns Unit

            repository.toggleFavorite("p1", "m1", ContentType.MOVIE)

            coVerify(exactly = 1) { favoriteDao.insert(any()) }
            coVerify(exactly = 0) { favoriteDao.deleteByKeys(any(), any(), any()) }
            assertEquals("p1", entitySlot.captured.profileId)
            assertEquals("m1", entitySlot.captured.contentId)
            assertEquals(ContentType.MOVIE.name, entitySlot.captured.contentType)
        }

    @Test
    fun `toggleFavorite deletes when item is already a favorite`() =
        runTest(testDispatcher) {
            every {
                favoriteDao.isFavorite("p1", "m1", ContentType.MOVIE.name)
            } returns flowOf(true)
            coEvery {
                favoriteDao.deleteByKeys("p1", "m1", ContentType.MOVIE.name)
            } returns Unit

            repository.toggleFavorite("p1", "m1", ContentType.MOVIE)

            coVerify(exactly = 1) { favoriteDao.deleteByKeys("p1", "m1", "MOVIE") }
            coVerify(exactly = 0) { favoriteDao.insert(any()) }
        }

    @Test
    fun `toggleFavorite converts ContentType enum to name String for the read-then-write`() =
        runTest(testDispatcher) {
            every {
                favoriteDao.isFavorite("p1", "s1", ContentType.SERIES.name)
            } returns flowOf(false)
            coEvery { favoriteDao.insert(any()) } returns Unit

            repository.toggleFavorite("p1", "s1", ContentType.SERIES)

            coVerify(exactly = 1) { favoriteDao.isFavorite("p1", "s1", "SERIES") }
        }

    // ── observeFavorites ──────────────────────────────────────────────────────

    @Test
    fun `observeFavorites emits empty list when DAO emits empty list`() =
        runTest(testDispatcher) {
            every { favoriteDao.observeFavorites("p1") } returns flowOf(emptyList())

            repository.observeFavorites("p1").test {
                assertEquals(emptyList<Any>(), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `observeFavorites maps entities to domain FavoriteItem objects`() =
        runTest(testDispatcher) {
            val entities = listOf(
                buildEntity(contentId = "m2", addedAt = 2_000L),
                buildEntity(contentId = "m1", addedAt = 1_000L),
            )
            every { favoriteDao.observeFavorites("p1") } returns flowOf(entities)

            repository.observeFavorites("p1").test {
                val items = awaitItem()
                assertEquals(2, items.size)
                assertEquals("m2", items[0].contentId)
                assertEquals(ContentType.MOVIE, items[0].contentType)
                assertEquals(2_000L, items[0].addedAt)
                assertEquals("m1", items[1].contentId)
                awaitComplete()
            }
        }

    // ── isFavorite ────────────────────────────────────────────────────────────

    @Test
    fun `isFavorite delegates to DAO with String contentType and emits true`() =
        runTest(testDispatcher) {
            every {
                favoriteDao.isFavorite("p1", "m1", ContentType.MOVIE.name)
            } returns flowOf(true)

            repository.isFavorite("p1", "m1", ContentType.MOVIE).test {
                assertTrue(awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `isFavorite emits false when DAO reports no match`() =
        runTest(testDispatcher) {
            every {
                favoriteDao.isFavorite("p1", "ghost", ContentType.SERIES.name)
            } returns flowOf(false)

            repository.isFavorite("p1", "ghost", ContentType.SERIES).test {
                assertEquals(false, awaitItem())
                awaitComplete()
            }
        }

    // ── clearFavorites ────────────────────────────────────────────────────────

    @Test
    fun `clearFavorites delegates to DAO clearByProfileId`() = runTest(testDispatcher) {
        coEvery { favoriteDao.clearByProfileId("p1") } returns Unit

        repository.clearFavorites("p1")

        coVerify(exactly = 1) { favoriteDao.clearByProfileId("p1") }
    }

    @Test
    fun `clearFavorites for unknown profileId is a no-op (DAO handles it)`() =
        runTest(testDispatcher) {
            coEvery { favoriteDao.clearByProfileId("ghost") } returns Unit

            repository.clearFavorites("ghost")

            coVerify(exactly = 1) { favoriteDao.clearByProfileId("ghost") }
        }
}
