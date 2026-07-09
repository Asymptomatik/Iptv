package com.bobot.iptvapp.ui.screen.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOrientationControllerTest {

    // ── shouldManageOrientation truth table ─────────────────────────────────

    @Test
    fun `shouldManageOrientation is true for phone below the tablet breakpoint`() {
        val result = shouldManageOrientation(isTv = false, smallestScreenWidthDp = 360)

        assertTrue(result)
    }

    @Test
    fun `shouldManageOrientation is false for phone at exactly the tablet breakpoint`() {
        val result = shouldManageOrientation(isTv = false, smallestScreenWidthDp = 600)

        assertFalse(result)
    }

    @Test
    fun `shouldManageOrientation is false for phone above the tablet breakpoint`() {
        val result = shouldManageOrientation(isTv = false, smallestScreenWidthDp = 720)

        assertFalse(result)
    }

    @Test
    fun `shouldManageOrientation is false for TV below the tablet breakpoint`() {
        val result = shouldManageOrientation(isTv = true, smallestScreenWidthDp = 360)

        assertFalse(result)
    }

    @Test
    fun `shouldManageOrientation is false for TV at exactly the tablet breakpoint`() {
        val result = shouldManageOrientation(isTv = true, smallestScreenWidthDp = 600)

        assertFalse(result)
    }

    @Test
    fun `shouldManageOrientation is false for TV above the tablet breakpoint`() {
        val result = shouldManageOrientation(isTv = true, smallestScreenWidthDp = 1280)

        assertFalse(result)
    }

    // ── toOrientationMode mapping ────────────────────────────────────────────

    @Test
    fun `toOrientationMode maps portraitLocked false to open state SENSOR_LANDSCAPE`() {
        val result = toOrientationMode(portraitLocked = false)

        assertEquals(OrientationMode.SENSOR_LANDSCAPE, result)
    }

    @Test
    fun `toOrientationMode maps portraitLocked true to PORTRAIT`() {
        val result = toOrientationMode(portraitLocked = true)

        assertEquals(OrientationMode.PORTRAIT, result)
    }

    // ── nextPortraitLocked toggle logic ─────────────────────────────────────

    @Test
    fun `first toggle from the open state locks portrait`() {
        val openState = false

        val afterFirstToggle = nextPortraitLocked(openState)

        assertTrue(afterFirstToggle)
        assertEquals(OrientationMode.PORTRAIT, toOrientationMode(afterFirstToggle))
    }

    @Test
    fun `second toggle returns to auto landscape`() {
        val openState = false
        val afterFirstToggle = nextPortraitLocked(openState)

        val afterSecondToggle = nextPortraitLocked(afterFirstToggle)

        assertFalse(afterSecondToggle)
        assertEquals(OrientationMode.SENSOR_LANDSCAPE, toOrientationMode(afterSecondToggle))
    }

    @Test
    fun `toggle is its own inverse`() {
        val locked = true

        val result = nextPortraitLocked(nextPortraitLocked(locked))

        assertEquals(locked, result)
    }

    // ── toActivityOrientation mapping ────────────────────────────────────────
    // Constant-only usage of android.content.pm.ActivityInfo — safe on plain JVM, no Robolectric.

    @Test
    fun `toActivityOrientation maps SENSOR_LANDSCAPE to SCREEN_ORIENTATION_SENSOR_LANDSCAPE`() {
        val result = OrientationMode.SENSOR_LANDSCAPE.toActivityOrientation()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, result)
    }

    @Test
    fun `toActivityOrientation maps PORTRAIT to SCREEN_ORIENTATION_PORTRAIT`() {
        val result = OrientationMode.PORTRAIT.toActivityOrientation()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, result)
    }

    @Test
    fun `toActivityOrientation maps SYSTEM to SCREEN_ORIENTATION_UNSPECIFIED`() {
        val result = OrientationMode.SYSTEM.toActivityOrientation()

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, result)
    }
}
