package com.bobot.iptvapp.data.remote.opensubtitles

import com.bobot.iptvapp.data.logout.DownloadedSubtitlePurger
import com.bobot.iptvapp.data.logout.SessionWriteGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/**
 * The app-private directory holding the subtitles downloaded from OpenSubtitles.
 *
 * A file is written under a temporary `.part` name, then moved over its final name, so a reader
 * never sees half a subtitle. The final name is built from the file id, a sanitised language and a
 * random token only — never from provider text — so it cannot leave [directory], and no two saves
 * ever share a file.
 *
 * A process that dies mid-visit leaves its `.part` and its finished `.srt` behind, since
 * [OnlineSubtitleVisit.close] never runs. The store is a process-wide singleton and the only
 * writer here, so whatever [directory] holds before its first write of the process belongs to a
 * dead one: [sweepOrphans] lists it at startup or before that first write, whichever comes first,
 * then deletes exactly those names — never a file of this process — retrying any it could not
 * delete at every later sweep or save.
 *
 * [listEntries] and [deleteEntry] are the sweep's only file-system calls, a seam for tests: a
 * permission-based failure cannot be staged reliably on every host.
 */
class OnlineSubtitleFileStore internal constructor(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionGate: SessionWriteGate,
    private val listEntries: (File) -> Array<File>?,
    private val deleteEntry: (File) -> Boolean,
) : DownloadedSubtitlePurger {

    constructor(directory: File, ioDispatcher: CoroutineDispatcher, sessionGate: SessionWriteGate) :
        this(directory, ioDispatcher, sessionGate, { it.listFiles() }, { it.delete() })

    private val sweepLock = Any()

    /** The names of the dead process's files still to delete; `null` until [directory] is listed. */
    private var orphans: MutableSet<String>? = null

    /**
     * Writes [content] as `<fileId>.<language>.<token>.srt`, owned by [visit] — or leaves nothing
     * and answers `null` when [sessionGate] refuses [sessionGeneration] or [visit] is closed.
     *
     * The whole write, rename included, runs inside the gate, so a logout purge either precedes it
     * (refused) or follows it (and deletes the file); it can never interleave with it. The file is
     * handed to [visit] in the same IO block as the rename, before anything can cancel the caller:
     * either the visit takes it and deletes it on [OnlineSubtitleVisit.close], or it is already
     * closed and the file goes at once — a pick cancelled mid-write cannot orphan it.
     */
    suspend fun save(
        fileId: Long,
        language: String?,
        content: ByteArray,
        sessionGeneration: Int,
        visit: OnlineSubtitleVisit,
    ): File? {
        var kept: File? = null
        sessionGate.runInSession(sessionGeneration) { kept = write(fileId, language, content, visit) }
        return kept
    }

    private suspend fun write(fileId: Long, language: String?, content: ByteArray, visit: OnlineSubtitleVisit): File? =
        withContext(ioDispatcher) {
            sweepOrphansOnce() // an orphan it could not delete is retried next time
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("cannot create the subtitle directory")
            }
            val target = File(directory, "$fileId.${sanitise(language)}.${UUID.randomUUID()}.srt")
            val temp = File.createTempFile("subtitle", PARTIAL_SUFFIX, directory)
            try {
                temp.writeBytes(content)
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), target.toPath())
                }
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
            if (visit.adopt(target)) {
                target
            } else {
                target.delete()
                null
            }
        }

    /**
     * Deletes the files a previous process left in [directory], and answers whether none is left.
     * `false` — the directory could not be listed, or a file could not be deleted — is retried by
     * the next sweep or save. Needs no session gate: it only deletes, and only what no visit of
     * this process can own.
     */
    suspend fun sweepOrphans(): Boolean = withContext(ioDispatcher) {
        try {
            sweepOrphansOnce()
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Holds [sweepLock] through the whole sweep, so a write arriving meanwhile waits for it to end
     * rather than having its file swept. Only the non-directory entries directly in [directory] go,
     * and nothing is recursed into, so the sweep cannot reach outside it.
     *
     * Throws when [directory] cannot be listed: the write calling it must then fail too, since a
     * file of this process written before the listing would pass for an orphan.
     */
    private fun sweepOrphansOnce(): Boolean = synchronized(sweepLock) {
        val pending = orphans ?: listOrphans().also { orphans = it }
        // A file some purge deleted meanwhile is gone all the same.
        pending.removeAll { name -> File(directory, name).let { deleteEntry(it) || !it.exists() } }
        pending.isEmpty()
    }

    private fun listOrphans(): MutableSet<String> {
        if (!directory.isDirectory) return mutableSetOf()
        // The store is app-private and never a link; if it ever is one, touch nothing through it.
        val absolute = directory.absoluteFile
        if (absolute.canonicalFile != File(absolute.parentFile?.canonicalFile, absolute.name)) {
            throw IOException("the subtitle directory is a link")
        }
        val entries = listEntries(directory) ?: throw IOException("cannot list the subtitle directory")
        return entries.filterNot { it.isDirectory }.mapTo(mutableSetOf()) { it.name }
    }

    override suspend fun purgeDownloadedSubtitles(): Unit = withContext(ioDispatcher) {
        // Best effort: whatever survives is reported by hasResidue, which makes the purge resume.
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }

    override suspend fun hasResidue(): Boolean = withContext(ioDispatcher) {
        !directory.listFiles().isNullOrEmpty()
    }

    private fun sanitise(language: String?): String =
        language.orEmpty()
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            .take(MAX_LANGUAGE_LENGTH)
            .ifEmpty { "und" }

    private companion object {
        const val PARTIAL_SUFFIX = ".part"
        const val MAX_LANGUAGE_LENGTH = 16
    }
}
