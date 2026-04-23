/*
 * CaptureTabBar.kt
 *
 * Segmented-picker row for the page editor tabs:
 *   Overview · Photos · Voice · To-do · Scans · Contacts · Location
 *
 * Visual: rounded-rect Muted container with seven equal-weight icon
 * segments. The active segment is marked by a coral "indicator" pill
 * that slides between positions via a spring animation when the
 * selection changes — matches the Mobile Segmented Picker pattern in
 * the design system. The selected icon flips to the on-accent tint so
 * it reads against the coral fill; unselected icons sit in
 * TextSecondary against the Muted base.
 *
 * Icon-only at every width. All seven segments share the available
 * horizontal space equally (`weight(1f)` per cell) — with s4 side
 * padding on our narrowest supported viewport (360dp) that leaves
 * ~45dp per cell, which is plenty for an 18dp icon. No horizontal
 * scroll fallback: if the viewport ever got narrower than the
 * minimum, segments would still divide the remaining space cleanly
 * rather than clipping off the right edge.
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing

// Inner padding of the segmented container (on all four sides). Keeps
// the indicator from touching the outer rounded edge on the leftmost /
// rightmost segment.
private val SegmentGutter = 4.dp

// Height of the indicator strip. Sizes the whole segmented bar.
private val SegmentHeight = 36.dp

@Composable
fun CaptureTabBar(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    modes: List<CaptureMode> = CaptureMode.entries,
) {
    // Defensive: an empty `modes` list would break the divide later.
    // Not expected in production — CaptureMode.entries is never empty —
    // but cheap to guard.
    if (modes.isEmpty()) return

    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)
    // Fractional index drives the indicator offset. `animateFloatAsState`
    // keeps Compose state in sync with the real selection and lerps the
    // rendered position on each frame until the spring settles.
    val animatedIndex by animateFloatAsState(
        targetValue   = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness    = Spring.StiffnessMediumLow,
        ),
        label = "capture-tab-indicator",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.Canvas)
            .padding(
                horizontal = AppSpacing.s4,
                vertical   = AppSpacing.s2,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(SegmentHeight)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(AppColors.Muted)
                .padding(SegmentGutter),
        ) {
            // `maxWidth` is the inner (padded) width of the container, so
            // segments and the indicator both measure against the same
            // canvas — offsets line up to the pixel.
            val segmentWidth = maxWidth / modes.size

            // Sliding indicator layer. Renders first so the icon layer
            // above it shows through with its tint flipped on the
            // currently-selected cell.
            Box(
                modifier = Modifier
                    .offset(x = segmentWidth * animatedIndex)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(AppAccent.primary),
            )

            // Tap-targets + icons. Each segment claims equal weight so
            // the selected indicator's geometry (segmentWidth) matches
            // the segment's own bounds.
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                modes.forEach { mode ->
                    CaptureSegment(
                        mode       = mode,
                        isSelected = selected == mode,
                        onClick    = { onSelect(mode) },
                        modifier   = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureSegment(
    mode: CaptureMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selected: on-accent tint so the icon reads against the primary
    // fill. Unselected: muted so segments recede into the Muted track.
    val tint = if (isSelected) AppColors.OnAccent else AppColors.TextSecondary

    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = mode.icon,
            contentDescription = mode.title,
            tint               = tint,
            modifier           = Modifier.size(18.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun CaptureTabBarPreview() {
    var mode by remember { mutableStateOf(CaptureMode.Overview) }
    Box(Modifier.height(80.dp)) {
        CaptureTabBar(selected = mode, onSelect = { mode = it })
    }
}
