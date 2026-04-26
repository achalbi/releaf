/*
 * SettingsScreen.kt
 *
 * User-facing preferences screen. Hosts:
 *   • [ThemePickerSection] — theme mode (System / Light / Dark) +
 *     accent palette (Coral / Green / Yellow / Dry). Moved here from
 *     the Home screen so the Home surface stays content-focused.
 *   • Drive sync settings.
 *   • The Tasks default-view picker — chooses which mode (List /
 *     Perspectives) the Tasks screen opens in. Writes the same
 *     [TaskDefaultView] preference the in-screen switcher uses, so
 *     toggling from either surface is equivalent.
 *   • Notebook layout variant.
 *   • The Sign Out action.
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.components.ThemePickerSection
import app.releaf.mobile.ui.theme.ActivityRetention
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.TextButton as M3TextButton
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppFontWeight
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.NotebookListVariant
import app.releaf.mobile.ui.theme.TaskDefaultView
import app.releaf.mobile.ui.theme.TimelineStyle
import app.releaf.mobile.ui.theme.UiPreferences
import app.releaf.mobile.ui.theme.toFontWeight
import app.releaf.mobile.ui.theme.LocalFontWeight

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

        // ── Appearance (theme mode + accent palette) ──────────────
        // Lives at the top of the settings list now — was previously
        // embedded in Home so it was one tap away from launch, but
        // the Home cards grew enough that this picker was crowding
        // them out.
        ThemePickerSection()

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

        // ── Notebook layout variant ────────────────────────────────
        NotebookVariantCard(
            selected = state.notebookVariant,
            onSelect = prefs::setNotebookVariant,
        )

        Spacer(Modifier.height(AppSpacing.s6))

        // ── Home timeline style ────────────────────────────────────
        // Two renderers for the same activity-feed data: the classic
        // dot-on-rail card or the editorial bramble vine variant.
        TimelineStyleCard(
            selected = state.timelineStyle,
            onSelect = prefs::setTimelineStyle,
        )

        Spacer(Modifier.height(AppSpacing.s6))

        // ── Typographic weight ─────────────────────────────────────
        FontWeightCard(
            selected = state.fontWeight,
            onSelect = prefs::setFontWeight,
        )

        Spacer(Modifier.height(AppSpacing.s6))

        // ── Activity log retention + manual clear ─────────────────
        ActivityLogCard(
            retention = state.activityRetention,
            onSelect  = prefs::setActivityRetention,
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

// ================================================================= Notebook variant card

/**
 * Two-option picker for which shelves/chapters/page treatment the
 * notebook tab renders with. Mirrors the Tasks default-view card
 * visually so the two prefs feel uniform.
 */
