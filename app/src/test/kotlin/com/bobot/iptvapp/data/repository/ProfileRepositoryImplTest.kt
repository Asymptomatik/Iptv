package com.bobot.iptvapp.data.repository

import app.cash.turbine.test
import com.bobot.iptvapp.data.local.dao.ProfileDao
import com.bobot.iptvapp.data.local.entity.ProfileEntity
import com.bobot.iptvapp.domain.model.Profile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProfileRepositoryImpl].
 *
 * Uses a MockK [ProfileDao] and a [StandardTestDispatcher] to:
 *  - verify that [ProfileDao.getAll] emissions are mapped to domain [Profile] objects,
 *  - verify that [createProfile] generates a unique UUID id and delegates to [ProfileDao.upsert],
 *  - verify that [updateProfile] delegates to [ProfileDao.upsert] without changing the id,
 *  - verify that [deleteProfile] delegates to [ProfileDao.deleteById],
 *  - verify that [getProfile] returns null for unknown ids and mapped domain for known ids.
 */
class ProfileRepositoryImplTest {

    private lateinit var profileDao: ProfileDao
    private lateinit var repository: ProfileRepositoryImpl
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        profileDao = mockk()
        repository = ProfileRepositoryImpl(
            profileDao = profileDao,
            ioDispatcher = testDispatcher,
        )
    }

    // ── observeProfiles ───────────────────────────────────────────────────────

    @Test
    fun `observeProfiles emits empty list when DAO emits empty list`() =
        runTest(testDispatcher) {
            every { profileDao.getAll() } returns flowOf(emptyList())

            repository.observeProfiles().test {
                assertEquals(emptyList<Profile>(), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `observeProfiles maps entities to domain Profile objects`() =
        runTest(testDispatcher) {
            val entities = listOf(
                ProfileEntity(id = "p1", name = "Alice", avatarUrl = "https://example.com/a.jpg"),
                ProfileEntity(id = "p2", name = "Bob", avatarUrl = null),
            )
            every { profileDao.getAll() } returns flowOf(entities)

            repository.observeProfiles().test {
                val profiles = awaitItem()
                assertEquals(2, profiles.size)
                assertEquals("p1", profiles[0].id)
                assertEquals("Alice", profiles[0].name)
                assertEquals("https://example.com/a.jpg", profiles[0].avatarUrl)
                assertEquals("p2", profiles[1].id)
                assertEquals("Bob", profiles[1].name)
                assertNull(profiles[1].avatarUrl)
                awaitComplete()
            }
        }

    @Test
    fun `observeProfiles emits updated list on subsequent Flow emissions`() =
        runTest(testDispatcher) {
            val firstEmit = listOf(ProfileEntity("p1", "Alice", null))
            val secondEmit = listOf(
                ProfileEntity("p1", "Alice", null),
                ProfileEntity("p2", "Bob", null),
            )
            every { profileDao.getAll() } returns flowOf(firstEmit, secondEmit)

            repository.observeProfiles().test {
                assertEquals(1, awaitItem().size)
                assertEquals(2, awaitItem().size)
                awaitComplete()
            }
        }

    // ── getProfile ────────────────────────────────────────────────────────────

    @Test
    fun `getProfile returns null when DAO returns null`() = runTest(testDispatcher) {
        coEvery { profileDao.getById("unknown") } returns null

        val result = repository.getProfile("unknown")
        assertNull(result)
    }

    @Test
    fun `getProfile returns mapped Profile when DAO returns entity`() =
        runTest(testDispatcher) {
            val entity = ProfileEntity(id = "p1", name = "Alice", avatarUrl = "https://avatar.url")
            coEvery { profileDao.getById("p1") } returns entity

            val result = repository.getProfile("p1")
            assertNotNull(result)
            assertEquals("p1", result!!.id)
            assertEquals("Alice", result.name)
            assertEquals("https://avatar.url", result.avatarUrl)
        }

    // ── createProfile ─────────────────────────────────────────────────────────

    @Test
    fun `createProfile generates a non-blank UUID id`() = runTest(testDispatcher) {
        coEvery { profileDao.upsert(any()) } returns Unit

        val profile = repository.createProfile("Alice", null)
        assertTrue(profile.id.isNotBlank())
    }

    @Test
    fun `createProfile returns Profile with provided name and avatarUrl`() =
        runTest(testDispatcher) {
            coEvery { profileDao.upsert(any()) } returns Unit

            val profile = repository.createProfile("Kids", "https://example.com/kids.png")
            assertEquals("Kids", profile.name)
            assertEquals("https://example.com/kids.png", profile.avatarUrl)
        }

    @Test
    fun `createProfile calls DAO upsert with entity matching the returned Profile`() =
        runTest(testDispatcher) {
            val entitySlot = slot<ProfileEntity>()
            coEvery { profileDao.upsert(capture(entitySlot)) } returns Unit

            val profile = repository.createProfile("Charlie", null)
            assertEquals(profile.id, entitySlot.captured.id)
            assertEquals("Charlie", entitySlot.captured.name)
            assertNull(entitySlot.captured.avatarUrl)
        }

    @Test
    fun `createProfile generates unique ids on successive calls`() = runTest(testDispatcher) {
        coEvery { profileDao.upsert(any()) } returns Unit

        val p1 = repository.createProfile("Alice", null)
        val p2 = repository.createProfile("Bob", null)
        assertTrue(p1.id != p2.id)
    }

    // ── updateProfile ─────────────────────────────────────────────────────────

    @Test
    fun `updateProfile calls DAO upsert and returns the same profile`() =
        runTest(testDispatcher) {
            coEvery { profileDao.upsert(any()) } returns Unit
            val profile = Profile(id = "p1", name = "Alice Updated", avatarUrl = null)

            val result = repository.updateProfile(profile)
            assertEquals(profile, result)
            coVerify(exactly = 1) { profileDao.upsert(any()) }
        }

    @Test
    fun `updateProfile preserves the profile id when upserting`() = runTest(testDispatcher) {
        val entitySlot = slot<ProfileEntity>()
        coEvery { profileDao.upsert(capture(entitySlot)) } returns Unit
        val profile = Profile(id = "p99", name = "Updated Name", avatarUrl = null)

        repository.updateProfile(profile)
        assertEquals("p99", entitySlot.captured.id)
        assertEquals("Updated Name", entitySlot.captured.name)
    }

    // ── deleteProfile ─────────────────────────────────────────────────────────

    @Test
    fun `deleteProfile delegates to DAO deleteById`() = runTest(testDispatcher) {
        coEvery { profileDao.deleteById("p1") } returns Unit

        repository.deleteProfile("p1")
        coVerify(exactly = 1) { profileDao.deleteById("p1") }
    }

    @Test
    fun `deleteProfile with unknown id is a no-op (DAO handles it)`() =
        runTest(testDispatcher) {
            coEvery { profileDao.deleteById("ghost") } returns Unit

            // Should not throw
            repository.deleteProfile("ghost")
            coVerify(exactly = 1) { profileDao.deleteById("ghost") }
        }
}
