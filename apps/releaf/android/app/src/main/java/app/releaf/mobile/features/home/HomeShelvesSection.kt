/*
 * HomeShelvesSection.kt
 * Compact "Notebooks" card for the Home tab — header eyebrow with a
 * "See all →" affordance on top, and a 4-up stats bar below
 * (STREAK / BOOKS / PAGES / OPEN TODOS). No filter chips, no shelf
 * hero cards on Home — the full library browsing lives on the
 * Notebooks tab.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
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
fun HomeShelvesSection(
    notebooks: List<Notebook>,
    onOpenAll: () -> Unit,
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
                text = "NOTEBOOKS · ${notebooks.size}",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "See all  →",
                style = AppTypography.Button,
                color = AppAccent.primary,
            )
        }

        StatsGrid(
            streak    = streak,
            books     = notebooks.size,
            pages     = totalPages,
            openTodos = openTodos,
        )
    }
}

// ---------- Stats grid (ported from the Shelf tab) ----------

@Composable
private fun StatsGrid(
    streak: Int,
    books: Int,
    pages: Int,
    openTodos: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        StatCard(
            label      = "STREAK",
            value      = "$streak",
            suffix     = "d",
            background = AppColors.CoralSoft,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "BOOKS",
            value      = "$books",
            background = AppColors.GreenSoft,
            border     = AppColors.ThemeGreenBorderSoft,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "PAGES",
            value      = "$pages",
            background = AppColors.CardSolid,
            border     = AppColors.BorderDefault,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "OPEN TODOS",
            value      = "$openTodos",
            valueColor = AppColors.Coral,
            background = AppColors.CardSolid,
            border     = AppColors.BorderDefault,
            modifier   = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
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
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 26.sp,
                fontWeight = LocalFontWeight.current,
                fontFamily = FontFamily.Serif,
            )
            if (suffix != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = suffix,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

// ---------- Helpers ----------

/**
 * Best-effort streak — distinct calendar days in the last 30 on
 * which any notebook was updated.
 */
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
