package com.bobot.iptvapp.navigation

import kotlinx.serialization.Serializable

/**
 * Sealed interface grouping all Navigation Compose type-safe route definitions.
 *
 * Navigation Compose 2.8 uses [@Serializable] objects and classes as route
 * tokens. The sealed interface provides a single bounded type for passing
 * routes around (e.g. for deep-link helpers or test utilities) without
 * duplicating route declarations.
 *
 * Route taxonomy:
 *  - Argument-free destinations → [data object] (no heap allocation per navigation)
 *  - Destinations with arguments → [data class] (fields become navigation arguments,
 *    encoded/decoded automatically by the Navigation back stack serializer)
 *
 * Default start destination: [Onboarding]. As of Task 16, [MainActivity] resolves the real
 * start destination at launch via `MainViewModel`, choosing [Profiles] instead when Xtream
 * Codes credentials are already persisted (returning user) — see [AppNavGraph]'s
 * `startDestination` parameter KDoc.
 */
sealed interface AppRoute

// ─── Argument-free destinations ──────────────────────────────────────────────

/**
 * First-run onboarding screen.
 * Entry point for users without persisted Xtream Codes credentials.
 */
@Serializable
data object Onboarding : AppRoute

/**
 * Profile selection / management screen.
 * Reached after onboarding completes or from the home screen account menu.
 */
@Serializable
data object Profiles : AppRoute

/**
 * Main home screen — horizontal category rows (Live, Movies, Series).
 * Primary destination after a profile is selected.
 */
@Serializable
data object Home : AppRoute

/**
 * Search screen — full-text query bar and results grid.
 * Accessible from the home screen top app bar.
 */
@Serializable
data object Search : AppRoute

/**
 * Settings screen — server URL, credentials, and application preferences.
 * Accessible from the home screen account menu or the onboarding back action.
 */
@Serializable
data object Settings : AppRoute

// ─── Destinations with arguments ─────────────────────────────────────────────

/**
 * Detail screen — metadata, cast, episode list, and playback entry point.
 *
 * @param contentType Xtream Codes content type: one of "live", "movie", or "series".
 * @param contentId   Xtream Codes numeric item identifier (carried as String to
 *                    avoid Navigation argument-type limitations in the back stack).
 */
@Serializable
data class Detail(
    val contentType: String,
    val contentId: String,
) : AppRoute

/**
 * Fullscreen player screen driven by Media3 / ExoPlayer.
 *
 * @param streamUrl Direct stream URL resolved by the Xtream Codes client (Task 6).
 * @param streamId  Xtream Codes stream identifier, used for EPG lookup (Task 20)
 *                  and Continue Watching resume tracking (Task 23).
 */
@Serializable
data class Player(
    val streamUrl: String,
    val streamId: String,
) : AppRoute
