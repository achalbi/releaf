/*
 * NotebookTabScreen.kt
 *
 * Top-level Notebooks tab — lists every live notebook and surfaces a FAB for
 * creating a new one. Backed by Room via NotebookTabViewModel; the screen
 * holds only transient UI state (dialog visibility, draft title).
 *
 * Layout primitives:
 *   - Scaffold hosts the FAB and a SnackbarHost (for the swipe-to-delete
 *     "Undo" action).
 *   - Header: eyebrow + title + search field. The search field does two
 *     things at once: filter notebooks by title (client-side substring) and
 *     fan out to an FTS5 page-body search across every notebook. Both sets
 *     of results render together so content hits aren't hidden behind a
 *     separate search mode.
 *   - Each notebook row is wrapped in a Material 3 SwipeToDismissBox (EndToStart
 *     only). A committed swipe commits the soft-delete immediately (Room is
 *     the source of truth; the row disappears from the Flow) and shows a
 *     snackbar with "Undo" that restores via
 *     NotebookRepository.undoSoftDeleteNotebook. Cascaded chapter/page rows
 *     stay tombstoned — see the VM doc for rationale.
 *
 * Tapping a notebook card routes to NotebookLocalDetailScreen for that
 * notebook id; tapping a page hit (in search mode) routes into the page
 * editor. The FAB opens a small create-notebook dialog with a title
 * TextField. No color picker yet.
 */

package app.releaf.mobile.features.notebook

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.Card
import kotlinx.coroutines.launch

@Composable
fun NotebookTabScreen(
    onOpenNotebook: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotebookTabViewModel = viewModel(factory = NotebookTabViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick        = { showCreateDialog = true },
                containerColor = AppColors.Coral,
                contentColor   = AppColors.OnAccent,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New notebook")
            }
        },
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
                !state.isEmpty ->
                    ResultsList(
                        state = state,
                        onOpenNotebook = onOpenNotebook,
                        onOpenPage = onOpenPage,
                        onDeleteRequest = { notebook ->
                            val id = notebook.id
                            viewModel.softDelete(id)
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message     = "Notebook deleted",
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
    }

    if (showCreateDialog) {
        CreateNotebookDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title ->
                viewModel.createNotebook(title) { newId ->
                    onOpenNotebook(newId)
                }
                showCreateDialog = false
            },
        )
    }
}

/* ---------- header / search ---------- */

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
        Text("NOTEBOOK", style = AppTypography.Eyebrow, color = AppColors.Coral)
        Text(
            "Your notebooks",
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
                    "Search notebooks and pages…",
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

/* ---------- empty states ---------- */

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
            "No notebooks yet",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Tap the + button to start your first notebook.",
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
            "Nothing in your notebooks matches \u201C$query\u201D.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
    }
}

/* ---------- results list ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultsList(
    state: NotebookTabUiState,
    onOpenNotebook: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onDeleteRequest: (NotebookEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start  = AppSpacing.s4,
            end    = AppSpacing.s4,
            top    = AppSpacing.s1,
            bottom = AppSpacing.s10,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Notebooks section — always shown when there's at least one match.
        if (state.notebooks.isNotEmpty()) {
            if (state.isSearching) {
                item(key = "header_notebooks") {
                    SectionHeader("Notebooks")
                }
            }
            items(items = state.notebooks, key = { "nb_${it.id}" }) { notebook ->
                SwipeableNotebookCard(
                    notebook = notebook,
                    onOpen   = { onOpenNotebook(notebook.id) },
                    onDelete = { onDeleteRequest(notebook) },
                )
            }
        }

        // Pages section — only shown when searching. Page results are global
        // across all notebooks; we don't group them by notebook yet.
        if (state.isSearching && state.matchingPages.isNotEmpty()) {
            item(key = "header_pages") {
                SectionHeader("Pages")
            }
            items(items = state.matchingPages, key = { "pg_${it.id}" }) { page ->
                PageHitCard(page = page, onClick = { onOpenPage(page.id) })
            }
        }

        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

@Composable
private fun SectionHeader(label: String) {
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
            label,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

/* ---------- swipeable notebook row ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNotebookCard(
    notebook: NotebookEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
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
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeDeleteBackground() },
    ) {
        NotebookCard(notebook, onClick = onOpen)
    }
}

@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(AppRadius.md))
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
private fun NotebookCard(notebook: NotebookEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Colored spine: thin rule on the left edge of the card whose
            // color is driven by the notebook's stored hex. Falls back to the
            // theme's coral accent when no color is set.
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 36.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(notebookSpineColor(notebook.colorHex)),
            )
            Spacer(Modifier.size(AppSpacing.s3))
            Column(
                modifier = Modifier.weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
            ) {
                Text(
                    notebook.title.ifBlank { "Untitled" },
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Updated ${notebook.updatedAt.take(10)}",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
            }
        }
    }
}

/* ---------- page hit row (search mode) ---------- */

@Composable
private fun PageHitCard(page: PageEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(
                displayPageTitle(page),
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            pagePreviewBody(page)?.let { preview ->
                Text(
                    preview,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "Updated ${page.updatedAt.take(10)}",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/** Title if set, otherwise the first non-empty line of the body. */
private fun displayPageTitle(page: PageEntity): String {
    page.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val firstLine = page.notes
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
    return firstLine?.takeIf { it.isNotEmpty() } ?: "Untitled page"
}

/**
 * Body preview — skip the first line if it's already the derived title so
 * we don't show the same string twice. Same logic as NotepadScreen's
 * `previewBody` but for pages.
 */
private fun pagePreviewBody(page: PageEntity): String? {
    val notes = page.notes.trim()
    if (notes.isEmpty()) return null
    val titleIsFromNotes = page.title.isNullOrBlank()
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

/* ---------- create-notebook dialog ---------- */

@Composable
private fun CreateNotebookDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    val canConfirm = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "New notebook",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Text(
                    "Give it a name to get started. You can rename it later.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
                DialogTitleField(
                    value = title,
                    onValueChange = { title = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(title) },
                enabled = canConfirm,
            ) {
                Text("Create", color = AppColors.Coral)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppColors.TextSecondary)
            }
        },
        containerColor = AppColors.CardSolid,
    )
}

@Composable
private fun DialogTitleField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.Canvas)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f, fill = true)) {
            if (value.isEmpty()) {
                Text(
                    "Notebook title",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.Coral),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Parse a `#RRGGBB` / `#AARRGGBB` hex string into a Color. Returns the
 * theme's Coral as a safe fallback on malformed input — we never want a
 * stored bad hex to crash the notebook list.
 *
 * @Composable because `AppColors.Coral` is a theme-aware getter; the fallback
 * has to be resolved inside a composition.
 */
@Composable
private fun notebookSpineColor(hex: String?): Color {
    val fallback = AppColors.Coral
    if (hex.isNullOrBlank()) return fallback
    val trimmed = hex.trim().removePrefix("#")
    val normalized = when (trimmed.length) {
        6 -> "FF$trimmed"
        8 -> trimmed
        else -> return fallback
    }
    return runCatching {
        Color(normalized.toLong(16))
    }.getOrDefault(fallback)
}
