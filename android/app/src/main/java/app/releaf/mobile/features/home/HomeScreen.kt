/*
 * HomeScreen.kt
 *
 * Signed-in Home. Keeps the existing greeting / onboarding / tasks
 * / reminders / theme picker, and appends two Room-backed summary
 * cards (Notebook + Notepad) at the end — a compact dashboard view
 * of what the user has actually captured. The mid-screen raw
 * notebook list from the classic design is gone; the Notebook
 * summary card covers that affordance.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.features.contacts.HomeContactsCard
import app.releaf.mobile.features.onboarding.OnboardingQuickGuideCard
import app.releaf.mobile.features.reminder.HomeRemindersCard
import app.releaf.mobile.features.tasks.HomeTasksCard
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun HomeScreen(
    session: GoogleAuthSession,
    onOpenNotebook: (String) -> Unit,
    onOpenNotebooksTab: () -> Unit,
    onOpenNotepadTab: () -> Unit,
    onOpenNotepadEntry: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenContacts: () -> Unit,
    onSignOut: () -> Unit,
    onShowOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeDashboardViewModel = viewModel(factory = HomeDashboardViewModel.factory(session)),
) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s6),
        ) {
            Header(session = session, onSignOut = onSignOut)
            OnboardingQuickGuideCard(onShowIntro = onShowOnboarding)
            HomeTasksCard(onOpenTasks = onOpenTasks)
            HomeRemindersCard(onOpenReminders = onOpenReminders)
            HomeContactsCard(onOpenContacts = onOpenContacts)
            // Appearance picker moved to Settings ▸ Appearance to keep
            // Home focused on content. Was: ThemePickerSection().

            // ── Dashboard cards (new) ────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
                if (state.isLoading) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = AppSpacing.s6),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AppAccent.primary)
                    }
                } else {
                    NotebookSummaryCard(
                        total           = state.totalNotebooks,
                        active          = state.activeNotebooks,
                        archived        = state.archivedNotebooks,
                        onOpenAll       = onOpenNotebooksTab,
                    )
                    NotepadSummaryCard(
                        totalEntries    = state.totalNotepadEntries,
                        todayCount      = state.todayNotepadCount,
                        onOpenAll       = onOpenNotepadTab,
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.s10))
        }
    }
}

// ================================================================== Header

@Composable
private fun Header(session: GoogleAuthSession, onSignOut: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Text("RELEAF", style = AppTypography.Eyebrow, color = AppAccent.primary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                greeting(session),
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Sign out",
                style = AppTypography.Button,
                color = AppAccent.primary,
                modifier = Modifier.clickable { onSignOut() },
            )
        }
    }
}

private fun greeting(session: GoogleAuthSession): String {
    val name = session.displayName?.trim().orEmpty()
    return if (name.isNotEmpty()) "Hi, $name" else "Good morning"
}

// ================================================================== Notebook card

@Composable
private fun NotebookSummaryCard(
    total: Int,
    active: Int,
    archived: Int,
    onOpenAll: () -> Unit,
) {
    PaletteSummaryCard(
        title = "Notebook",
        background = AppAccent.primary,
        onClick = onOpenAll,
    ) {
        SummaryStatsRow(
            items = listOf(
                SummaryStat("Total", "$total"),
                SummaryStat("Active", "$active"),
                SummaryStat("Archived", "$archived"),
            ),
        )
    }
}

// ================================================================== Notepad card

@Composable
private fun NotepadSummaryCard(
    totalEntries: Int,
    todayCount: Int,
    onOpenAll: () -> Unit,
) {
    PaletteSummaryCard(
        title = "Notepad",
        background = AppAccent.deep,
        onClick = onOpenAll,
    ) {
        SummaryStatsRow(
            items = listOf(
                SummaryStat("Entries", "$totalEntries"),
                SummaryStat("Today", "$todayCount"),
            ),
        )
    }
}

// ================================================================== Card shell

private data class SummaryStat(val label: String, val value: String)

@Composable
private fun PaletteSummaryCard(
    title: String,
    background: Color,
    onClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(background)
            .clickable { onClick() }
            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = AppTypography.SectionTitle, color = AppColors.OnAccent,
                 modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = AppColors.OnAccent.copy(alpha = 0.86f),
                modifier = Modifier.size(18.dp),
            )
        }
        body()
    }
}

@Composable
private fun SummaryStatsRow(items: List<SummaryStat>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        items.forEach { item ->
            SummaryStatTile(
                item = item,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryStatTile(
    item: SummaryStat,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.OnAccent.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = AppColors.OnAccent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(AppRadius.md),
            )
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = item.value,
            color = AppColors.OnAccent,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = item.label,
            style = AppTypography.Meta,
            color = AppColors.OnAccent.copy(alpha = 0.82f),
        )
    }
}