@Composable
private fun NotebookVariantCard(
    selected: NotebookListVariant,
    onSelect: (NotebookListVariant) -> Unit,
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
                text = "NOTEBOOKS",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text = "Layout",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text = "Which visual treatment to use for shelves, chapters, and pages.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            VariantOption(
                label = "Classic",
                subtitle = "List cards, plain chapters",
                isActive = selected == NotebookListVariant.Classic,
                onClick = { onSelect(NotebookListVariant.Classic) },
                modifier = Modifier.weight(1f),
            )
            VariantOption(
                label = "Hero cards",
                subtitle = "Colored volumes + editorial pages",
                isActive = selected == NotebookListVariant.Variant1,
                onClick = { onSelect(NotebookListVariant.Variant1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ================================================================= Timeline style card

/**
 * Two-option picker for which renderer the Home activity timeline
 * uses. Identical data shape (RecentActivityViewModel) — the picker
 * just toggles the visual treatment between the classic dot-on-rail
 * card and the bramble vine variant.
 */
@Composable
private fun TimelineStyleCard(
    selected: TimelineStyle,
    onSelect: (TimelineStyle) -> Unit,
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
                text = "HOME",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text = "Timeline style",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text = "Which renderer to use for the recent-activity card on Home.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            VariantOption(
                label = "Classic",
                subtitle = "Dot-on-rail timeline",
                isActive = selected == TimelineStyle.Classic,
                onClick = { onSelect(TimelineStyle.Classic) },
                modifier = Modifier.weight(1f),
            )
            VariantOption(
                label = "Bramble",
                subtitle = "Vine with leaves and berries",
                isActive = selected == TimelineStyle.Bramble,
                onClick = { onSelect(TimelineStyle.Bramble) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VariantOption(
    label: String,
    subtitle: String,
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
                onClickLabel = "Set notebook layout to $label",
            ) { onClick() }
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(
            text  = label,
            style = AppTypography.Button,
            color = fg,
        )
        Text(
            text  = subtitle,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
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
                style = AppTypography.Button,
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

// ================================================================= Font-weight card

/**
 * Picker for the global typographic weight. Writes [AppFontWeight]
 * into [UiPreferences]; `ReleafTheme` reads it on the next frame and
 * provides the resolved `FontWeight` via `LocalFontWeight`, which
 * every type role on `AppTypography` reads — so changing the
 * selection here repaints every styled `Text` in the app.
 */
@Composable
private fun FontWeightCard(
    selected: AppFontWeight,
    onSelect: (AppFontWeight) -> Unit,
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
                text  = "TYPOGRAPHY",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text  = "Font weight",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Applies to every text style across the app.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            AppFontWeight.values().forEach { option ->
                FontWeightOption(
                    weight   = option,
                    isActive = selected == option,
                    onClick  = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FontWeightOption(
    weight: AppFontWeight,
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
                onClickLabel = "Set font weight to ${weight.label}",
            ) { onClick() }
            .padding(vertical = AppSpacing.s3, horizontal = AppSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        // Render the label in its own weight so the picker previews
        // each option visually — picking "Medium" reads heavier than
        // picking "Light" right in the chip.
        Text(
            text  = "Aa",
            style = AppTypography.SectionTitle.copy(fontWeight = weight.toFontWeight()),
            color = fg,
        )
        Text(
            text  = weight.label,
            style = AppTypography.Meta,
            color = fg,
        )
    }
}

private val AppFontWeight.label: String
    get() = when (this) {
        AppFontWeight.Light    -> "Light"
        AppFontWeight.Regular  -> "Regular"
        AppFontWeight.Medium   -> "Medium"
        AppFontWeight.SemiBold -> "SemiBold"
    }

// ================================================================= Activity log card

/**
 * Settings card for the audit-log feature: live event count,
 * retention picker (drives the daily prune worker), and a manual
 * "Clear all activity" affordance with a confirmation dialog.
 *
 * Reads the count via a `produceState` that re-runs on every
 * recomposition where the underlying audit table emits — combined
 * with `RecentActivityRepository.countForUser`, the chip stays
 * live without a dedicated VM.
 */
@Composable
private fun ActivityLogCard(
    retention: ActivityRetention,
    onSelect: (ActivityRetention) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Local name `releafApp` to avoid shadowing the `app.*` package
    // namespace used by fully-qualified type references elsewhere.
    val releafApp = context.applicationContext as ReleafApp
    val signedIn = releafApp.authStore.state.collectAsState().value
        as? AuthState.SignedIn
    val userId = signedIn?.session?.userId
    val scope  = androidx.compose.runtime.rememberCoroutineScope()
    var showClearDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // Count is observed via the same audit Flow the timeline uses —
    // size of an effectively-unbounded snapshot. Capped at 10k so an
    // enormous log doesn't allocate a huge list just for the count.
    val items by produceState(initialValue = 0, userId) {
        if (userId == null) {
            value = 0
            return@produceState
        }
        releafApp.recentActivityRepository
            .observe(userId, maxItems = 10_000)
            .collect { value = it.size }
    }

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
                text  = "ACTIVITY",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text  = "Activity log",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "$items events stored. The daily prune worker drops anything older than the retention window.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            ActivityRetention.values().forEach { option ->
                RetentionOption(
                    retention = option,
                    isActive  = retention == option,
                    onClick   = { onSelect(option) },
                    modifier  = Modifier.weight(1f),
                )
            }
        }

        AppButton(
            text      = "Clear all activity",
            onClick   = { showClearDialog = true },
            variant   = AppButtonVariant.Secondary,
            fillWidth = false,
        )
    }

    if (showClearDialog && userId != null) {
        M3AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    "Clear all activity?",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                )
            },
            text = {
                Text(
                    "This drops every audit event for your account. The log will reseed itself from your current notebooks + notes on next launch.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                M3TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        releafApp.recentActivityRepository.clearForUser(userId)
                    }
                }) {
                    Text("Clear", color = AppColors.Danger)
                }
            },
            dismissButton = {
                M3TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSolid,
        )
    }
}

@Composable
private fun RetentionOption(
    retention: ActivityRetention,
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
                onClickLabel = "Set retention to ${retention.label}",
            ) { onClick() }
            .padding(vertical = AppSpacing.s3, horizontal = AppSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(
            text  = retention.label,
            style = AppTypography.Button,
            color = fg,
        )
    }
}

private val ActivityRetention.label: String
    get() = when (this) {
        ActivityRetention.Days30  -> "30 days"
        ActivityRetention.Days90  -> "90 days"
        ActivityRetention.Days365 -> "1 year"
        ActivityRetention.Forever -> "Forever"
    }
