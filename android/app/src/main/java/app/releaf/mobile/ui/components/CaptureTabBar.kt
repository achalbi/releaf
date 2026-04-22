/*
 * CaptureTabBar.kt
 *
 * Row of 7 icon buttons inside page detail:
 *   Overview · Photos · Voice · To-do · Scans · Contacts · Location
 *
 * - Active tab: filled coral rounded rectangle, white icon.
 * - Inactive : transparent, dark-brown icon, icon-only on mobile.
 * - Spacing is tuned so all 7 tabs fit on the narrowest phone widths
 *   we support (375dp / iPhone SE, similar Android small devices).
 *   The `horizontalScroll` wrapper stays as a safety net for the rare
 *   case of a device smaller than that, but in practice the row
 *   renders flush without scrolling on every production target.
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing

@Composable
fun CaptureTabBar(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    modes: List<CaptureMode> = CaptureMode.entries,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.Canvas)
            .drawBottomBorder(AppColors.BorderDefault)
            .horizontalScroll(rememberScrollState())
            .padding(
                // Tighter side padding + narrower inter-tab gap than the
                // rest of the app: 7 tabs × 44dp + 6 × 4dp + 2 × 12dp =
                // 356dp, which fits inside a 360dp-wide viewport with a
                // little to spare. This is the minimum we support.
                horizontal = AppSpacing.s3,
                vertical   = AppSpacing.s2,
            ),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        modes.forEach { mode ->
            CaptureTabButton(
                mode = mode,
                isSelected = selected == mode,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun CaptureTabButton(
    mode: CaptureMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) AppColors.TextOnAccent else AppColors.TextPrimary
    val bg   = if (isSelected) AppColors.Coral        else Color.Transparent

    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = mode.icon,
            contentDescription = mode.title,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 1dp hairline along the bottom edge. */
private fun Modifier.drawBottomBorder(color: Color): Modifier =
    this.drawBehind {
        val w = 1.dp.toPx()
        drawLine(
            color = color,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = w,
        )
    }

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun CaptureTabBarPreview() {
    var mode by remember { mutableStateOf(CaptureMode.Overview) }
    Box(Modifier.height(80.dp)) {
        CaptureTabBar(selected = mode, onSelect = { mode = it })
    }
}
