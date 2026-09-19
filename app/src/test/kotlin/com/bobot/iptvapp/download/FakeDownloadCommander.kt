package com.bobot.iptvapp.download

/**
 * Recording [DownloadCommander] test double.
 *
 * @param onEnqueue Invoked with the download id every time [enqueue] is called, before recording
 *   it — lets a test wire this straight into a [com.bobot.iptvapp.download.purge.FakeMedia3DownloadIndexGateway]
 *   to model when (or whether) the command actually lands, matching the real
 *   [com.bobot.iptvapp.download.IptvDownloadService.Commander] going through the intent-dispatched
 *   `DownloadService` rather than the [androidx.media3.exoplayer.offline.DownloadManager] directly.
 * @param onRemove Invoked with the download id every time [remove] is called, before recording
 *   it — lets a test model a compensating remove as guaranteed-ordered behind a still-landing add
 *   sent to the same intent-dispatched `DownloadService`.
 */
class FakeDownloadCommander(
    private val onEnqueue: (downloadId: String) -> Unit = {},
    private val onRemove: (downloadId: String) -> Unit = {},
) : DownloadCommander {

    /** Every command issued, as `"verb:downloadId"`, in call order. */
    val commands = mutableListOf<String>()

    override fun enqueue(downloadId: String, streamUrl: String) {
        commands += "enqueue:$downloadId"
        onEnqueue(downloadId)
    }

    override fun pause(downloadId: String) {
        commands += "pause:$downloadId"
    }

    override fun resume(downloadId: String) {
        commands += "resume:$downloadId"
    }

    override fun remove(downloadId: String) {
        commands += "remove:$downloadId"
        onRemove(downloadId)
    }
}
