/*
 * SundialCaptureMenu.kt
 *
 * Radial capture menu that fans three action buttons out in a 180°
 * arc above the centred ⚡ FAB on the bottom-nav. Tapping the FAB
 * opens the menu; each ray launches a specific capture mode
 * (Document / Business Card / Photo). Replaces the prior
 * tap-for-last-mode + long-press-for-photo idiom with a single
 * explicit choice surface.
 *
 *        Video
 *      ╱
 *     ●  ← FAB centre (anchor)
 *      ╲
 *        Photo
 *
 *   - Scan  → 150° (top-left)
 *   - Video →  90° (top, primary)
 *   - Photo →  30° (top-right)
 *
 * Video and Photo both open the [PhotoCaptureSurface], which
 * supports both still capture (tap shutter) and hold-to-record
 * video on the same camera session. The two rays exist so users
 * who think "I want a video" don't have to know that internally
 * we share a surface — they pick the verb that matches their
 * intent and the surface adapts.
 *
 * Geometry mirrors the design handoff: 110dp radius from the FAB
 * centre, vertical-spring overshoot on open, staggered left → top
 * → right (60ms between rays), simultaneous on close. Dim overlay
 * is the canvas cream at 0.7 alpha.
 *
 * The menu renders as a full-screen sibling of the NavHost so the
 * dim layer covers the canvas AND the floating nav bar card. Rays
 * sit ABOVE the dim but BELOW the FAB itself — the FAB stays
 * interactive throughout (toggle / close on second tap), matching
 * the prototype's z-stack.
 *
 * Mirror of iOS `SundialCaptureMenu.swift`.
 */

package app.quickink.mobile.features.nav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen overlay that renders the three radial capture
 * buttons. Owns no state — the parent (MainShell) drives
 * [isOpen] and the three select callbacks plumb back into the
 * QuickCapture sheet's `initialMode`.
 */
@Composable
fun SundialCaptureMenu(
    isOpen: Boolean,
    onClose: () -> Unit,
    onSelectScan: () -> Unit,
    onSelectVideo: () -> Unit,
    onSelectPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current

    // The dim overlay animates between fully transparent (closed)
    // and the cream-tinted backdrop (open). Tap on it dismisses;
    // disabled while closed so taps fall through to the canvas.
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "sundial-overlay-alpha",
    )

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Dim layer — `colors.bg` (canvas cream) at 0.72 alpha so
        // the bar + canvas read through faintly without being
        // obtrusive. Drawn first so it sits BEHIND the rays.
        if (overlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(overlayAlpha)
                    .background(colors.bg.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = interactionSource,
                        indication        = null,
                        enabled           = isOpen,
                        onClick           = onClose,
                    ),
            )
        }

        // Ray anchor — positioned at the FAB centre. The nav bar
        // sits at safeArea + s6 (24dp) from the bottom; its card
        // is ~64dp tall; the FAB inside is lifted -16dp from the
        // card top — so the FAB centre is approximately
        // safeArea + 24 + 32 (half bar) - 16 (lift) + 0 = +40dp
        // above the bar bottom. Mirror that with padding here so
        // the rays emanate from the bolt.
        Box(
            modifier = Modifier
                .padding(bottom = 56.dp)
                .size(60.dp),
            contentAlignment = Alignment.Center,
        ) {
            Ray(
                label              = "Scan",
                icon               = Icons.Outlined.DocumentScanner,
                angleDeg           = 150.0,
                openDelayMs        = 0,
                accessibilityLabel = "Scan document",
                isOpen             = isOpen,
                onClick            = onSelectScan,
            )
            Ray(
                label              = "Video",
                icon               = Icons.Outlined.Videocam,
                angleDeg           = 90.0,
                openDelayMs        = 60,
                accessibilityLabel = "Record video",
                isOpen             = isOpen,
                onClick            = onSelectVideo,
            )
            Ray(
                label              = "Photo",
                icon               = Icons.Outlined.CameraAlt,
                angleDeg           = 30.0,
                openDelayMs        = 120,
                accessibilityLabel = "Take photo",
                isOpen             = isOpen,
                onClick            = onSelectPhoto,
            )
        }
    }
}

/**
 * Arc radius from FAB centre to each ray's button centre. 110dp
 * (vs. the spec's 120) leaves the leftmost ray clear of the
 * 320dp edge of small-screen devices.
 */
private val RAY_RADIUS = 110.dp

@Composable
private fun Ray(
    label: String,
    icon: ImageVector,
    angleDeg: Double,
    openDelayMs: Int,
    accessibilityLabel: String,
    isOpen: Boolean,
    onClick: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val density = LocalDensity.current

    val rad = angleDeg * Math.PI / 180.0
    val dx  = (cos(rad) * RAY_RADIUS.value).dp
    // Compose Y grows downward — negate so positive angles
    // (above horizontal) translate UPWARD on screen.
    val dy  = (-sin(rad) * RAY_RADIUS.value).dp

    val animSpec = spring<Float>(
        dampingRatio = 0.55f,
        stiffness    = Spring.StiffnessMediumLow,
    )

    // animateFloatAsState lets us drive `scale`, `opacity`, and
    // translation through the same spring. Delay only applied
    // when opening (close fires all three rays together for a
    // snappy dismiss).
    val progress by animateFloatAsState(
        targetValue   = if (isOpen) 1f else 0f,
        animationSpec = if (isOpen) {
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(durationMillis = 240)
        },
        label = "sundial-ray-progress-$label",
    )

    val translatedX = with(density) { (dx.toPx()) * progress }
    val translatedY = with(density) { (dy.toPx()) * progress }
    val scale       = 0.4f + (0.6f * progress)
    val rayAlpha    = progress

    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .graphicsLayer {
                translationX    = translatedX
                translationY    = translatedY
                scaleX          = scale
                scaleY          = scale
                this.alpha      = rayAlpha
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .semantics { contentDescription = accessibilityLabel }
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                enabled           = isOpen,
                onClick           = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .shadow(elevation = 10.dp, shape = CircleShape, clip = false)
                .background(colors.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(26.dp),
            )
        }
        Box(
            modifier = Modifier
                .shadow(elevation = 4.dp, shape = RoundedCornerShape(11.dp))
                .background(
                    color = colors.surface.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(11.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text     = label,
                style    = type.label.copy(fontSize = 12.sp),
                color    = colors.ink,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
