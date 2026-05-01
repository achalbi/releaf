/*
 * PagePreviewRow.kt
 *
 * Compact row for a page preview inside chapter sections or the home
 * "Continue" list.
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun PagePreviewRow(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Filled.Description,
    photoCount: Int = 0,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onClick)
            .padding(AppSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        IconChip(icon = icon)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = AppTypography.Button,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = meta,
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (photoCount > 0) {
            Thumbnail(photoCount = photoCount)
        }
    }
}

@Composable
private fun IconChip(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppAccent.soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent.deep,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun Thumbnail(photoCount: Int) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppColors.Subtle),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (photoCount > 1) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.TextPrimary.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "+${photoCount - 1}",
                    style = AppTypography.Tag,
                    color = AppColors.TextOnAccent,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun PagePreviewRowPreview() {
    Column(
        modifier = Modifier
            .background(AppColors.Canvas)
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        PagePreviewRow(
            title = "Sunday, Apr 19 — Page 1",
            description = "Standup notes, follow-ups on the auth rework.",
            meta = "Updated 2 hours ago",
            photoCount = 3,
        )
        PagePreviewRow(
            title = "Saturday grocery run",
            meta = "Updated yesterday",
        )
    }
}
