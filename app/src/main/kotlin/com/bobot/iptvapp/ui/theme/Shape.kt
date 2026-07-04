package com.bobot.iptvapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App-wide shape scale — single source of truth for corner radii.
 *
 * Radius values are derived from the "Cinematic Glass" design system (styles.css):
 *  - extraSmall (sm)  → 10 dp  — chips, badges, input fields
 *  - small      (sm)  → 10 dp  — compact cards, snackbars, tooltips
 *  - medium     (md)  → 16 dp  — poster cards, list tiles, text fields
 *  - large      (lg)  → 20 dp  — full-width poster cards, bottom sheets, navigation drawers
 *  - extraLarge (xl)  → 28 dp  — modal dialogs, hero cards, large overlays
 *
 * Consumed by:
 *  - [IptvAppTheme] via [androidx.compose.material3.MaterialTheme] (phone).
 *  - [IptvAppTvTheme] via the outer [androidx.compose.material3.MaterialTheme] wrapper
 *    (TV shapes come from the M3 composition local since the TV MaterialTheme does not
 *    expose a separate shape parameter).
 *
 * Individual radius constants are also exposed below for direct use in components
 * that draw shapes manually (e.g. gradient borders, pill-shaped focus rings).
 */
val AppShapes = Shapes(
    // Chips, badges, input fields  (--radius-sm: 10px)
    extraSmall = RoundedCornerShape(10.dp),
    // Compact cards, snackbars, tooltips  (--radius-sm: 10px)
    small      = RoundedCornerShape(10.dp),
    // Poster cards, list tiles  (--radius-md: 16px)
    medium     = RoundedCornerShape(16.dp),
    // Full-width cards, bottom sheets  (--radius-lg: 20px)
    large      = RoundedCornerShape(20.dp),
    // Modal dialogs, hero cards, sheets  (--radius-xl: 28px)
    extraLarge = RoundedCornerShape(28.dp),
)

// ─── Named radius constants ───────────────────────────────────────────────────
// Exposed for components that apply radii directly (e.g. Modifier.clip, Canvas).

/** 10 dp — chips, badges, input fields. Corresponds to --radius-sm. */
val RadiusSm = 10.dp

/** 16 dp — standard card radius. Corresponds to --radius-md. */
val RadiusMd = 16.dp

/** 20 dp — poster card radius. Corresponds to --radius-lg. */
val RadiusLg = 20.dp

/** 28 dp — hero / sheet radius. Corresponds to --radius-xl. */
val RadiusXl = 28.dp

/** Full pill radius — used for buttons, chips, tab bars. */
val RadiusPill = 999.dp
