package com.bobot.iptvapp.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [DataStoreAppPreferencesStore].
 *
 * Uses an in-process DataStore backed by a temporary file (via [TemporaryFolder]) so
 * that no Android runtime is required and tests run on the JVM.
 *
 * Covers:
 *  - getActiveProfileId returns null when nothing is stored
 *  - setActiveProfileId then getActiveProfileId round-trips correctly
 *  - setActiveProfileId(null) clears the stored id
 *  - observeActiveProfileId emits current value and reacts to changes
 */
class DataStoreAppPreferencesStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val dataStoreScope = CoroutineScope(testDispatcher + Job())

    private lateinit var store: DataStoreAppPreferencesStore

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test_app_prefs.preferences_pb") },
        )
        store = DataStoreAppPreferencesStore(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    // ── getActiveProfileId ────────────────────────────────────────────────────

    @Test
    fun `getActiveProfileId returns null when nothing stored`() = testScope.runTest {
        assertNull(store.getActiveProfileId())
    }

    @Test
    fun `setActiveProfileId then getActiveProfileId returns the stored id`() = testScope.runTest {
        store.setActiveProfileId("profile-abc")
        assertEquals("profile-abc", store.getActiveProfileId())
    }

    @Test
    fun `setActiveProfileId overwrites previous id`() = testScope.runTest {
        store.setActiveProfileId("profile-old")
        store.setActiveProfileId("profile-new")
        assertEquals("profile-new", store.getActiveProfileId())
    }

    // ── setActiveProfileId(null) ──────────────────────────────────────────────

    @Test
    fun `setActiveProfileId with null clears the stored id`() = testScope.runTest {
        store.setActiveProfileId("profile-abc")
        store.setActiveProfileId(null)
        assertNull(store.getActiveProfileId())
    }

    @Test
    fun `setActiveProfileId with null on empty store returns null without error`() =
        testScope.runTest {
            store.setActiveProfileId(null) // no-op
            assertNull(store.getActiveProfileId())
        }

    // ── observeActiveProfileId ────────────────────────────────────────────────

    @Test
    fun `observeActiveProfileId emits null initially when nothing stored`() = testScope.runTest {
        store.observeActiveProfileId().test {
            assertNull(awaitItem())
            cancel()
        }
    }

    @Test
    fun `observeActiveProfileId emits stored value immediately on collection`() =
        testScope.runTest {
            store.setActiveProfileId("profile-123")

            store.observeActiveProfileId().test {
                assertEquals("profile-123", awaitItem())
                cancel()
            }
        }

    @Test
    fun `observeActiveProfileId emits null then new id after set`() = testScope.runTest {
        store.observeActiveProfileId().test {
            assertNull(awaitItem()) // initial

            store.setActiveProfileId("profile-xyz")
            assertEquals("profile-xyz", awaitItem())

            cancel()
        }
    }

    @Test
    fun `observeActiveProfileId emits null after clearing`() = testScope.runTest {
        store.setActiveProfileId("profile-xyz")

        store.observeActiveProfileId().test {
            assertEquals("profile-xyz", awaitItem()) // initial — already stored

            store.setActiveProfileId(null)
            assertNull(awaitItem())

            cancel()
        }
    }

    @Test
    fun `observeActiveProfileId reacts to multiple sequential changes`() = testScope.runTest {
        store.observeActiveProfileId().test {
            assertNull(awaitItem()) // initial

            store.setActiveProfileId("p1")
            assertEquals("p1", awaitItem())

            store.setActiveProfileId("p2")
            assertEquals("p2", awaitItem())

            store.setActiveProfileId(null)
            assertNull(awaitItem())

            cancel()
        }
    }
}
