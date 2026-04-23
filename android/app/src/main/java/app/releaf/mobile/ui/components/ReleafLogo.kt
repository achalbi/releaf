/*
 * ReleafLogo.kt
 * Leaf brand mark. Path data is the canonical 24-unit SVG from the
 * Releaf Branding spec (April 2026). Renders as three layers to match
 * the brand:
 *   1. Green gradient fill (primary → deep, top-to-bottom)
 *   2. Cream (OnAccent) outline — the iconic brand stroke
 *   3. S-curve vein in OnAccent @ 40% alpha
 * Callers can force a solid-outline variant (for monochrome contexts
 * like small inline marks) with `filled = false`.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors

/**
 * Build the canonical leaf body path at the caller's resolved size.
 * Numbers are the 24-viewport SVG scaled by `scale = size / 24`.
 */
private fun leafBody(scale: Float): Path = Path().apply {
    fun x(n: Float) = n * scale
    fun y(n: Float) = n * scale
    moveTo(x(12f), y(3f))
    cubicTo(x(12f), y(3f), x(6f), y(6f), x(6f), y(12f))
    cubicTo(x(6f), y(15.5f), x(8f), y(18.5f), x(11f), y(20f))
    cubicTo(x(11.5f), y(20.2f), x(12f), y(20.5f), x(12f), y(21f))
    cubicTo(x(12f), y(20.5f), x(12.5f), y(20.2f), x(13f), y(20f))
    cubicTo(x(16f), y(18.5f), x(18f), y(15.5f), x(18f), y(12f))
    cubicTo(x(18f), y(6f), x(12f), y(3f), x(12f), y(3f))
    close()
}

private fun leafVein(scale: Float): Path = Path().apply {
    fun x(n: Float) = n * scale
    fun y(n: Float) = n * scale
    moveTo(x(12f), y(6f))
    cubicTo(x(12f), y(6f), x(14f), y(8.5f), x(14f), y(11.5f))
    cubicTo(x(14f), y(13.5f), x(13f), y(15f), x(12f), y(16f))
}

@Composable
fun ReleafLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    filled: Boolean = true,
    outlineColor: Color = AppColors.OnAccent,
    fillGradientStart: Color = AppAccent.primary,
    fillGradientEnd: Color = AppAccent.deep,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        val body = leafBody(scale)
        val vein = leafVein(scale)
        val sw = strokeWidth.toPx()

        if (filled) {
            val gradient = Brush.linearGradient(
                colors = listOf(fillGradientStart, fillGradientEnd),
                start = Offset(12f * scale, 3f * scale),
                end   = Offset(12f * scale, 21f * scale),
            )
            drawPath(body, brush = gradient)
            // Hairline deep-green rim, matches the brand SVG's 0.5-unit stroke
            drawPath(
                body,
                color = fillGradientEnd,
                style = Stroke(width = sw * 0.25f, join = StrokeJoin.Round),
            )
        }

        // Cream brand outline
        drawPath(
            body,
            color = outlineColor,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Vein @ 40% alpha of outline color
        drawPath(
            vein,
            color = outlineColor.copy(alpha = 0.4f),
            style = Stroke(width = sw * 0.67f, cap = StrokeCap.Round),
        )
    }
}

/**
 * Outline-only variant used where the leaf sits on a colored plate (e.g.
 * the Releaf Branding splash — cream outline on coral / green). The
 * body gradient is skipped so the plate color shows through.
 */
@Composable
fun ReleafLogoOutline(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    color: Color = AppColors.OnAccent,
    strokeWidth: Dp = 3.dp,
) {
    ReleafLogo(
        modifier = modifier,
        size = size,
        filled = false,
        outlineColor = color,
        strokeWidth = strokeWidth,
    )
}
