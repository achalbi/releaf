/*
 * LeafDropletGlyph.kt
 *
 * The tiny leaf/droplet glyph that sits in the corner of every stat
 * tile in the page Overview. Drawn as a pointed-top oval — a stylized
 * leaf seen from the side, not a literal water droplet.
 *
 * This shared definition is intentionally CaptureMode-agnostic so it
 * can live in `:shared:designsystem` (which `PageHeaderControls.kt`
 * needs). The `CaptureMode` → tint mapping moved out to
 * `LeafDropletGlyphTints.kt` in the Releaf app target — see PR #4h.
 *
 * Mirrors iOS PR #4g's same split.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LeafDropletGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 11.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w / 2f, 0f)
            quadraticTo(w * 1.15f, h * 0.45f, w / 2f, h)
            quadraticTo(-w * 0.15f, h * 0.45f, w / 2f, 0f)
            close()
        }
        drawPath(path, color = tint)
    }
}
