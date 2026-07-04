package com.bobot.iptvapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusPill
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextPrimary

/**
 * Small focusable/clickable text pill aligned with the "Cinematic Glass" V2 language.
 *
 * ## V2 update
 * Previously rendered a transparent / [BackgroundElevated]-filled box with a solid
 * [AccentSolid] border on focus.  In V2 the component uses the shared **glass surface +
 * gradient focus ring** pattern ([glassSurface] + [focusRingBehind]) so it sits
 * consistently beside [PrimaryButton], [GhostButton], and [FocusableCard]:
 *
 *  - **Rest** — glass pill ([glassSurface], pill shape), [TextPrimary] label.
 *  - **Focused** — glass surface strengthens (`strong = true`) and an [AccentGradient]
 *    ring animates in around the pill.
 *
 * The component was originally extracted in Task 19 from duplicated private composables in
 * [com.bobot.iptvapp.ui.screen.home.HomeScreen] and
 * [com.bobot.iptvapp.ui.screen.moviedetail.MovieDetailScreen].  This V2 restyle preserves
 * the same 3-parameter public API (`label`, `onClick`, `modifier`) — all existing call
 * sites remain source-compatible without any change.
 *
 * Used for secondary text actions: top-bar links (Home), error-state retry actions, and
 * small text-styled toggles.  Reachable by both D-pad focus and touch.
 *
 * @param label    Button text.
 * @param onClick  Invoked on touch tap or D-pad Enter.
 * @param modifier Caller-supplied modifier.
 */
@Composable
fun FocusableTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusRingAlpha by animateFloatAsState(
        targetValue   = if (isFocused) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label         = "textBtnFocusRing",
    )

    val pillShape = RoundedCornerShape(RadiusPill)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics { role = Role.Button }
            // Gradient focus ring — consistent with PrimaryButton / FocusableCard.
            .focusRingBehind(
                focusRingAlpha = focusRingAlpha,
                cornerRadiusDp = RadiusPill,
            )
            // Glass pill — strengthens to strong variant when focused.
            .glassSurface(shape = pillShape, strong = isFocused)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md)
            .defaultMinSize(minHeight = 40.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelLarge,
            color    = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(
    name            = "FocusableTextButton — rest",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun FocusableTextButtonPreview() {
    IptvAppTheme {
        FocusableTextButton(label = "Réessayer", onClick = {})
    }
}

@Preview(
    name            = "FocusableTextButton — focused",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun FocusableTextButtonFocusedPreview() {
    IptvAppTheme {
        // Static preview of the focused visual state — padding shows the ring.
        Box(modifier = Modifier.padding(12.dp)) {
            FocusableTextButton(label = "Recherche", onClick = {})
        }
    }
}
