/*
 * NotesListScreen.kt
 *
 * QuickInk's Library — upgraded from the simple list per the
 * mockup brief:
 *
 *   - Top bar: title + sort menu + grid/list toggle + new note
 *   - Filter chips by category (All, Ideas, Projects, ...)
 *   - Time-grouped notes (Today / This week / Earlier)
 *   - Grid view: handwritten Caveat preview on lined paper with
 *     paper-tone backgrounds
 *   - List view: dense rows with title + meta + handwritten preview
 *
 * Reads `NotepadDao.observeActive(userId)` directly via Compose
 * state — same pattern as before. The grouping and filter state
 * live in this view (UI-only).
 *
 * Mirror of iOS `NotesListScreen.swift`.
 */

package app.quickink.mobile.features.notes

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry

private val CATEGORIES = listOf("All", "Ideas", "Projects", "Brainstorm", "Meetings", "Journal", "Study")

private enum class ViewMode { Grid, List }
private enum class SortOrder(val label: String) { Newest("Newest first"), Oldest("Oldest first"), Alphabetical("Alphabetical") }

@Composable
fun NotesListScreen(
    dao: NotepadDao,
    userId: String,
    onBack: () -> Unit,
    onOpenEntry: (entryId: String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val entries by dao.observeActive(userId).collectAsState(initial = emptyList())

    var viewMode by remember { mutableStateOf(ViewMode.Grid) }
    var sort by remember { mutableStateOf(SortOrder.Newest) }
    var activeCategory by remember { mutableStateOf("All") }
    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
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
            Text(text = "Library", style = type.pageTitle, color = colors.ink)
            Spacer(Modifier.weight(1f))

            // Sort dropdown.
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
                            text     = { Text(option.label, style = type.body, color = colors.ink) },
                            onClick  = { sort = option; sortMenuOpen = false },
                        )
                    }
                }
            }

            // Grid / list toggle.
            IconButton(onClick = {
                viewMode = if (viewMode == ViewMode.Grid) ViewMode.List else ViewMode.Grid
            }) {
                Icon(
                    imageVector       = if (viewMode == ViewMode.Grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                    contentDescription = "Toggle grid/list view",
                    tint              = colors.ink,
                )
            }

            IconButton(onClick = { onOpenEntry(NoteEditorController.NEW_ENTRY_ID) }) {
                Icon(
                    imageVector       = Icons.Filled.Add,
                    contentDescription = "New note",
                    tint              = colors.accent,
                )
            }
        }

        // Filter chips
        LazyRow(
            modifier             = Modifier.padding(top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s3),
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            contentPadding       = androidx.compose.foundation.layout.PaddingValues(horizontal = QuickInkSpacing.s5),
        ) {
            items(CATEGORIES) { cat ->
                val active = (cat == activeCategory)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(if (active) colors.accent else colors.borderSoft)
                        .clickable { activeCategory = cat }
                        .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
                ) {
                    Text(
                        text  = cat,
                        style = type.label,
                        color = if (active) colors.textOnAccent else colors.ink,
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            EmptyState()
        } else {
            val groups = remember(entries, sort, activeCategory) {
                groupEntries(entries, sort, activeCategory)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = QuickInkSpacing.s5)
                    .padding(bottom = QuickInkSpacing.s7),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
            ) {
                groups.forEach { (label, list) ->
                    Column {
                        Text(
                            text  = label.uppercase(),
                            style = type.eyebrow,
                            color = colors.muted,
                        )
                        Spacer(Modifier.size(QuickInkSpacing.s3))
                        if (viewMode == ViewMode.Grid) {
                            // 2-col grid via LazyVerticalGrid would
                            // conflict with parent vertical scroll;
                            // hand-roll a 2-col flow.
                            list.chunked(2).forEachIndexed { i, pair ->
                                if (i > 0) Spacer(Modifier.size(QuickInkSpacing.s3))
                                Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                                    pair.forEachIndexed { idx, entry ->
                                        LibraryGridCard(
                                            entry    = entry,
                                            seed     = (i * 2 + idx),
                                            onTap    = { onOpenEntry(entry.id) },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    // Right-pad with spacer if list size is odd.
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            list.forEachIndexed { idx, entry ->
                                if (idx > 0) Spacer(Modifier.size(QuickInkSpacing.s3))
                                LibraryListRow(entry = entry, onTap = { onOpenEntry(entry.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun groupEntries(
    entries: List<NotepadEntry>,
    sort: SortOrder,
    @Suppress("UNUSED_PARAMETER") category: String,
): List<Pair<String, List<NotepadEntry>>> {
    // Category filter — NotepadEntry doesn't yet expose a category
    // field; pass-through. When the shared model adds it, filter
    // by entry.category here.
    val filtered = entries
    val sorted = when (sort) {
        SortOrder.Newest       -> filtered                       // DAO default is newest-first.
        SortOrder.Oldest       -> filtered.reversed()
        SortOrder.Alphabetical -> filtered.sortedBy { it.title.orEmpty() }
    }
    // Time bucketing — we have entry.entryDate as String; without
    // a parseable date we conservatively bucket everything into
    // "All". When the shared model exposes `updatedAt: Instant`,
    // implement Today / This week / Earlier with a real comparison.
    return listOf("All" to sorted)
}

@Composable
private fun EmptyState() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier             = Modifier.fillMaxSize(),
        horizontalAlignment  = Alignment.CenterHorizontally,
        verticalArrangement  = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
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
        Text(text = "Your library is empty", style = type.heading, color = colors.ink)
        Text(
            text     = "Tap the ⚡ on Home to capture your first page.",
            style    = type.body,
            color    = colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s7),
        )
    }
}

@Composable
private fun LibraryGridCard(
    entry: NotepadEntry,
    seed: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val title = entry.title?.takeIf { it.isNotBlank() } ?: "Untitled"
    val preview = entry.notes.take(120).ifEmpty { title }

    Column(
        modifier             = modifier.clickable(onClick = onTap),
        verticalArrangement  = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .quickInkLinedPaper(seed = seed.hashCode())
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                .padding(QuickInkSpacing.s3),
        ) {
            Text(
                text     = preview,
                style    = type.handwritten,
                color    = colors.ink.copy(alpha = 0.78f),
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column {
            Text(
                text     = title,
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = entry.entryDate,
                style = type.caption,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun LibraryListRow(entry: NotepadEntry, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val title = entry.title?.takeIf { it.isNotBlank() } ?: "Untitled"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 60.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .quickInkLinedPaper(seed = entry.id.hashCode(), lineSpacing = 6.dp, lineOpacity = 0.18f)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Description,
                contentDescription = null,
                tint              = colors.accent.copy(alpha = 0.7f),
                modifier          = Modifier.size(14.dp),
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text     = title,
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.notes.isNotBlank()) {
                Text(
                    text     = entry.notes,
                    style    = type.body,
                    color    = colors.inkSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = entry.entryDate, style = type.caption, color = colors.muted)
        }
    }
}
