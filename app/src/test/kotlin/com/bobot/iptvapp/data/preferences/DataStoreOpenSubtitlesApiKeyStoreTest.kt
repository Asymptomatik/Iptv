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

/** Unit tests for [DataStoreOpenSubtitlesApiKeyStore], on a temporary-file DataStore. */
class DataStoreOpenSubtitlesApiKeyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val dataStoreScope = CoroutineScope(testDispatcher + Job())

    private lateinit var store: DataStoreOpenSubtitlesApiKeyStore

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test_opensubtitles.preferences_pb") },
        )
        store = DataStoreOpenSubtitlesApiKeyStore(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `nothing is configured on a fresh install`() = testScope.runTest {
        assertNull(store.getApiKey())
        store.observeIsConfigured().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a stored key is read back trimmed`() = testScope.runTest {
        store.setApiKey("  abc123  ")

        assertEquals("abc123", store.getApiKey())
    }

    @Test
    fun `configured state follows set and clear`() = testScope.runTest {
        store.observeIsConfigured().test {
            assertEquals(false, awaitItem())
            store.setApiKey("abc123")
            assertEquals(true, awaitItem())
            store.clearApiKey()
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(store.getApiKey())
    }

    @Test
    fun `a blank key clears the stored one`() = testScope.runTest {
        store.setApiKey("abc123")
        store.setApiKey("   ")

        assertNull(store.getApiKey())
    }
}
