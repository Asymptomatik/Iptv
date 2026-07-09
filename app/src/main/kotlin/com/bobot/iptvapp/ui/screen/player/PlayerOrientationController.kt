package com.bobot.iptvapp.ui.screen.player

import android.content.pm.ActivityInfo

/**
 * Conceptual orientation states the player can request from the hosting Activity.
 *
 * This is the framework-free decision logic for the player's auto-landscape feature (approved
 * brief: "Auto-landscape at PlayerScreen open on phone; binary toggle button landscape<->
 * portrait-locked in controls left zone; restore system orientation on exit; neutralized on
 * Android TV and tablet"), mirroring the pattern established by
 * [com.bobot.iptvapp.player.StreamTypeResolver]: every function in this file has no Android
 * import and is fully JVM-testable ([PlayerOrientationControllerTest]), except
 * [toActivityOrientation] which touches `android.content.pm.ActivityInfo` and is isolated on
 * purpose so the rest of the logic stays framework-free.
 *
 * Consumed by `PlayerScreen`, which calls [shouldManageOrientation] to decide whether to touch
 * the Activity's orientation at all, drives a `portraitLocked` boolean toggled by the controls'
 * orientation button via [nextPortraitLocked] / [toOrientationMode], and applies the result to
 * the hosting Activity via [toActivityOrientation], restoring [OrientationMode.SYSTEM] on exit.
 *
 * These states are intentionally coarser than the full `ActivityInfo.SCREEN_ORIENTATION_*`
 * surface — only the three states the player actually needs are represented, keeping the binary
 * toggle (see [nextPortraitLocked]) simple and exhaustive.
 */
enum class OrientationMode {

    /**
     * Auto-landscape: both landscape senses (normal and reverse) are allowed, following the
     * device's sensor; portrait is blocked. This is the state the player opens in on phones
     * (see [shouldManageOrientation]).
     */
    SENSOR_LANDSCAPE,

    /** Locked portrait, entered when the user taps the orientation toggle button once. */
    PORTRAIT,

    /**
     * Restores the Activity's system/manifest-default orientation. Used when the player screen
     * is exited, so orientation control does not leak into the rest of the app.
     */
    SYSTEM,
}

/**
 * `true` when the player should actively manage the Activity's screen orientation.
 *
 * Per the approved brief, auto-landscape is neutralized on:
 * - Android TV (`isTv`, detected upstream via `PackageManager.FEATURE_LEANBACK`, consistent with
 *   [com.bobot.iptvapp.MainActivity] — TV devices are already landscape-only, so orientation
 *   management is meaningless there);
 * - tablets (`smallestScreenWidthDp >= 600`, the breakpoint recommended by the Material Design
 *   guidelines for the phone/tablet split) — tablets are comfortable in portrait and should not
 *   be forced into landscape.
 */
fun shouldManageOrientation(isTv: Boolean, smallestScreenWidthDp: Int): Boolean {
    return !isTv && smallestScreenWidthDp < 600
}

/**
 * Maps the current `portraitLocked` toggle state to its [OrientationMode].
 *
 * Binary model: the player opens in auto-landscape ([OrientationMode.SENSOR_LANDSCAPE]); the
 * orientation toggle button flips a single boolean between that and
 * [OrientationMode.PORTRAIT]. `false` (the initial/open state) maps to auto-landscape, `true`
 * maps to locked portrait.
 */
fun toOrientationMode(portraitLocked: Boolean): OrientationMode {
    return if (portraitLocked) OrientationMode.PORTRAIT else OrientationMode.SENSOR_LANDSCAPE
}

/**
 * Computes the next `portraitLocked` state when the user taps the orientation toggle button.
 *
 * Pure boolean flip — kept as its own named function (rather than inlining `!portraitLocked` at
 * every call site) so the toggle's intent is explicit and independently testable.
 */
fun nextPortraitLocked(currentPortraitLocked: Boolean): Boolean {
    return !currentPortraitLocked
}

/**
 * Maps [OrientationMode] to the Android `ActivityInfo.SCREEN_ORIENTATION_*` int constant it
 * corresponds to.
 *
 * This is the ONLY place in the orientation feature that imports `android.content.pm.ActivityInfo`
 * — every other function in this file is plain Kotlin so it can run under plain JUnit on the JVM
 * (no Robolectric). Callers apply the returned int to
 * `Activity.requestedOrientation` at the Activity boundary.
 */
fun OrientationMode.toActivityOrientation(): Int {
    return when (this) {
        OrientationMode.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        OrientationMode.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
