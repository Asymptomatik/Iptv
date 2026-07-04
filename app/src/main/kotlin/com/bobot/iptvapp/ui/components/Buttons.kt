package com.bobot.iptvapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.ui.theme.AccentGlow
import com.bobot.iptvapp.ui.theme.AccentGradient
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusPill
import com.bobot.iptvapp.ui.theme.Spacing
import com.bobot.iptvapp.ui.theme.TextOnAccent
import com.bobot.iptvapp.ui.theme.TextPrimary

// ─── PrimaryButton ───────────────────────────────────────────────────────────

/**
 * "Cinematic Glass" **primary action button** — pill-shaped, filled with the
 * [AccentGradient] violet→cyan brush, text/icon rendered in [TextOnAccent].
 *
 * ## Focus language (phone + Android TV D-pad)
 * At rest the button carries its accent-gradient background.  When focused,
 * a bright gradient ring ([AccentGradient]) animates in around the pill border
 * via [focusRingBehind], and the [AccentGlow] shadow intensifies.
 *
 * ## Disabled state
 * When [enabled] is `false` the button ignores clicks and its content is
 * rendered at 38 % opacity (M3 disabled convention), with no glow or focus ring.
 *
 * @param label    Button label text.
 * @param onClick  Invoked on touch tap or D-pad Enter when [enabled].
 * @param modifier Caller-supplied modifier.
 * @param icon     Optional leading icon displayed to the left of [label].
 * @param enabled  When `false` the button is visually dimmed and non-interactive.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusRingAlpha by animateFloatAsState(
        targetValue   = if (isFocused && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label         = "primaryBtnFocusRing",
    )

    val glowAlpha by animateFloatAsState(
        targetValue   = if (isFocused && enabled) 1f else 0.45f,
        animationSpec = tween(durationMillis = 180),
        label         = "primaryBtnGlow",
    )

    val pillShape    = RoundedCornerShape(RadiusPill)
    val contentAlpha = if (enabled) 1f else 0.38f

    // Perf: remember the Paint object; only the shadow colour changes per animation.
    val glowPaint = remember {
        Paint().also { p ->
            p.asFrameworkPaint().apply {
                isAntiAlias = true
                color       = android.graphics.Color.TRANSPARENT
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics { role = Role.Button }
            // Accent glow shadow — always present, intensifies on focus.
            .drawBehind {
                val glowColor = AccentGlow.copy(alpha = AccentGlow.alpha * glowAlpha)
                glowPaint.asFrameworkPaint().setShadowLayer(36f, 0f, 10f, glowColor.toArgb())
                drawIntoCanvas { canvas ->
                    val r = RadiusPill.toPx().coerceAtMost(size.height / 2f)
                    canvas.drawRoundRect(
                        left    = 0f,
                        top     = 0f,
                        right   = size.width,
                        bottom  = size.height,
                        radiusX = r,
                        radiusY = r,
                        paint   = glowPaint,
                    )
                }
            }
            // Focus ring — gradient ring just outside the pill border.
            .focusRingBehind(
                focusRingAlpha = focusRingAlpha,
                cornerRadiusDp = RadiusPill,
            )
            // Pill clip + gradient fill.
            .clip(pillShape)
            .background(brush = AccentGradient)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.lg)
            .defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = TextOnAccent.copy(alpha = contentAlpha),
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(
                text     = label,
                style    = MaterialTheme.typography.titleMedium,
                color    = TextOnAccent.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── GhostButton ─────────────────────────────────────────────────────────────

/**
 * "Cinematic Glass" **ghost / secondary button** — pill-shaped, glass surface
 * background ([glassSurface] translucent fill + [GlassBorder] border), text in
 * [TextPrimary].
 *
 * ## Focus language
 * On focus the glass surface strengthens to [strong = true] and an
 * [AccentGradient] ring animates in around the pill via [focusRingBehind] —
 * matching the primary button and [FocusableCard] focus language.
 *
 * @param label    Button label text.
 * @param onClick  Invoked on touch tap or D-pad Enter when [enabled].
 * @param modifier Caller-supplied modifier.
 * @param icon     Optional leading icon.
 * @param enabled  When `false` the button is dimmed and non-interactive.
 */
@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusRingAlpha by animateFloatAsState(
        targetValue   = if (isFocused && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label         = "ghostBtnFocusRing",
    )

    val pillShape    = RoundedCornerShape(RadiusPill)
    val contentAlpha = if (enabled) 1f else 0.38f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics { role = Role.Button }
            .focusRingBehind(
                focusRingAlpha = focusRingAlpha,
                cornerRadiusDp = RadiusPill,
            )
            // glassSurface() handles clip + fill + border + sheen.
            .glassSurface(shape = pillShape, strong = isFocused && enabled)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.lg)
            .defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = TextPrimary.copy(alpha = contentAlpha),
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(
                text     = label,
                style    = MaterialTheme.typography.titleMedium,
                color    = TextPrimary.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── GlassIconButton ─────────────────────────────────────────────────────────

/**
 * "Cinematic Glass" **circular icon button** — 46 dp circle, glass surface
 * background, used for secondary actions such as ♡ favourite, ⓘ info, ⚙ settings,
 * or CC subtitles.
 *
 * ## Focus language
 * On focus an [AccentGradient] ring appears around the circle via [focusRingBehind] —
 * consistent with [FocusableCard] and [PrimaryButton].
 *
 * @param icon              The icon to display inside the circle.
 * @param contentDescription  Accessibility label.
 * @param onClick           Invoked on touch tap or D-pad Enter when [enabled].
 * @param modifier          Caller-supplied modifier.
 * @param enabled           When `false` the button is dimmed and non-interactive.
 * @param size              Diameter of the circle. Defaults to 46 dp (design spec).
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 46.dp,
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusRingAlpha by animateFloatAsState(
        targetValue   = if (isFocused && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label         = "iconBtnFocusRing",
    )

    val circleShape  = RoundedCornerShape(RadiusPill)
    val contentAlpha = if (enabled) 1f else 0.38f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics { role = Role.Button }
            .size(size)
            .focusRingBehind(
                focusRingAlpha = focusRingAlpha,
                cornerRadiusDp = RadiusPill,
            )
            .glassSurface(shape = circleShape, strong = isFocused && enabled)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = TextPrimary.copy(alpha = contentAlpha),
            modifier           = Modifier.size(20.dp),
        )
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(
    name            = "Buttons — PrimaryButton variants",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun PrimaryButtonPreview() {
    IptvAppTheme {
        Column(
            modifier            = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PrimaryButton(label = "Lire", onClick = {})
            PrimaryButton(label = "Lire", onClick = {}, enabled = false)
        }
    }
}

@Preview(
    name            = "Buttons — GhostButton variants",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun GhostButtonPreview() {
    IptvAppTheme {
        Column(
            modifier            = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            GhostButton(label = "En savoir plus", onClick = {})
            GhostButton(label = "En savoir plus", onClick = {}, enabled = false)
        }
    }
}
