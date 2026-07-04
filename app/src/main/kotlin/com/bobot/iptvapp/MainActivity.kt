package com.bobot.iptvapp

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobot.iptvapp.navigation.AppNavGraph
import com.bobot.iptvapp.navigation.AppRoute
import com.bobot.iptvapp.ui.theme.BackgroundBase
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.IptvAppTvTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity entry point for both the phone / tablet and Android TV form
 * factors.
 *
 * Responsibilities:
 *  1. [@AndroidEntryPoint] — registers this activity with Hilt so that
 *     ViewModels and other Hilt-provided dependencies can be injected into
 *     composables hosted here via [hiltViewModel].
 *  2. [enableEdgeToEdge] — requests that the system draws the window content
 *     behind the status bar and navigation bar, giving a full-bleed dark surface.
 *     The [IptvAppTheme] SideEffect then sets bar colours to match the dark
 *     background, completing the edge-to-edge dark setup.
 *  3. Form-factor detection — checks for the Android TV leanback feature at
 *     runtime so the correct theme wrapper is chosen. [IptvAppTvTheme] nests
 *     both M3 and [androidx.tv.material3] themes; [IptvAppTheme] is M3-only for
 *     phone and tablet.
 *  4. Dynamic start destination (Task 16, Volet B) — [MainViewModel] (obtained via
 *     [hiltViewModel], the same convention every other screen ViewModel in this codebase uses —
 *     scoped to this Activity's [androidx.lifecycle.ViewModelStoreOwner] since it is requested
 *     outside any `NavBackStackEntry`) resolves whether credentials are already persisted via a
 *     one-shot suspend check
 *     ([MainViewModel.startDestination], `null` until resolved). While `null`, a minimal blank
 *     [BackgroundBase] surface is shown instead of composing [AppNavGraph] — `NavHost` needs
 *     its start destination synchronously at first composition, so [AppNavGraph] cannot be
 *     composed before the check completes. In practice this loading surface is visible for at
 *     most a single DataStore read (microseconds to a few milliseconds), not a spinner-worthy
 *     wait, so no progress indicator is shown — see [LoadingSurface].
 *  5. [AppNavGraph] — hosts the Navigation Compose graph with all destination
 *     composables, once the resolved start destination is available. The graph lives inside the
 *     theme wrapper so every screen inherits the correct colour scheme and typography tokens.
 *
 * Declared in AndroidManifest.xml with two intent-filters:
 *  - [android.intent.category.LAUNCHER]         → phone / tablet home screen
 *  - [android.intent.category.LEANBACK_LAUNCHER] → Android TV home screen
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: content draws behind status and navigation bars.
        // Must be called before setContent so the window flags are set before
        // the first Compose frame is measured.
        enableEdgeToEdge()

        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        setContent {
            if (isTv) {
                IptvAppTvTheme {
                    MainContent()
                }
            } else {
                IptvAppTheme {
                    MainContent()
                }
            }
        }
    }
}

/**
 * Renders [AppNavGraph] once the start destination has been resolved by [MainViewModel]
 * (obtained here via [hiltViewModel], scoped to the enclosing Activity), or a [LoadingSurface]
 * while it is still `null`. See [MainActivity] KDoc point 4.
 */
@Composable
private fun MainContent(mainViewModel: MainViewModel = hiltViewModel()) {
    val startDestination: AppRoute? by mainViewModel.startDestination.collectAsStateWithLifecycle()
    val resolvedDestination = startDestination

    if (resolvedDestination == null) {
        LoadingSurface()
    } else {
        AppNavGraph(startDestination = resolvedDestination)
    }
}

/**
 * Minimal blank surface shown for the brief instant [MainViewModel] needs to resolve the real
 * start destination. Matches [BackgroundBase] so there is no visible flash/flicker against the
 * dark theme once [AppNavGraph] composes underneath it.
 */
@Composable
private fun LoadingSurface() {
    Box(modifier = Modifier.fillMaxSize().background(BackgroundBase))
}
