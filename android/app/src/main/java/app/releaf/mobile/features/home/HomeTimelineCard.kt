/*
 * HomeTimelineCard.kt
 *
 * "Today" timeline preview on the Home screen — five most-recent
 * entity-level edits (notepad entries, pages, chapters, notebooks)
 * stitched together by RecentActivityRepository. Phase 1 of the
 * activity-log work; richer per-action events arrive when the audit
 * table lands.
 *
 * Tap "See full timeline" to push the full ActivityScreen.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.activity.ActivityAction
import app.releaf.mobile.data.activity.ActivityItem
import app.releaf.mobile.data.activity.ActivityKind
import app.releaf.mobile.features.activity.RecentActivityViewModel
import app.releaf.mobile.ui.components.ActivityEntry
import app.releaf.mobile.ui.components.ActivityProminence
import app.releaf.mobile.ui.components.ActivityTimeline
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AccentPaletteId
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.TimelineStyle
import app.releaf.mobile.ui.theme.UiPreferences
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeTimelineCard(
    onSeeAll: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RecentActivityViewModel = viewModel(
        factory = RecentActivityViewModel.factory(RecentActivityViewModel.HOME_LIMIT),
    ),
) {
    val context = LocalContext.current
    val prefs = remember(context) { UiPreferences.get(context) }
    val style by prefs.state.collectAsState()
    val items by viewModel.items.collectAsState()

    when (style.timelineStyle) {
        TimelineStyle.Classic -> ClassicTimelineCard(items = items, onSeeAll = onSeeAll, modifier = modifier)
        TimelineStyle.Bramble -> BrambleTimelineCard(items = items, onSeeAll = onSeeAll, modifier = modifier)
    }
}

@Composable
private fun ClassicTimelineCard(
    items: List<ActivityItem>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateLabel = remember {
        DateTimeFormatter.ofPattern("EEE MMM d").format(LocalDate.now()).uppercase()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "RECENT · $dateLabel",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${items.size}",
                style = AppTypography.Tag,
                color = AppColors.TextTertiary,
            )
        }

        if (items.isEmpty()) {
            Text(
                text = "No activity yet — start by adding a note.",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                items.forEachIndexed { index, item ->
                    TimelineRow(item, isLast = index == items.lastIndex)
                }
            }
        }

        Text(
            text = "See full timeline  →",
            style = AppTypography.Button,
            color = AppAccent.primary,
            modifier = Modifier.clickable { onSeeAll() },
        )
    }
}

/**
 * Bramble variant — same data, rendered through [ActivityTimeline] so
 * each entry becomes a colored 5-petal flower on the serpentine vine.
 *
 * Maps each [ActivityItem] to an [ActivityEntry]:
 *   - `kind` → [AccentPaletteId] via [paletteFor]
 *   - top entry → `Featured` prominence (bigger flower); rest → `Routine`
 *   - `relativeTimeAgo(timestamp)` powers the date label so the
 *     existing tense matches the Classic card.
 */
@Composable
private fun BrambleTimelineCard(
    items: List<ActivityItem>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // When there's no real activity yet, render a single placeholder
    // entry so the bramble visual is still visible — otherwise flipping
    // the toggle on a fresh install looks identical to Classic and the
    // user has no way to know the switch worked.
    val entries = if (items.isEmpty()) {
        listOf(
            ActivityEntry(
                date = "When you start",
                title = "Your activity will appear here",
                preview = "Add a note or open a notebook to begin.",
                theme = AccentPaletteId.Coral,
                prominence = ActivityProminence.Featured,
            ),
        )
    } else {
        items.mapIndexed { index, item ->
            ActivityEntry(
                id = item.id,
                date = relativeTimeAgo(item.timestamp),
                title = labelFor(item),
                preview = item.context,
                theme = paletteFor(item.kind),
                prominence = if (index == 0) ActivityProminence.Featured else ActivityProminence.Routine,
            )
        }
    }

    ActivityTimeline(
        entries = entries,
        header = "ACTIVITY",
        showsArrow = true,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onSeeAll() },
    )
}

