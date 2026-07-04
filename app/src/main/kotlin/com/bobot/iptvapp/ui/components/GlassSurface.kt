package com.bobot.iptvapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobot.iptvapp.ui.theme.GlassBorder
import com.bobot.iptvapp.ui.theme.GlassBorderStrong
import com.bobot.iptvapp.ui.theme.GlassFill
import com.bobot.iptvapp.ui.theme.GlassFillStrong
import com.bobot.iptvapp.ui.theme.IptvAppTheme
import com.bobot.iptvapp.ui.theme.RadiusLg

/**
 * "Cinematic Glass" surface — `Modifier` extension for a reusable frosted-glass look.
 *
 * ## Visual layers (bottom → top)
 * 1. **Translucent fill** — [GlassFill] (or [GlassFillStrong] when `strong = true`).
 *    This is the **baseline glass look** that works on every API level and on Android TV.
 * 2. **1 dp border** — [GlassBorder] (or [GlassBorderStrong] when `strong = true`),
 *    drawn inside the clip boundary at the surface edge.
 * 3. **Top-edge highlight** — a very-low-alpha white-to-transparent gradient painted
 *    across the top ~20 % of the surface to suggest a glass sheen under ambient light.
 *    Applied via [drawWithContent] — rendered before composable content so it sits below it.
 *
 * ## Blur strategy and baseline
 * True CSS `backdrop-filter: blur(...)` — blurring the content *behind* the surface — is
 * not achievable in Compose without a third-party library (explicitly excluded from V2) or
 * by rendering the backdrop content *inside* the surface. The approach taken is therefore:
 *
 * - **All API levels (including TV):** translucent fill + border + top-highlight — the
 *   baseline that looks correct on the dark `BackgroundBase` (#0A0A0F) gradient background.
 *   This **is** the production design baseline, not a fallback.
 * - No blur dependency is added. The `GlassSurface` composable documents a reserved
 *   `enableBlur` parameter for forward compatibility. See ADR-008 for full rationale.
 *
 * ## Usage
 * ```kotlin
 * Box(
 *   modifier = Modifier
 *     .size(200.dp)
 *     .glassSurface(shape = RoundedCornerShape(RadiusMd))
 * ) { /* content */ }
 * ```
 *
 * @param shape  The clipping shape. Defaults to [RoundedCornerShape(RadiusLg)].
 * @param strong When `true` uses [GlassFillStrong] and [GlassBorderStrong] for
 *               a slightly more opaque and more visually prominent surface.
 */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(RadiusLg),
    strong: Boolean = false,
): Modifier {
    val fill   = if (strong) GlassFillStrong else GlassFill
    val border = if (strong) GlassBorderStrong else GlassBorder

    // Top-edge highlight: white → transparent over the top 20 % of the surface.
    // Alpha is intentionally very low (0.08) so the effect reads as glass specular
    // light without overwhelming the content on TV or mobile.
    val highlightBrush = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color.White.copy(alpha = 0.08f),
            0.20f to Color.Transparent,
        ),
    )

    return this
        // 1. Clip to the glass shape — must precede both background and border so all
        //    three layers share the same rounded boundary.
        .clip(shape)
        // 2. Translucent fill — baseline glass appearance on all API levels.
        .background(color = fill, shape = shape)
        // 3. 1 dp border inside the clip edge.
        .border(width = 1.dp, color = border, shape = shape)
        // 4. Top-edge glass sheen — draw highlight first (below content), then content.
        .drawWithContent {
            drawRect(brush = highlightBrush)
            drawContent()
        }
}

// ─── GlassSurface composable ────────────────────────────────────────────────

/**
 * "Cinematic Glass" container composable — a convenience wrapper around [glassSurface]
 * that composes a [Box] with the glass look applied.
 *
 * ## Blur limitation and design decision
 * A true CSS `backdrop-filter: blur(...)` effect — blurring the content *behind* the
 * surface — is NOT achievable in Compose without either a third-party library (Haze) or
 * rendering the backdrop content *inside* this composable so `Modifier.blur` can operate
 * on it. Neither option is available here without new dependencies (explicitly forbidden
 * in the V2 brief). See ADR-008 for the full rationale.
 *
 * The `enableBlur` parameter is **reserved for future use**: when `true` and
 * `Build.VERSION.SDK_INT >= 31`, callers that wrap their own backdrop content as the
 * first child can apply `Modifier.blur` to that child before calling [GlassSurface].
 * The parameter exists to make the API forward-compatible and to communicate intent in
 * the source. In the current implementation it has no visual effect — the baseline
 * translucent fill is the only background treatment on all API levels.
 *
 * On the dark [BackgroundBase] background (#0A0A0F), the translucent fill + border +
 * highlight approach produces a visually correct and intentional glass look. This is
 * the production baseline.
 *
 * @param modifier   Caller-supplied modifier (size, padding, graphicsLayer, etc.).
 * @param shape      Clipping shape — defaults to [RoundedCornerShape(RadiusLg)].
 * @param strong     When `true`, uses the stronger fill/border tokens.
 * @param enableBlur Reserved — see above. Currently has no visual effect. Defaults to `false`.
 * @param content    Composable content rendered inside the glass surface.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(RadiusLg),
    strong: Boolean = false,
    @Suppress("UNUSED_PARAMETER") enableBlur: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.glassSurface(shape = shape, strong = strong)) {
        content()
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@Preview(
    name            = "GlassSurface — default (rest)",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun GlassSurfacePreview() {
    IptvAppTheme {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) { /* empty surface — shows fill + border + highlight */ }
    }
}

@Preview(
    name            = "GlassSurface — strong variant",
    showBackground  = true,
    backgroundColor = 0xFF0A0A0F,
)
@Composable
private fun GlassSurfaceStrongPreview() {
    IptvAppTheme {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            strong = true,
        ) { /* empty strong surface */ }
    }
}
