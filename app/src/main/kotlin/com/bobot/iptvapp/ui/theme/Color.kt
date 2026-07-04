package com.bobot.iptvapp.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ─── Backgrounds ─────────────────────────────────────────────────────────────

/** Deep violet-tinted near-black — primary app background. Corresponds to --bg-base. */
val BackgroundBase = Color(0xFF0A0A0F)

/** Raised background band — elevated sections and panels. Corresponds to --bg-elevated. */
val BackgroundElevated = Color(0xFF12121B)

/** Scrim / modal base — the darkest layer. Corresponds to --bg-sunken. */
val BackgroundSunken = Color(0xFF050507)

// ─── Glass surfaces (frosted) ─────────────────────────────────────────────────

/** Glass fill — subtle white-tinted translucent surface. Corresponds to --glass-fill. */
val GlassFill = Color(0x0EFFFFFF) // rgba(255,255,255,0.055) → ~0x0E

/** Strong glass fill — slightly more opaque translucent surface. Corresponds to --glass-fill-strong. */
val GlassFillStrong = Color(0x17FFFFFF) // rgba(255,255,255,0.09) → ~0x17

/** Glass border — subtle white outline. Corresponds to --glass-border. */
val GlassBorder = Color(0x1AFFFFFF) // rgba(255,255,255,0.10) → 0x1A

/** Strong glass border — more visible outline for emphasis. Corresponds to --glass-border-strong. */
val GlassBorderStrong = Color(0x2EFFFFFF) // rgba(255,255,255,0.18) → ~0x2E

// ─── Accent — violet → cyan gradient ─────────────────────────────────────────

/** Violet endpoint of the accent gradient. Corresponds to --accent-violet. */
val AccentViolet = Color(0xFF8B5CF6)

/** Cyan endpoint of the accent gradient. Corresponds to --accent-cyan. */
val AccentCyan = Color(0xFF22D3EE)

/** Mid-point blue of the accent gradient (used in gradient stops). */
val AccentBlue = Color(0xFF6D8BFF)

/**
 * Single-colour accent — focus ring, active dots, M3 [primary] colour role.
 * Corresponds to --accent-solid.
 */
val AccentSolid = Color(0xFF7C6BF5)

/**
 * Accent glow — semi-transparent violet used as shadow/glow colour for buttons and focus rings.
 * Corresponds to --accent-glow: rgba(124,107,245,0.45).
 */
val AccentGlow = Color(0x73_7C6BF5) // 0x73 ≈ 0.45 * 255

/**
 * Violet→cyan linear gradient brush (120°) for buttons, active chips, nav items, progress bars.
 * Corresponds to --accent-gradient: linear-gradient(120deg, #8B5CF6 0%, #6D8BFF 45%, #22D3EE 100%).
 *
 * NOTE: This is a [Brush] helper for direct use in Compose draw calls and brush parameters.
 * It is intentionally NOT placed in the M3 [ColorScheme] — use [AccentSolid] for that role.
 */
val AccentGradient: Brush = Brush.linearGradient(
    colorStops = arrayOf(
        0.00f to AccentViolet,
        0.45f to AccentBlue,
        1.00f to AccentCyan,
    ),
)

// ─── Text ────────────────────────────────────────────────────────────────────

/** Primary text — near-white for headings and important labels. Corresponds to --text-primary. */
val TextPrimary = Color(0xFFF5F5FA)

/** Secondary text — muted lavender-grey for metadata and supporting labels. Corresponds to --text-secondary. */
val TextSecondary = Color(0xFFA6A6BC)

/** Dimmed text — for hints, captions, and disabled text. Corresponds to --text-dimmed. */
val TextDimmed = Color(0xFF6C6C82)

/** On-accent text — dark background colour used on gradient/solid accent backgrounds. Corresponds to --text-on-accent. */
val TextOnAccent = Color(0xFF0A0A0F)

// ─── Semantic ────────────────────────────────────────────────────────────────

/** Success indicator — green. Corresponds to --success. */
val SemanticSuccess = Color(0xFF34D399)

/** Error indicator — soft red/pink. Corresponds to --error. */
val SemanticError = Color(0xFFFB7185)

/** Warning indicator — amber. Corresponds to --warning. */
val SemanticWarning = Color(0xFFFBBF24)

/** Live badge colour — vivid red-pink for LIVE stream indicators. Corresponds to --live. */
val SemanticLive = Color(0xFFFF4D6D)

// ─── Disabled ─────────────────────────────────────────────────────────────────

/** Disabled controls, dividers, and outlines — uses glass border value for coherence. */
val DisabledSurface = GlassBorder
