package com.bobot.iptvapp.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

// ─── Material3 colour scheme (phone) ─────────────────────────────────────────

/**
 * Dark [androidx.compose.material3.ColorScheme] mapping the "Cinematic Glass" palette
 * defined in [Color.kt] to M3 semantic colour roles.
 *
 * Design direction: near-black #0A0A0F background, frosted-glass surfaces,
 * violet→cyan gradient accent (via [AccentGradient] Brush), [AccentSolid] as the
 * single M3 [primary] colour token.
 *
 * No light scheme is defined — the app is dark-only by design.
 */
private val AppDarkColorScheme = darkColorScheme(
    primary              = AccentSolid,
    onPrimary            = TextOnAccent,
    primaryContainer     = AccentViolet,
    onPrimaryContainer   = TextOnAccent,
    inversePrimary       = AccentCyan,
    secondary            = TextSecondary,
    onSecondary          = BackgroundBase,
    secondaryContainer   = BackgroundElevated,
    onSecondaryContainer = TextPrimary,
    tertiary             = TextDimmed,
    onTertiary           = BackgroundBase,
    tertiaryContainer    = GlassFillStrong,
    onTertiaryContainer  = TextPrimary,
    background           = BackgroundBase,
    onBackground         = TextPrimary,
    surface              = BackgroundElevated,
    onSurface            = TextPrimary,
    surfaceVariant       = GlassFillStrong,
    onSurfaceVariant     = TextSecondary,
    inverseSurface       = TextPrimary,
    inverseOnSurface     = BackgroundBase,
    error                = SemanticError,
    onError              = BackgroundSunken,
    errorContainer       = SemanticError,
    onErrorContainer     = TextPrimary,
    outline              = GlassBorder,
    outlineVariant       = GlassBorderStrong,
    scrim                = BackgroundSunken,
)

// ─── Compose for TV colour scheme ────────────────────────────────────────────

/**
 * Dark [androidx.tv.material3.ColorScheme] built from the same "Cinematic Glass" palette.
 *
 * The TV colour scheme type is DISTINCT from M3's — it lives in
 * [androidx.tv.material3] and has its own composition local. Both schemes are
 * provided simultaneously inside [IptvAppTvTheme] so that TV components and any
 * shared M3 helpers each resolve the correct tokens.
 *
 * TV-specific colour roles ([border], [borderVariant]) replace M3's
 * [outline] / [outlineVariant] in the TV scheme.
 */
private val AppTvDarkColorScheme = tvDarkColorScheme(
    primary              = AccentSolid,
    onPrimary            = TextOnAccent,
    primaryContainer     = AccentViolet,
    onPrimaryContainer   = TextOnAccent,
    inversePrimary       = AccentCyan,
    secondary            = TextSecondary,
    onSecondary          = BackgroundBase,
    secondaryContainer   = BackgroundElevated,
    onSecondaryContainer = TextPrimary,
    tertiary             = TextDimmed,
    onTertiary           = BackgroundBase,
    tertiaryContainer    = GlassFillStrong,
    onTertiaryContainer  = TextPrimary,
    background           = BackgroundBase,
    onBackground         = TextPrimary,
    surface              = BackgroundElevated,
    onSurface            = TextPrimary,
    surfaceVariant       = GlassFillStrong,
    onSurfaceVariant     = TextSecondary,
    inverseSurface       = TextPrimary,
    inverseOnSurface     = BackgroundBase,
    error                = SemanticError,
    onError              = BackgroundSunken,
    errorContainer       = SemanticError,
    onErrorContainer     = TextPrimary,
    scrim                = BackgroundSunken,
    border               = GlassBorder,
    borderVariant        = GlassBorderStrong,
)

// ─── Phone / tablet theme ─────────────────────────────────────────────────────

/**
 * Dark Compose theme for the **phone / tablet** form factor.
 *
 * Provides the "Cinematic Glass" dark [AppDarkColorScheme], [AppTypography], and
 * [AppShapes] to the M3 composition locals. Also sets the window status and
 * navigation bar colours to [BackgroundBase] (#0A0A0F) so no lighter chrome
 * bleeds through before Compose draws its first frame.
 *
 * Edge-to-edge inset handling (WindowCompat.setDecorFitsSystemWindows) is
 * deferred to the Activity wiring task.
 *
 * Preview-safe: the [SideEffect] block is skipped when [LocalView.isInEditMode]
 * is true (i.e. in Android Studio layout previews).
 */
@Composable
fun IptvAppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Safe cast: view.context is Activity in normal runtime, but may
            // differ in Robolectric or previews that slip past isInEditMode.
            val activity = view.context as? Activity ?: return@SideEffect
            val darkColor = BackgroundBase.toArgb()
            activity.window.statusBarColor     = darkColor
            activity.window.navigationBarColor = darkColor
        }
    }

    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        typography  = AppTypography,
        shapes      = AppShapes,
        content     = content,
    )
}

// ─── Android TV theme ─────────────────────────────────────────────────────────

/**
 * Dark Compose theme for the **Android TV** form factor.
 *
 * Architecture note — dual-layer wrapping:
 *
 *  1. Outer [MaterialTheme] (M3): provides [AppShapes] and [AppDarkColorScheme]
 *     to M3 composition locals. Shared helper composables that rely on
 *     [androidx.compose.material3.MaterialTheme.shapes] or M3 colour roles
 *     will resolve correctly even when embedded in TV screens.
 *
 *  2. Inner [TvMaterialTheme] (androidx.tv.material3): provides
 *     [AppTvDarkColorScheme] and [AppTvTypography] to TV composition locals.
 *     All TV-specific components (Cards, NavigationDrawer, etc.) resolve
 *     colours from here.
 *
 * This nesting is the recommended pattern for Compose for TV apps so that
 * both TV components and any reused M3 components see consistent dark tokens.
 *
 * No status-bar SideEffect: Android TV devices do not display a status bar.
 */
@Composable
fun IptvAppTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppDarkColorScheme,
        typography  = AppTypography,
        shapes      = AppShapes,
    ) {
        TvMaterialTheme(
            colorScheme = AppTvDarkColorScheme,
            typography  = AppTvTypography,
            content     = content,
        )
    }
}
