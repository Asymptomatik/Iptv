package com.bobot.iptvapp.download.purge

/**
 * Minimal seam over the live Media3 [androidx.media3.exoplayer.offline.DownloadManager] and its
 * download index.
 *
 * Media3's own types cannot be driven on the plain-JVM unit test classpath this project uses
 * (`isReturnDefaultValues = true`, no Robolectric), so the *decision* logic — remove everything,
 * then keep checking until the index is observably empty — lives in [Media3DownloadStoragePurger]
 * behind this two-method interface, and only the thin adapter that calls Media3 is left untested.
 */
interface Media3DownloadIndexGateway {

    /**
     * Asks Media3 to remove every download it knows about, **including the one currently
     * transferring**. Returns as soon as the request is registered — completion is not implied,
     * which is exactly why [countIndexedDownloads] exists.
     */
    suspend fun removeAllDownloads()

    /**
     * Number of entries left in the live download index, whatever their state.
     *
     * This is the index itself, not the in-memory list of *current* (non-terminal) downloads: a
     * completed download is gone from the latter while still holding its index row and its bytes.
     */
    suspend fun countIndexedDownloads(): Int
}

/**
 * Minimal seam over the live Media3 [androidx.media3.datasource.cache.Cache] singleton.
 *
 * Deliberately exposes *per-resource* eviction and no directory-level operation. Deleting
 * `filesDir/downloads` from underneath a live `SimpleCache` corrupts the instance's in-memory index
 * — the singleton keeps serving keys whose files no longer exist, and the next `SimpleCache` built
 * on that directory can refuse to open it. Going through the live instance is the only safe way to
 * release the bytes while the app is running, so the unsafe option is simply not offered here.
 */
interface Media3CacheGateway {

    /** Keys currently held by the cache. */
    suspend fun cachedKeys(): Set<String>

    /** Releases every span held for [key] through the live cache instance. */
    suspend fun removeResource(key: String)
}
