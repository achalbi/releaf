/*
 * NotesBackground.kt
 *
 * Background-pattern layer for a sub-page's notes body. Sits at the
 * bottom of the Z-stack (below the rich-text editor, the ledger form,
 * or the drawing overlay) and draws one of five patterns at a
 * user-controlled scale.
 *
 * Pure rendering: no pointer input, no state. The picker popover owns
 * the pattern choice + scale value and passes them in.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent

/** Spacing at scale 1.0. Grid cells / dot lattice / line gaps all use this. */
private val BaseSpacing = 24.dp

/** Nudge the horizontal rows this far down so the first line / dot row
 *  lands a hair below the text baseline — the rich-text editor has a
 *  small internal top margin and without this offset the text sits a
 *  couple of pixels above the grid, reading as misaligned even though
 *  the spacing itself is correct.
 *
 *  Re-tuned for the current editor text style
 *  (`lineHeight = 24.sp`, `LineHeightStyle(Alignment.Center, Trim.None)`,
 *  `PlatformTextStyle(includeFontPadding = false)`). With those knobs
 *  in place the baseline sits ≈ 17sp below the top of the 24sp line
 *  box, plus the editor's 12dp top padding. 6dp (1dp past baseline)
 *  keeps the text perched just above the ruled line instead of
 *  intersecting it, which reads as "text sitting on the line". */
private val VerticalNudge = 6.dp

/** Margin line position on the ruled pattern. 2/3 of width means the
 *  margin sits to the right — writing area is the left 2/3, margin
 *  column the right 1/3. This is the traditional "ruled paper" look
 *  that sits behind the ledger form on `BG_RULED` sub-pages. */
private const val RuledMarginFraction = 2f / 3f

@Composable
fun NotesBackground(
    background: String,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    if (background == SubPage.BG_PLAIN) return
    val density = LocalDensity.current
    val spacingPx = with(density) { (BaseSpacing * scale).toPx() }
    val strokePx  = with(density) { 1.dp.toPx() }
    val dotRadius = with(density) { 1.5.dp.toPx() }
    // Keep the nudge constant in dp-space regardless of `scale`; the
    // text body doesn't scale, so the alignment offset shouldn't
    // either. Zooming the pattern just changes the *gap* between rows,
    // not where the first row starts relative to the cursor.
    val verticalNudgePx = with(density) { VerticalNudge.toPx() }

    val color = AppColors.BorderDefault
    val marginColor = AppAccent.primary.copy(alpha = 0.35f)

    Canvas(modifier = modifier.fillMaxSize()) {
        when (background) {
            SubPage.BG_GRID  -> drawGrid(spacingPx, verticalNudgePx, strokePx, color)
            SubPage.BG_DOTS  -> drawDots(spacingPx, verticalNudgePx, dotRadius, color)
            SubPage.BG_LINES -> drawHorizontalLines(spacingPx, verticalNudgePx, strokePx, color)
            SubPage.BG_RULED -> {
                drawHorizontalLines(spacingPx, verticalNudgePx, strokePx, color)
                drawVerticalMargin(RuledMarginFraction, strokePx, marginColor)
            }
        }
    }
}

private fun DrawScope.drawGrid(
    spacing: Float,
    verticalOffset: Float,
    strokeWidth: Float,
    color: Color,
) {
    var x = spacing
    while (x < size.width) {
        drawLine(
            color       = color,
            start       = Offset(x, 0f),
            end         = Offset(x, size.height),
            strokeWidth = strokeWidth,
        )
        x += spacing
    }
    var y = spacing + verticalOffset
    while (y < size.height) {
        drawLine(
            color       = color,
            start       = Offset(0f, y),
            end         = Offset(size.width, y),
            strokeWidth = strokeWidth,
        )
        y += spacing
    }
}

private fun DrawScope.drawDots(
    spacing: Float,
    verticalOffset: Float,
    radius: Float,
    color: Color,
) {
    var y = spacing + verticalOffset
    while (y < size.height) {
        var x = spacing
        while (x < size.width) {
            drawCircle(
                color  = color,
                radius = radius,
                center = Offset(x, y),
            )
            x += spacing
        }
        y += spacing
    }
}

private fun DrawScope.drawHorizontalLines(
    spacing: Float,
    verticalOffset: Float,
    strokeWidth: Float,
    color: Color,
) {
    var y = spacing + verticalOffset
    while (y < size.height) {
        drawLine(
            color       = color,
            start       = Offset(0f, y),
            end         = Offset(size.width, y),
            strokeWidth = strokeWidth,
        )
        y += spacing
    }
}

private fun DrawScope.drawVerticalMargin(fraction: Float, strokeWidth: Float, color: Color) {
    val x = size.width * fraction
    drawLine(
        color       = color,
        start       = Offset(x, 0f),
        end         = Offset(x, size.height),
        strokeWidth = strokeWidth,
    )
}
