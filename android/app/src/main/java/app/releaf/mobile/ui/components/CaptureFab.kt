/*
 * CaptureFab.kt
 *
 * Coral FAB for opening the quick-capture sheet.
 *
 * Visual spec:
 *   - 56dp coral circle
 *   - White `+` icon, semibold weight
 *   - Soft coral-tinted shadow
 *   - Pinned bottom-end, above the BottomNav (16-20dp clearance)
 *   - Respects safe-area (WindowInsets.navigationBars) via the overlay helper
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing

@Composable
fun CaptureFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Add,
    contentDescription: String = "New capture",
) {
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                ambientColor = AppAccent.primary,
                spotColor = AppAccent.primary,
            )
            .clip(CircleShape)
            .background(AppAccent.primary)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = AppAccent.deep),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AppColors.TextOnAccent,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Pins a CaptureFab to the bottom-end of a Box, lifted above the bottom nav
 * (default 72dp) with safe-area padding applied to the bottom edge.
 */
@Composable
fun BoxScope.CaptureFabOverlay(
    onClick: () -> Unit,
    liftAboveBar: Dp = 72.dp,
    trailingInset: Dp = AppSpacing.s4,
    icon: ImageVector = Icons.Filled.Add,
) {
    CaptureFab(
        onClick = onClick,
        icon = icon,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(end = trailingInset, bottom = liftAboveBar),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390, heightDp = 640)
@Composable
private fun CaptureFabPreview() {
    Box(Modifier.background(AppColors.Canvas)) {
        CaptureFabOverlay(onClick = {})
    }
}
