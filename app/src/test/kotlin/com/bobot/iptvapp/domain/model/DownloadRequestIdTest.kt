package com.bobot.iptvapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRequestIdTest {

    @Test
    fun `download request id distinguishes a movie from an episode with the same source id`() {
        assertEquals("MOVIE:42", DownloadRequestId.create(DownloadContentType.MOVIE, "42"))
        assertEquals("EPISODE:42", DownloadRequestId.create(DownloadContentType.EPISODE, "42"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `download request id rejects a blank source id`() {
        DownloadRequestId.create(DownloadContentType.MOVIE, "   ")
    }
}
