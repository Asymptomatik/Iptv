package com.bobot.iptvapp.download

/** Recording [DownloadCommander] test double. */
class FakeDownloadCommander : DownloadCommander {

    /** Every command issued, as `"verb:downloadId"`, in call order. */
    val commands = mutableListOf<String>()

    override fun enqueue(downloadId: String, streamUrl: String) {
        commands += "enqueue:$downloadId"
    }

    override fun pause(downloadId: String) {
        commands += "pause:$downloadId"
    }

    override fun resume(downloadId: String) {
        commands += "resume:$downloadId"
    }

    override fun remove(downloadId: String) {
        commands += "remove:$downloadId"
    }
}
