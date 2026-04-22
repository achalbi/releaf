/*
 * DrawingOverlay.kt
 *
 * Freehand drawing layer that stacks above the rich-text editor. When
 * `mode` is `Pen`, drag gestures capture a new stroke; `Eraser` removes
 * any stroke whose points fall within the eraser radius of the pointer.
 * When `Off`, the composable doesn't install a pointerInput modifier so
 * touches fall through to the text editor underneath.
 *
 * Coordinates are stored in dp on the Stroke itself — rendering converts
 * to px at draw time via the current density. This keeps strokes usable
 * across densities; they're pinned to layout position (not to text
 * offsets) per the v1 spec.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.Stroke
import kotlin.math.hypot

enum class DrawingMode { Off, Pen, Eraser }

/**
 * Live pen config — `color` is the swatch pick, `opacity` is the 0..1
 * multiplier from the opacity picker, `widthDp` is the thickness preset,
 * `nib` drives the render style.
 */
data class PenConfig(
    val color: Color,
    val opacity: Float,
    val widthDp: Float,
    val nib: String,
)

private const val ERASER_RADIUS_DP = 12f

@Composable
fun DrawingOverlay(
    strokes: List<Stroke>,
    mode: DrawingMode,
    penConfig: PenConfig,
    onStrokesChange: (List<Stroke>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerDp = density.density
    val eraserRadiusPx = ERASER_RADIUS_DP * pxPerDp

    val latestStrokes by rememberUpdatedState(strokes)
    val latestOnChange by rememberUpdatedState(onStrokesChange)
    val latestConfig by rememberUpdatedState(penConfig)

    // Active in-progress stroke (pen mode). Points in dp.
    var livePoints by remember { mutableStateOf<List<Float>>(emptyList()) }

    // Re-key pointerInput on mode only — changing color/width mid-stroke
    // shouldn't abort the current gesture, and we read `latestConfig`
    // through rememberUpdatedState anyway.
    val gestureModifier = if (mode == DrawingMode.Off) {
        Modifier
    } else {
        Modifier.pointerInput(mode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val buffer = mutableListOf(
                    down.position.x / pxPerDp,
                    down.position.y / pxPerDp,
                )
                if (mode == DrawingMode.Pen) livePoints = buffer.toList()
                if (mode == DrawingMode.Eraser) {
                    latestOnChange(eraseAt(latestStrokes, down.position, eraserRadiusPx, pxPerDp))
                }

                var active = true
                while (active) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) {
                        active = false
                    } else {
                        buffer += change.position.x / pxPerDp
                        buffer += change.position.y / pxPerDp
                        if (mode == DrawingMode.Pen) livePoints = buffer.toList()
                        if (mode == DrawingMode.Eraser) {
                            latestOnChange(eraseAt(latestStrokes, change.position, eraserRadiusPx, pxPerDp))
                        }
                        change.consume()
                    }
                }

                if (mode == DrawingMode.Pen && buffer.size >= 2) {
                    val cfg = latestConfig
                    val argb = cfg.color.copy(alpha = cfg.opacity).toArgb()
                    val committed = Stroke(
                        id     = Uuidv7.generate(),
                        points = buffer.toList(),
                        color  = argb,
                        width  = cfg.widthDp,
                        nib    = cfg.nib,
                    )
                    latestOnChange(latestStrokes + committed)
                    livePoints = emptyList()
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(gestureModifier),
    ) {
        strokes.forEach { drawStroke(it, pxPerDp) }
        if (livePoints.size >= 2) {
            val cfg = latestConfig
            val argb = cfg.color.copy(alpha = cfg.opacity).toArgb()
            drawStroke(
                Stroke(
                    id     = "live",
                    points = livePoints,
                    color  = argb,
                    width  = cfg.widthDp,
                    nib    = cfg.nib,
                ),
                pxPerDp,
            )
        }
    }
}

/**
 * Stroke-level erase. Returns the list with every stroke removed that
 * has any point within `radiusPx` of `hitPx`. Keeps v1 simple — we
 * don't split strokes at the eraser, we drop the whole thing. If the
 * list is unchanged we still hand back the same instance so callers
 * can short-circuit.
 */
private fun eraseAt(
    strokes: List<Stroke>,
    hitPx: Offset,
    radiusPx: Float,
    pxPerDp: Float,
): List<Stroke> {
    if (strokes.isEmpty()) return strokes
    val radiusSq = radiusPx * radiusPx
    val survivors = strokes.filterNot { stroke ->
        var i = 0
        val pts = stroke.points
        while (i + 1 < pts.size) {
            val dx = pts[i] * pxPerDp - hitPx.x
            val dy = pts[i + 1] * pxPerDp - hitPx.y
            if (dx * dx + dy * dy <= radiusSq) return@filterNot true
            i += 2
        }
        false
    }
    return if (survivors.size == strokes.size) strokes else survivors
}

private fun DrawScope.drawStroke(s: Stroke, pxPerDp: Float) {
    if (s.points.size < 2) return
    val color = Color(s.color)
    val basePx = s.width * pxPerDp
    when (s.nib) {
        Stroke.NIB_FOUNTAIN    -> drawFountain(s.points, color, basePx, pxPerDp)
        Stroke.NIB_HIGHLIGHTER -> drawHighlighter(s.points, color, basePx, pxPerDp)
        else                   -> drawBallpoint(s.points, color, basePx, pxPerDp)
    }
}

private fun DrawScope.drawBallpoint(points: List<Float>, color: Color, widthPx: Float, pxPerDp: Float) {
    if (points.size == 2) {
        drawCircle(
            color  = color,
            radius = widthPx / 2f,
            center = Offset(points[0] * pxPerDp, points[1] * pxPerDp),
        )
        return
    }
    val path = buildPath(points, pxPerDp)
    drawPath(
        path  = path,
        color = color,
        style = DrawStroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Highlighter: wider, flatter cap, with a cap on alpha so stacking
 * strokes doesn't blow out to fully opaque. The extra 0.6 multiplier
 * on alpha is the "highlighter feel" even when the user picked 100%
 * opacity — matches the way real highlighters still see-through.
 */
private fun DrawScope.drawHighlighter(points: List<Float>, color: Color, widthPx: Float, pxPerDp: Float) {
    val c = color.copy(alpha = (color.alpha * 0.6f).coerceIn(0f, 1f))
    val w = widthPx * 1.8f
    if (points.size == 2) {
        drawCircle(color = c, radius = w / 2f, center = Offset(points[0] * pxPerDp, points[1] * pxPerDp))
        return
    }
    val path = buildPath(points, pxPerDp)
    drawPath(
        path  = path,
        color = c,
        style = DrawStroke(width = w, cap = StrokeCap.Butt, join = StrokeJoin.Bevel),
    )
}

/**
 * Fountain: per-segment width inversely proportional to segment
 * length, approximating pressure (faster strokes = thinner line). We
 * lerp between a min (fast) and max (slow) width band around the
 * configured nominal width so the line reads as "fountain pen"
 * without needing real stylus pressure.
 */
private fun DrawScope.drawFountain(points: List<Float>, color: Color, widthPx: Float, pxPerDp: Float) {
    val minW = widthPx * 0.35f
    val maxW = widthPx * 1.3f
    // Anchor the velocity ramp — distances above this go to min width.
    val fastThresholdPx = 24f * pxPerDp
    if (points.size == 2) {
        drawCircle(color = color, radius = maxW / 2f, center = Offset(points[0] * pxPerDp, points[1] * pxPerDp))
        return
    }
    var i = 0
    while (i + 3 < points.size) {
        val x0 = points[i] * pxPerDp
        val y0 = points[i + 1] * pxPerDp
        val x1 = points[i + 2] * pxPerDp
        val y1 = points[i + 3] * pxPerDp
        val dist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        val t = (dist / fastThresholdPx).coerceIn(0f, 1f)
        val w = maxW + (minW - maxW) * t
        drawLine(
            color       = color,
            start       = Offset(x0, y0),
            end         = Offset(x1, y1),
            strokeWidth = w,
            cap         = StrokeCap.Round,
        )
        i += 2
    }
}

private fun buildPath(points: List<Float>, pxPerDp: Float): Path {
    val p = Path()
    p.moveTo(points[0] * pxPerDp, points[1] * pxPerDp)
    var i = 2
    while (i + 1 < points.size) {
        p.lineTo(points[i] * pxPerDp, points[i + 1] * pxPerDp)
        i += 2
    }
    return p
}
