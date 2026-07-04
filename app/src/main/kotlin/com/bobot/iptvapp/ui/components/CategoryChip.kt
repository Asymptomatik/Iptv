package com.bobot.iptvapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.ui.theme.AccentGradient
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusPill
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextOnAccent
import com.bobot.iptvapp.ui.theme.TextSecondary

/**
 * "Cinematic Glass" **category / filter chip** — pill-shaped, used for horizontal
 * filter rows on the Home, Search, and Detail screens.
 *
 * ## Visual states
 *  - **Rest (unselected)** — glass surface ([glassSurface]) background, [TextSecondary] label.
 *  - **Active / selected** — [AccentGradient] background, [TextOnAccent] label, no glass border.
 *  - **Focused (TV D-pad)** — gradient focus ring ([AccentGradient]) animates in around the
 *    pill; the glass surface strengthens to the `strong` variant for unselected chips,
 *    while selected chips gain the ring directly over their gradient fill.
 *
 * ## Accessibility
 * The chip reports [Role.Button] and a [selected] semantic property so screen readers
 * can announce filter state to the user.
 *
 * @param label      Chip label text (e.g. "Action", "Drama", "Tout").
 * @param selected   Whether this chip represents the currently active filter.
 * @param onClick    Invoked on touch tap or D-pad Enter.
 * @param modifier   Caller-supplied modifier.
 * @param enabled    When `false` the chip is dimmed and non-interactive.
 */
@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusRingAlpha by animateFloatAsState(
        targetValue   = if (isFocused && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label         = "chipFocusRing",
    )

    val pillShape    = RoundedCornerShape(RadiusPill)
    val contentAlpha = if (enabled) 1f else 0.38f
    val textColor    = if (selected) TextOnAccent else TextSecondary

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics {
                role = Role.Button
                this.selected = selected
            }
            // Focus ring drawn behind the chip clip.
            .focusRingBehind(
                focusRingAlpha = focusRingAlpha,
                cornerRadiusDp = RadiusPill,
            )
            // Surface: gradient fill when selected, glass surface otherwise.
            .then(
                if (selected) {
                    Modifier
                        .clip(pillShape)
                        .background(brush = AccentGradient)
                } else {
                    Modifier.glassSurface(
                        shape  = pillShape,
                        strong = isFocused && enabled,
                    )
                }
            )
            .onFocusChanged { state -> isFocused = state.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.md)
            .defaultMinSize(minHeight = 34.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelLarge,
            color    = textColor.copy(alpha = contentAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(
    name            = "CategoryChip — unselected + selected",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun CategoryChipPreview() {
    IptvAppTheme {
        Row(
            modifier            = Modifier.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CategoryChip(label = "Tout",    selected = true,  onClick = {})
            CategoryChip(label = "Action",  selected = false, onClick = {})
            CategoryChip(label = "Comédie", selected = false, onClick = {})
        }
    }
}
