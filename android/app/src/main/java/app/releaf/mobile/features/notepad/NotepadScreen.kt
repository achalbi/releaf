/*
 * NotepadScreen.kt
 *
 * Top-level Notepad tab — lists the signed-in user's existing entries and
 * surfaces a coral FAB for composing a new one. Backed entirely by Room via
 * NotepadListViewModel; the screen itself holds no persistent state.
 *
 * Layout primitives:
 *   - Scaffold hosts the FAB and the SnackbarHost (keeps insets + IME
 *     handling off our back).
 *   - LazyColumn groups entries by entry_date into sticky date headers, with
 *     within-group rows still ordered by updated_at DESC (inherited from the
 *     DAO). Date grouping is suppressed while searching — flat rank order
 *     makes more sense for FTS hits.
 *   - Each row is wrapped in a Material 3 SwipeToDismissBox. Only the
 *     EndToStart direction is enabled (right-to-left swipe = delete). On a
 *     committed swipe: commit the soft-delete immediately (Room is the source
 *     of truth; the row disappears from the Flow), then show a Snackbar with
 *     "Undo". The undo restores via NotepadRepository.undoSoftDelete.
 *
 * Tapping a card routes to NotepadEditorScreen for that entry id; tapping
 * the FAB routes to the editor with the `NEW_ENTRY_ID` sentinel.
 *
 * Search: the SearchField binds to the VM's query StateFlow. The VM
 * debounces non-blank queries into the FTS5 virtual table in
 * `fts_notepad_notes` (see ReleafDatabase.SchemaCallback).
 */

package app.releaf.mobile.features.notepad

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.ui.components.DotGridBackground
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.Card
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NotepadScreen(
    onOpenEntry: (String) -> Unit,
    onComposeNew: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotepadListViewModel = viewModel(factory = NotepadListViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Dot-grid canvas sits behind the whole Notepad tab so the entry
    // cards float on the same textured paper the editor uses. Matches
    // the Releaf Branding template's "Recent Entries" background.
    Box(modifier = modifier.fillMaxSize().background(AppColors.Canvas)) {
        DotGridBackground()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onComposeNew,
                containerColor = AppColors.Coral,
                contentColor   = AppColors.OnAccent,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New entry")
            }
        },
        // Scaffold paints an M3 surface by default; we want the app's canvas
        // + dot-grid to show through so our themed colors win.
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Header(
                query = state.query,
                onQueryChange = viewModel::updateQuery,
                onClearQuery = viewModel::clearQuery,
            )
            when {
                state.entries.isNotEmpty() ->
                    EntryList(
                        entries    = state.entries,
                        grouped    = !state.isSearching,
                        onOpenEntry = onOpenEntry,
                        onDeleteRequest = { entry ->
                            // Commit the soft-delete immediately — Room is the
                            // source of truth, the row vanishes from the Flow.
                            // Then offer an Undo via snackbar.
                            val id = entry.id
                            viewModel.softDelete(id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message     = "Entry deleted",
                                    actionLabel = "Undo",
                                    duration    = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    viewModel.undoDelete(id)
                                }
                            }
                        },
                    )

                state.isSearching ->
                    EmptySearchState(query = state.query)

                else ->
                    EmptyState()
            }
        }
    } // end Scaffold
    } // end dot-grid Box
}

@Composable
private fun Header(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s4,
                bottom = AppSpacing.s3,
            ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Text("NOTEPAD", style = AppTypography.Eyebrow, color = AppColors.Coral)
        Text(
            "Quick scratch",
            style = AppTypography.EditorialTitle,
            color = AppColors.TextPrimary,
        )
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            onClearQuery = onClearQuery,
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.pill),
            )
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Box(Modifier.weight(1f, fill = true)) {
            if (query.isEmpty()) {
                Text(
                    "Search notes…",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.Coral),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.size(AppSpacing.s2))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear search",
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onClearQuery() },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing here yet",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Tap the + button to jot something down.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
    }
}

