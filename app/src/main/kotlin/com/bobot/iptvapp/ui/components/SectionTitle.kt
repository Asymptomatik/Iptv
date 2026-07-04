package com.bobot.iptvapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.LayoutDimens
import com.bobot.iptvapp.ui.theme.TextPrimary
import com.bobot.iptvapp.ui.theme.TextSecondary

/**
 * Category / section row header displayed above a horizontal content row.
 *
 * ## V2 "Cinematic Glass" update
 * The title now uses the **h3** type style ([MaterialTheme.typography.headlineMedium] —
 * 20 sp / SemiBold / lh 26 sp) matching the `section-head h2` rule in `styles.css`
 * (`--fs-h3: 20px; --fw-h3: 600`).  An optional `trailingAction` slot is provided
 * for a "Tout voir ›" affordance rendered right-aligned; callers that do not supply
 * a trailing action see no change in the rendered layout.
 *
 * ## API backward compatibility
 * The original 2-parameter signature `SectionTitle(title, modifier)` is fully preserved.
 * The new `trailingAction` parameter defaults to `null`, keeping every existing call site
 * source-compatible without modification.
 *
 * Stateless display-only composable — no interaction, no business logic.
 * The bottom padding separates the title from the card row below it.
 * Horizontal padding is intentionally omitted so the caller can apply the
 * correct form-factor padding (phone vs TV) via [modifier].
 *
 * @param title          Category label (e.g. "Popular on Netflix", "Action").
 * @param modifier       Caller-supplied modifier (typically adds horizontal padding).
 * @param trailingAction Optional composable placed at the end of the row — typically a
 *                       "Tout voir ›" [FocusableTextButton] or similar affordance.
 *                       When `null` (default) only the title is rendered.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    if (trailingAction == null) {
        // Fast path — single text, same layout as before, no extra Row overhead.
        Text(
            text     = title,
            style    = MaterialTheme.typography.headlineMedium,
            color    = TextPrimary,
            modifier = modifier.padding(bottom = LayoutDimens.SectionTitleBottomPadding),
        )
    } else {
        Row(
            modifier              = modifier.padding(bottom = LayoutDimens.SectionTitleBottomPadding),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text     = title,
                style    = MaterialTheme.typography.headlineMedium,
                color    = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            trailingAction()
        }
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(
    name            = "SectionTitle — title only",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun SectionTitlePreview() {
    IptvAppTheme {
        SectionTitle(
            title    = "Popular on Netflix",
            modifier = Modifier.padding(horizontal = LayoutDimens.ContentPaddingPhone),
        )
    }
}

@Preview(
    name            = "SectionTitle — with trailing action",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun SectionTitleWithTrailingPreview() {
    IptvAppTheme {
        SectionTitle(
            title    = "En ce moment",
            modifier = Modifier.padding(horizontal = LayoutDimens.ContentPaddingPhone),
            trailingAction = {
                Text(
                    text  = "Tout voir ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary,
                )
            },
        )
    }
}

@Preview(
    name            = "SectionTitle — long",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun SectionTitleLongPreview() {
    IptvAppTheme {
        SectionTitle(
            title    = "Because You Watched: La Casa de Papel",
            modifier = Modifier.padding(horizontal = LayoutDimens.ContentPaddingPhone),
        )
    }
}
