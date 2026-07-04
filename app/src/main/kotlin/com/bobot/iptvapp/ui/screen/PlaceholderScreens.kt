package com.bobot.iptvapp.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder screens for Navigation Compose destinations — Task 4.
 *
 * Each composable in this file is a minimal, centred-text stub that:
 *  - satisfies the NavHost destination slot so the nav graph compiles and runs;
 *  - exposes the correct callback signatures that define this screen's navigation
 *    contract (future tasks replace the body while keeping the same signature);
 *  - carries no state, ViewModel, or business logic.
 *
 * Replacement plan:
 *  - Onboarding placeholder        → replaced by [com.bobot.iptvapp.ui.screen.onboarding.OnboardingScreen] (Task 14)
 *  - Profiles placeholder          → replaced by [com.bobot.iptvapp.ui.screen.profiles.ProfilesScreen] (Task 16)
 *  - Home placeholder               → replaced by [com.bobot.iptvapp.ui.screen.home.HomeScreen] (Task 17)
 *  - [DetailPlaceholderScreen]     → "movie" branch replaced by
 *                                    [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailScreen] (Task 18, done);
 *                                    "series" branch replaced by
 *                                    [com.bobot.iptvapp.ui.screen.seriesdetail.SeriesDetailScreen] (Task 19, done);
 *                                    "live" branch replaced by
 *                                    [com.bobot.iptvapp.ui.screen.livedetail.LiveDetailScreen] (Task 20, done).
 *                                    All three known content types now have real screens; this
 *                                    placeholder is purely a defensive fallback for unexpected/unknown
 *                                    `contentType` values — see
 *                                    [com.bobot.iptvapp.navigation.AppNavGraph]'s `composable<Detail>`
 *                                    dispatch on `contentType`.
 *  - Player placeholder            → replaced by [com.bobot.iptvapp.ui.screen.player.PlayerScreen] (Task 13)
 *  - Search placeholder            → replaced by [com.bobot.iptvapp.ui.screen.search.SearchScreen] (Task 21)
 *  - Settings placeholder          → replaced by [com.bobot.iptvapp.ui.screen.settings.SettingsScreen] (Task 15)
 */

// ─── Detail ───────────────────────────────────────────────────────────────────

/**
 * Detail placeholder — as of Task 20, all three known content types ("movie", "series", "live")
 * are handled by real screens ([com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailScreen],
 * [com.bobot.iptvapp.ui.screen.seriesdetail.SeriesDetailScreen], and
 * [com.bobot.iptvapp.ui.screen.livedetail.LiveDetailScreen] respectively). This placeholder is now
 * purely a defensive fallback for unexpected/unknown `contentType` values reaching
 * [com.bobot.iptvapp.navigation.AppNavGraph]'s `composable<Detail>` `else` branch — not a real
 * destination for any of the three known types.
 *
 * @param contentType         An unrecognised content type string (should not occur in practice).
 * @param contentId           Xtream Codes item identifier.
 * @param onNavigateToPlayer  Opens the fullscreen player for a resolved stream.
 */
@Composable
fun DetailPlaceholderScreen(
    contentType: String = "",
    contentId: String = "",
    onNavigateToPlayer: (streamUrl: String, streamId: String) -> Unit = { _, _ -> },
) {
    CenteredLabel(label = "Detail [$contentType / $contentId] — unknown content type")
}

// ─── Shared internal helper ───────────────────────────────────────────────────

/**
 * Fills the available space and renders [label] centred.
 * Used exclusively by this file's placeholder composables.
 */
@Composable
private fun CenteredLabel(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier.fillMaxSize(),
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
