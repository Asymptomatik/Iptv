package com.bobot.iptvapp.data.remote.opensubtitles

import com.bobot.iptvapp.data.logout.FakeSessionWriteGate
import com.bobot.iptvapp.data.logout.LogoutCoordinator
import com.bobot.iptvapp.data.preferences.FakeLogoutPurgeMarkerStore
import com.bobot.iptvapp.domain.logout.LogoutPurgeState
import com.bobot.iptvapp.domain.logout.LogoutPurger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

/** [OnlineSubtitleFileStore] against a real temporary directory. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSubtitleFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val directory by lazy { File(tmp.root, "online_subtitles") }
    private val gate = FakeSessionWriteGate()
    private val store by lazy { OnlineSubtitleFileStore(directory, Dispatchers.Unconfined, gate) }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nBonjour\n".toByteArray()
    private val visit = OnlineSubtitleVisit()

    private suspend fun save(
        fileId: Long,
        language: String?,
        content: ByteArray = srt,
        owner: OnlineSubtitleVisit = visit,
    ): File = store.save(fileId, language, content, gate.generation, owner)!!

    @Test
    fun `a saved subtitle is a complete srt file inside the store directory, and nothing else is left`() = runTest {
        val file = save(fileId = 222, language = "fr")

        assertEquals(directory.canonicalFile, file.parentFile!!.canonicalFile)
        assertTrue(file.name.endsWith(".srt"))
        assertArrayEquals(srt, file.readBytes())
        assertEquals(listOf(file.name), directory.list()!!.toList())
    }

    @Test
    fun `the file name is built from the id and a sanitised language, never from provider text`() = runTest {
        val file = save(fileId = 7, language = "../../etc/pt-BR")

        assertEquals(directory.canonicalFile, file.parentFile!!.canonicalFile)
        assertTrue(file.name, file.name.matches(Regex("[a-z0-9._-]+")))
        assertFalse(file.name.contains(".."))
    }

    @Test
    fun `saving the same subtitle again gives a new file and leaves the first one alone`() = runTest {
        val first = save(fileId = 222, language = "fr")
        val replacement = "1\n00:00:03,000 --> 00:00:04,000\nSalut\n".toByteArray()

        val second = save(fileId = 222, language = "fr", content = replacement, owner = OnlineSubtitleVisit())

        assertFalse(first == second)
        assertArrayEquals(srt, first.readBytes())
        assertArrayEquals(replacement, second.readBytes())
    }

    // ── visits ────────────────────────────────────────────────────────────────

    @Test
    fun `closing a visit deletes the files it saved and only those, even for the same subtitle`() = runTest {
        val mine = listOf(save(fileId = 222, language = "fr"), save(fileId = 333, language = "en"))
        val otherPlayer = OnlineSubtitleVisit()
        val theirs = save(fileId = 222, language = "fr", owner = otherPlayer)

        visit.close()

        mine.forEach { assertFalse(it.name, it.exists()) }
        assertEquals(listOf(theirs.name), directory.list()!!.toList())
        assertArrayEquals(srt, theirs.readBytes())
    }

    @Test
    fun `a save for a closed visit writes nothing and leaves nothing behind`() = runTest {
        visit.close()

        assertNull(store.save(fileId = 1, language = "fr", content = srt, sessionGeneration = gate.generation, visit = visit))
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun `closing a visit twice, or after a purge took its files, is harmless`() = runTest {
        save(fileId = 1, language = "fr")
        store.purgeDownloadedSubtitles()
        val next = save(fileId = 2, language = "fr", owner = OnlineSubtitleVisit())

        visit.close()
        visit.close()

        assertEquals(listOf(next.name), directory.list()!!.toList())
    }

    @Test
    fun `a failed save leaves no partial file behind`() = runTest {
        // The store directory cannot be created: a plain file sits where it should be.
        directory.writeText("in the way")

        try {
            save(fileId = 1, language = "fr")
            fail("expected an IOException")
        } catch (expected: IOException) {
            // expected
        }
        assertEquals(listOf("online_subtitles"), tmp.root.list()!!.toList())
        assertEquals("in the way", directory.readText())
    }

    @Test
    fun `a save the session gate refuses writes nothing at all`() = runTest {
        val stale = gate.generation
        gate.generation++

        assertNull(store.save(fileId = 1, language = "fr", content = srt, sessionGeneration = stale, visit = visit))
        gate.open = false
        assertNull(store.save(fileId = 1, language = "fr", content = srt, sessionGeneration = gate.generation, visit = visit))

        assertFalse("not even the directory is created", directory.exists())
    }

    @Test
    fun `purging removes every subtitle and every leftover temporary file`() = runTest {
        save(fileId = 1, language = "fr")
        save(fileId = 2, language = "en")
        File(directory, "crashed.part").writeText("half")
        assertTrue(store.hasResidue())

        store.purgeDownloadedSubtitles()

        assertFalse(store.hasResidue())
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun `an empty or missing store has no residue and purges as a no-op`() = runTest {
        assertFalse(store.hasResidue())

        store.purgeDownloadedSubtitles()

        assertFalse(store.hasResidue())
    }

    // ── orphans of a dead process ─────────────────────────────────────────────

    /** What a process killed mid-visit leaves: a finished subtitle and a half-written one. */
    private fun plantOrphans(): List<File> {
        directory.mkdirs()
        return listOf(
            File(directory, "222.fr.0f0f0f0f-dead-visit.srt").apply { writeBytes(srt) },
            File(directory, "subtitle123.part").apply { writeText("half") },
        )
    }

    @Test
    fun `at restart, the sweep deletes a dead process's finished and half-written subtitles`() = runTest {
        val orphans = plantOrphans()

        store.sweepOrphans() // a fresh store is a fresh process

        orphans.forEach { assertFalse(it.name, it.exists()) }
        assertFalse(store.hasResidue())
    }

    @Test
    fun `the first save of a process sweeps the orphans before writing, and keeps its own file`() = runTest {
        val orphans = plantOrphans()

        val file = save(fileId = 1, language = "fr")

        orphans.forEach { assertFalse(it.name, it.exists()) }
        assertEquals(listOf(file.name), directory.list()!!.toList())
    }

    @Test
    fun `the sweep never touches a file of this process's visits, and runs only once`() = runTest {
        val active = save(fileId = 1, language = "fr")
        store.sweepOrphans()
        store.sweepOrphans()
        val later = save(fileId = 2, language = "en", owner = OnlineSubtitleVisit())

        assertArrayEquals(srt, active.readBytes())
        assertTrue(later.exists())
        visit.close()
        assertFalse("the visit still owns and deletes its file", active.exists())
    }

    @Test
    fun `the sweep stays inside the store directory`() = runTest {
        plantOrphans()
        val sibling = File(tmp.root, "sibling.srt").apply { writeBytes(srt) }
        val nested = File(directory, "nested").apply { mkdirs() }
        val nestedFile = File(nested, "keep.srt").apply { writeBytes(srt) }

        store.sweepOrphans()

        assertTrue(sibling.exists())
        assertTrue(nestedFile.exists())
        assertEquals(listOf("nested"), directory.list()!!.toList())
    }

    @Test
    fun `a missing store directory sweeps as a no-op and creates nothing`() = runTest {
        assertTrue(store.sweepOrphans())

        assertFalse(directory.exists())
    }

    // ── a sweep that fails ────────────────────────────────────────────────────

    private var listingFails = false
    private var deletionFails = false

    private val failingStore by lazy {
        OnlineSubtitleFileStore(
            directory,
            Dispatchers.Unconfined,
            gate,
            listEntries = { if (listingFails) null else it.listFiles() },
            deleteEntry = { !deletionFails && it.delete() },
        )
    }

    @Test
    fun `an orphan the sweep could not delete is retried later, and this process's files are never taken`() =
        runTest {
            val orphans = plantOrphans()
            deletionFails = true

            assertFalse("the failure is reported", failingStore.sweepOrphans())
            val mine = failingStore.save(1, "fr", srt, gate.generation, visit)!!
            orphans.forEach { assertTrue(it.name, it.exists()) }

            deletionFails = false
            val later = failingStore.save(2, "en", srt, gate.generation, OnlineSubtitleVisit())!!

            orphans.forEach { assertFalse(it.name, it.exists()) }
            assertEquals(setOf(mine.name, later.name), directory.list()!!.toSet())
            assertTrue(failingStore.sweepOrphans())
        }

    @Test
    fun `a directory that cannot be listed fails the sweep and the save, until a later save succeeds`() = runTest {
        val orphans = plantOrphans()
        listingFails = true

        assertFalse(failingStore.sweepOrphans())
        try {
            failingStore.save(1, "fr", srt, gate.generation, visit)
            fail("expected an IOException")
        } catch (expected: IOException) {
            // expected: a file written now would pass for an orphan
        }
        assertEquals(orphans.map { it.name }.toSet(), directory.list()!!.toSet())

        listingFails = false
        val file = failingStore.save(1, "fr", srt, gate.generation, visit)!!

        orphans.forEach { assertFalse(it.name, it.exists()) }
        assertEquals(listOf(file.name), directory.list()!!.toList())
    }

    @Test
    fun `a store directory that is a link is neither swept through nor written to`() = runTest {
        val elsewhere = File(tmp.root, "elsewhere").apply { mkdirs() }
        val outside = File(elsewhere, "keep.srt").apply { writeBytes(srt) }
        try {
            Files.createSymbolicLink(directory.toPath(), elsewhere.toPath())
        } catch (e: Exception) {
            assumeTrue("symbolic links unsupported here", false)
        }

        assertFalse(store.sweepOrphans())
        try {
            save(fileId = 1, language = "fr")
            fail("expected an IOException")
        } catch (expected: IOException) {
            // expected
        }
        assertEquals(listOf(outside.name), elsewhere.list()!!.toList())
    }

    // ── races with a logout, through the real LogoutCoordinator ───────────────

    /**
     * Holds every IO block until [drain] runs it, so a test decides exactly where a write or a
     * purge is suspended — no timing, no real threads.
     */
    private class ManualDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue += block
        }
    }

    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private val io = ManualDispatcher()
    private val markerStore = FakeLogoutPurgeMarkerStore()

    /** When set, the purge waits on it once the marker is set, i.e. mid-purge. */
    private var midPurge: CompletableDeferred<Unit>? = null

    private val coordinator: LogoutCoordinator = LogoutCoordinator(
        logoutPurger = object : LogoutPurger {
            override suspend fun logOut() {
                markerStore.markPurgePending()
                midPurge?.await()
                gatedStore.purgeDownloadedSubtitles()
                markerStore.clearPurgePending()
            }

            override suspend fun recoverIfNeeded(): Boolean = false
        },
        markerStore = markerStore,
        applicationScope = applicationScope,
    )

    private val gatedStore: OnlineSubtitleFileStore by lazy { OnlineSubtitleFileStore(directory, io, coordinator) }

    /** Runs coroutines and IO blocks alternately until neither has anything left. */
    private fun TestScope.drain() {
        do {
            advanceUntilIdle()
            while (io.queue.isNotEmpty()) io.queue.removeFirst().run()
            advanceUntilIdle()
        } while (io.queue.isNotEmpty())
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    @Test
    fun `a download picked before a completed logout is not written after it`() = runTest(testDispatcher) {
        val generation = coordinator.sessionGeneration // the user picks a subtitle…
        launch { coordinator.logOut() } // …and logs out while it downloads.
        drain()
        assertEquals(LogoutPurgeState.Completed, coordinator.state.value)

        val saved = async { gatedStore.save(1, "fr", srt, generation, visit) }
        drain()

        assertNull(saved.await())
        assertTrue(directory.list().isNullOrEmpty())
        // The next account's downloads are not refused.
        val next = async { gatedStore.save(2, "fr", srt, coordinator.sessionGeneration, visit) }
        drain()
        assertNotNull(next.await())
    }

    @Test
    fun `a download finishing while a purge runs is refused and nothing survives the purge`() = runTest(testDispatcher) {
        midPurge = CompletableDeferred()
        val generation = coordinator.sessionGeneration
        launch { coordinator.logOut() }
        drain()
        assertEquals(LogoutPurgeState.Running, coordinator.state.value)

        val saved = async { gatedStore.save(1, "fr", srt, generation, visit) }
        drain()
        assertNull(saved.await())

        midPurge!!.complete(Unit)
        drain()
        assertEquals(LogoutPurgeState.Completed, coordinator.state.value)
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun `a purge starting while a download is being written waits for it, then deletes it`() = runTest(testDispatcher) {
        val generation = coordinator.sessionGeneration
        val saved = async { gatedStore.save(1, "fr", srt, generation, visit) }
        advanceUntilIdle() // the write holds the gate and sits in the IO queue
        assertEquals(1, io.queue.size)

        launch { coordinator.logOut() }
        advanceUntilIdle()
        assertEquals("the purge must wait for the write", LogoutPurgeState.Idle, coordinator.state.value)

        drain()
        val file = saved.await()
        assertNotNull("the write started in session, so it lands…", file)
        assertEquals(LogoutPurgeState.Completed, coordinator.state.value)
        assertFalse("…and the purge that followed it removes it", file!!.exists())
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun `a write already under way when its visit closes deletes its own file`() = runTest(testDispatcher) {
        val saved = async { gatedStore.save(1, "fr", srt, coordinator.sessionGeneration, visit) }
        advanceUntilIdle() // the write holds the gate and sits in the IO queue
        assertEquals(1, io.queue.size)

        visit.close() // the player is left meanwhile
        drain()

        assertNull(saved.await())
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test
    fun `a download is refused while a failed purge is still owed`() = runTest(testDispatcher) {
        markerStore.markPurgePending()

        val saved = async { gatedStore.save(1, "fr", srt, coordinator.sessionGeneration, visit) }
        drain()

        assertNull(saved.await())
        assertFalse(directory.exists())
    }

    // ── orphans vs. saves and logouts, through the real LogoutCoordinator ─────

    @Test
    fun `a save whose write runs before the startup sweep keeps its file, and the orphans still go`() =
        runTest(testDispatcher) {
            val orphans = plantOrphans()
            val saved = async { gatedStore.save(1, "fr", srt, coordinator.sessionGeneration, visit) }
            advanceUntilIdle() // the write holds the gate and is first in the IO queue…
            val swept = async { gatedStore.sweepOrphans() } // …the startup sweep queues behind it
            advanceUntilIdle()
            assertEquals(2, io.queue.size)

            drain()
            swept.await()

            val file = saved.await()
            assertNotNull(file)
            assertTrue("this process's file survives the late sweep", file!!.exists())
            orphans.forEach { assertFalse(it.name, it.exists()) }
            assertEquals(listOf(file.name), directory.list()!!.toList())
        }

    @Test
    fun `a startup sweep running first leaves the directory clean for the save that follows`() =
        runTest(testDispatcher) {
            val orphans = plantOrphans()
            val swept = async { gatedStore.sweepOrphans() }
            advanceUntilIdle()
            val saved = async { gatedStore.save(1, "fr", srt, coordinator.sessionGeneration, visit) }

            drain()
            swept.await()

            val file = saved.await()!!
            orphans.forEach { assertFalse(it.name, it.exists()) }
            assertEquals(listOf(file.name), directory.list()!!.toList())
        }

    @Test
    fun `while a purge is still owed, saves are refused but the startup sweep still clears the orphans`() =
        runTest(testDispatcher) {
            val orphans = plantOrphans()
            markerStore.markPurgePending()

            val saved = async { gatedStore.save(1, "fr", srt, coordinator.sessionGeneration, visit) }
            val swept = async { gatedStore.sweepOrphans() }
            drain()
            swept.await()

            assertNull(saved.await())
            orphans.forEach { assertFalse(it.name, it.exists()) }
            assertTrue(directory.list().isNullOrEmpty())
        }

    @Test
    fun `a startup sweep racing a logout purge leaves nothing, and the next account can save`() =
        runTest(testDispatcher) {
            plantOrphans()
            midPurge = CompletableDeferred()
            val stale = coordinator.sessionGeneration
            launch { coordinator.logOut() }
            drain()
            assertEquals(LogoutPurgeState.Running, coordinator.state.value)

            val swept = async { gatedStore.sweepOrphans() }
            drain()
            swept.await()
            midPurge!!.complete(Unit)
            drain()

            assertEquals(LogoutPurgeState.Completed, coordinator.state.value)
            assertTrue(directory.list().isNullOrEmpty())
            val refused = async { gatedStore.save(1, "fr", srt, stale, visit) }
            val next = async { gatedStore.save(2, "fr", srt, coordinator.sessionGeneration, OnlineSubtitleVisit()) }
            drain()
            assertNull(refused.await())
            assertEquals(listOf(next.await()!!.name), directory.list()!!.toList())
        }
}
