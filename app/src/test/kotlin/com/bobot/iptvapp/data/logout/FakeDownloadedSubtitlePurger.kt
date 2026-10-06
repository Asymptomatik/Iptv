package com.bobot.iptvapp.data.logout

/**
 * In-memory [DownloadedSubtitlePurger]: [files] stands for the subtitle directory.
 *
 * @property onPurge            called on every purge, before the files go — lets a test journal it.
 * @property resurrectOnPurgeCount when > 0, that many purges leave a file behind, as a download
 *                              finishing right behind the purge would.
 * @property failWith           when set, the purge throws it.
 */
class FakeDownloadedSubtitlePurger(
    vararg initialFiles: String,
    private val onPurge: () -> Unit = {},
) : DownloadedSubtitlePurger {

    val files = initialFiles.toMutableSet()
    var resurrectOnPurgeCount = 0
    var failWith: Throwable? = null

    override suspend fun purgeDownloadedSubtitles() {
        onPurge()
        failWith?.let { throw it }
        files.clear()
        if (resurrectOnPurgeCount > 0) {
            resurrectOnPurgeCount--
            files += "late.srt"
        }
    }

    override suspend fun hasResidue(): Boolean = files.isNotEmpty()
}