/**
 * ActivityKind → AccentPaletteId mapping for the bramble timeline.
 * Mirrors the iOS mapping in [paletteFor] in the Swift companion file.
 *
 *  - Capture-in-the-moment (notepad, voice) → coral
 *  - Organic structure (notebook / page / chapter) → green
 *  - Highlights / actions worth landing on (photo / scan / todo) → yellow
 *  - Reference data (contact / location) → dry brown
 */
internal fun paletteFor(kind: ActivityKind): AccentPaletteId = when (kind) {
    ActivityKind.NotepadEntry, ActivityKind.Voice    -> AccentPaletteId.Coral
    ActivityKind.Notebook, ActivityKind.Page,
    ActivityKind.Chapter                              -> AccentPaletteId.Green
    ActivityKind.Photo, ActivityKind.Scan,
    ActivityKind.Todo                                 -> AccentPaletteId.Yellow
    ActivityKind.Contact, ActivityKind.Location       -> AccentPaletteId.Dry
}

@Composable
private fun TimelineRow(item: ActivityItem, isLast: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accentFor(item.kind))
                    .border(2.dp, AppColors.CardSolid, CircleShape),
            )
            // Hide the rail segment under the last row so the trail
            // doesn't dangle into empty space below the list.
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(12.dp)
                        .background(AppColors.Subtle),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = relativeTimeAgo(item.timestamp),
                style = AppTypography.Tag,
                color = AppColors.TextSecondary,
            )
            Text(
                text = labelFor(item),
                style = AppTypography.Meta,
                color = AppColors.TextPrimary,
                maxLines = 2,
            )
        }
    }
}

/** Per-kind dot color so the timeline is scannable at a glance.
 *  Sub-event captures (Photo/Scan/Voice/Todo/Contact/Location) map
 *  to the same swatches used in the Trees Saved hero legend so the
 *  timeline and hero read as the same palette. */
internal fun accentFor(kind: ActivityKind): Color = when (kind) {
    ActivityKind.NotepadEntry -> Color(0xFFE77850) // coral
    ActivityKind.Page         -> Color(0xFF1E5943) // green
    ActivityKind.Chapter      -> Color(0xFFB8956A) // dry
    ActivityKind.Notebook     -> Color(0xFFF4C430) // yellow
    ActivityKind.Photo        -> Color(0xFFF4C430) // SegPhotos — yellow
    ActivityKind.Scan         -> Color(0xFFE77850) // SegScans — coral
    ActivityKind.Voice        -> Color(0xFFFCEAE0) // SegVoice — peach
    ActivityKind.Todo         -> Color(0xFF7AA874) // SegNotes — leaf green
    ActivityKind.Contact      -> Color(0xFFD9EDE2) // SegContacts — pale green
    ActivityKind.Location     -> Color(0xFFB8956A) // SegLocations — dry
}

/** "<verb> <thing> · <title>", e.g. "Updated note · Morning notes". */
internal fun labelFor(item: ActivityItem): String {
    val verb = when (item.action) {
        ActivityAction.Created  -> "Created"
        ActivityAction.Updated  -> "Updated"
        ActivityAction.Deleted  -> "Deleted"
        ActivityAction.Restored -> "Restored"
        ActivityAction.Merged   -> "Merged"
        ActivityAction.Moved    -> "Moved"
    }
    val noun = when (item.kind) {
        ActivityKind.NotepadEntry -> "note"
        ActivityKind.Page         -> "page"
        ActivityKind.Chapter      -> "chapter"
        ActivityKind.Notebook     -> "notebook"
        ActivityKind.Photo        -> "photo"
        ActivityKind.Scan         -> "scan"
        ActivityKind.Voice        -> "voice note"
        ActivityKind.Todo         -> "todo"
        ActivityKind.Contact      -> "contact"
        ActivityKind.Location     -> "location"
    }
    return "$verb $noun · ${item.title}"
}
