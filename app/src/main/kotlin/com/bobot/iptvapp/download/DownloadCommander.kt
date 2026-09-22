package com.bobot.iptvapp.download

/**
 * Commands sent to the Media3 download queue.
 *
 * Extracted from [IptvDownloadService.Commander] so the repository can be unit-tested on the JVM:
 * the concrete commander is a thin wrapper over `DownloadService`'s static senders and needs a real
 * Android `Context`, which the plain-JVM test classpath does not provide.
 */
interface DownloadCommander {
    fun enqueue(downloadId: String, streamUrl: String)
    fun pause(downloadId: String)
    fun resume(downloadId: String)
    fun remove(downloadId: String)
}
