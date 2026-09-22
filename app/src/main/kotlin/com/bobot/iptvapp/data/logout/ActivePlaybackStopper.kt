package com.bobot.iptvapp.data.logout

/**
 * Stops whatever playback is active, so its cache writer stops producing before the storage purge
 * scans the Media3 cache it shares with the player for account residue.
 *
 * Without this, [com.bobot.iptvapp.download.purge.Media3DownloadStoragePurger.evictCachedResources]'s
 * bounded wait is racing a producer nothing ever tells to stop: a player screen left open through
 * logout keeps writing to the same live cache instance the purge is trying to empty, for as long as
 * playback continues. Stopping it *before* the storage purge runs turns that race into an ordering —
 * see [DefaultLogoutPurger.purgeEveryStore].
 */
interface ActivePlaybackStopper {

    /** Idempotent, and never throws by contract — mirrors [SessionCacheInvalidator]. */
    suspend fun stopActivePlayback()
}
