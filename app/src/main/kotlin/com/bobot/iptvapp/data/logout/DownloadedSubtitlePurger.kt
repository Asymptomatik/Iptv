package com.bobot.iptvapp.data.logout

/**
 * Deletes the subtitle files downloaded from OpenSubtitles.
 *
 * They hold no Xtream data, but a subtitle fetched for a title records what was watched on this
 * install, so they go with the rest of the account at logout — see
 * [com.bobot.iptvapp.domain.logout.LogoutPurger].
 */
interface DownloadedSubtitlePurger {

    /** Deletes every downloaded subtitle and any leftover partial file. Idempotent. */
    suspend fun purgeDownloadedSubtitles()

    /** Whether any file is left — the purge's residue check. */
    suspend fun hasResidue(): Boolean
}
