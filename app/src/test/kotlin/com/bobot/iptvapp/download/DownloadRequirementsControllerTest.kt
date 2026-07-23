package com.bobot.iptvapp.download

import androidx.media3.exoplayer.scheduler.Requirements
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DownloadRequirementsController.requirementsFor].
 *
 * The mapping function is a pure JVM-testable function that converts a Boolean Wi-Fi-only
 * preference into the corresponding Media3 [Requirements] network flag applied to the
 * [androidx.media3.exoplayer.offline.DownloadManager].
 *
 * Covers:
 * - requirementsFor(true) → Requirements(NETWORK_UNMETERED)
 * - requirementsFor(false) → Requirements(NETWORK)
 */
class DownloadRequirementsControllerTest {

    @Test
    fun `requirementsFor true returns Requirements with NETWORK_UNMETERED`() {
        val requirements = DownloadRequirementsController.requirementsFor(true)
        val expected = Requirements(Requirements.NETWORK_UNMETERED)

        assertEquals(expected, requirements)
    }

    @Test
    fun `requirementsFor false returns Requirements with NETWORK`() {
        val requirements = DownloadRequirementsController.requirementsFor(false)
        val expected = Requirements(Requirements.NETWORK)

        assertEquals(expected, requirements)
    }

    @Test
    fun `requirementsFor consistently maps true to NETWORK_UNMETERED`() {
        val r1 = DownloadRequirementsController.requirementsFor(true)
        val r2 = DownloadRequirementsController.requirementsFor(true)

        assertEquals(r1, r2)
    }

    @Test
    fun `requirementsFor consistently maps false to NETWORK`() {
        val r1 = DownloadRequirementsController.requirementsFor(false)
        val r2 = DownloadRequirementsController.requirementsFor(false)

        assertEquals(r1, r2)
    }
}
