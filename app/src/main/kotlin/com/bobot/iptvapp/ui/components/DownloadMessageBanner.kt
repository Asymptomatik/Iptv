package com.bobot.iptvapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.ui.theme.BackgroundElevated
import com.bobot.iptvapp.ui.theme.GlassBorderStrong
import com.bobot.iptvapp.ui.theme.RadiusMd
import com.bobot.iptvapp.ui.theme.SemanticError
import com.bobot.iptvapp.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Transient banner for a download action that came back refused, shared by the film and série
 * detail screens.
 *
 * ## Why a banner and not the screens' error state
 * The detail screens' `errorMessage` means "there is nothing to show here" and replaces the whole
 * sheet. A refused download is the opposite: the content is fine, one button press did not take.
 * This renders *over* the content and leaves it interactive.
 *
 * ## Why it dismisses itself
 * There is no action to offer — the refusal lasts exactly as long as the purge does, and the user
 * cannot shorten it (see [com.bobot.iptvapp.ui.util.DOWNLOAD_REFUSED_MESSAGE]). A banner needing a
 * tap to dismiss would be one more thing to do about something they can do nothing about. The
 * timer is keyed on [message], so a second refusal re-arms it rather than inheriting the remainder
 * of the first one's.
 *
 * @param message What to show, or `null` to show nothing.
 * @param onShown Called once the banner has had its time; the owner clears its state here. It is
 *   what makes the message a one-shot rather than a permanent part of the screen.
 */
@Composable
fun DownloadMessageBanner(
    message: String?,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message == null) return

    LaunchedEffect(message) {
        delay(VISIBLE_DURATION_MILLIS)
        onShown()
    }

    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = SemanticError,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .background(BackgroundElevated, RoundedCornerShape(RadiusMd))
            .border(1.dp, GlassBorderStrong, RoundedCornerShape(RadiusMd))
            .padding(Spacing.md)
            // Announced by TalkBack without stealing focus: the banner is informational, and
            // moving focus here would pull the user off the button they just pressed.
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Long enough to read two lines of French, short enough not to sit over the artwork. */
private const val VISIBLE_DURATION_MILLIS = 4_000L
