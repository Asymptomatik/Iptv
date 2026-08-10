package com.bobot.iptvapp.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bobot.iptvapp.ui.screen.DetailPlaceholderScreen
import com.bobot.iptvapp.ui.screen.downloads.DownloadsScreen
import com.bobot.iptvapp.ui.screen.home.HomeScreen
import com.bobot.iptvapp.ui.screen.livedetail.LiveDetailScreen
import com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailScreen
import com.bobot.iptvapp.ui.screen.onboarding.OnboardingScreen
import com.bobot.iptvapp.ui.screen.player.PlayerScreen
import com.bobot.iptvapp.ui.screen.profiles.ProfilesScreen
import com.bobot.iptvapp.ui.screen.search.SearchScreen
import com.bobot.iptvapp.ui.screen.seriesdetail.SeriesDetailScreen
import com.bobot.iptvapp.ui.screen.settings.SettingsScreen

/**
 * Root navigation graph for the IptvApp.
 *
 * Uses the Navigation Compose 2.8 type-safe API: destinations are declared with
 * [composable]<T> where T is a [@Serializable] [AppRoute] data object or class.
 * Argument encoding/decoding is handled automatically by the navigation back
 * stack serializer — no manual string route construction required.
 *
 * Start destination: dynamic, resolved by [MainActivity] via `MainViewModel`
 * (Task 16) from [com.bobot.iptvapp.data.source.CredentialsProvider.getCredentials]:
 * [Profiles] when Xtream Codes credentials are already persisted (returning user),
 * [Onboarding] otherwise (first launch). See [startDestination] param KDoc for why
 * this graph itself stays a plain, synchronous parameter rather than resolving
 * credentials itself.
 *
 * Navigation pattern — all nav actions are lifted out of screens:
 *  - Screens receive lambda callbacks for every outbound nav action.
 *  - This keeps screens decoupled from the [NavHostController] and testable
 *    in isolation.
 *
 * @param navController     Navigation controller that owns the back stack.
 *                          Defaults to a freshly [rememberNavController] instance
 *                          for normal production use; callers may inject their own
 *                          for testing or deep-link scenarios.
 * @param startDestination  Which [AppRoute] the [NavHost] should start on. [NavHost]'s
 *                          own `startDestination` parameter is typed `Any` (not a reified
 *                          generic) precisely to support this "decided at runtime" pattern —
 *                          route matching happens against the runtime type of the object
 *                          passed in, not this parameter's static [AppRoute] type, so no
 *                          `@Serializable` annotation is needed on the [AppRoute] sealed
 *                          interface itself. Defaults to [Onboarding] so existing callers/tests
 *                          that do not yet know about credential state keep working unchanged.
 *                          [MainActivity] passes the real resolved value once `MainViewModel`
 *                          finishes its one-shot suspend credentials check (see [MainActivity]
 *                          KDoc for the loading-state gate that avoids composing this graph
 *                          before that check completes).
 */
