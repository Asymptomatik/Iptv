package com.bobot.iptvapp.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.bobot.iptvapp.data.local.IptvDatabase
import com.bobot.iptvapp.data.local.entity.FavoriteEntity
import com.bobot.iptvapp.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [FavoriteDao] using an in-memory [IptvDatabase].
 *
 * ## Running
 * ```
 * ./gradlew connectedAndroidTest
 * # or filter to this class:
 * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bobot.iptvapp.data.local.dao.FavoriteDaoTest
 * ```
 *
 * Tests cover:
 *  - toggle (insert → isFavorite=true → deleteByKeys → isFavorite=false)
 *  - observeFavorites ordering by [FavoriteEntity.addedAt] descending
 *  - composite key scoping (different profileId, different contentType)
 *  - reactive updates via [observeFavorites] and [isFavorite] Flows
 */
@RunWith(AndroidJUnit4::class)
class FavoriteDaoTest {

    private lateinit var db: IptvDatabase
    private lateinit var favoriteDao: FavoriteDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        favoriteDao = db.favoriteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun favorite(
        profileId: String = "profile1",
        contentId: String = "content1",
        contentType: ContentType = ContentType.MOVIE,
        addedAt: Long = 1000L,
    ) = FavoriteEntity(
        profileId = profileId,
        contentId = contentId,
        contentType = contentType.name,
        addedAt = addedAt,
    )

    // ── insert / isFavorite ───────────────────────────────────────────────────

    @Test
    fun insert_makesIsFavoriteEmitTrue() = runTest {
        favoriteDao.insert(favorite())

        favoriteDao.isFavorite("profile1", "content1", ContentType.MOVIE.name).test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun isFavorite_emitsFalse_whenNotPresent() = runTest {
        favoriteDao.isFavorite("profile1", "ghost", ContentType.MOVIE.name).test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── toggle: insert then deleteByKeys ──────────────────────────────────────

    @Test
    fun deleteByKeys_removesFavorite_andIsFavoriteEmitsFalse() = runTest {
        favoriteDao.insert(favorite())
        favoriteDao.deleteByKeys("profile1", "content1", ContentType.MOVIE.name)

        favoriteDao.isFavorite("profile1", "content1", ContentType.MOVIE.name).test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun isFavorite_updatesReactively_onInsertAndDelete() = runTest {
        favoriteDao.isFavorite("profile1", "content1", ContentType.MOVIE.name).test {
            // Initially not a favorite
            assertFalse(awaitItem())

            // Toggle on
            favoriteDao.insert(favorite())
            assertTrue(awaitItem())

            // Toggle off
            favoriteDao.deleteByKeys("profile1", "content1", ContentType.MOVIE.name)
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── composite key scoping ─────────────────────────────────────────────────

    @Test
    fun isFavorite_isScopedToProfileId() = runTest {
        favoriteDao.insert(favorite(profileId = "profile1"))

        // profile2 must not see profile1's favorites
        favoriteDao.isFavorite("profile2", "content1", ContentType.MOVIE.name).test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun isFavorite_isScopedToContentType() = runTest {
        favoriteDao.insert(favorite(contentType = ContentType.MOVIE))

        // SERIES variant of the same contentId must return false
        favoriteDao.isFavorite("profile1", "content1", ContentType.SERIES.name).test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sameContentId_differentContentType_areIndependentFavorites() = runTest {
        favoriteDao.insert(favorite(contentType = ContentType.MOVIE))
        favoriteDao.insert(favorite(contentType = ContentType.SERIES))

        favoriteDao.isFavorite("profile1", "content1", ContentType.MOVIE.name).test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        favoriteDao.isFavorite("profile1", "content1", ContentType.SERIES.name).test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        favoriteDao.isFavorite("profile1", "content1", ContentType.LIVE.name).test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── observeFavorites ordering ─────────────────────────────────────────────

    @Test
    fun observeFavorites_ordersItemsByAddedAtDescending() = runTest {
        favoriteDao.insert(favorite(contentId = "old", addedAt = 1000L))
        favoriteDao.insert(favorite(contentId = "new", addedAt = 3000L))
        favoriteDao.insert(favorite(contentId = "mid", addedAt = 2000L))

        favoriteDao.observeFavorites("profile1").test {
            val items = awaitItem()
            assertEquals(3, items.size)
            assertEquals("new", items[0].contentId)
            assertEquals("mid", items[1].contentId)
            assertEquals("old", items[2].contentId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeFavorites_emitsUpdatedList_onInsertAndDelete() = runTest {
        favoriteDao.observeFavorites("profile1").test {
            assertEquals(0, awaitItem().size)

            favoriteDao.insert(favorite(contentId = "c1", addedAt = 1000L))
            assertEquals(1, awaitItem().size)

            favoriteDao.insert(favorite(contentId = "c2", addedAt = 2000L))
            val two = awaitItem()
            assertEquals(2, two.size)
            assertEquals("c2", two[0].contentId) // most recent first

            favoriteDao.deleteByKeys("profile1", "c1", ContentType.MOVIE.name)
            val one = awaitItem()
            assertEquals(1, one.size)
            assertEquals("c2", one[0].contentId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeFavorites_isScopedToProfileId() = runTest {
        favoriteDao.insert(favorite(profileId = "profile1", contentId = "c1"))
        favoriteDao.insert(favorite(profileId = "profile2", contentId = "c2"))

        favoriteDao.observeFavorites("profile1").test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("c1", items[0].contentId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
