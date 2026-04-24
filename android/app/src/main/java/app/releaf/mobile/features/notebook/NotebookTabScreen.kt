/*
 * NotebookTabScreen.kt
 *
 * Top-level Notebooks tab. Shows the user's active notebooks under a
 * serif "Your shelves" header and splits the list into Current /
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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.key
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
import app.releaf.mobile.data.notebook.PageSearchHit
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.RoundIconButton
import app.releaf.mobile.ui.components.ScreenHeader
import app.releaf.mobile.ui.components.StatGrid
import app.releaf.mobile.ui.components.StatItem
import app.releaf.mobile.ui.components.StatTone
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
    var hiddenNotebookIds by rememberSaveable(state.tab) { mutableStateOf(setOf<String>()) }
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
        // Outer SignedInShell Scaffold already consumes the system-bar
        // insets; swallow them here so the header docks flush to the
        // top instead of doubling the status-bar padding. Same trick
        // the Notepad screen uses.
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ScreenHeader(
                eyebrow = "Shelves",
                title = "Your shelves",
                // Match the Notepad screen's top rhythm — its custom
                // header uses s3 above the eyebrow, so we drop the
                // default s4 here to keep the two top-level tabs
                // aligned vertically.
                topPadding = AppSpacing.s3,
                // Lighter serif weight than the default heavy
                // EditorialTitle — reads as a tab label rather than a
                // display heading, which fits the more list-heavy
                // notebook surface.
                titleStyle = AppTypography.EditorialTitleLight,
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
                NotebookOverview(state = state)
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
                    hiddenNotebookIds = hiddenNotebookIds + notebook.id
                    if (state.tab == NotebookListTab.Archive) {
                        viewModel.unarchive(notebook.id) { success ->
                            if (!success) {
                                hiddenNotebookIds = hiddenNotebookIds - notebook.id
                            }
                        }
                    } else {
                        viewModel.archive(notebook.id) { success ->
                            if (!success) {
                                hiddenNotebookIds = hiddenNotebookIds - notebook.id
                            }
                        }
                    }
                },
                onDeleteRequest = { notebook -> pendingDelete = notebook },
                hiddenNotebookIds = hiddenNotebookIds,
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
            shelves  = state.shelves,
            onDismiss = { showCreateDialog = false },
            onCreateShelf = { name, onCreated ->
                viewModel.createShelf(name) { id -> onCreated(id) }
            },
            onConfirm = { title, description, shelfId ->
                viewModel.createNotebook(
                    title       = title,
                    description = description,
                    shelfId     = shelfId,
                ) { newId -> onOpenNotebook(newId) }
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
    // Segmented control — two icons in one connected pill. Outer
    // hairline border + inner divider replace the previous gap-and-
    // separate-chips layout; selected segment fills with accent.soft
    // so the active tab reads at a glance.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabSegment(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = "Current notebooks",
            selected = tab == NotebookListTab.Current,
            onClick = { onSelect(NotebookListTab.Current) },
        )
        Box(
            modifier = Modifier
                .size(width = 1.dp, height = 24.dp)
                .background(AppColors.BorderDefault),
        )
        TabSegment(
            icon = Icons.Filled.Archive,
            contentDescription = "Archive",
            selected = tab == NotebookListTab.Archive,
            onClick = { onSelect(NotebookListTab.Archive) },
        )
    }
}

@Composable
private fun TabSegment(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) AppAccent.soft else Color.Transparent
    val tint = if (selected) AppAccent.deep else AppColors.TextSecondary
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 40.dp)
            .background(bg)
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
private fun NotebookOverview(state: NotebookTabUiState) {
    val helper = when {
        state.isSearching ->
            "Showing notebook title matches and page hits for \u201C${state.query}\u201D."
        state.tab == NotebookListTab.Archive ->
            "Archived notebooks stay readable and keep their chapter/page counts."
        else ->
            "Open a notebook to keep working inside the right chapter and page."
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        StatGrid(
            items = listOf(
                StatItem("Notebooks", "${state.notebookCount}", StatTone.Coral),
                StatItem("Chapters",  "${state.chapterCount}",  StatTone.Neutral),
                StatItem("Pages",     "${state.pageCount}",     StatTone.Green),
            ),
        )
        Text(
            text = helper,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

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
    hiddenNotebookIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val archive = state.tab == NotebookListTab.Archive
    val visibleNotebooks = state.notebooks.filterNot { it.entity.id in hiddenNotebookIds }

    // Current tab: one CollapsibleCard per shelf. Archive tab: the
    // old single "Archive" card (archived books can span multiple
    // shelves; the tab's job is to surface them flat by archive
    // date, not re-group by shelf).
    val perShelf = !archive
    val booksByShelf = visibleNotebooks.groupBy { it.entity.shelfId }
    val orderedShelves = state.shelves.filter { booksByShelf[it.id]?.isNotEmpty() == true }
    val orphanIds = booksByShelf.keys.filter { id -> state.shelves.none { it.id == id } }
    val orphanBooks = orphanIds.flatMap { booksByShelf[it].orEmpty() }
    val emptyShelves = state.shelves.filter { booksByShelf[it.id].isNullOrEmpty() }

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
        if (perShelf) {
            if (orderedShelves.isEmpty() && orphanBooks.isEmpty() && emptyShelves.isEmpty()) {
                item(key = "empty_notebook_card") {
                    NotebookCard(
                        state = state,
                        visibleBooks = visibleNotebooks,
                        shelfName = null,
                        archive = archive,
                        expanded = listExpanded,
                        onToggle = onToggleList,
                        onOpenNotebook = onOpenNotebook,
                        onArchive = onArchive,
                        onDeleteRequest = onDeleteRequest,
                    )
                }
            } else {
                orderedShelves.forEach { shelf ->
                    val books = booksByShelf[shelf.id].orEmpty()
                    item(key = "shelf_card_${shelf.id}") {
                        NotebookCard(
                            state = state,
                            visibleBooks = books,
                            shelfName = shelf.name,
                            archive = archive,
                            expanded = listExpanded,
                            onToggle = onToggleList,
                            onOpenNotebook = onOpenNotebook,
                            onArchive = onArchive,
                            onDeleteRequest = onDeleteRequest,
                        )
                    }
                }
                // Surface empty shelves below the populated ones so
                // the user can see where a freshly-created shelf
                // has landed (and tap "+ New book" to fill it).
                emptyShelves.forEach { shelf ->
                    item(key = "shelf_card_empty_${shelf.id}") {
                        NotebookCard(
                            state = state,
                            visibleBooks = emptyList(),
                            shelfName = shelf.name,
                            archive = archive,
                            expanded = listExpanded,
                            onToggle = onToggleList,
                            onOpenNotebook = onOpenNotebook,
                            onArchive = onArchive,
                            onDeleteRequest = onDeleteRequest,
                        )
                    }
                }
                if (orphanBooks.isNotEmpty()) {
                    item(key = "shelf_card_unshelved") {
                        NotebookCard(
                            state = state,
                            visibleBooks = orphanBooks,
                            shelfName = "Unshelved",
                            archive = archive,
                            expanded = listExpanded,
                            onToggle = onToggleList,
                            onOpenNotebook = onOpenNotebook,
                            onArchive = onArchive,
                            onDeleteRequest = onDeleteRequest,
                        )
                    }
                }
            }
        } else {
            item(key = "notebook_card") {
                NotebookCard(
                    state = state,
                    visibleBooks = visibleNotebooks,
                    shelfName = null, // archive tab keeps the tab-level title
                    archive = archive,
                    expanded = listExpanded,
                    onToggle = onToggleList,
                    onOpenNotebook = onOpenNotebook,
                    onArchive = onArchive,
                    onDeleteRequest = onDeleteRequest,
                )
            }
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
    visibleBooks: List<NotebookSummary>,
    shelfName: String?,
    archive: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onArchive: (NotebookEntity) -> Unit,
    onDeleteRequest: (NotebookEntity) -> Unit,
) {
    val title = when {
        archive         -> "Archive"
        shelfName != null -> shelfName
        else            -> "Current notebooks"
    }
    val count = visibleBooks.size
    val countLabel = when {
        archive && count == 0 -> "Empty"
        archive               -> "$count archived"
        count == 0            -> "No books"
        count == 1            -> "1 book"
        else                  -> "$count books"
    }

    CollapsibleCard(
        title = title,
        expanded = expanded,
        onToggle = onToggle,
        // Lighter weight than the default heavy SectionTitle —
        // matches the tab-label feel of the screen header above.
        titleStyle = AppTypography.SectionTitleLight,
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
            EmptyListBody(state = state, shelfName = shelfName)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                visibleBooks.forEachIndexed { index, summary ->
                    if (index > 0) HairlineDivider()
                    key(summary.entity.id) {
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
}


@Composable
private fun EmptyListBody(state: NotebookTabUiState, shelfName: String? = null) {
    val text = when {
        state.isSearching -> "Nothing matches \u201C${state.query}\u201D."
        state.tab == NotebookListTab.Archive -> "No archived notebooks yet."
        shelfName != null -> "No books on \u201C$shelfName\u201D yet. Tap + to add one."
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
                    // Lighter than the default bold SectionTitle —
                    // reads as a list row rather than a section head,
                    // which is what this row actually is.
                    style = AppTypography.SectionTitleLight,
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
    pages: List<PageSearchHit>,
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
private fun PageHitRow(page: PageSearchHit, onClick: () -> Unit) {
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
                style = AppTypography.SectionTitleLight,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${displayNotebookTitle(page.notebookTitle)} \u00b7 ${displayChapterTitle(page.chapterTitle)}",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
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

/* ---------- create dialog (title + description + shelf) ---------- */

