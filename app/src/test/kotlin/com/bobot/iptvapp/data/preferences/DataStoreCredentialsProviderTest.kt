package com.bobot.iptvapp.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.bobot.iptvapp.domain.model.XtreamCredentials
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
 * Unit tests for [DataStoreCredentialsProvider].
 *
 * Uses an in-process DataStore backed by a temporary file (via [TemporaryFolder]) so
 * that no Android runtime is required and tests run on the JVM. Each test method gets
 * a fresh [DataStoreCredentialsProvider] instance with an isolated DataStore file to
 * prevent state bleed between tests.
 *
 * Covers:
 *  - getCredentials returns null when nothing is stored
 *  - setCredentials then getCredentials round-trips correctly
 *  - clearCredentials removes all fields
 *  - getCredentials returns null when any required field is blank
 *  - observeCredentials emits current value then changes reactively
 */
class DataStoreCredentialsProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val dataStoreScope = CoroutineScope(testDispatcher + Job())

    private lateinit var provider: DataStoreCredentialsProvider

    @Before
    fun setUp() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("test_credentials.preferences_pb") },
        )
        provider = DataStoreCredentialsProvider(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    // ── getCredentials ────────────────────────────────────────────────────────

    @Test
    fun `getCredentials returns null when no credentials stored`() = testScope.runTest {
        assertNull(provider.getCredentials())
    }

    @Test
    fun `setCredentials then getCredentials returns the stored credentials`() = testScope.runTest {
        val creds = XtreamCredentials("http://example.com:8080", "alice", "s3cr3t")
        provider.setCredentials(creds)
        assertEquals(creds, provider.getCredentials())
    }

    @Test
    fun `setCredentials overwrites previous credentials`() = testScope.runTest {
        provider.setCredentials(XtreamCredentials("http://old.com:8080", "user1", "pass1"))
        val newCreds = XtreamCredentials("http://new.com:9000", "user2", "pass2")
        provider.setCredentials(newCreds)
        assertEquals(newCreds, provider.getCredentials())
    }

    // ── clearCredentials ──────────────────────────────────────────────────────

    @Test
    fun `clearCredentials removes stored credentials`() = testScope.runTest {
        provider.setCredentials(XtreamCredentials("http://example.com:8080", "alice", "s3cr3t"))
        provider.clearCredentials()
        assertNull(provider.getCredentials())
    }

    @Test
    fun `clearCredentials on empty store returns null without error`() = testScope.runTest {
        provider.clearCredentials() // no-op
        assertNull(provider.getCredentials())
    }

    // ── null-when-blank contract ──────────────────────────────────────────────

    @Test
    fun `getCredentials returns null when baseUrl is blank`() = testScope.runTest {
        provider.setCredentials(XtreamCredentials("", "alice", "s3cr3t"))
        assertNull(provider.getCredentials())
    }

    @Test
    fun `getCredentials returns null when username is blank`() = testScope.runTest {
        provider.setCredentials(XtreamCredentials("http://example.com:8080", "", "s3cr3t"))
        assertNull(provider.getCredentials())
    }

    @Test
    fun `getCredentials returns null when password is blank`() = testScope.runTest {
        provider.setCredentials(XtreamCredentials("http://example.com:8080", "alice", ""))
        assertNull(provider.getCredentials())
    }

    @Test
    fun `getCredentials returns null when all fields are blank`() = testScope.runTest {
        provider.setCredentials(XtreamCredentials("", "", ""))
        assertNull(provider.getCredentials())
    }

    // ── observeCredentials ────────────────────────────────────────────────────

    @Test
    fun `observeCredentials emits null initially when nothing is stored`() = testScope.runTest {
        provider.observeCredentials().test {
            assertNull(awaitItem())
            cancel()
        }
    }

    @Test
    fun `observeCredentials emits stored credentials on collection then reacts to changes`() =
        testScope.runTest {
            val creds1 = XtreamCredentials("http://server1.com:8080", "user1", "pass1")
            val creds2 = XtreamCredentials("http://server2.com:9000", "user2", "pass2")

            provider.observeCredentials().test {
                assertNull(awaitItem()) // initial — nothing stored yet

                provider.setCredentials(creds1)
                assertEquals(creds1, awaitItem())

                provider.setCredentials(creds2)
                assertEquals(creds2, awaitItem())

                provider.clearCredentials()
                assertNull(awaitItem())

                cancel()
            }
        }

    @Test
    fun `observeCredentials emits existing credentials immediately on collection`() =
        testScope.runTest {
            val creds = XtreamCredentials("http://example.com:8080", "alice", "s3cr3t")
            provider.setCredentials(creds)

            provider.observeCredentials().test {
                assertEquals(creds, awaitItem()) // already stored — emits immediately
                cancel()
            }
        }

    @Test
    fun `observeCredentials emits null for blank credentials`() = testScope.runTest {
        provider.observeCredentials().test {
            assertNull(awaitItem()) // initial

            // Setting blank credentials — toCredentials() returns null
            provider.setCredentials(XtreamCredentials("", "user", "pass"))
            assertNull(awaitItem())

            cancel()
        }
    }
}
