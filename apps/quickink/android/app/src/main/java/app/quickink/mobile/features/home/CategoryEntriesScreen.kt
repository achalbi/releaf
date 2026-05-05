/*
 * CategoryEntriesScreen.kt
 *
 * Per-category browse surface. Routed to from the Home screen's
 * category grid: tap "Ideas" → land here scoped to category="Ideas".
 * Lists every active scan whose `capture.category` matches, sorted
 * latest-first by `createdAt`, grouped under "28TH APR, 2026" day
 * headers — same visual language as the SearchScreen timeline so
 * users learn the pattern once.
 *
 * Match is case-insensitive against `capture.category` so "Ideas" /
 * "ideas" / "IDEAS" all bucket together. Tap → `ScanDetailScreen`.
 *
 * Mirror of iOS `CategoryEntriesScreen.swift`.
 */

package app.quickink.mobile.features.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CategoryEntriesScreen(
    userId: String,
    categoryName: String,
    onBack: () -> Unit,
    onOpenScan: (captureId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    // Same DAO Flow the home Recent rail and Library use. We filter
    // client-side by category — dataset is small enough that a
    // single observed Flow + client filter beats threading a
    // category arg through the DAO.
    val captureDao = remember(app) { app.database.captureDao() }
    val allCaptures by captureDao.observeActive(userId).collectAsState(initial = emptyList())

    val needle = categoryName.lowercase()
    val capturesInCategory = remember(needle, allCaptures) {
        allCaptures.filter { (it.category ?: "").lowercase() == needle }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint              = colors.ink,
                )
            }
            Text(text = categoryName, style = type.pageTitle, color = colors.ink)
        }

        Divider(color = colors.border, thickness = 1.dp)

        if (capturesInCategory.isEmpty()) {
            EmptyCategoryState(categoryName = categoryName)
        } else {
            Timeline(captures = capturesInCategory, onOpen = onOpenScan)
        }
    }
}

@Composable
private fun Timeline(captures: List<CaptureEntity>, onOpen: (String) -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val grouped = remember(captures) {
        captures.sortedByDescending { it.createdAt }
            .groupBy { dayKey(it.createdAt) }
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(
            start  = QuickInkSpacing.s5,
            end    = QuickInkSpacing.s5,
            top    = QuickInkSpacing.s4,
            bottom = QuickInkSpacing.s7,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
    ) {
        grouped.forEach { (dayIso, dayCaptures) ->
            item(key = "day-$dayIso") {
                Text(
                    text  = formatDayHeader(dayIso),
                    style = type.eyebrow,
                    color = colors.muted,
                )
            }
            items(dayCaptures, key = { "row-${it.id}" }) { capture ->
                TimelineRow(capture = capture, onClick = { onOpen(capture.id) })
            }
        }
    }
}

@Composable
private fun TimelineRow(capture: CaptureEntity, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val time = formatTime(capture.createdAt)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 72.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.sm)),
        ) {
            val previewUri = capture.previewUri
            if (previewUri.isNullOrBlank()) {
                Box(modifier = Modifier.fillMaxSize().background(colors.borderSoft))
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(previewUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.size(QuickInkSpacing.s3))

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Text(
                text     = capture.displayTitle(),
                style    = type.heading,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Import chip — flags captures sourced from the
                // system photo picker rather than the document
                // scanner. Renders inline with time/page-count
                // because the thumbnail is too narrow (56dp) to
                // host a corner pill cleanly.
                if (capture.source == "import") {
                    Text(
                        text  = "Import",
                        style = type.caption,
                        color = colors.textOnAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.sm))
                            .background(colors.accent)
                            .padding(horizontal = QuickInkSpacing.s2, vertical = 1.dp),
                    )
                }
                if (time.isNotEmpty()) {
                    Text(text = time, style = type.caption, color = colors.muted)
                }
                if (capture.pageCount > 1) {
                    Text(
                        text  = "• ${capture.pageCount} pages",
                        style = type.caption,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCategoryState(categoryName: String) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier             = Modifier
            .fillMaxSize()
            .padding(horizontal = QuickInkSpacing.s7),
        horizontalAlignment  = Alignment.CenterHorizontally,
        verticalArrangement  = Arrangement.Center,
    ) {
        Text(
            text      = "No $categoryName scans yet",
            style     = type.heading,
            color     = colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text      = "Scans tagged $categoryName on the post-scan review screen will collect here.",
            style     = type.body,
            color     = colors.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Date helpers

private fun dayKey(iso: String): String = try {
    val date = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
} catch (_: Exception) {
    iso.take(10)
}

private fun formatDayHeader(yyyymmdd: String): String = try {
    val date = LocalDate.parse(yyyymmdd)
    val day = date.dayOfMonth
    val suffix = ordinalSuffix(day)
    val monthYear = date.format(DateTimeFormatter.ofPattern("MMM, yyyy")).uppercase(Locale.getDefault())
    "$day$suffix $monthYear"
} catch (_: Exception) {
    yyyymmdd
}

private fun ordinalSuffix(day: Int): String {
    val mod100 = day % 100
    if (mod100 in 11..13) return "TH"
    return when (day % 10) {
        1    -> "ST"
        2    -> "ND"
        3    -> "RD"
        else -> "TH"
    }
}

private fun formatTime(iso: String): String = try {
    val instant = Instant.parse(iso)
    instant.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))
} catch (_: Exception) {
    ""
}