@Composable
private fun CreateNotebookDialog(
    shelves: List<app.releaf.mobile.data.domain.Shelf>,
    onDismiss: () -> Unit,
    onCreateShelf: (String, (String) -> Unit) -> Unit,
    onConfirm: (title: String, description: String, shelfId: String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var selectedShelfId by rememberSaveable(shelves.firstOrNull()?.id) {
        mutableStateOf(shelves.firstOrNull()?.id ?: "shelf-general")
    }
    var showShelfList by remember { mutableStateOf(false) }
    var showNewShelfPrompt by remember { mutableStateOf(false) }
    val canConfirm = title.isNotBlank()
    val selectedShelf = shelves.firstOrNull { it.id == selectedShelfId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "New notebook",
                style = AppTypography.SectionTitleLight,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Text(
                    "Give it a name, a short description, and pick the shelf it lives on.",
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

                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
                    Text(
                        "Shelf",
                        style = AppTypography.Eyebrow,
                        color = AppColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.md))
                            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
                            .clickable { showShelfList = !showShelfList }
                            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedShelf?.name ?: "General",
                            style = AppTypography.Body,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (showShelfList) "▲" else "▼",
                            style = AppTypography.Meta,
                            color = AppColors.TextSecondary,
                        )
                    }
                    if (showShelfList) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.md))
                                .background(AppColors.CardSolid)
                                .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md)),
                        ) {
                            shelves.forEach { shelf ->
                                val isSelected = shelf.id == selectedShelfId
                                Text(
                                    text  = shelf.name,
                                    style = AppTypography.Body,
                                    color = if (isSelected) AppAccent.primary else AppColors.TextPrimary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isSelected) AppAccent.soft
                                            else AppColors.CardSolid
                                        )
                                        .clickable {
                                            selectedShelfId = shelf.id
                                            showShelfList = false
                                        }
                                        .padding(AppSpacing.s3),
                                )
                            }
                            Text(
                                text  = "+ New shelf…",
                                style = AppTypography.Body,
                                color = AppAccent.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showShelfList = false
                                        showNewShelfPrompt = true
                                    }
                                    .padding(AppSpacing.s3),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(title, description, selectedShelfId) },
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

    if (showNewShelfPrompt) {
        NewShelfPromptDialog(
            onDismiss = { showNewShelfPrompt = false },
            onConfirm = { name ->
                onCreateShelf(name) { newId ->
                    selectedShelfId = newId
                    showNewShelfPrompt = false
                }
            },
        )
    }
}

@Composable
private fun NewShelfPromptDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "New shelf",
                style = AppTypography.SectionTitleLight,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                Text(
                    "Shelf name",
                    style = AppTypography.Eyebrow,
                    color = AppColors.TextSecondary,
                )
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "e.g. Garden",
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty(),
            ) {
                Text("Create shelf", color = AppAccent.primary)
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
private fun displayPageTitle(page: PageSearchHit): String {
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
private fun pagePreviewBody(page: PageSearchHit): String? {
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

private fun displayNotebookTitle(title: String): String =
    title.trim().ifEmpty { "Untitled notebook" }

private fun displayChapterTitle(title: String): String =
    title.trim().ifEmpty { "Untitled chapter" }
