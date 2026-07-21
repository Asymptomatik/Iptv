package com.bobot.iptvapp.download

import androidx.media3.exoplayer.offline.Download
import com.bobot.iptvapp.domain.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3DownloadStateMapperTest {

    @Test
    fun `maps Media3 lifecycle states to persistent UI states`() {
        assertEquals(DownloadState.QUEUED, DownloadStateMapper.fromMedia3(Download.STATE_QUEUED))
        assertEquals(DownloadState.DOWNLOADING, DownloadStateMapper.fromMedia3(Download.STATE_DOWNLOADING))
        assertEquals(DownloadState.PAUSED, DownloadStateMapper.fromMedia3(Download.STATE_STOPPED))
        assertEquals(DownloadState.COMPLETED, DownloadStateMapper.fromMedia3(Download.STATE_COMPLETED))
        assertEquals(DownloadState.FAILED, DownloadStateMapper.fromMedia3(Download.STATE_FAILED))
    }
}
