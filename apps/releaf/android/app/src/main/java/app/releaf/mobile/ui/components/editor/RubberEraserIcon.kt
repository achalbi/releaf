/*
 * RubberEraserIcon.kt
 *
 * Custom ImageVector for the drawing toolbar's erase mode. Material
 * Icons Extended 1.7.3 doesn't ship an `InkEraser` / eraser glyph, and
 * the best analog we'd normally reach for (`Backspace`) still reads as
 * a keyboard key rather than a pencil rubber. This draws a tilted
 * rectangular eraser body + a small baseline bar underneath so the
 * glyph is unambiguous at toolbar size (24 dp).
 *
 * Viewport is 24×24 — matches the rest of the Material Icons the
 * toolbar uses, so mixing with other `Icons.Filled.*` values inside
 * `ModeIcon` keeps visual weights consistent.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Pencil-rubber (British-English "rubber") eraser icon — drawn to read
 * like Material Symbols' `ink_eraser` glyph.
 *
 * Two sub-paths:
 *   1. The eraser body — a parallelogram tilted ~45° off vertical, so
 *      the shape reads as an eraser-on-its-side.
 *   2. A short horizontal baseline at the bottom of the viewport —
 *      suggests the paper the eraser rubs against, which is the
 *      visual cue that sells the "eraser" association at 24 dp.
 *
 * Fill is `Color.Black` so `Icon`'s `tint` parameter recolors the
 * whole glyph consistently with every other Material Icon we use.
 */
val RubberEraser: ImageVector = ImageVector.Builder(
    name = "RubberEraser",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    // --- Eraser body (parallelogram, tilted upper-right → lower-left) ---
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 3f)
        lineTo(21f, 8f)
        lineTo(11f, 18f)
        lineTo(6f, 13f)
        close()
    }
    // --- Surface line under the eraser ---
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 20f)
        lineTo(21f, 20f)
        lineTo(21f, 21f)
        lineTo(3f, 21f)
        close()
    }
}.build()
