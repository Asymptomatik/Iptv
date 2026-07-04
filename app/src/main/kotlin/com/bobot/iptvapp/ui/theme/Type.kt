package com.bobot.iptvapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Shared TextStyle definitions ────────────────────────────────────────────
// Declared once and referenced by both the Material3 and Compose-for-TV
// Typography instances. Keeps font sizes, weights, and tracking in a single
// location. System default typeface is used — no custom font files in this task.
//
// Scale derived from the "Cinematic Glass" design system (styles.css):
//   display  → 56sp / 700 / lh 60sp  (--fs-display / --fw-display)
//   h1       → 34sp / 700 / lh 40sp  (--fs-h1 / --fw-h1)
//   h2       → 26sp / 650 / lh 32sp  (--fs-h2 / --fw-h2) — mapped as W600 (no W650 in Compose)
//   h3       → 20sp / 600 / lh 26sp  (--fs-h3 / --fw-h3)
//   title    → 16sp / 600 / lh 22sp  (--fs-title / --fw-title)
//   body     → 15sp / 400 / lh 22sp  (--fs-body / --fw-body)
//   label    → 13sp / 600 / lh 18sp  (--fs-label / --fw-label)
//   caption  → 12sp / 500 / lh 16sp  (--fs-caption / --fw-caption)
//
// M3 slot mapping (see AppTypography below):
//   displayLarge   → display (56sp / 700)
//   displayMedium  → h1      (34sp / 700)
//   displaySmall   → h2      (26sp / SemiBold)
//   headlineLarge  → h2      (26sp / SemiBold)
//   headlineMedium → h3      (20sp / SemiBold)
//   headlineSmall  → h3      (20sp / SemiBold)
//   titleLarge     → title   (16sp / SemiBold) — raised from 22sp to match design title role
//   titleMedium    → title   (16sp / SemiBold)
//   titleSmall     → label   (13sp / SemiBold)
//   bodyLarge      → body    (15sp / Normal)
//   bodyMedium     → body    (15sp / Normal)
//   bodySmall      → caption (12sp / Medium)
//   labelLarge     → label   (13sp / SemiBold)
//   labelMedium    → caption (12sp / Medium)
//   labelSmall     → caption (12sp / Medium)

private val DisplayLargeStyle = TextStyle(
    fontWeight    = FontWeight.Bold,
    fontSize      = 56.sp,
    lineHeight    = 60.sp,
    letterSpacing = (-0.02).sp,
)

private val DisplayMediumStyle = TextStyle(
    fontWeight    = FontWeight.Bold,
    fontSize      = 34.sp,
    lineHeight    = 40.sp,
    letterSpacing = (-0.015).sp,
)

private val DisplaySmallStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 26.sp,
    lineHeight    = 32.sp,
    letterSpacing = (-0.01).sp,
)

private val HeadlineLargeStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 26.sp,
    lineHeight    = 32.sp,
    letterSpacing = (-0.01).sp,
)

private val HeadlineMediumStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 20.sp,
    lineHeight    = 26.sp,
    letterSpacing = 0.sp,
)

private val HeadlineSmallStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 20.sp,
    lineHeight    = 26.sp,
    letterSpacing = 0.sp,
)

private val TitleLargeStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 16.sp,
    lineHeight    = 22.sp,
    letterSpacing = 0.sp,
)

private val TitleMediumStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 16.sp,
    lineHeight    = 22.sp,
    letterSpacing = 0.sp,
)

private val TitleSmallStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 13.sp,
    lineHeight    = 18.sp,
    letterSpacing = 0.sp,
)

private val BodyLargeStyle = TextStyle(
    fontWeight    = FontWeight.Normal,
    fontSize      = 15.sp,
    lineHeight    = 22.sp,
    letterSpacing = 0.sp,
)

private val BodyMediumStyle = TextStyle(
    fontWeight    = FontWeight.Normal,
    fontSize      = 15.sp,
    lineHeight    = 22.sp,
    letterSpacing = 0.sp,
)

private val BodySmallStyle = TextStyle(
    fontWeight    = FontWeight.Medium,
    fontSize      = 12.sp,
    lineHeight    = 16.sp,
    letterSpacing = 0.sp,
)

private val LabelLargeStyle = TextStyle(
    fontWeight    = FontWeight.SemiBold,
    fontSize      = 13.sp,
    lineHeight    = 18.sp,
    letterSpacing = 0.sp,
)

private val LabelMediumStyle = TextStyle(
    fontWeight    = FontWeight.Medium,
    fontSize      = 12.sp,
    lineHeight    = 16.sp,
    letterSpacing = 0.sp,
)

private val LabelSmallStyle = TextStyle(
    fontWeight    = FontWeight.Medium,
    fontSize      = 12.sp,
    lineHeight    = 16.sp,
    letterSpacing = 0.sp,
)

// ─── Material3 Typography (phone) ────────────────────────────────────────────

/**
 * Typography scale for the phone form factor.
 * Consumed by [IptvAppTheme] via [androidx.compose.material3.MaterialTheme].
 */
val AppTypography = Typography(
    displayLarge  = DisplayLargeStyle,
    displayMedium = DisplayMediumStyle,
    displaySmall  = DisplaySmallStyle,
    headlineLarge  = HeadlineLargeStyle,
    headlineMedium = HeadlineMediumStyle,
    headlineSmall  = HeadlineSmallStyle,
    titleLarge  = TitleLargeStyle,
    titleMedium = TitleMediumStyle,
    titleSmall  = TitleSmallStyle,
    bodyLarge  = BodyLargeStyle,
    bodyMedium = BodyMediumStyle,
    bodySmall  = BodySmallStyle,
    labelLarge  = LabelLargeStyle,
    labelMedium = LabelMediumStyle,
    labelSmall  = LabelSmallStyle,
)

// ─── Compose for TV Typography (TV) ──────────────────────────────────────────

/**
 * Typography scale for the Android TV form factor.
 *
 * [androidx.tv.material3.Typography] is a distinct type from M3's [Typography],
 * but it uses the same slot names. We reuse the identical [TextStyle] objects
 * defined above so both form factors share a consistent visual rhythm.
 *
 * TV text is typically read from ~3 m away. The "Cinematic Glass" scale uses
 * generous sizes and high font weights which aid legibility at distance.
 * A future iteration can supply a display-optimised font (e.g. Inter).
 *
 * Consumed by [IptvAppTvTheme] via [androidx.tv.material3.MaterialTheme].
 */
val AppTvTypography = androidx.tv.material3.Typography(
    displayLarge  = DisplayLargeStyle,
    displayMedium = DisplayMediumStyle,
    displaySmall  = DisplaySmallStyle,
    headlineLarge  = HeadlineLargeStyle,
    headlineMedium = HeadlineMediumStyle,
    headlineSmall  = HeadlineSmallStyle,
    titleLarge  = TitleLargeStyle,
    titleMedium = TitleMediumStyle,
    titleSmall  = TitleSmallStyle,
    bodyLarge  = BodyLargeStyle,
    bodyMedium = BodyMediumStyle,
    bodySmall  = BodySmallStyle,
    labelLarge  = LabelLargeStyle,
    labelMedium = LabelMediumStyle,
    labelSmall  = LabelSmallStyle,
)
