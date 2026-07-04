package com.bobot.iptvapp.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.bobot.iptvapp.ui.theme.AccentGradient
import com.bobot.iptvapp.ui.theme.CardDimens

/**
 * Draws an [AccentGradient] ring just outside the composable bounds when
 * [focusRingAlpha] > 0.
 *
 * This is the shared focus-ring primitive used by [PrimaryButton], [GhostButton],
 * [GlassIconButton], [FocusableTextButton], and [CategoryChip].  It replicates the
 * same visual language as the ring in [FocusableCard] so all interactive elements
 * respond to D-pad / touch focus with a consistent gradient border.
 *
 * ## Drawing approach
 * The ring uses [drawBehind] (executed on the draw thread) with [drawRoundRect] —
 * lighter than the Path approach used in [FocusableCard] for pill-shaped elements
 * where a simple rounded rect suffices.  No per-frame heap allocation: the stroke
 * parameters and brush are computed from stable tokens and the alpha parameter only.
 *
 * ## Usage
 * ```kotlin
 * Modifier.focusRingBehind(focusRingAlpha = alpha, cornerRadiusDp = RadiusPill)
 * ```
 *
 * @param focusRingAlpha Animated alpha in [0, 1].  0 = invisible, 1 = fully visible.
 * @param cornerRadiusDp Corner radius of the shape in dp (converted internally to px).
 * @param ringStrokeDp   Ring stroke width.  Defaults to [CardDimens.FocusBorderWidth].
 */
internal fun Modifier.focusRingBehind(
    focusRingAlpha: Float,
    cornerRadiusDp: Dp,
    ringStrokeDp: Dp = CardDimens.FocusBorderWidth,
): Modifier = drawBehind {
    if (focusRingAlpha <= 0f) return@drawBehind

    val strokePx = ringStrokeDp.toPx()
    val radiusPx = cornerRadiusDp.toPx().coerceAtMost(size.minDimension / 2f)
    val inset    = strokePx / 2f

    // Build gradient brush using the package-level AccentGradient tinted with
    // focusRingAlpha via drawRoundRect's alpha parameter to avoid per-call copy().
    drawRoundRect(
        brush       = AccentGradient,
        topLeft     = Offset(-inset, -inset),
        size        = Size(size.width + strokePx, size.height + strokePx),
        cornerRadius = CornerRadius(radiusPx + inset, radiusPx + inset),
        style       = Stroke(width = strokePx),
        alpha       = focusRingAlpha,
    )
}
