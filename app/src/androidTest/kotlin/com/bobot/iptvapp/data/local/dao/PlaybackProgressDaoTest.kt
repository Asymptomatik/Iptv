package com.bobot.iptvapp.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.bobot.iptvapp.data.local.IptvDatabase
import com.bobot.iptvapp.data.local.entity.PlaybackProgressEntity
import com.bobot.iptvapp.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [PlaybackProgressDao] using an in-memory [IptvDatabase].
 *
 * ## Running
 * ```
 * ./gradlew connectedAndroidTest
 * # or filter to this class:
 * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bobot.iptvapp.data.local.dao.PlaybackProgressDaoTest
 * ```
 *
 * Tests cover:
 *  - upsert insert and update semantics
 *  - [observeContinueWatching] ordering by [PlaybackProgressEntity.lastUpdatedMillis] DESC
 *  - profile scoping
 *  - [getProgress] for a single record
 *  - deleteByKeys
 *  - reactive Flow emissions after writes
 */
@RunWith(AndroidJUnit4::class)
class PlaybackProgressDaoTest {

    private lateinit var db: IptvDatabase
    private lateinit var progressDao: PlaybackProgressDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        progressDao = db.playbackProgressDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun progress(
        profileId: String = "profile1",
        contentId: String = "content1",
        contentType: ContentType = ContentType.MOVIE,
        positionMillis: Long = 30_000L,
        durationMillis: Long = 90_000L,
        lastUpdatedMillis: Long = 1000L,
    ) = PlaybackProgressEntity(
        profileId = profileId,
        contentId = contentId,
        contentType = contentType.name,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        lastUpdatedMillis = lastUpdatedMillis,
    )

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    fun upsert_insertsNewRecord_andGetProgressReturnsIt() = runTest {
        val p = progress(positionMillis = 30_000L, durationMillis = 90_000L)
        progressDao.upsert(p)

        val retrieved = progressDao.getProgress("profile1", "content1", ContentType.MOVIE.name)
        assertEquals(30_000L, retrieved?.positionMillis)
        assertEquals(90_000L, retrieved?.durationMillis)
    }

    @Test
    fun upsert_updatesExistingRecord_byCompositeKey() = runTest {
        progressDao.upsert(progress(positionMillis = 30_000L, lastUpdatedMillis = 1000L))

        // Second upsert with same composite key — updates position and timestamp
        progressDao.upsert(progress(positionMillis = 60_000L, lastUpdatedMillis = 2000L))

        val retrieved = progressDao.getProgress("profile1", "content1", ContentType.MOVIE.name)
        assertEquals(60_000L, retrieved?.positionMillis)
        assertEquals(2000L, retrieved?.lastUpdatedMillis)
    }

    // ── getProgress ───────────────────────────────────────────────────────────

    @Test
    fun getProgress_returnsNull_whenNoRecordExists() = runTest {
        val result = progressDao.getProgress("profile1", "ghost", ContentType.MOVIE.name)
        assertNull(result)
    }

    @Test
    fun getProgress_isScopedByAllThreeKeyFields() = runTest {
        progressDao.upsert(progress(contentType = ContentType.MOVIE))

        // Same profileId + contentId but different contentType — should return null
        val mismatch = progressDao.getProgress("profile1", "content1", ContentType.SERIES.name)
        assertNull(mismatch)
    }

    // ── observeContinueWatching — ordering ───────────────────────────────────

    @Test
    fun observeContinueWatching_ordersItemsByLastUpdatedMillisDescending() = runTest {
        progressDao.upsert(progress(contentId = "c1", lastUpdatedMillis = 1000L))
        progressDao.upsert(progress(contentId = "c2", lastUpdatedMillis = 3000L))
        progressDao.upsert(progress(contentId = "c3", lastUpdatedMillis = 2000L))

        progressDao.observeContinueWatching("profile1", limit = 50).test {
            val items = awaitItem()
            assertEquals(3, items.size)
            assertEquals("c2", items[0].contentId) // most recent
            assertEquals("c3", items[1].contentId)
            assertEquals("c1", items[2].contentId) // least recent
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── observeContinueWatching — scoping ─────────────────────────────────────

    @Test
    fun observeContinueWatching_isScopedToProfileId() = runTest {
        progressDao.upsert(progress(profileId = "profile1", contentId = "c1"))
        progressDao.upsert(progress(profileId = "profile2", contentId = "c2"))

        progressDao.observeContinueWatching("profile1", limit = 50).test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("c1", items[0].contentId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── observeContinueWatching — reactive updates ────────────────────────────

    @Test
    fun observeContinueWatching_emitsUpdatedList_afterUpsert() = runTest {
        progressDao.observeContinueWatching("profile1", limit = 50).test {
            assertEquals(0, awaitItem().size)

            progressDao.upsert(progress(contentId = "c1", lastUpdatedMillis = 1000L))
            assertEquals(1, awaitItem().size)

            // Second upsert with later timestamp — should appear first
            progressDao.upsert(progress(contentId = "c2", lastUpdatedMillis = 2000L))
            val two = awaitItem()
            assertEquals(2, two.size)
            assertEquals("c2", two[0].contentId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeContinueWatching_reordersAfterPositionUpdate() = runTest {
        progressDao.upsert(progress(contentId = "c1", lastUpdatedMillis = 2000L))
        progressDao.upsert(progress(contentId = "c2", lastUpdatedMillis = 1000L))

        progressDao.observeContinueWatching("profile1", limit = 50).test {
            val initial = awaitItem()
            assertEquals("c1", initial[0].contentId) // c1 was more recently watched

            // Now update c2 to be the most recent
            progressDao.upsert(progress(contentId = "c2", lastUpdatedMillis = 3000L))
            val reordered = awaitItem()
            assertEquals("c2", reordered[0].contentId) // c2 is now first
            assertEquals("c1", reordered[1].contentId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── observeContinueWatching — LIMIT enforcement ───────────────────────────

    @Test
    fun observeContinueWatching_respectsLimit() = runTest {
        // Insert 5 records; requesting limit=3 must return only the 3 most recent.
        for (i in 1..5) {
            progressDao.upsert(progress(contentId = "c$i", lastUpdatedMillis = i * 1000L))
        }

        progressDao.observeContinueWatching("profile1", limit = 3).test {
            val items = awaitItem()
            assertEquals(3, items.size)
            // Most recent is c5 (lastUpdatedMillis=5000), then c4, then c3
            assertEquals("c5", items[0].contentId)
            assertEquals("c4", items[1].contentId)
            assertEquals("c3", items[2].contentId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── deleteByKeys ──────────────────────────────────────────────────────────

    @Test
    fun deleteByKeys_removesRecord_andGetProgressReturnsNull() = runTest {
        progressDao.upsert(progress())
        progressDao.deleteByKeys("profile1", "content1", ContentType.MOVIE.name)

        val retrieved = progressDao.getProgress("profile1", "content1", ContentType.MOVIE.name)
        assertNull(retrieved)
    }

    @Test
    fun deleteByKeys_isNoOp_whenRecordDoesNotExist() = runTest {
        // Should not throw
        progressDao.deleteByKeys("profile1", "ghost", ContentType.MOVIE.name)
    }
}
