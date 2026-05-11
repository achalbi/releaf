/*
 * CardGuideOverlay.kt
 *
 * The translucent business-card-shaped guide drawn over the
 * camera preview. Three responsibilities:
 *
 *   1. Frame the 1.586:1 / 70%-of-preview-width target so the
 *      user knows where to hold the card.
 *   2. Tint the frame neutral / yellow / green based on
 *      detection state.
 *   3. Render the "Hold steady…" / "Position card inside the
 *      frame" hint text below the guide.
 *
 * Pure Compose — no Android-View interop. The parent surface
 * composes this on top of the PreviewView and feeds it the
 * current [OverlayState] from the analyzer.
 */

package app.quickink.mobile.features.scan.cardcapture

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkTypography

/**
 * What the overlay is being told to show. Mirrors the spec's
 * neutral / yellow / green visual states 1:1.
 */
enum class OverlayState {
    /** No valid quad anywhere. Frame is neutral; hint blank. */
    Neutral,
    /** A quad is being seen but failed at least one gate. Frame yellow; hint "Hold the card flat". */
    Partial,
    /** A valid quad — within IoU, aspect, etc. Frame green; hint "Hold steady…". */
    Valid,
    /** No detection for ≥8 s. Frame neutral; hint "Position card inside the frame". */
    Idle,
}

/**
 * Card-frame dimensions in canvas pixels. The same arithmetic
 * powers both the drawn rect and the analyzer-frame
 * [GuideRect] — the surface re-derives the analyzer-space rect
 * by scaling these numbers by analyzer-width / canvas-width.
 */
data class GuideMetrics(
    val rect: Rect,
    val cornerRadiusPx: Float,
) {
    companion object {
        /** ISO 7810 ID-1 aspect — same as the spec's 1.586:1. */
        const val CARD_ASPECT_RATIO = 1.586f
        /** Guide width as a fraction of the preview width. */
        const val GUIDE_WIDTH_FRACTION = 0.70f

        fun compute(canvasWidth: Float, canvasHeight: Float): GuideMetrics {
            val targetW = canvasWidth * GUIDE_WIDTH_FRACTION
            val targetH = targetW / CARD_ASPECT_RATIO
            val cx = canvasWidth * 0.5f
            // 45% rather than 50% — gives the hint text room
            // between the guide and the shutter row below.
            val cy = canvasHeight * 0.45f
            val left = cx - targetW / 2f
            val top  = cy - targetH / 2f
            return GuideMetrics(
                rect = Rect(left, top, left + targetW, top + targetH),
                cornerRadiusPx = targetW * 0.04f,
            )
        }
    }
}

@Composable
fun CardGuideOverlay(
    state: OverlayState,
    onMetricsKnown: (GuideMetrics) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameColor by animateColorAsState(
        targetValue = when (state) {
            OverlayState.Valid   -> Color(0xFF34D399) // green
            OverlayState.Partial -> Color(0xFFFACC15) // yellow
            OverlayState.Neutral,
            OverlayState.Idle    -> Color.White.copy(alpha = 0.65f)
        },
        animationSpec = tween(durationMillis = 150),
        label = "card-overlay-frame-color",
    )

    val hintText = when (state) {
        OverlayState.Valid   -> "Hold steady…"
        OverlayState.Partial -> "Hold the card flat"
        OverlayState.Idle    -> "Position card inside the frame"
        OverlayState.Neutral -> ""
    }

    val type = LocalQuickInkTypography.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canvasWidthPx  = with(density) { maxWidth.toPx() }
        val canvasHeightPx = with(density) { maxHeight.toPx() }
        val metrics = GuideMetrics.compute(canvasWidthPx, canvasHeightPx)

        // Publish metrics back to the surface so it can compute
        // the analyzer-space GuideRect for the detector. Fires
        // every recomposition that changes constraints; safe to
        // pass the same struct repeatedly since the surface
        // stores it in `remember { mutableStateOf(...) }`.
        onMetricsKnown(metrics)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Punched-hole dim — full-screen scrim with the card
            // rect cut out. Path difference so the alpha doesn't
            // multiply against the preview.
            val scrim = Path().apply {
                addRect(Rect(Offset.Zero, Size(size.width, size.height)))
            }
            val cardHole = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = metrics.rect,
                        cornerRadius = CornerRadius(metrics.cornerRadiusPx, metrics.cornerRadiusPx),
                    )
                )
            }
            val masked = Path().apply {
                op(scrim, cardHole, PathOperation.Difference)
            }
            drawPath(path = masked, color = Color(0xCC0F0E0D))

            // Card-shaped frame stroke.
            drawRoundRect(
                color  = frameColor,
                topLeft = Offset(metrics.rect.left, metrics.rect.top),
                size   = Size(metrics.rect.width, metrics.rect.height),
                cornerRadius = CornerRadius(metrics.cornerRadiusPx, metrics.cornerRadiusPx),
                style  = Stroke(width = with(density) { 3.dp.toPx() }),
            )
        }

        // Hint text — horizontally centered inside the overlay,
        // offset vertically so it lands just below the guide
        // rect's bottom edge.
        if (hintText.isNotEmpty()) {
            val hintTopDp = with(density) { (metrics.rect.bottom + 24f).toDp() }
            Box(
                modifier = Modifier
                    .offset(x = 0.dp, y = hintTopDp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = hintText,
                    style = type.label,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}
