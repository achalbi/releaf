/*
 * NotebookTabScreen.kt
 *
 * Top-level Notebooks tab. Shows the user's active notebooks under a
 * serif "Project notebooks" header and splits the list into Current /
 * Archive tabs. Each row carries an icon chip, an Active badge, the
 * notebook's description, chapter + page counts, and a relative update
 * time — the richer shape the design calls for.
 *
 * The screen holds only transient UI state (dialog visibility, collapse
 * flags). Everything data-backed (query, tab, list, counts, page-search
 * hits) lives on NotebookTabViewModel.
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ViewAgenda
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.RoundIconButton
import app.releaf.mobile.ui.components.ScreenHeader
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
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
    var listExpanded by rememberSaveable { mutableStateOf(true) }
    var pagesExpanded by rememberSaveable { mutableStateOf(true) }
    // Pending delete — user swiped a notebook; holding the row here until
    // they confirm (or dismiss) the guard dialog.
    var pendingDelete by remember { mutableStateOf<NotebookEntity?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Only exposed on the Current tab — Archive is a read-only
            // destination for already-created notebooks.
            if (state.tab == NotebookListTab.Current) {
                FloatingActionButton(
                    onClick        = { showCreateDialog = true },
                    containerColor = AppAccent.primary,
                    contentColor   = AppColors.OnAccent,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New notebook")
                }
            }
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ScreenHeader(
                eyebrow = "Notebook",
                title = "Project notebooks",
                avatarInitial = "A",
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                TabSwitcher(
                    tab = state.tab,
                    onSelect = viewModel::setTab,
                )
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::updateQuery,
                    onClearQuery = viewModel::clearQuery,
                )
            }

            Spacer(Modifier.height(AppSpacing.s3))

            Lists(
                modifier = Modifier.weight(1f, fill = true),
                state = state,
                listExpanded = listExpanded,
                onToggleList = { listExpanded = !listExpanded },
                pagesExpanded = pagesExpanded,
                onTogglePages = { pagesExpanded = !pagesExpanded },
                onOpenNotebook = onOpenNotebook,
                onOpenPage = onOpenPage,
                onArchive = { notebook ->
                    if (state.tab == NotebookListTab.Archive) {
                        viewModel.unarchive(notebook.id)
                    } else {
                        viewModel.archive(notebook.id)
                    }
                },
                onDeleteRequest = { notebook -> pendingDelete = notebook },
            )
        }
    }

    pendingDelete?.let { notebook ->
        val title = notebook.title.ifBlank { "Untitled" }
        DeleteConfirmationDialog(
            title = "Delete notebook?",
            message = "\u201C$title\u201D and all its chapters and pages will be deleted. " +
                "You can undo this immediately after.",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val id = notebook.id
                pendingDelete = null
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
    }

    if (showCreateDialog) {
        CreateNotebookDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, description ->
                viewModel.createNotebook(title, description) { newId ->
                    onOpenNotebook(newId)
                }
                showCreateDialog = false
            },
        )
    }
}

/* ---------- tabs + search ---------- */

@Composable
private fun TabSwitcher(
    tab: NotebookListTab,
    onSelect: (NotebookListTab) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        TabChip(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = "Current notebooks",
            selected = tab == NotebookListTab.Current,
            onClick = { onSelect(NotebookListTab.Current) },
        )
        TabChip(
            icon = Icons.Filled.Archive,
            contentDescription = "Archive",
            selected = tab == NotebookListTab.Archive,
            onClick = { onSelect(NotebookListTab.Archive) },
        )
    }
}

@Composable
private fun TabChip(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) AppAccent.soft else AppColors.CardSolid
    val border = if (selected) AppAccent.border else AppColors.BorderDefault
    val tint = if (selected) AppAccent.deep else AppColors.TextSecondary
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint,
             modifier = Modifier.size(18.dp))
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
                    "Search notebooks, descriptions, or chapter names",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppAccent.primary),
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

/* ---------- list body ---------- */

