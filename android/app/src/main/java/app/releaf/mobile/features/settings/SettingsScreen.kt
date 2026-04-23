/*
 * SettingsScreen.kt
 *
 * User-facing preferences screen. Today it hosts two things:
 *   • The Tasks default-view picker — chooses which mode (List /
 *     Perspectives) the Tasks screen opens in. Writes the same
 *     [TaskDefaultView] preference the in-screen switcher uses, so
 *     toggling from either surface is equivalent.
 *   • The Sign Out action.
 *
 * Appearance (theme-mode + palette) is still handled by the home-
 * screen [ThemePickerSection]; it hasn't moved here yet.
 */

package app.releaf.mobile.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.TaskDefaultView
import app.releaf.mobile.ui.theme.UiPreferences

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs   = remember(context) { UiPreferences.get(context) }
    val state  by prefs.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SETTINGS", style = AppTypography.Eyebrow, color = AppAccent.primary)
        Spacer(Modifier.height(AppSpacing.s1))
        Text(
            "Preferences",
            style = AppTypography.EditorialTitle,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(AppSpacing.s6))

        // ── Drive sync ─────────────────────────────────────────────
        DriveSettingsSection()

        Spacer(Modifier.height(AppSpacing.s6))

        // ── Tasks default view ─────────────────────────────────────
        DefaultTaskViewCard(
            selected = state.defaultTaskView,
            onSelect = prefs::setDefaultTaskView,
        )

        Spacer(Modifier.height(AppSpacing.s6))

        // ── Sign out ───────────────────────────────────────────────
        AppButton(
            text    = "Sign out",
            onClick = onSignOut,
            variant = AppButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

// ================================================================= Default-view card

/**
 * Bounded card with a two-option picker for which view the Tasks
 * screen opens in. Mirrors the Tasks screen's own view-mode
 * switcher visually — icon + label on each option — but with
 * helper copy on the active choice so the user knows what they're
 * picking.
 */
@Composable
private fun DefaultTaskViewCard(
    selected: TaskDefaultView,
    onSelect: (TaskDefaultView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(
                text  = "TASKS",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text  = "Default view",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Which layout the Tasks screen opens in. The switcher at the top of Tasks writes to the same preference.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            ViewOption(
                label    = "Perspectives",
                subtitle = "Context tiles with a board inside",
                icon     = Icons.Filled.ViewColumn,
                isActive = selected == TaskDefaultView.Perspectives,
                onClick  = { onSelect(TaskDefaultView.Perspectives) },
                modifier = Modifier.weight(1f),
            )
            ViewOption(
                label    = "List",
                subtitle = "Editorial, date-grouped feed",
                icon     = Icons.AutoMirrored.Filled.FormatListBulleted,
                isActive = selected == TaskDefaultView.List,
                onClick  = { onSelect(TaskDefaultView.List) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ViewOption(
    label: String,
    subtitle: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg     = if (isActive) AppAccent.soft else AppColors.InputBg
    val fg     = if (isActive) AppAccent.primary else AppColors.TextPrimary
    val border = if (isActive) AppAccent.primary else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(AppRadius.md))
            .clickable(
                role = Role.RadioButton,
                onClickLabel = "Set default view to $label",
            ) { onClick() }
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(
                        if (isActive) AppAccent.primary else AppColors.CardSolid,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = if (isActive) AppColors.OnAccent else AppColors.TextSecondary,
                    modifier           = Modifier.size(16.dp),
                )
            }
            Text(
                text  = label,
                style = AppTypography.Button.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
            )
        }
        Text(
            text  = subtitle,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}
