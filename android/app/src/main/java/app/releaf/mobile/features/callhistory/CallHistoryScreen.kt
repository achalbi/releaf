/*
 * CallHistoryScreen.kt
 *
 * Timeline-style list of outbound calls placed from inside the
 * app. Each row renders a coral-outlined badge (phone icon)
 * connected to its neighbours via a vertical rail, with the date,
 * contact name, and a "phone · duration" subtitle on the right.
 *
 * Duration is null for rows that ended before the observer saw a
 * connect event — those surface as "Not connected" so the user
 * can tell the call didn't go through.
 */

package app.releaf.mobile.features.callhistory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.callhistory.CallHistoryRecord
import app.releaf.mobile.ui.components.ReleafLogo
import app.releaf.mobile.ui.components.ScreenHeader
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Shared timeline geometry — the connector-line math in
// `TimelineBadge` needs to match these exact values so the rail
// lines up with each row's badge. Keep them as top-level
// constants rather than row-local locals so the math reads
// without indirection.
private val TIMELINE_RAIL_WIDTH   = 60.dp
private val TIMELINE_BADGE_SIZE   = 48.dp
private val TIMELINE_BADGE_TOP    = 16.dp
private val TIMELINE_ROW_BOTTOM   = 24.dp

@Composable
fun CallHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CallHistoryViewModel = viewModel(factory = CallHistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4)
                .padding(top = AppSpacing.s3, bottom = AppSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(24.dp).clickable { onBack() },
            )
            Spacer(Modifier.weight(1f))
            if (state.entries.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Clear all",
                    tint = AppColors.TextPrimary,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { showClearConfirm = true },
                )
            }
        }
        ScreenHeader(
            eyebrow    = "Calls",
            title      = "Call history",
            topPadding = AppSpacing.s1,
            titleStyle = AppTypography.EditorialTitleLight,
        )

        when {
            state.isLoading -> {
                Text(
                    "Loading…",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(AppSpacing.s4),
                )
            }
            state.isEmpty -> {
                EmptyState()
            }
            else -> {
                val entries = state.entries
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        // Horizontal padding lives on each row so the
                        // connector rail can reach the row's exact
                        // bottom edge.
                        top = AppSpacing.s3,
                    ),
                ) {
                    itemsIndexed(entries, key = { _, record -> record.id }) { index, record ->
                        TimelineRow(
                            record  = record,
                            isFirst = index == 0,
                            isLast  = index == entries.lastIndex,
                        )
                    }
                    item(key = "tail") { Spacer(Modifier.height(AppSpacing.s10)) }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear call history?", style = AppTypography.SectionTitle) },
            text = {
                Text(
                    "This removes every call from the log. It doesn't affect the OS call log.",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearConfirm = false
                }) { Text("Clear", color = AppColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSolid,
        )
    }
}

/**
 * Single timeline entry: a badge in the left rail, the date /
 * name / subtitle stack on the right, and a connector line
 * stitched to its neighbours. The line is drawn in the rail's
 * `drawBehind` so the coral-outlined badge (with canvas fill) sits
 * cleanly on top without any clipping math.
 */
@Composable
private fun TimelineRow(
    record: CallHistoryRecord,
    isFirst: Boolean,
    isLast: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // IntrinsicSize.Min makes the rail Box stretch to match
            // the right column's height so the connector line
            // reaches the full bottom edge of the row.
            .height(IntrinsicSize.Min)
            .padding(horizontal = AppSpacing.s4),
    ) {
        TimelineRail(isFirst = isFirst, isLast = isLast)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top    = TIMELINE_BADGE_TOP,
                    bottom = TIMELINE_ROW_BOTTOM,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                formatDateTime(record.startedAt),
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
            Text(
                record.contactName,
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                "${record.phoneNumber} \u00B7 ${formatDuration(record)}",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun TimelineRail(isFirst: Boolean, isLast: Boolean) {
    val line  = AppColors.BorderDefault
    val ring  = AppColors.Coral
    val inner = AppColors.Canvas

    Box(
        modifier = Modifier
            .width(TIMELINE_RAIL_WIDTH)
            .fillMaxHeight()
            .drawBehind {
                val centerX        = size.width / 2f
                val badgeTop       = TIMELINE_BADGE_TOP.toPx()
                val badgeSize      = TIMELINE_BADGE_SIZE.toPx()
                val badgeBottom    = badgeTop + badgeSize
                val stroke         = 1.dp.toPx()
                if (!isFirst) {
                    drawLine(
                        color       = line,
                        start       = Offset(centerX, 0f),
                        end         = Offset(centerX, badgeTop),
                        strokeWidth = stroke,
                    )
                }
                if (!isLast) {
                    drawLine(
                        color       = line,
                        start       = Offset(centerX, badgeBottom),
                        end         = Offset(centerX, size.height),
                        strokeWidth = stroke,
                    )
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = TIMELINE_BADGE_TOP)
                .size(TIMELINE_BADGE_SIZE)
                .clip(CircleShape)
                .background(inner)
                .drawBehind {
                    // Coral ring around the badge. `drawBehind`
                    // keeps it crisp at any density without the
                    // border modifier's 1px inset artefact.
                    val strokeWidth = 2.dp.toPx()
                    drawCircle(
                        color = ring,
                        radius = (size.minDimension - strokeWidth) / 2f,
                        style = Stroke(width = strokeWidth),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // Brand leaf sits inside the coral-ringed badge. The
            // logo's own cream outline blends into the canvas fill,
            // leaving the green body as the visible mark.
            ReleafLogo(size = 24.dp, strokeWidth = 1.dp)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s4)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .padding(AppSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text("No calls yet", style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
        Text(
            "Calls placed from Contacts show up here with duration.",
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )
    }
}

// =================================================================== Formatting

private fun formatDuration(record: CallHistoryRecord): String {
    val connected = record.connectedAt
    val ended = record.endedAt
    val seconds = record.durationSeconds
    return when {
        seconds != null && seconds > 0 -> prettyDuration(seconds)
        connected != null && ended == null -> "In progress"
        record.wasMissedOrCancelled -> "Not connected"
        seconds == 0L -> "Under 1s"
        else -> "Duration unavailable"
    }
}

private fun prettyDuration(totalSeconds: Long): String {
    val d = Duration.ofSeconds(totalSeconds)
    val h = d.toHours()
    val m = d.toMinutes() % 60
    val s = d.seconds % 60
    return buildString {
        if (h > 0) { append(h); append("h ") }
        if (h > 0 || m > 0) { append(m); append("m ") }
        append(s); append("s")
    }
}

private val FULL_DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM d, yyyy")
private val TIME_FORMAT      = DateTimeFormatter.ofPattern("h:mm a")

/**
 * Matches the timeline design: "Month d, yyyy · h:mm a" for
 * every entry, with "Today"/"Yesterday" substituted for the
 * recent-day buckets so the most common cases read faster.
 */
private fun formatDateTime(instant: Instant): String {
    val zoned = instant.atZone(ZoneId.systemDefault())
    val today = LocalDate.now(ZoneId.systemDefault())
    val day   = zoned.toLocalDate()
    val time  = TIME_FORMAT.format(zoned)
    val datePart = when {
        day == today                -> "Today"
        day == today.minusDays(1)   -> "Yesterday"
        else                        -> FULL_DATE_FORMAT.format(zoned)
    }
    return "$datePart \u00B7 $time"
}
