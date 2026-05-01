/*
 * HomeLibrarySection.kt
 * Unified "Your library" card for the Home tab — left column shows
 * notebook stats (STREAK / BOOKS / PAGES / OPEN TODOS), right column
 * shows notepad stats (ENTRIES / TODAY / CAPTURES / OPEN TODOS).
 * Replaces the earlier HomeShelvesSection + HomeNotepadSection
 * stack with a single card, matching prototype B.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeLibrarySection(
    notebooks: List<Notebook>,
    totalNotepadEntries: Int,
    totalNotepadCaptures: Int,
    openNotepadTodos: Int,
    todayNotepadCount: Int,
    onOpenNotebooks: () -> Unit,
    onOpenNotepad: () -> Unit,
    openTodos: Int = 0,
    modifier: Modifier = Modifier,
) {
    val streak = remember(notebooks) { computeStreak(notebooks) }
    val totalPages = remember(notebooks) { notebooks.sumOf { it.pageCount } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "YOUR LIBRARY",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "→",
                style = AppTypography.Button,
                color = AppAccent.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            // Notebooks column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenNotebooks() }
                    .padding(end = AppSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                SubheaderLine(text = "NOTEBOOKS · ${notebooks.size}")
                StatRow(label = "STREAK", value = "$streak", suffix = "d")
                StatRow(label = "BOOKS",  value = "${notebooks.size}")
                StatRow(label = "PAGES",  value = "$totalPages")
                StatRow(label = "OPEN TODOS", value = "$openTodos",
                        valueColor = AppColors.Coral)
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(180.dp)
                    .background(AppColors.BorderDefault),
            )

            // Notepad column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenNotepad() }
                    .padding(start = AppSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                SubheaderLine(text = "NOTEPAD · $totalNotepadEntries")
                StatRow(label = "ENTRIES",    value = "$totalNotepadEntries")
                StatRow(label = "TODAY",      value = "$todayNotepadCount")
                StatRow(label = "CAPTURES",   value = "$totalNotepadCaptures")
                StatRow(label = "OPEN TODOS", value = "$openNotepadTodos",
                        valueColor = AppColors.Coral)
            }
        }
    }
}

@Composable
private fun SubheaderLine(text: String) {
    Text(
        text = text,
        style = AppTypography.Eyebrow,
        color = AppColors.TextSecondary,
        maxLines = 1,
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    suffix: String? = null,
    valueColor: Color = AppColors.TextPrimary,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 20.sp,
                fontWeight = LocalFontWeight.current,
                fontFamily = FontFamily.Serif,
            )
            if (suffix != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = suffix,
                    style = AppTypography.Tag,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

private fun computeStreak(notebooks: List<Notebook>): Int {
    if (notebooks.isEmpty()) return 0
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val floor = today.minusDays(30)
    return notebooks.asSequence()
        .map { it.updatedAt.atZone(zone).toLocalDate() }
        .filter { !it.isBefore(floor) }
        .distinct()
        .count()
}
