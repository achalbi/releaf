/*
 * OnboardingIllustrations.kt
 *
 * Hero illustrations for the 3 onboarding steps. Mirror of iOS
 * `NotebookScanIllustration`, `CameraIllustration`,
 * `DriveIllustration` defined in `OnboardingScaffold.swift`.
 *
 * Each illustration is its own composable — slot into
 * `OnboardingScaffold(illustration = { NotebookScanIllustration() })`
 * etc.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkLinedPaper

// MARK: - Step 1: Notebook + scan-line

/**
 * Layered notebook + page hero with a coral scan line and four
 * detection corners. Mirror of iOS `NotebookScanIllustration`.
 */
@Composable
fun NotebookScanIllustration() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val transition = rememberInfiniteTransition(label = "scan-sweep")
    val sweep by transition.animateFloat(
        initialValue = -90f,
        targetValue  = 110f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-sweep-y",
    )

    Box(contentAlignment = Alignment.Center) {

        // Back notebook silhouette — slightly tilted.
        Box(
            modifier = Modifier
                .offset(x = (-16).dp, y = 8.dp)
                .rotate(-6f)
                .size(width = 220.dp, height = 280.dp)
                .shadow(8.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(colors.paper1)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
        )

        // Front "page" — lined paper with coral margin + handwritten
        // text peek.
        Box(
            modifier = Modifier
                .offset(x = 20.dp, y = (-8).dp)
                .rotate(2f)
                .size(width = 200.dp, height = 260.dp)
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        ) {
            // Lined paper background overlay (uses the surface tone
            // as base, not a paper tone, so the front page reads as
            // "the active page" against the warm back notebook).
            Canvas(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 200.dp, height = 260.dp),
            ) {
                val lineColor = colors.ink.copy(alpha = 0.10f)
                val spacing = 14.dp.toPx()
                var y = spacing
                while (y < size.height) {
                    drawLine(
                        color       = lineColor,
                        start       = Offset(0f, y),
                        end         = Offset(size.width, y),
                        strokeWidth = 0.5f,
                    )
                    y += spacing
                }
            }
            // Coral margin line.
            Box(
                modifier = Modifier
                    .offset(x = 28.dp)
                    .padding(vertical = 12.dp)
                    .width(1.5.dp)
                    .height(236.dp)
                    .background(colors.accent.copy(alpha = 0.7f))
            )
            // Handwritten title peek.
            Column(
                modifier = Modifier
                    .padding(start = 40.dp, top = 24.dp),
            ) {
                Text(
                    text  = "Ideas",
                    style = type.handwritten.copy(fontSize = 22.sp),
                    color = colors.ink.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "notebook synced.",
                    style = type.handwritten.copy(fontSize = 16.sp),
                    color = colors.ink.copy(alpha = 0.5f),
                )
            }
        }

        // Scan line — animated coral horizontal sweep with gradient.
        Box(
            modifier = Modifier
                .offset(x = 20.dp, y = sweep.dp)
                .size(width = 200.dp, height = 2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            colors.accent.copy(alpha = 0f),
                            colors.accent.copy(alpha = 0.6f),
                            colors.accent,
                            colors.accent.copy(alpha = 0.6f),
                            colors.accent.copy(alpha = 0f),
                        )
                    )
                ),
        )

        // Detection corners — four coral L-shapes at the front
        // page bounds.
        DetectionCorner(rotation = 0f,   xOffset = (-80).dp, yOffset = (-120).dp)
        DetectionCorner(rotation = 90f,  xOffset = 120.dp,   yOffset = (-120).dp)
        DetectionCorner(rotation = 270f, xOffset = (-80).dp, yOffset = 104.dp)
        DetectionCorner(rotation = 180f, xOffset = 120.dp,   yOffset = 104.dp)
    }
}

