/*
 * LibraryShelfIcon.kt
 *
 * Custom ImageVector for the BottomNav "Library" tab. Material Icons
 * Extended doesn't ship a glyph that reads as "row of book spines on a
 * shelf" — `LibraryBooks` is a stack of horizontal documents, `MenuBook`
 * is an open book, etc. This draws four upright book spines of varying
 * heights with the rightmost one tilted off-vertical, matching the
 * Variant D bottom-nav prototype.
 *
 * Strokes only, no fill — `Icon`'s `tint` parameter recolors the whole
 * glyph via ColorFilter.tint so it picks up the active/inactive coral
 * vs. textPrimary tints from the BottomNav exactly like every other
 * Material Icon in the bar.
 *
 * Viewport is 24×24 — same as Material Icons — so visual weight stays
 * consistent when mixed with `Icons.Outlined.*` siblings.
 */

package app.releaf.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bookshelf-row glyph: three upright book spines of varying heights
 * plus a fourth book tilted slightly off vertical at the right edge.
 * Outlined (stroke only) so the bar reads as a uniform line-icon set.
 */
val LibraryShelf: ImageVector = ImageVector.Builder(
    name = "LibraryShelf",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    val strokeColor = SolidColor(Color.Black)
    val strokeW = 1.6f

    // Book 1 — leftmost, mid-height spine.
    path(
        fill = SolidColor(Color.Transparent),
        stroke = strokeColor,
        strokeLineWidth = strokeW,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3.5f, 4f)
        lineTo(6f, 4f)
        lineTo(6f, 20f)
        lineTo(3.5f, 20f)
        close()
    }

    // Book 2 — slightly shorter spine, sits behind Book 3.
    path(
        fill = SolidColor(Color.Transparent),
        stroke = strokeColor,
        strokeLineWidth = strokeW,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(7.5f, 6f)
        lineTo(10f, 6f)
        lineTo(10f, 20f)
        lineTo(7.5f, 20f)
        close()
    }

    // Book 3 — tallest upright spine.
    path(
        fill = SolidColor(Color.Transparent),
        stroke = strokeColor,
        strokeLineWidth = strokeW,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(11.5f, 3f)
        lineTo(14f, 3f)
        lineTo(14f, 20f)
        lineTo(11.5f, 20f)
        close()
    }

    // Book 4 — tilted parallelogram, leans right of vertical.
    path(
        fill = SolidColor(Color.Transparent),
        stroke = strokeColor,
        strokeLineWidth = strokeW,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(16f, 8f)
        lineTo(19.5f, 7f)
        lineTo(21f, 19f)
        lineTo(17.5f, 20f)
        close()
    }
}.build()
