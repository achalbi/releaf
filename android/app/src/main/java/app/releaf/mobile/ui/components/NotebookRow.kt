/*
 * NotebookRow.kt
 *
 * List row for a notebook: icon chip + title + chapter tag + description
 * + meta + active status + chevron.
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChevronRight
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
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun NotebookRow(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    chapterTag: String? = null,
    description: String? = null,
    isActive: Boolean = false,
    icon: ImageVector = Icons.Filled.Book,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        IconChip(icon = icon)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Text(
                    text = title,
                    style = AppTypography.Button,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (chapterTag != null) ChapterTag(chapterTag)
                Spacer(Modifier.weight(1f))
                if (isActive) ActivePill()
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }

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
    }
}

@Composable
private fun IconChip(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppColors.CoralSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.CoralDeep,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ChapterTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.CoralSoft)
            .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
    ) {
        Text(text = text, style = AppTypography.Tag, color = AppColors.CoralDeep)
    }
}

@Composable
private fun ActivePill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.SuccessSoft)
            .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(AppColors.Success),
        )
        Text("Active", style = AppTypography.Tag, color = AppColors.Success)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun NotebookRowPreview() {
    Column(
        modifier = Modifier
            .background(AppColors.Canvas)
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        NotebookRow(
            title = "Observability",
            chapterTag = "Ch. 1",
            description = "Dashboards, alerts, and on-call runbooks.",
            meta = "2 chapters · 3 pages · Updated 10 days ago",
            isActive = true,
        )
        NotebookRow(
            title = "Daily journal",
            meta = "1 chapter · 12 pages · Updated today",
        )
    }
}
