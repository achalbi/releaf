/*
 * HomeActionChipsRow.kt
 * Three compact chips on the Home tab — Tasks (open of total),
 * Reminders (up next · remaining time), Contacts (added count).
 * Sits above the existing full Tasks / Reminders / Contacts cards
 * as a quick-glance summary; each chip taps through to the same
 * destination handler the full card uses.
 *
 * Counts are stubbed for now — wire to real view-model state once
 * HomeDashboardViewModel exposes tasks/reminders/contacts summaries.
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight

@Composable
fun HomeActionChipsRow(
    onOpenTasks: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenContacts: () -> Unit,
    openTasks: Int = 2,
    totalTasks: Int = 5,
    nextReminderMinutes: Int? = 56,
    contactsAdded: Int = 1,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Chip(
            label     = "TASKS",
            primary   = "$openTasks",
            secondary = "of $totalTasks open",
            tint      = ChipTint.Coral,
            onClick   = onOpenTasks,
            modifier  = Modifier.weight(1f),
        )
        Chip(
            label     = "REMINDERS",
            primary   = reminderPrimary(nextReminderMinutes),
            secondary = if (nextReminderMinutes == null) "nothing queued" else "up next",
            tint      = ChipTint.Green,
            onClick   = onOpenReminders,
            modifier  = Modifier.weight(1f),
        )
        Chip(
            label     = "CONTACTS",
            primary   = "$contactsAdded",
            secondary = "added",
            tint      = ChipTint.Neutral,
            onClick   = onOpenContacts,
            modifier  = Modifier.weight(1f),
        )
    }
}

// ---------- Chip ----------

private enum class ChipTint { Coral, Green, Neutral }

@Composable
private fun Chip(
    label: String,
    primary: String,
    secondary: String,
    tint: ChipTint,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (background, border, primaryColor) = when (tint) {
        ChipTint.Coral   -> Triple(AppColors.CoralSoft,   Color.Transparent,             AppColors.CoralDeep)
        ChipTint.Green   -> Triple(AppColors.GreenSoft,   AppColors.ThemeGreenBorderSoft, AppColors.ThemeGreenDeep)
        ChipTint.Neutral -> Triple(AppColors.CardSolid,   AppColors.BorderDefault,       AppColors.TextPrimary)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(AppRadius.md))
            .clickable { onClick() }
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = label,
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
            maxLines = 1,
        )
        Text(
            text = primary,
            color = primaryColor,
            fontSize = 22.sp,
            fontWeight = LocalFontWeight.current,
            fontFamily = FontFamily.Serif,
            maxLines = 1,
        )
        Text(
            text = secondary,
            style = AppTypography.Tag,
            color = AppColors.TextSecondary,
            maxLines = 1,
        )
    }
}

private fun reminderPrimary(mins: Int?): String {
    if (mins == null) return "—"
    if (mins >= 60) return "${mins / 60}h"
    return "${mins}m"
}
