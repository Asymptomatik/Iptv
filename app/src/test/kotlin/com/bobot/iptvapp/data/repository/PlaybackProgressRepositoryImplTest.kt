package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.PlaybackProgressDao
import com.bobot.iptvapp.data.local.entity.PlaybackProgressEntity
import com.bobot.iptvapp.domain.model.ContentType
import com.bobot.iptvapp.domain.model.PlaybackProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PlaybackProgressRepositoryImpl].
 *
 * Uses a MockK [PlaybackProgressDao] and a [StandardTestDispatcher] to:
 *  - verify that [observeContinueWatching] passes the limit to the DAO and maps entities,
 *  - verify that [upsertProgress] maps the domain object to an entity before calling the DAO,
 *  - verify that [getProgress] returns null when the DAO returns null and a domain object otherwise,
 *  - verify that [deleteProgress] converts the ContentType enum to a name String before delegation,
 *  - verify that [clearProgress] delegates to [PlaybackProgressDao.clearByProfileId].
 */
class PlaybackProgressRepositoryImplTest {

    private lateinit var progressDao: PlaybackProgressDao
    private lateinit var repository: PlaybackProgressRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        progressDao = mockk()
        repository = PlaybackProgressRepositoryImpl(
            progressDao = progressDao,
            ioDispatcher = testDispatcher,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildDomainProgress(
        profileId: String = "p1",
        contentId: String = "m1",
        contentType: ContentType = ContentType.MOVIE,
        positionMillis: Long = 30_000L,
        durationMillis: Long = 90_000L,
        lastUpdatedMillis: Long = 1_000L,
    ) = PlaybackProgress(
        profileId = profileId,
        contentId = contentId,
        contentType = contentType,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        lastUpdatedMillis = lastUpdatedMillis,
    )

    private fun buildEntity(
        profileId: String = "p1",
        contentId: String = "m1",
        contentType: String = ContentType.MOVIE.name,
        positionMillis: Long = 30_000L,
        durationMillis: Long = 90_000L,
        lastUpdatedMillis: Long = 1_000L,
    ) = PlaybackProgressEntity(
        profileId = profileId,
        contentId = contentId,
        contentType = contentType,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        lastUpdatedMillis = lastUpdatedMillis,
    )

    // ── upsertProgress ────────────────────────────────────────────────────────

    @Test
    fun `upsertProgress calls DAO upsert with correctly mapped entity`() =
        runTest(testDispatcher) {
            val entitySlot = slot<PlaybackProgressEntity>()
            coEvery { progressDao.upsert(capture(entitySlot)) } returns Unit

            val progress = buildDomainProgress(
                positionMillis = 45_000L,
                durationMillis = 120_000L,
                lastUpdatedMillis = 9_999L,
            )
            repository.upsertProgress(progress)

            coVerify(exactly = 1) { progressDao.upsert(any()) }
            assertEquals("p1", entitySlot.captured.profileId)
            assertEquals("m1", entitySlot.captured.contentId)
            assertEquals(ContentType.MOVIE.name, entitySlot.captured.contentType)
            assertEquals(45_000L, entitySlot.captured.positionMillis)
            assertEquals(120_000L, entitySlot.captured.durationMillis)
            assertEquals(9_999L, entitySlot.captured.lastUpdatedMillis)
        }

    // ── getProgress ───────────────────────────────────────────────────────────

    @Test
    fun `getProgress returns null when DAO returns null`() = runTest(testDispatcher) {
        coEvery {
            progressDao.getProgress("p1", "ghost", ContentType.MOVIE.name)
        } returns null

        val result = repository.getProgress("p1", "ghost", ContentType.MOVIE)
        assertNull(result)
    }

    @Test
    fun `getProgress returns mapped PlaybackProgress when DAO returns entity`() =
        runTest(testDispatcher) {
            val entity = buildEntity(positionMillis = 60_000L, durationMillis = 180_000L)
            coEvery {
                progressDao.getProgress("p1", "m1", ContentType.MOVIE.name)
            } returns entity

            val result = repository.getProgress("p1", "m1", ContentType.MOVIE)
            assertEquals("p1", result?.profileId)
            assertEquals("m1", result?.contentId)
            assertEquals(ContentType.MOVIE, result?.contentType)
            assertEquals(60_000L, result?.positionMillis)
            assertEquals(180_000L, result?.durationMillis)
        }

    @Test
    fun `getProgress converts ContentType enum to name String before DAO call`() =
        runTest(testDispatcher) {
            coEvery {
                progressDao.getProgress("p1", "s1", ContentType.SERIES.name)
            } returns null

            repository.getProgress("p1", "s1", ContentType.SERIES)

            // Verify DAO was called with the String name, not the enum
            coVerify(exactly = 1) {
                progressDao.getProgress("p1", "s1", "SERIES")
            }
        }

    // ── observeContinueWatching ───────────────────────────────────────────────

    @Test
    fun `observeContinueWatching passes limit to DAO and maps entities to domain`() =
        runTest(testDispatcher) {
            val entities = listOf(
                buildEntity(contentId = "m2", lastUpdatedMillis = 2_000L),
                buildEntity(contentId = "m1", lastUpdatedMillis = 1_000L),
            )
            every { progressDao.observeContinueWatching("p1", 20) } returns flowOf(entities)

            repository.observeContinueWatching("p1", limit = 20).test {
                val items = awaitItem()
                assertEquals(2, items.size)
                assertEquals("m2", items[0].contentId)
                assertEquals("m1", items[1].contentId)
                assertEquals(ContentType.MOVIE, items[0].contentType)
                awaitComplete()
            }
        }

    @Test
    fun `observeContinueWatching emits empty list when DAO emits empty`() =
        runTest(testDispatcher) {
            every {
                progressDao.observeContinueWatching("p1", 20)
            } returns flowOf(emptyList())

            repository.observeContinueWatching("p1").test {
                assertEquals(emptyList<PlaybackProgress>(), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `observeContinueWatching default limit is 20`() = runTest(testDispatcher) {
        every { progressDao.observeContinueWatching("p1", 20) } returns flowOf(emptyList())

        // Calling without explicit limit — must use default = 20
        repository.observeContinueWatching("p1").test {
            awaitItem()
            awaitComplete()
        }

        // The DAO must have been called with limit = 20
        coVerify { progressDao.observeContinueWatching("p1", 20) }
    }

    @Test
    fun `observeContinueWatching uses custom limit when provided`() = runTest(testDispatcher) {
        every { progressDao.observeContinueWatching("p1", 5) } returns flowOf(emptyList())

        repository.observeContinueWatching("p1", limit = 5).test {
            awaitItem()
            awaitComplete()
        }

        coVerify { progressDao.observeContinueWatching("p1", 5) }
    }

    // ── deleteProgress ────────────────────────────────────────────────────────

    @Test
    fun `deleteProgress delegates to DAO deleteByKeys with String contentType`() =
        runTest(testDispatcher) {
            coEvery {
                progressDao.deleteByKeys("p1", "m1", ContentType.MOVIE.name)
            } returns Unit

            repository.deleteProgress("p1", "m1", ContentType.MOVIE)

            coVerify(exactly = 1) { progressDao.deleteByKeys("p1", "m1", "MOVIE") }
        }

    @Test
    fun `deleteProgress with SERIES contentType passes correct String to DAO`() =
        runTest(testDispatcher) {
            coEvery {
                progressDao.deleteByKeys("p1", "ep1", ContentType.SERIES.name)
            } returns Unit

            repository.deleteProgress("p1", "ep1", ContentType.SERIES)

            coVerify(exactly = 1) { progressDao.deleteByKeys("p1", "ep1", "SERIES") }
        }

    // ── clearProgress ─────────────────────────────────────────────────────────

    @Test
    fun `clearProgress delegates to DAO clearByProfileId`() = runTest(testDispatcher) {
        coEvery { progressDao.clearByProfileId("p1") } returns Unit

        repository.clearProgress("p1")

        coVerify(exactly = 1) { progressDao.clearByProfileId("p1") }
    }

    @Test
    fun `clearProgress for unknown profileId is a no-op (DAO handles it)`() =
        runTest(testDispatcher) {
            coEvery { progressDao.clearByProfileId("ghost") } returns Unit

            repository.clearProgress("ghost")

            coVerify(exactly = 1) { progressDao.clearByProfileId("ghost") }
        }
}
