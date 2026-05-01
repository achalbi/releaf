/*
 * DotGridBackground.kt
 *
 * Releaf canvas + subtle dot-grid texture. The notebook feel of the app
 * comes mostly from this single overlay — placed behind editor and list
 * surfaces, it gives the cream canvas just enough tooth that it reads
 * as paper instead of a flat fill.
 *
 * Specs are sourced from `design-system/design-tokens.json`:
 *   - `pattern.dotGrid.spacing` → 24dp gap between dot centres
 *   - `pattern.dotGrid.size`    → 1dp dot diameter
 *   - `color.pattern.dotGrid`   → warm brown @ ~35% alpha on light,
 *                                 neutral-50 @ ~5% on dark (texture fades
 *                                 back so the dark canvas stays calm).
 *
 * Mirror of `DotGridBackground.swift` so both platforms share the same
 * visual treatment. Draws every dot in a single `Canvas` call — cheap
 * enough that it can live behind an entire scrolling screen without
 * measurable cost.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors

/**
 * Fills its parent with the warm canvas and stamps the dot grid on top.
 * Caller typically places it as the backmost layer of a `Box` so the
 * real content stacks over it:
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     DotGridBackground()
 *     Content(...)
 * }
 * ```
 *
 * Parameters are overridable for cases where a surface wants a denser
 * or sparser texture (e.g. in a sheet where the grid would fight the
 * content). Defaults match the design tokens.
 */
@Composable
fun DotGridBackground(
    modifier: Modifier = Modifier,
    spacing: Dp = 24.dp,
    dotSize: Dp = 1.dp,
    dotColor: Color = AppColors.DotGrid,
    background: Color = AppColors.Canvas,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(background),
    ) {
        val spacingPx = spacing.toPx()
        val radiusPx  = dotSize.toPx() / 2f

        // Start the grid half a cell in so dots don't hug the edges.
        var y = spacingPx / 2f
        while (y <= size.height) {
            var x = spacingPx / 2f
            while (x <= size.width) {
                drawCircle(
                    color  = dotColor,
                    radius = radiusPx,
                    center = Offset(x, y),
                )
                x += spacingPx
            }
            y += spacingPx
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun DotGridBackgroundPreview() {
    DotGridBackground()
}
