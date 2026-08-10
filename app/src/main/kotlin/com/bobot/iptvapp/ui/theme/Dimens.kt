package com.bobot.iptvapp.ui.theme

import androidx.compose.ui.unit.dp

// ─── Spacing scale (4 pt grid) ───────────────────────────────────────────────

/**
 * App-wide spacing scale used as padding and gap values in layouts.
 * Acts as the single source of truth — screens and components import from here.
 *
 * Derived from the "Cinematic Glass" design system (styles.css --space-* tokens).
 */
object Spacing {
    /** 4 dp — micro gap, icon padding. Corresponds to --space-1. */
    val xs  =  4.dp
    /** 8 dp — tight gap, inner padding. Corresponds to --space-2. */
    val sm  =  8.dp
    /** 12 dp — compact inner padding. Corresponds to --space-3. */
    val sm2 = 12.dp
    /** 16 dp — standard inner padding. Corresponds to --space-4. */
    val md  = 16.dp
    /** 24 dp — section gap, generous padding. Corresponds to --space-5. */
    val lg  = 24.dp
    /** 32 dp — large section gap. Corresponds to --space-6. */
    val xl  = 32.dp
    /** 48 dp — hero padding, large vertical gap. Corresponds to --space-8. */
    val xxl = 48.dp
    /** 64 dp — full-bleed top/bottom padding. Corresponds to --space-10. */
    val xxxl = 64.dp
}

// ─── Glass effect ─────────────────────────────────────────────────────────────

/**
 * Blur radius used for frosted-glass surfaces.
 * Corresponds to --glass-blur: 22px in the design system.
 * Used by [androidx.compose.ui.graphics.RenderEffect] or blur modifiers.
 */
val GlassBlurRadius = 22.dp

// ─── Card / poster dimensions ────────────────────────────────────────────────

object CardDimens {
    /** Poster card width on phone / tablet. */
    val PosterWidthPhone = 120.dp

    /** Poster card width on Android TV (larger focus/click target). */
    val PosterWidthTv = 180.dp

    /**
     * Standard poster aspect ratio (portrait 2∶3).
     * Used with [androidx.compose.foundation.layout.aspectRatio].
     */
    const val PosterAspectRatio = 2f / 3f

    /** Wide banner / hero card ratio (16∶9). */
    const val BannerAspectRatio = 16f / 9f

    /**
     * Inset applied around logo artwork (channel cards).  Channel logos are wide and are drawn
     * with [androidx.compose.ui.layout.ContentScale.Fit] inside a portrait card, so without an
     * inset they touch the card edges.
     */
    val LogoArtworkPadding = 12.dp

    /**
     * Uniform card corner radius — matches [AppShapes.large] (Cinematic Glass lg = 20 dp).
     * Updated from V1 (8 dp) to V2 "Cinematic Glass" (20 dp).
     */
    val CornerRadius = RadiusLg

    /** Resting card elevation (shadow depth). */
    val CardElevation = 4.dp

    /**
     * Scale factor applied to a card when it receives D-pad focus or pointer hover.
     * Produces the "lift" effect from the Cinematic Glass design system.
     */
    const val FocusedCardScale = 1.06f

    /** Stroke width of the gradient focus ring drawn around a focused card. */
    val FocusBorderWidth = 2.5.dp

    /**
     * Vertical padding applied to the title text overlay at the bottom of a
     * poster card.
     */
    val TitleVerticalPadding = 6.dp
}

// ─── Screen layout dimensions ────────────────────────────────────────────────

object LayoutDimens {
    /** Horizontal content padding on phone. */
    val ContentPaddingPhone = 16.dp

    /**
     * Horizontal content padding on TV.
     * Matches the Android TV content-safe area recommendation (~48 dp).
     */
    val ContentPaddingTv = 48.dp

    /**
     * Height of the floating home top bar (excluding the status-bar inset).
     * Used to reserve clearance at the top of the hero so its title never
     * collides with the "Accueil / Recherche / Reglages" overlay.
     */
    val TopBarHeight = 56.dp

    /**
     * Height reserved for the [androidx.compose.material3.TabRow] docked under the home
     * top bar (Task 1 — home tab navigation). Matches the default M3 Tab height so the
     * hero content and the "no hero" spacer clear the floating header precisely.
     */
    val TabRowHeight = 48.dp

    /** Gap between adjacent poster cards in a horizontal row. */
    val CardRowSpacing = 12.dp

    /** Vertical gap between category section rows. Corresponds to --space-5 (24 dp). */
    val RowSectionSpacing = 24.dp

    /** Space between a section title and the card row below it. */
    val SectionTitleBottomPadding = 8.dp

    /**
     * Extra padding around a LazyRow so that the focus border / scale overflow
     * of a focused card is not clipped by the row's default bounds.
     */
    val LazyRowFocusPadding = 8.dp
}
