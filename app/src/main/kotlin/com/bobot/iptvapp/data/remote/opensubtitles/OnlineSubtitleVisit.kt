package com.bobot.iptvapp.data.remote.opensubtitles

import java.io.File

/**
 * The subtitle files one visit of the player downloaded — see [OnlineSubtitleFileStore.save].
 *
 * [close] deletes them when the player is left. A save still finishing after that (a download
 * cannot be interrupted mid-write) is refused by [adopt] and deletes its own file, so nothing
 * outlives the visit. Every save gets a file of its own, so closing a visit never touches another
 * player's or another account's file, even for the same OpenSubtitles file id.
 */
class OnlineSubtitleVisit {

    private val files = mutableListOf<File>()
    private var closed = false

    /** Takes [file] into this visit — `false` once it is closed, and the caller deletes it. */
    internal fun adopt(file: File): Boolean = synchronized(this) {
        if (!closed) files += file
        !closed
    }

    /**
     * Deletes this visit's files and refuses any later one. Idempotent, and never waits on a write
     * under way. Best effort: a file that cannot be deleted is left to the logout purge and to the
     * system reclaiming the cache.
     */
    fun close() {
        val owned = synchronized(this) {
            closed = true
            files.toList().also { files.clear() }
        }
        owned.forEach { it.delete() }
    }
}