@Composable
private fun EmptySearchState(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No matches",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Nothing in your notepad matches \u201C$query\u201D.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<NotepadEntry>,
    grouped: Boolean,
    onOpenEntry: (String) -> Unit,
    onDeleteRequest: (NotepadEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start  = AppSpacing.s4,
            end    = AppSpacing.s4,
            top    = AppSpacing.s1,
            // Leave clearance so the FAB doesn't overlap the last card.
            bottom = AppSpacing.s10,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        if (grouped) {
            // Group by entry_date and sort groups by date DESC (ISO strings
            // sort lexically = chronologically). Within a group, preserve the
            // DAO's updated_at DESC ordering so recent edits rise to the top.
            val groups = entries
                .groupBy { it.entryDate }
                .toSortedMap(compareByDescending { it })
            groups.forEach { (date, group) ->
                stickyHeader(key = "header_$date") {
                    DateHeader(date)
                }
                items(items = group, key = { it.id }) { entry ->
                    SwipeableEntryCard(
                        entry = entry,
                        onOpen = { onOpenEntry(entry.id) },
                        onDelete = { onDeleteRequest(entry) },
                    )
                }
            }
        } else {
            items(items = entries, key = { it.id }) { entry ->
                SwipeableEntryCard(
                    entry = entry,
                    onOpen = { onOpenEntry(entry.id) },
                    onDelete = { onDeleteRequest(entry) },
                )
            }
        }
        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

@Composable
private fun DateHeader(isoDate: String) {
    // Date headers sit above the card rail. Transparent-but-solid background
    // so they can "stick" over scrolling cards without bleeding through.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Canvas)
            .padding(
                top    = AppSpacing.s2,
                bottom = AppSpacing.s1,
            ),
    ) {
        Text(
            formatDateHeader(isoDate),
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEntryCard(
    entry: NotepadEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    // Keyed on id + updatedAt so that a restored row (whose updatedAt bumps)
    // gets a fresh swipe state instead of inheriting a stale "dismissed"
    // value from before the undo.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        // Right-to-left swipe only. Swiping right doesn't do anything useful
        // here and would just invite accidental gestures from the tab-bar edge.
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeDeleteBackground() },
    ) {
        EntryCard(entry, onClick = onOpen)
    }
}

@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Matches the entry card's new 16dp radius so the red
            // reveal stays flush with the card as it slides away.
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.Danger)
            .padding(horizontal = AppSpacing.s4),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = AppColors.OnAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun EntryCard(entry: NotepadEntry, onClick: () -> Unit) {
    // Mirrors the "Recent Entries" card in the Releaf Branding
    // template — date label at the top as a small meta row, then
    // title + body preview below. Bumps radius + padding to 16 / 24
    // so cards read as editorial containers rather than compact tiles.
    // Title fallback (`displayTitle`) still pulls from the first body
    // line if the entry has no explicit title, so empty-titled notes
    // don't collapse to "Untitled" by default.
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        radius   = AppRadius.lg,
        padding  = AppSpacing.s6,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Text(
                formatDateHeader(entry.entryDate),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            Text(
                displayTitle(entry),
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            previewBody(entry)?.let { preview ->
                Text(
                    preview,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Title if set, otherwise the first non-empty line of the body. */
private fun displayTitle(entry: NotepadEntry): String {
    entry.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val firstLine = entry.notes
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
    return firstLine?.takeIf { it.isNotEmpty() } ?: "Untitled"
}

/**
 * Body preview — skip the first line if it's already the display title (so
 * we don't show the same string twice), otherwise show the body as-is.
 */
private fun previewBody(entry: NotepadEntry): String? {
    val notes = entry.notes.trim()
    if (notes.isEmpty()) return null
    val titleIsFromNotes = entry.title.isNullOrBlank()
    return if (titleIsFromNotes) {
        notes.lineSequence()
            .drop(1)
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    } else {
        notes
    }
}

/**
 * Format an ISO date (YYYY-MM-DD) for a section header:
 *   - today / yesterday get friendly names
 *   - other dates fall back to "April 21, 2026"
 * Malformed input (shouldn't happen — the column is GLOB-validated upstream)
 * is echoed through as-is rather than crashing the list.
 */
private fun formatDateHeader(iso: String): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val today = LocalDate.now()
    return when (date) {
        today               -> "Today"
        today.minusDays(1)  -> "Yesterday"
        else                -> date.format(LONG_DATE_FMT)
    }
}

private val LONG_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
