package com.bobot.iptvapp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [DataStoreLogoutPurgeMarkerStore] — slice 4 of the logout purge.
 *
 * Backed by a real DataStore over a temporary file, like [DataStoreCredentialsProviderTest], because
 * the whole point of this marker is that it survives; an in-memory double would prove nothing.
 */
class DataStoreLogoutPurgeMarkerStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val dataStoreScope = CoroutineScope(testDispatcher + Job())

    private lateinit var markerFile: File
    private lateinit var store: DataStoreLogoutPurgeMarkerStore

    @Before
    fun setUp() {
        markerFile = tempFolder.newFile("test_logout_marker.preferences_pb")
        store = DataStoreLogoutPurgeMarkerStore(dataStoreOver(markerFile))
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    private fun dataStoreOver(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { file })

    @Test
    fun `nothing is pending on a fresh install`() = runTest(testDispatcher) {
        assertFalse(store.isPurgePending())
    }

    @Test
    fun `marking a purge pending makes it readable back`() = runTest(testDispatcher) {
        store.markPurgePending()

        assertTrue(store.isPurgePending())
    }

    @Test
    fun `clearing the marker makes the purge no longer pending`() = runTest(testDispatcher) {
        store.markPurgePending()

        store.clearPurgePending()

        assertFalse(store.isPurgePending())
    }

    @Test
    fun `marking and clearing twice are both idempotent`() = runTest(testDispatcher) {
        store.markPurgePending()
        store.markPurgePending()
        assertTrue(store.isPurgePending())

        store.clearPurgePending()
        store.clearPurgePending()
        assertFalse(store.isPurgePending())
    }

    @Test
    fun `a pending marker survives the store instance that wrote it`() = runTest(testDispatcher) {
        // Stands in for the case the marker exists for: the app was killed mid-purge and the next
        // process must pick the work back up. DataStore refuses two live instances over one file,
        // and it only releases the file once the owning job has *completed*, not merely been
        // cancelled — hence cancelAndJoin. That completion is the previous process going away.
        val file = tempFolder.newFile("restart.preferences_pb")
        val firstProcessScope = CoroutineScope(testDispatcher + Job())
        DataStoreLogoutPurgeMarkerStore(
            PreferenceDataStoreFactory.create(scope = firstProcessScope, produceFile = { file }),
        ).markPurgePending()
        firstProcessScope.coroutineContext.job.cancelAndJoin()

        val secondProcessScope = CoroutineScope(testDispatcher + Job())
        val afterRestart = DataStoreLogoutPurgeMarkerStore(
            PreferenceDataStoreFactory.create(scope = secondProcessScope, produceFile = { file }),
        )

        assertTrue(afterRestart.isPurgePending())
        secondProcessScope.cancel()
    }
}
