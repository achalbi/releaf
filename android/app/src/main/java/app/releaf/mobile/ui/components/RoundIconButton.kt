/*
 * RoundIconButton.kt
 *
 * Small rounded-square button with a coral-soft wash behind a tinted icon.
 * Used throughout the notebook / chapter / page cards for lightweight inline
 * actions — edit, collapse, add — where a full `AppButton` would be too
 * heavy.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius

@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    background: Color = AppAccent.soft,
    tint: Color = AppAccent.deep,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
