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
 *  - getDefaultLanguageFilter/observeDefaultLanguageFilter absent-vs-empty semantics
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

    // observeWifiOnlyDownloads ───────────────────────────────────────────

    @Test
    fun `observeWifiOnlyDownloads emits false initially when nothing stored`() = testScope.runTest {
        store.observeWifiOnlyDownloads().test {
            assertEquals(false, awaitItem())
            cancel()
        }
    }

    @Test
    fun `observeWifiOnlyDownloads emits stored value immediately on collection`() = testScope.runTest {
        store.setWifiOnlyDownloads(true)

        store.observeWifiOnlyDownloads().test {
            assertEquals(true, awaitItem())
            cancel()
        }
    }

    @Test
    fun `observeWifiOnlyDownloads emits false then true after set`() = testScope.runTest {
        store.observeWifiOnlyDownloads().test {
            assertEquals(false, awaitItem()) // initial

            store.setWifiOnlyDownloads(true)
            assertEquals(true, awaitItem())

            cancel()
        }
    }

    @Test
    fun `observeWifiOnlyDownloads emits true then false after set`() = testScope.runTest {
        store.setWifiOnlyDownloads(true)

        store.observeWifiOnlyDownloads().test {
            assertEquals(true, awaitItem()) // initial — already stored

            store.setWifiOnlyDownloads(false)
            assertEquals(false, awaitItem())

            cancel()
        }
    }

    @Test
    fun `observeWifiOnlyDownloads reacts to multiple sequential changes`() = testScope.runTest {
        store.observeWifiOnlyDownloads().test {
            assertEquals(false, awaitItem()) // initial

            store.setWifiOnlyDownloads(true)
            assertEquals(true, awaitItem())

            store.setWifiOnlyDownloads(false)
            assertEquals(false, awaitItem())

            store.setWifiOnlyDownloads(true)
            assertEquals(true, awaitItem())

            cancel()
        }
    }

    // ── defaultLanguageFilter ────────────────────────────────────────────

    @Test
    fun `getDefaultLanguageFilter returns FR when nothing stored`() = testScope.runTest {
        assertEquals("FR", store.getDefaultLanguageFilter())
    }

    @Test
    fun `setDefaultLanguageFilter then getDefaultLanguageFilter returns the stored tag`() =
        testScope.runTest {
            store.setDefaultLanguageFilter("EN")
            assertEquals("EN", store.getDefaultLanguageFilter())
        }

    @Test
    fun `setDefaultLanguageFilter with null clears the filter to null`() = testScope.runTest {
        store.setDefaultLanguageFilter("EN")
        store.setDefaultLanguageFilter(null)
        assertNull(store.getDefaultLanguageFilter())
    }

    @Test
    fun `setDefaultLanguageFilter trims and uppercases the stored tag`() = testScope.runTest {
        store.setDefaultLanguageFilter("  fr ")
        assertEquals("FR", store.getDefaultLanguageFilter())
    }

    @Test
    fun `observeDefaultLanguageFilter emits FR initially when nothing stored`() =
        testScope.runTest {
            store.observeDefaultLanguageFilter().test {
                assertEquals("FR", awaitItem())
                cancel()
            }
        }

    @Test
    fun `observeDefaultLanguageFilter emits current value then reacts to changes`() =
        testScope.runTest {
            store.observeDefaultLanguageFilter().test {
                assertEquals("FR", awaitItem()) // initial — nothing stored yet

                store.setDefaultLanguageFilter("EN")
                assertEquals("EN", awaitItem())

                store.setDefaultLanguageFilter(null)
                assertNull(awaitItem())

                store.setDefaultLanguageFilter("  fr ")
                assertEquals("FR", awaitItem())

                cancel()
            }
        }

    // ── Chaines tab default (region tags, not language tags) ─────────────────

    @Test
    fun `getDefaultLiveLanguageFilter returns EU when nothing stored`() = testScope.runTest {
        assertEquals("EU", store.getDefaultLiveLanguageFilter())
    }

    @Test
    fun `setDefaultLiveLanguageFilter then getDefaultLiveLanguageFilter returns the stored tag`() =
        testScope.runTest {
            store.setDefaultLiveLanguageFilter("AM")
            assertEquals("AM", store.getDefaultLiveLanguageFilter())
        }

    @Test
    fun `setDefaultLiveLanguageFilter with null clears the filter to null`() = testScope.runTest {
        store.setDefaultLiveLanguageFilter("AM")
        store.setDefaultLiveLanguageFilter(null)
        assertNull(store.getDefaultLiveLanguageFilter())
    }

    @Test
    fun `setDefaultLiveLanguageFilter trims and uppercases the stored tag`() = testScope.runTest {
        store.setDefaultLiveLanguageFilter("  eu ")
        assertEquals("EU", store.getDefaultLiveLanguageFilter())
    }

    @Test
    fun `observeDefaultLiveLanguageFilter emits EU initially then reacts to changes`() =
        testScope.runTest {
            store.observeDefaultLiveLanguageFilter().test {
                assertEquals("EU", awaitItem()) // initial — nothing stored yet

                store.setDefaultLiveLanguageFilter("AM")
                assertEquals("AM", awaitItem())

                store.setDefaultLiveLanguageFilter(null)
                assertNull(awaitItem())

                cancel()
            }
        }

    @Test
    fun `the two default filters are stored independently`() = testScope.runTest {
        // Distinct DataStore keys: writing one must not disturb the other, in either direction.
        store.setDefaultLanguageFilter("EN")
        assertEquals("EU", store.getDefaultLiveLanguageFilter())

        store.setDefaultLiveLanguageFilter("AM")
        assertEquals("EN", store.getDefaultLanguageFilter())
        assertEquals("AM", store.getDefaultLiveLanguageFilter())
    }
}
