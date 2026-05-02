/*
 * QuickInkLinedPaper.kt
 *
 * Repeating-line gradient that fakes notebook ruling. Mirror of
 * the JSX mockup's `repeating-linear-gradient` thumbnail effect
 * and iOS's `QuickInkLinedPaper` Canvas-based view.
 *
 * Usage — wrap a Box with this as a background to render a
 * note-thumbnail surface:
 *
 *     Box(
 *         modifier = Modifier
 *             .size(width = 160.dp, height = 200.dp)
 *             .quickInkLinedPaper(seed = note.id.hashCode()),
 *     ) { ... }
 */

package app.quickink.mobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Paint a paper tone background with horizontal ruled lines.
 *
 * @param seed         Stable per-note identifier (hash of note ID
 *                     etc.) so each note gets a consistent paper
 *                     tone across sessions.
 * @param lineSpacing  Vertical gap between rules.
 * @param lineOpacity  Opacity of the ink line — lower for subtler
 *                     paper, higher for more pronounced ruling.
 */
@Composable
fun Modifier.quickInkLinedPaper(
    seed: Int,
    lineSpacing: Dp = 12.dp,
    lineOpacity: Float = 0.12f,
): Modifier {
    val colors = LocalQuickInkColors.current
    val tone = colors.paper(seed)
    val lineColor = colors.ink.copy(alpha = lineOpacity)
    return this
        .background(tone)
        .drawBehind {
            val spacingPx = lineSpacing.toPx()
            var y = spacingPx
            while (y < size.height) {
                drawLine(
                    color       = lineColor,
                    start       = androidx.compose.ui.geometry.Offset(0f, y),
                    end         = androidx.compose.ui.geometry.Offset(size.width, y),
                    strokeWidth = 0.5f,
                )
                y += spacingPx
            }
        }
}
