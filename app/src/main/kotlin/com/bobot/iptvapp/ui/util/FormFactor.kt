package com.bobot.iptvapp.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * `true` when running on an Android TV device (leanback UI mode), used to pick TV-sized layout
 * tokens (card widths, content padding, etc.).
 *
 * Extracted from the identical private `rememberIsTvDevice` implementations duplicated across
 * [com.bobot.iptvapp.ui.screen.home.HomeScreen] and
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailScreen] — the Task 18 Code Reviewer flagged
 * this duplication and recommended a shared extraction before a third screen (Task 19, series
 * detail) repeated it again. This is a pure, behavior-preserving extraction: identical logic to
 * both original private copies. Home and Movie detail were updated to call this shared function
 * instead of their now-removed private copies.
 *
 * The theme wrapper itself is already chosen per form factor in
 * [com.bobot.iptvapp.MainActivity] via `PackageManager.FEATURE_LEANBACK`; this composable-local
 * check exists because that boolean is not threaded through
 * [com.bobot.iptvapp.navigation.AppNavGraph] into individual screens.
 */
@Composable
fun rememberIsTvDevice(): Boolean {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
