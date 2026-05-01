/*
 * LeafDropletGlyph.kt
 *
 * The tiny leaf/droplet glyph that sits in the corner of every stat
 * tile in the page Overview. Drawn as a pointed-top oval — a stylized
 * leaf seen from the side, not a literal water droplet.
 *
 * Color is keyed to the capture mode via [tintFor] so the six tiles in
 * the AT A GLANCE grid read as a small palette rather than uniform
 * decoration. Mirrors `LeafDropletGlyph.swift` byte-for-byte on the
 * mapping rule.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors

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

/**
 * Resolve the glyph tint for a capture mode. Mapping mirrors the iOS
 * `LeafDropletGlyph.tint(for:)` rule exactly:
 *
 *   photos   → deep forest green
 *   scans    → mid leaf green
 *   todo     → sprouty light green
 *   contacts → info blue (people, not plants)
 *   place    → coral (location pin)
 *   voice    → warning amber (transcripts decay)
 *   overview → mid leaf green (rarely used — glyph drives stat tiles,
 *              and the overview tile sits in the strip header instead)
 */
@Composable
@ReadOnlyComposable
fun leafDropletTintFor(mode: CaptureMode): Color = when (mode) {
    CaptureMode.Photos   -> AppColors.Green
    CaptureMode.Scans    -> AppColors.ThemeGreenPrimary
    CaptureMode.Todo     -> AppColors.ThemeGreenPrimary.copy(alpha = 0.55f)
    CaptureMode.Contacts -> AppColors.Info
    CaptureMode.Location -> AppColors.CoralDeep
    CaptureMode.Voice    -> AppColors.Warning
    CaptureMode.Overview -> AppColors.ThemeGreenPrimary
    // Notes — text capture, deep-coral tint matching the brand
    // accent so the droplet reads as the "writing" surface
    // among the other section glyphs.
    CaptureMode.Notes    -> AppColors.CoralDeep
}