@Composable
private fun DetectionCorner(rotation: Float, xOffset: androidx.compose.ui.unit.Dp, yOffset: androidx.compose.ui.unit.Dp) {
    val colors = LocalQuickInkColors.current
    Canvas(
        modifier = Modifier
            .offset(x = xOffset + 20.dp, y = yOffset - 8.dp)
            .rotate(rotation)
            .size(14.dp),
    ) {
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width, 0f)
        }
        drawPath(
            path  = path,
            color = colors.accent,
            style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

// MARK: - Step 2: Camera viewfinder

/**
 * Camera viewfinder ring with a small page glyph centered. Mirror
 * of iOS `CameraIllustration`.
 */
@Composable
fun CameraIllustration() {
    val colors = LocalQuickInkColors.current

    Box(contentAlignment = Alignment.Center) {

        // Outer dashed ring.
        Canvas(modifier = Modifier.size(240.dp)) {
            drawCircle(
                color  = colors.accent.copy(alpha = 0.35f),
                radius = size.minDimension / 2 - 1.dp.toPx(),
                style  = Stroke(
                    width      = 2.dp.toPx(),
                    cap        = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx())),
                ),
            )
        }

        // Inner soft fill circle.
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
        )

        // Page glyph in center.
        Box(
            modifier = Modifier
                .rotate(-4f)
                .size(width = 100.dp, height = 130.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
        ) {
            Canvas(modifier = Modifier.padding(8.dp).size(width = 84.dp, height = 114.dp)) {
                val lineColor = colors.ink.copy(alpha = 0.12f)
                val spacing = 10.dp.toPx()
                var y = spacing
                while (y < size.height) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y),
                        end   = Offset(size.width, y),
                        strokeWidth = 0.5f,
                    )
                    y += spacing
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = 14.dp)
                    .padding(vertical = 8.dp)
                    .width(1.dp)
                    .height(114.dp)
                    .background(colors.accent.copy(alpha = 0.7f))
            )
        }

        // Aperture marker on the ring.
        Box(
            modifier = Modifier
                .offset(y = (-120).dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.accent),
        )
    }
}

// MARK: - Step 3: Drive cloud

/**
 * Cloud upload glyph paired with notebook pages. Mirror of iOS
 * `DriveIllustration`.
 *
 * Uses Material's `Icons.Filled.Cloud` for the silhouette rather
 * than a hand-built rounded-rectangle proxy — the Material cloud
 * renders the iconic cloud shape recognisably at any size. The
 * "bordered surface" look is reproduced by stacking the outline
 * variant (`Icons.Outlined.Cloud`) on top in the border tint.
 */
@Composable
fun DriveIllustration() {
    val colors = LocalQuickInkColors.current

    Box(contentAlignment = Alignment.Center) {

        // Soft halo behind cloud.
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
        )

        // Cloud silhouette — surface-tinted filled cloud with a
        // thin border-tinted outline stacked on top. No drop shadow:
        // an earlier pass tried a heavy 24dp ink-tinted shadow to
        // match the mock, but Compose's elevation paints a hard
        // rectangle behind the icon (it doesn't respect the SVG
        // path), which read as a dark box framing the cloud rather
        // than a soft drop. The accentSoft halo behind the cloud
        // already gives enough lift on the warm canvas.
        Box(
            modifier         = Modifier.offset(y = (-20).dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Cloud,
                contentDescription = null,
                tint              = colors.surface,
                modifier          = Modifier.size(width = 220.dp, height = 150.dp),
            )
            Icon(
                imageVector       = Icons.Outlined.Cloud,
                contentDescription = null,
                tint              = colors.border,
                modifier          = Modifier.size(width = 220.dp, height = 150.dp),
            )
            // Up-arrow inside the cloud — coral, indicating upload.
            Icon(
                imageVector       = Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier
                    .size(48.dp)
                    .offset(y = (-2).dp),
            )
        }

        // Two paper cards below cloud, suggesting pages flowing up.
        Box(
            modifier = Modifier
                .offset(x = (-10).dp, y = 80.dp)
                .rotate(6f)
                .size(width = 50.dp, height = 64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.paper1)
                .border(1.dp, colors.border, RoundedCornerShape(4.dp)),
        )
        Box(
            modifier = Modifier
                .offset(x = 30.dp, y = 84.dp)
                .rotate(-4f)
                .size(width = 50.dp, height = 64.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.paper2)
                .border(1.dp, colors.border, RoundedCornerShape(4.dp)),
        )
    }
}

