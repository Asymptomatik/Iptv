package com.bobot.iptvapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStateTest {

    @Test
    fun `download state exposes a user-facing French label for each persisted state`() {
        assertEquals("À télécharger", DownloadState.NOT_DOWNLOADED.label)
        assertEquals("En attente", DownloadState.QUEUED.label)
        assertEquals("Téléchargement…", DownloadState.DOWNLOADING.label)
        assertEquals("En pause", DownloadState.PAUSED.label)
        assertEquals("Téléchargé", DownloadState.COMPLETED.label)
        assertEquals("Erreur", DownloadState.FAILED.label)
    }
}
