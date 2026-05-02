/*
 * QuickInkDotGridBackground.kt
 *
 * Notebook-paper dot-grid texture for QuickInk surfaces. Mirror of
 * `:shared:designsystem`'s `DotGridBackground` but expressed as a
 * `Modifier` so it can drop into any existing modifier chain in
 * place of `.background(colors.bg)`. Same shape and posture as the
 * sibling `quickInkLinedPaper` modifier.
 *
 * Usage — drop in on the root container of any screen:
 *
 *     Column(
 *         modifier = Modifier
 *             .fillMaxSize()
 *             .quickInkDotGridBackground(),
 *     ) { ... }
 *
 * Tokens are baked in (warm-brown `inkSoft` @ 35% alpha on `bg`)
 * so every screen reads as the same paper texture without per-call
 * params. Spacing 24dp / dot 1dp matches the shared `DotGridBackground`
 * defaults.
 */

package app.quickink.mobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.quickInkDotGridBackground(
    spacing: Dp = 24.dp,
    dotSize: Dp = 1.dp,
): Modifier {
    val colors = LocalQuickInkColors.current
    val dotColor = colors.inkSoft.copy(alpha = 0.35f)
    val canvas = colors.bg
    return this
        .background(canvas)
        .drawBehind {
            val spacingPx = spacing.toPx()
            val radiusPx  = dotSize.toPx() / 2f
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