@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: AppRoute = Onboarding,
) {
    NavHost(
        navController    = navController,
        startDestination = startDestination,
    ) {

        // ── Onboarding ───────────────────────────────────────────────────────
        composable<Onboarding> {
            OnboardingScreen(
                onNavigateToProfiles = {
                    navController.navigate(Profiles) {
                        // Pop onboarding off the stack so back does not return to it.
                        popUpTo<Onboarding> { inclusive = true }
                    }
                },
            )
        }

        // ── Profiles ─────────────────────────────────────────────────────────
        composable<Profiles> {
            ProfilesScreen(
                onNavigateToHome = {
                    navController.navigate(Home) {
                        popUpTo<Profiles> { inclusive = true }
                    }
                },
            )
        }

        // ── Home ─────────────────────────────────────────────────────────────
        composable<Home> {
            HomeScreen(
                onNavigateToDetail = { type, id ->
                    navController.navigate(Detail(contentType = type, contentId = id))
                },
                onNavigateToPlayer = { url, id ->
                    // Task 23: "Reprendre" (Continue Watching) cards resume playback directly,
                    // bypassing the Detail screen — see HomeScreen KDoc "Navigation".
                    navController.navigate(Player(streamUrl = url, streamId = id))
                },
                onNavigateToSearch = {
                    navController.navigate(Search)
                },
                onNavigateToSettings = {
                    navController.navigate(Settings)
                },
                onNavigateToDownloads = {
                    navController.navigate(Downloads)
                },
            )
        }

        // ── Downloads ────────────────────────────────────────────────────────
        composable<Downloads> {
            DownloadsScreen(
                onNavigateBack = { navController.popBackStack() },
                onPlay = { url, id ->
                    navController.navigate(Player(streamUrl = url, streamId = id))
                },
            )
        }

        // ── Detail ───────────────────────────────────────────────────────────
        // Dispatches on `route.contentType`: "movie" (Task 18), "series" (Task 19), and "live"
        // (Task 20) are all implemented as of this task — see `DetailPlaceholderScreen` KDoc.
        // The `else` branch is now a purely defensive fallback for unexpected/unknown
        // `contentType` values, not a real destination for any of the three known types.
        composable<Detail> { backStackEntry ->
            val route = backStackEntry.toRoute<Detail>()
            val onNavigateToPlayer: (String, String) -> Unit = { url, id ->
                navController.navigate(Player(streamUrl = url, streamId = id))
            }

            when (route.contentType) {
                "movie" -> MovieDetailScreen(
                    movieId            = route.contentId,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                "series" -> SeriesDetailScreen(
                    seriesId           = route.contentId,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                "live" -> LiveDetailScreen(
                    channelId          = route.contentId,
                    onNavigateToPlayer = onNavigateToPlayer,
                )

                else -> DetailPlaceholderScreen(
                    contentType        = route.contentType,
                    contentId          = route.contentId,
                    onNavigateToPlayer = onNavigateToPlayer,
                )
            }
        }

        // ── Player ───────────────────────────────────────────────────────────
        // `route` here is `com.bobot.iptvapp.navigation.Player` (this file's package).
        // `PlayerScreen` (Task 13) takes plain `streamUrl` / `streamId` String parameters —
        // it never imports this route type — so `androidx.media3.common.Player` is not
        // referenced anywhere in this file and no import alias is needed here; see
        // `PlayerScreen` / `PlayerViewModel` KDoc for where the two `Player` symbols are
        // genuinely adjacent and the alias convention that guards against the collision.
        composable<Player> { backStackEntry ->
            val route = backStackEntry.toRoute<Player>()
            PlayerScreen(
                streamUrl      = route.streamUrl,
                streamId       = route.streamId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ── Search ───────────────────────────────────────────────────────────
        composable<Search> {
            SearchScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { type, id ->
                    navController.navigate(Detail(contentType = type, contentId = id))
                },
            )
        }

        // ── Settings ─────────────────────────────────────────────────────────
        composable<Settings> {
            SettingsScreen(
                onNavigateToProfiles = {
                    // Simple, reversible navigation — the user must be able to press back to
                    // return to Settings, so no popUpTo is applied here (per this task's spec).
                    navController.navigate(Profiles)
                },
                onLoggedOut = {
                    navController.navigate(Onboarding) {
                        // Equivalent of the classic `popUpTo(0)` "clear everything" trick: the
                        // root NavGraph's own id is never a real back-stack entry, so popping up
                        // to it (inclusive) removes every destination currently on the stack —
                        // including the start destination itself — leaving a clean stack with
                        // only the freshly navigated-to Onboarding destination. This differs from
                        // the onboarding->profiles pop above (`popUpTo<Onboarding>`), which only
                        // pops back to a *named* destination still present on the stack; logout
                        // must clear unconditionally regardless of how deep the user has
                        // navigated (Home, Detail, Player, Search, Settings, …).
                        popUpTo(navController.graph.id) {
                            inclusive = true
                        }
                    }
                },
            )
        }
    }
}
