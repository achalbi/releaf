/*
 * NotesListScreen.kt
 *
 * QuickInk's Library — the user's full scan gallery, sorted /
 * filterable / grouped. Same data source as the home rail
 * (`captures` via `CaptureDao.observeActive`) but unbounded and
 * with day-bucket grouping + chip filters off the live `categories`
 * table.
 *
 * Tap → `ScanDetailScreen` for the selected capture (preview +
 * OCR-on-demand). The notepad-entries-driven editor is no longer
 * the destination — captures are the canonical artifact.
 *
 * Mirror of iOS `NotesListScreen.swift`.
 */

package app.quickink.mobile.features.notes

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
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
import java.time.temporal.ChronoUnit

private enum class ViewMode { Grid, List }
/**
 * Library always sorts on `capture.created_at` — only the direction
 * is user-selectable. Category remains a filter (chips), not a sort
 * key.
 */
private enum class SortOrder(val label: String) { Newest("Newest first"), Oldest("Oldest first") }

@Composable
fun NotesListScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenScan: (captureId: String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val captureDao = remember(app) { app.database.captureDao() }
    val categoryDao = remember(app) { app.database.categoryDao() }

    val captures by remember(userId, captureDao) {
        captureDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    val categories by remember(userId, categoryDao) {
        categoryDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    var viewMode by remember { mutableStateOf(ViewMode.Grid) }
    var sort by remember { mutableStateOf(SortOrder.Newest) }
    var activeCategory by remember { mutableStateOf("All") }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val filteredSorted = remember(captures, activeCategory, sort) {
        val filtered = if (activeCategory == "All") {
            captures
        } else {
            val needle = activeCategory.lowercase()
            captures.filter { (it.category ?: "").lowercase() == needle }
        }
        when (sort) {
            SortOrder.Newest -> filtered // DAO already returns newest-first.
            SortOrder.Oldest -> filtered.reversed()
        }
    }

    val grouped = remember(filteredSorted) { groupByDay(filteredSorted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
    ) {
        // Top bar
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
            Text(text = "Library", style = type.pageTitle, color = colors.ink, modifier = Modifier.weight(1f))

            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(
                        imageVector       = Icons.Filled.SwapVert,
                        contentDescription = "Sort",
                        tint              = colors.ink,
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SortOrder.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = colors.ink, style = type.body) },
                            onClick = {
                                sort = option
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }

            IconButton(onClick = {
                viewMode = if (viewMode == ViewMode.Grid) ViewMode.List else ViewMode.Grid
            }) {
                Icon(
                    imageVector       = if (viewMode == ViewMode.Grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                    contentDescription = "Toggle grid/list view",
                    tint              = colors.ink,
                )
            }
        }

        // Filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2),
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            val names = listOf("All") + categories.map { it.name }
            names.forEach { cat ->
                FilterChip(
                    label    = cat,
                    selected = cat == activeCategory,
                    onClick  = { activeCategory = cat },
                )
            }
        }

        if (filteredSorted.isEmpty()) {
            EmptyState(activeCategory)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = QuickInkSpacing.s5),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
            ) {
                grouped.forEach { (label, list) ->
                    item(key = "header-$label") {
                        Text(
                            text  = label.uppercase(),
                            style = type.eyebrow,
                            color = colors.muted,
                            modifier = Modifier.padding(top = QuickInkSpacing.s4),
                        )
                    }
                    if (viewMode == ViewMode.Grid) {
                        // Two-column grid laid out as paired rows so we
                        // can keep using LazyColumn (mixing LazyColumn +
                        // LazyVerticalGrid in the same scroll surface
                        // is awkward). Each row holds up to 2 cards.
                        list.chunked(2).forEachIndexed { idx, pair ->
                            item(key = "grid-$label-$idx-${pair.first().id}") {
                                Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                                    pair.forEach { capture ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            LibraryScanGridCard(
                                                capture = capture,
                                                onTap   = { onOpenScan(capture.id) },
                                            )
                                        }
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        items(list, key = { "list-${it.id}" }) { capture ->
                            LibraryScanListRow(
                                capture = capture,
                                onTap   = { onOpenScan(capture.id) },
                            )
                        }
                    }
                }
                item(key = "bottom-pad") { Spacer(Modifier.size(QuickInkSpacing.s7)) }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(if (selected) colors.accent else colors.borderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
    ) {
        Text(
            text  = label,
            style = type.label,
            color = if (selected) colors.textOnAccent else colors.ink,
        )
    }
}

@Composable
private fun EmptyState(activeCategory: String) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(QuickInkSpacing.s7),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Description,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text(
            text  = if (activeCategory == "All") "Your library is empty" else "No $activeCategory scans yet",
            style = type.heading,
            color = colors.ink,
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = if (activeCategory == "All")
                "Tap the ⚡ on Home to capture your first page."
            else
                "Scans you tag $activeCategory on the review screen will collect here.",
            style = type.body,
            color = colors.inkSoft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LibraryScanGridCard(capture: CaptureEntity, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.borderSoft)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
        ) {
            val previewUri = capture.previewUri
            if (previewUri.isNullOrBlank()) {
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector       = Icons.Filled.Description,
                        contentDescription = null,
                        tint              = colors.muted,
                        modifier          = Modifier.size(36.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(previewUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = capture.category ?: "Scan",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
            if (capture.pageCount > 1) {
                Text(
                    text  = "${capture.pageCount} pages",
                    style = type.caption,
                    color = colors.textOnAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(QuickInkSpacing.s2)
                        .background(
                            color = colors.ink.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(QuickInkRadius.sm),
                        )
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.size(QuickInkSpacing.s2))

        Text(
            text     = capture.category ?: "Scan",
            style    = type.label,
            color    = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text  = friendlyMonthDay(capture.createdAt),
            style = type.caption,
            color = colors.muted,
        )
    }
}

@Composable
private fun LibraryScanListRow(capture: CaptureEntity, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap)
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
                Box(
                    modifier         = Modifier.fillMaxSize().background(colors.borderSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector       = Icons.Filled.Description,
                        contentDescription = null,
                        tint              = colors.muted,
                        modifier          = Modifier.size(16.dp),
                    )
                }
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

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = capture.category ?: "Scan",
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                Text(
                    text  = friendlyMonthDay(capture.createdAt),
                    style = type.caption,
                    color = colors.muted,
                )
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

/**
 * Group captures into Today / This week / Earlier buckets keyed off
 * the capture's `createdAt` ISO timestamp (parsed in the device's
 * local timezone). Falls back to one "All" bucket if every input
 * fails to parse — defensive but rarely hit in practice.
 */
private fun groupByDay(captures: List<CaptureEntity>): List<Pair<String, List<CaptureEntity>>> {
    val today = mutableListOf<CaptureEntity>()
    val week  = mutableListOf<CaptureEntity>()
    val earlier = mutableListOf<CaptureEntity>()
    val now = LocalDate.now(ZoneId.systemDefault())
    val weekAgo = now.minusDays(6) // Today + previous 6 days = "this week".

    for (c in captures) {
        val date = try {
            Instant.parse(c.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (_: Exception) {
            null
        }
        when {
            date == null         -> earlier += c
            date == now          -> today   += c
            !date.isBefore(weekAgo) -> week  += c
            else                 -> earlier += c
        }
    }

    val out = mutableListOf<Pair<String, List<CaptureEntity>>>()
    if (today.isNotEmpty())   out += "Today"     to today
    if (week.isNotEmpty())    out += "This week" to week
    if (earlier.isNotEmpty()) out += "Earlier"   to earlier
    if (out.isEmpty())        out += "All"       to captures
    return out
}

private fun friendlyMonthDay(iso: String): String =
    try {
        val instant = Instant.parse(iso)
        val date    = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        date.format(DateTimeFormatter.ofPattern("MMM d"))
    } catch (_: Exception) {
        iso.take(10)
    }
