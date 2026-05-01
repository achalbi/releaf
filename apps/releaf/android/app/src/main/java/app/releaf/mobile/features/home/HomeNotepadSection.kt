/*
 * HomeNotepadSection.kt
 * Compact "Notepad" card for the Home tab — header eyebrow with an
 * "Open →" affordance on top, and a 4-up stats bar below aggregating
 * all notepad pages (ENTRIES / PHOTOS / TODOS / TODAY). Mirrors the
 * visual treatment of [HomeShelvesSection] so the Home tab reads as
 * two parallel summary cards.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight

@Composable
fun HomeNotepadSection(
    totalEntries: Int,
    totalPhotos: Int,
    openTodos: Int,
    todayCount: Int,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.lg),
            )
            .clickable { onOpenAll() }
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "NOTEPAD · $totalEntries",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Open  →",
                style = AppTypography.Button,
                color = AppAccent.primary,
            )
        }

        StatsGrid(
            entries    = totalEntries,
            photos     = totalPhotos,
            openTodos  = openTodos,
            today      = todayCount,
        )
    }
}

// ---------- Stats grid ----------

@Composable
private fun StatsGrid(
    entries: Int,
    photos: Int,
    openTodos: Int,
    today: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        StatCard(
            label      = "ENTRIES",
            value      = "$entries",
            background = AppColors.GreenSoft,
            border     = AppColors.ThemeGreenBorderSoft,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "PHOTOS",
            value      = "$photos",
            background = AppColors.CardSolid,
            border     = AppColors.BorderDefault,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "TODOS",
            value      = "$openTodos",
            valueColor = AppColors.Coral,
            background = AppColors.CardSolid,
            border     = AppColors.BorderDefault,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "TODAY",
            value      = "$today",
            background = AppColors.CoralSoft,
            modifier   = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    background: Color,
    border: Color? = null,
    valueColor: Color = AppColors.TextPrimary,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(background)
            .then(
                if (border != null)
                    Modifier.border(1.dp, border, RoundedCornerShape(AppRadius.md))
                else Modifier
            )
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = label,
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 26.sp,
            fontWeight = LocalFontWeight.current,
            fontFamily = FontFamily.Serif,
        )
    }
}
