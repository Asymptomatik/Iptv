package com.bobot.iptvapp.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.bobot.iptvapp.data.local.IptvDatabase
import com.bobot.iptvapp.data.local.entity.ProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ProfileDao] using an in-memory [IptvDatabase].
 *
 * ## Running
 * ```
 * ./gradlew connectedAndroidTest
 * # or filter to this class:
 * ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bobot.iptvapp.data.local.dao.ProfileDaoTest
 * ```
 *
 * No Hilt injection is used — the database is constructed directly with
 * `Room.inMemoryDatabaseBuilder` and [allowMainThreadQueries] for test simplicity.
 * Each test starts with a fresh in-memory database (created in [setUp]) that is
 * closed in [tearDown].
 */
@RunWith(AndroidJUnit4::class)
class ProfileDaoTest {

    private lateinit var db: IptvDatabase
    private lateinit var profileDao: ProfileDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            IptvDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        profileDao = db.profileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── upsert (insert) ───────────────────────────────────────────────────────

    @Test
    fun upsert_insertsNewProfile_andGetByIdReturnsIt() = runTest {
        val profile = ProfileEntity(id = "p1", name = "Alice", avatarUrl = null)

        profileDao.upsert(profile)

        val retrieved = profileDao.getById("p1")
        assertEquals(profile, retrieved)
    }

    @Test
    fun upsert_updatesExistingProfile_byPrimaryKey() = runTest {
        val original = ProfileEntity(id = "p1", name = "Alice", avatarUrl = null)
        profileDao.upsert(original)

        val updated = original.copy(name = "Alice Updated", avatarUrl = "http://example.com/avatar.png")
        profileDao.upsert(updated)

        val retrieved = profileDao.getById("p1")
        assertEquals("Alice Updated", retrieved?.name)
        assertEquals("http://example.com/avatar.png", retrieved?.avatarUrl)
    }

    @Test
    fun upsertAll_insertsMultipleProfiles() = runTest {
        val profiles = listOf(
            ProfileEntity(id = "p1", name = "Alice", avatarUrl = null),
            ProfileEntity(id = "p2", name = "Bob", avatarUrl = null),
        )

        profileDao.upsertAll(profiles)

        assertEquals("Alice", profileDao.getById("p1")?.name)
        assertEquals("Bob", profileDao.getById("p2")?.name)
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    fun getById_returnsNull_whenProfileDoesNotExist() = runTest {
        val result = profileDao.getById("nonexistent")
        assertNull(result)
    }

    // ── deleteById ────────────────────────────────────────────────────────────

    @Test
    fun deleteById_removesProfile_andGetByIdReturnsNull() = runTest {
        profileDao.upsert(ProfileEntity(id = "p1", name = "Alice", avatarUrl = null))

        profileDao.deleteById("p1")

        assertNull(profileDao.getById("p1"))
    }

    @Test
    fun deleteById_isNoOp_whenProfileDoesNotExist() = runTest {
        // Should not throw
        profileDao.deleteById("ghost")
    }

    // ── getAll (Flow) ─────────────────────────────────────────────────────────

    @Test
    fun getAll_emitsEmptyList_whenNoProfiles() = runTest {
        profileDao.getAll().test {
            assertEquals(emptyList<ProfileEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getAll_emitsUpdatedList_afterUpsert() = runTest {
        profileDao.getAll().test {
            assertEquals(0, awaitItem().size)

            profileDao.upsert(ProfileEntity(id = "p1", name = "Alice", avatarUrl = null))
            val after = awaitItem()
            assertEquals(1, after.size)
            assertEquals("p1", after[0].id)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getAll_ordersProfilesAlphabeticallyByName() = runTest {
        profileDao.upsertAll(
            listOf(
                ProfileEntity(id = "p3", name = "Zara", avatarUrl = null),
                ProfileEntity(id = "p1", name = "Alice", avatarUrl = null),
                ProfileEntity(id = "p2", name = "Bob", avatarUrl = null),
            ),
        )

        profileDao.getAll().test {
            val names = awaitItem().map { it.name }
            assertEquals(listOf("Alice", "Bob", "Zara"), names)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getAll_emitsUpdatedList_afterDelete() = runTest {
        profileDao.upsert(ProfileEntity(id = "p1", name = "Alice", avatarUrl = null))

        profileDao.getAll().test {
            assertEquals(1, awaitItem().size)

            profileDao.deleteById("p1")
            assertEquals(0, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