@Composable
private fun Lists(
    state: NotebookTabUiState,
    listExpanded: Boolean,
    onToggleList: () -> Unit,
    pagesExpanded: Boolean,
    onTogglePages: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onArchive: (NotebookEntity) -> Unit,
    onDeleteRequest: (NotebookEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start  = AppSpacing.s4,
            end    = AppSpacing.s4,
            top    = AppSpacing.s0,
            bottom = AppSpacing.s10,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        item(key = "notebook_card") {
            NotebookCard(
                state = state,
                expanded = listExpanded,
                onToggle = onToggleList,
                onOpenNotebook = onOpenNotebook,
                onArchive = onArchive,
                onDeleteRequest = onDeleteRequest,
            )
        }
        if (state.isSearching && state.matchingPages.isNotEmpty()) {
            item(key = "pages_card") {
                PageHitsCard(
                    pages = state.matchingPages,
                    expanded = pagesExpanded,
                    onToggle = onTogglePages,
                    onOpen = onOpenPage,
                )
            }
        }
        item(key = "tail_spacer") { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

@Composable
private fun NotebookCard(
    state: NotebookTabUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onArchive: (NotebookEntity) -> Unit,
    onDeleteRequest: (NotebookEntity) -> Unit,
) {
    val archive = state.tab == NotebookListTab.Archive
    val title = if (archive) "Archive" else "Current notebooks"
    val count = state.notebooks.size
    val countLabel = when {
        archive && count == 0  -> "Empty"
        archive                -> "$count archived"
        count == 0             -> "No notebooks"
        count == 1             -> "1 active notebook"
        else                   -> "$count active notebooks"
    }

    CollapsibleCard(
        title = title,
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                MetaPill(text = countLabel, accent = !archive)
                // Layout toggle is a visual affordance for now — no grid
                // mode shipped yet. Left in the design so callers can
                // wire it up when the grid layout lands.
                RoundIconButton(
                    icon = Icons.Filled.ViewAgenda,
                    contentDescription = "Layout",
                    onClick = { /* TODO(layout-toggle) */ },
                )
            }
        },
    ) {
        if (count == 0) {
            EmptyListBody(state = state)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.notebooks.forEachIndexed { index, summary ->
                    if (index > 0) HairlineDivider()
                    SwipeableNotebookListItem(
                        summary = summary,
                        archive = archive,
                        onOpen = { onOpenNotebook(summary.entity.id) },
                        onArchive = { onArchive(summary.entity) },
                        onDelete = { onDeleteRequest(summary.entity) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyListBody(state: NotebookTabUiState) {
    val text = when {
        state.isSearching -> "Nothing matches \u201C${state.query}\u201D."
        state.tab == NotebookListTab.Archive -> "No archived notebooks yet."
        else -> "Tap the + button to start your first notebook."
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/* ---------- notebook row ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableNotebookListItem(
    summary: NotebookSummary,
    archive: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                // Don't auto-dismiss on delete — bubble the request up to the
                // screen which pops a confirmation dialog. Returning false
                // snaps the row back while the dialog is open.
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); false }
                SwipeToDismissBoxValue.StartToEnd -> { onArchive(); true }
                else -> false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = true,
        enableDismissFromStartToEnd = true,
        backgroundContent = {
            SwipeBackground(
                direction = dismissState.dismissDirection,
                archiveMode = archive,
            )
        },
    ) {
        NotebookListItem(summary = summary, onClick = onOpen)
    }
}

@Composable
private fun NotebookListItem(
    summary: NotebookSummary,
    onClick: () -> Unit,
) {
    val notebook = summary.entity
    // Opaque fill is required: SwipeToDismissBox lays the delete / archive
    // backgrounds *behind* the foreground row, so a transparent row would
    // leak those strips through at rest.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        IconChipSquare(icon = Icons.Filled.Book)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Text(
                    text = notebook.title.ifBlank { "Untitled" },
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                if (notebook.archivedAt == null) MetaPill("Active", accent = true)
                else MetaPill("Archived")
            }
            if (!notebook.description.isNullOrBlank()) {
                Text(
                    text = notebook.description,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CountsRow(
                chapterCount = summary.chapterCount,
                pageCount = summary.pageCount,
            )
            Text(
                text = "Updated ${relativeTimeAgo(notebook.updatedAt)}",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
        }
    }
}

@Composable
private fun CountsRow(chapterCount: Int, pageCount: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountChip(icon = Icons.AutoMirrored.Filled.MenuBook,
                  label = if (chapterCount == 1) "1 chapter" else "$chapterCount chapters")
        CountChip(icon = Icons.Filled.Description,
                  label = if (pageCount == 1) "1 page" else "$pageCount pages")
    }
}

@Composable
private fun CountChip(icon: ImageVector, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun IconChipSquare(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppAccent.soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent.deep,
            modifier = Modifier.size(20.dp),
        )
    }
}

/* ---------- page hit card (search mode) ---------- */

@Composable
private fun PageHitsCard(
    pages: List<PageEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    CollapsibleCard(
        title = "Pages",
        subtitle = "Content matches across your notebooks",
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            MetaPill(text = "${pages.size} result${if (pages.size == 1) "" else "s"}", accent = true)
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            pages.forEachIndexed { index, page ->
                if (index > 0) HairlineDivider()
                PageHitRow(page = page, onClick = { onOpen(page.id) })
            }
        }
    }
}

@Composable
private fun PageHitRow(page: PageEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        IconChipSquare(icon = Icons.Filled.Description)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
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
                text = "Updated ${relativeTimeAgo(page.updatedAt)}",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/* ---------- swipe backgrounds ---------- */

@Composable
private fun SwipeBackground(
    direction: SwipeToDismissBoxValue,
    archiveMode: Boolean,
) {
    when (direction) {
        SwipeToDismissBoxValue.EndToStart -> DeleteBackground()
        SwipeToDismissBoxValue.StartToEnd -> ArchiveBackground(archiveMode)
        else -> Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun DeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
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
private fun ArchiveBackground(archiveMode: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppAccent.soft)
            .padding(horizontal = AppSpacing.s4),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            imageVector = if (archiveMode) Icons.Filled.Unarchive else Icons.Filled.Archive,
            contentDescription = null,
            tint = AppAccent.deep,
            modifier = Modifier.size(20.dp),
        )
    }
}

/* ---------- create dialog (title + description) ---------- */

@Composable
private fun CreateNotebookDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
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
                    "Give it a name and a short description. You can rename it later.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
                DialogTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Notebook title",
                    singleLine = true,
                )
                DialogTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Describe this notebook (optional)",
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(title, description) },
                enabled = canConfirm,
            ) {
                Text("Create", color = AppAccent.primary)
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
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
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
                    placeholder,
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppAccent.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ---------- shared helpers ---------- */

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
 * we don't show the same string twice.
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
