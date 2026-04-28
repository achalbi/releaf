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

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.LeafColorPicker
import app.releaf.mobile.ui.components.LeafDropdownDivider
import app.releaf.mobile.ui.components.LeafDropdownItem
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.PageOverflowButton
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
    val rawState by viewModel.state.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    // View-side sort layer — applied on top of the ViewModel's
    // unsorted `state.notebooks` so the existing 4-arg combine()
    // doesn't have to grow. Recomputes only when sortMode or
    // notebooks change.
    val state = remember(rawState, sortMode) {
        rawState.copy(
            notebooks = when (sortMode) {
                NotebookSortMode.Recent -> rawState.notebooks.sortedByDescending { it.entity.updatedAt }
                NotebookSortMode.Name   -> rawState.notebooks.sortedBy { it.entity.title.lowercase() }
                NotebookSortMode.Pages  -> rawState.notebooks.sortedByDescending { it.pageCount }
            }
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showCreateShelfDialog by rememberSaveable { mutableStateOf(false) }
    var showArchivedSheet by rememberSaveable { mutableStateOf(false) }
    // Bottom-sheet prototype for the top-of-screen overflow. Replaces
    // the kebab DropdownMenu with a ModalBottomSheet so create + sort
    // + archived actions sit inside a thumb-reachable surface.
    var showActionsSheet by rememberSaveable { mutableStateOf(false) }
    var newShelfName by rememberSaveable { mutableStateOf("") }
    var newShelfColorToken by rememberSaveable { mutableStateOf("coral") }
    var listExpanded by rememberSaveable { mutableStateOf(true) }
    var pagesExpanded by rememberSaveable { mutableStateOf(true) }
    var hiddenNotebookIds by rememberSaveable(state.tab) { mutableStateOf(setOf<String>()) }
    // Pending delete / archive — user tapped an action tile on a
    // swiped row; holding the notebook here until they confirm (or
    // dismiss) the guard dialog so destructive / state-changing
    // actions never fire on a single tap.
    var pendingDelete by remember { mutableStateOf<NotebookEntity?>(null) }
    var pendingArchive by remember { mutableStateOf<NotebookEntity?>(null) }
    // Pending shelf delete — captures the shelf's id (the entity
    // metadata isn't needed beyond the title we read off `state.shelves`).
    var pendingShelfDelete by remember { mutableStateOf<String?>(null) }

    // Outer Column with `.padding(AppSpacing.s4)` — exact structural
    // twin of the notepad tab's outer Column. LeafEyebrow is the
    // first direct child of this Column, so its top edge sits at
    // Box.top + 16dp (s4) — the same coordinate the notepad eyebrow
    // sits at.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            LeafEyebrow(label = "releaf · library")

            // Title row — big serif "your shelves" + overflow menu
            // on the right. The overflow button is taller than the
            // title, but the eyebrow above is anchored at its own
            // natural top, so this row's bulk doesn't push the
            // eyebrow.
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text     = "your shelves",
                    style    = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize   = 32.sp,
                    ),
                    color    = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Kebab → opens the bottom-sheet action menu below.
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AppColors.CardSolid.copy(alpha = 0.6f))
                        .border(1.dp, AppColors.BorderDefault, CircleShape)
                        .clickable { showActionsSheet = true }
                        .size(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.MoreVert,
                        contentDescription = "More",
                        tint               = AppColors.TextPrimary,
                        modifier           = Modifier.size(15.dp),
                    )
                }
            }

            // Tab switcher / search / overview no longer need their
            // own horizontal padding — the outer Column's `.padding
            // (AppSpacing.s4)` already insets all children by 16dp.
            TabSwitcher(
                tab = state.tab,
                onSelect = viewModel::setTab,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            SearchField(
                query = state.query,
                onQueryChange = viewModel::updateQuery,
                onClearQuery = viewModel::clearQuery,
            )
            NotebookOverview(state = state)

            Lists(
                modifier = Modifier.weight(1f, fill = true),
                state = state,
                listExpanded = listExpanded,
                onToggleList = { listExpanded = !listExpanded },
                pagesExpanded = pagesExpanded,
                onTogglePages = { pagesExpanded = !pagesExpanded },
                onOpenNotebook = onOpenNotebook,
                onOpenPage = onOpenPage,
                onArchive = { notebook -> pendingArchive = notebook },
                onDeleteRequest = { notebook -> pendingDelete = notebook },
                onDeleteShelf = { id -> pendingShelfDelete = id },
                hiddenNotebookIds = hiddenNotebookIds,
            )
        }

        // Snackbar host overlay — anchored bottom-center so it
        // peeks above the BottomNav (which the outer SignedInShell
        // Scaffold already accounts for in its content-area inset).
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // FAB overlay — only exposed on the Current tab; Archive is
        // a read-only destination for already-created notebooks.
        // Bottom-end alignment matches Material's default FAB
        // placement; the 16dp margin matches Scaffold's default FAB
        // padding. Shelf creation lives in the overflow menu, not as
        // a second FAB, so the bottom-right stays a single primary
        // affordance.
        if (state.tab == NotebookListTab.Current) {
            FloatingActionButton(
                onClick        = { showCreateDialog = true },
                containerColor = AppAccent.primary,
                contentColor   = AppColors.OnAccent,
                modifier       = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppSpacing.s4),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New notebook")
            }
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

    pendingArchive?.let { notebook ->
        val title       = notebook.title.ifBlank { "Untitled" }
        val unarchiving = state.tab == NotebookListTab.Archive
        // State-changing (not destructive) — uses the accent color
        // for the confirm button instead of routing through
        // DeleteConfirmationDialog (which paints the confirm in
        // AppColors.Danger). Mirrors the delete dialog's structure
        // so both action paths read as one family.
        AlertDialog(
            onDismissRequest = { pendingArchive = null },
            title = {
                Text(
                    text  = if (unarchiving) "Unarchive notebook?" else "Archive notebook?",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                )
            },
            text = {
                Text(
                    text = if (unarchiving)
                        "“$title” will move back to your active notebooks."
                    else
                        "“$title” will move to the archive. You can restore it from the Archive tab.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = notebook.id
                    pendingArchive = null
                    hiddenNotebookIds = hiddenNotebookIds + id
                    if (unarchiving) {
                        viewModel.unarchive(id) { success ->
                            if (!success) hiddenNotebookIds = hiddenNotebookIds - id
                        }
                    } else {
                        viewModel.archive(id) { success ->
                            if (!success) hiddenNotebookIds = hiddenNotebookIds - id
                        }
                    }
                }) {
                    Text(
                        text  = if (unarchiving) "Unarchive" else "Archive",
                        color = AppAccent.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchive = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSolid,
        )
    }

    pendingShelfDelete?.let { id ->
        val shelf     = state.shelves.firstOrNull { it.id == id }
        val name      = shelf?.name?.ifBlank { "Untitled shelf" } ?: "this shelf"
        val bookCount = state.notebooks.count { it.entity.shelfId == id }
        if (bookCount > 0) {
            // Shelves are only deletable when empty — books otherwise
            // get orphaned. Show a single-button info dialog instead
            // of the destructive confirm so there's no path to delete
            // by accident.
            AlertDialog(
                onDismissRequest = { pendingShelfDelete = null },
                title = {
                    Text(
                        text  = "Can’t delete shelf",
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                    )
                },
                text = {
                    Text(
                        text = "“$name” still holds $bookCount " +
                            "${if (bookCount == 1) "book" else "books"}. " +
                            "Move or delete the " +
                            "${if (bookCount == 1) "book" else "books"} first, " +
                            "then try again.",
                        style = AppTypography.Body,
                        color = AppColors.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { pendingShelfDelete = null }) {
                        Text("Got it", color = AppAccent.primary)
                    }
                },
                containerColor = AppColors.CardSolid,
            )
        } else {
            DeleteConfirmationDialog(
                title = "Delete shelf?",
                message = "“$name” will be deleted. You can undo this immediately after.",
                onDismiss = { pendingShelfDelete = null },
                onConfirm = {
                    pendingShelfDelete = null
                    viewModel.softDeleteShelf(id)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message     = "Shelf deleted",
                            actionLabel = "Undo",
                            duration    = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoDeleteShelf(id)
                        }
                    }
                },
            )
        }
    }

    if (showActionsSheet) {
        LibraryActionsSheet(
            sortMode      = sortMode,
            onDismiss     = { showActionsSheet = false },
            onNewShelf    = {
                showActionsSheet = false
                newShelfName = ""
                showCreateShelfDialog = true
            },
            onNewNotebook = {
                showActionsSheet = false
                showCreateDialog = true
            },
            onPickSort    = { mode ->
                viewModel.setSortMode(mode)
                showActionsSheet = false
            },
            onArchived    = {
                showActionsSheet = false
                showArchivedSheet = true
            },
        )
    }

    if (showArchivedSheet) {
        ArchivedPagesSheet(
            onDismiss = { showArchivedSheet = false },
            onOpenPage = { id ->
                showArchivedSheet = false
                onOpenPage(id)
            },
        )
    }

    if (showCreateShelfDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreateShelfDialog = false
                newShelfColorToken = "coral"
            },
            title  = { Text("New shelf") },
            text   = {
                Column {
                    Text(
                        text  = "Shelves group notebooks by area of life — \"work\", \"garden\", \"daily\".",
                        style = AppTypography.Meta,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(Modifier.height(AppSpacing.s3))
                    androidx.compose.material3.OutlinedTextField(
                        value         = newShelfName,
                        onValueChange = { newShelfName = it },
                        label         = { Text("Shelf name") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.s3))
                    Text(
                        text  = "COLOR",
                        style = AppTypography.Eyebrow,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(Modifier.height(AppSpacing.s2))
                    LeafColorPicker(
                        selection   = newShelfColorToken,
                        onSelect    = { newShelfColorToken = it },
                        showPreview = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = newShelfName.trim().ifEmpty { "Untitled shelf" }
                    viewModel.createShelf(trimmed, colorToken = newShelfColorToken)
                    showCreateShelfDialog = false
                    newShelfColorToken = "coral"
                }) {
                    Text("Create shelf")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreateShelfDialog = false
                    newShelfColorToken = "coral"
                }) {
                    Text("Cancel")
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
            onConfirm = { title, description, shelfId, colorToken ->
                viewModel.createNotebook(
                    title       = title,
                    description = description,
                    shelfId     = shelfId,
                    colorToken  = colorToken,
                ) { newId -> onOpenNotebook(newId) }
                showCreateDialog = false
            },
        )
    }
}

/* ---------- tabs + search ---------- */

/**
 * Active / Archived switch — visually the same "classic mode" switch
 * the notepad tab uses for Day / Recents. Two text-labeled segments
 * inside a rounded capsule with a muted-cream track; selected
 * segment fills with the deep theme green and white text. Wider and
 * more readable than the previous icon-pair pill, and consistent
 * with the rest of the app's segmented controls.
 */
@Composable
private fun TabSwitcher(
    tab: NotebookListTab,
    onSelect: (NotebookListTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            // Track color matches the muted-cream tone used on the
            // notepad tab's switch — keeps every segmented control
            // across the app in one visual family.
            .background(Color(0xFFEFE7CD))
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(50),
            )
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabSegment(
            label = "Active",
            isActive = tab == NotebookListTab.Current,
            onClick = { onSelect(NotebookListTab.Current) },
            modifier = Modifier.weight(1f),
        )
        TabSegment(
            label = "Archived",
            isActive = tab == NotebookListTab.Archive,
            onClick = { onSelect(NotebookListTab.Archive) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabSegment(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isActive) AppColors.ThemeGreenDeep else Color.Transparent
    val fg = if (isActive) AppColors.OnAccent else AppColors.TextSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Button,
            color = fg,
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
    onDeleteShelf: (String) -> Unit,
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
        // No horizontal contentPadding — the outer Column in
        // NotebookTabScreen already insets every child by 16dp
        // (.padding(AppSpacing.s4)). Adding more here double-pads
        // the cards relative to the header, tab switcher, search
        // field, and overview that share the same parent.
        contentPadding = PaddingValues(
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
                        shelfId = null,
                        archive = archive,
                        expanded = listExpanded,
                        onToggle = onToggleList,
                        onOpenNotebook = onOpenNotebook,
                        onArchive = onArchive,
                        onDeleteRequest = onDeleteRequest,
                        onDeleteShelf = onDeleteShelf,
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
                            shelfId = shelf.id,
                            archive = archive,
                            expanded = listExpanded,
                            onToggle = onToggleList,
                            onOpenNotebook = onOpenNotebook,
                            onArchive = onArchive,
                            onDeleteRequest = onDeleteRequest,
                            onDeleteShelf = onDeleteShelf,
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
                            shelfId = shelf.id,
                            archive = archive,
                            expanded = listExpanded,
                            onToggle = onToggleList,
                            onOpenNotebook = onOpenNotebook,
                            onArchive = onArchive,
                            onDeleteRequest = onDeleteRequest,
                            onDeleteShelf = onDeleteShelf,
                        )
                    }
                }
                if (orphanBooks.isNotEmpty()) {
                    item(key = "shelf_card_unshelved") {
                        NotebookCard(
                            state = state,
                            visibleBooks = orphanBooks,
                            shelfName = "Unshelved",
                            shelfId = null,
                            archive = archive,
                            expanded = listExpanded,
                            onToggle = onToggleList,
                            onOpenNotebook = onOpenNotebook,
                            onArchive = onArchive,
                            onDeleteRequest = onDeleteRequest,
                            onDeleteShelf = onDeleteShelf,
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
                    shelfId = null,
                    archive = archive,
                    expanded = listExpanded,
                    onToggle = onToggleList,
                    onOpenNotebook = onOpenNotebook,
                    onArchive = onArchive,
                    onDeleteRequest = onDeleteRequest,
                    onDeleteShelf = onDeleteShelf,
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
    shelfId: String?,
    archive: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onArchive: (NotebookEntity) -> Unit,
    onDeleteRequest: (NotebookEntity) -> Unit,
    onDeleteShelf: (String) -> Unit,
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
    // Shelf overflow only makes sense for real, non-default shelves.
    // The synthetic groups ("Current notebooks", "Archive",
    // "Unshelved") have no shelfId, and the seeded General shelf
    // must always exist as the fallback parent for new notebooks.
    val canDeleteShelf = shelfId != null &&
        shelfId != app.releaf.mobile.data.shelf.ShelfEntity.DEFAULT_GENERAL_ID

    CollapsibleCard(
        title = title,
        expanded = expanded,
        onToggle = onToggle,
        // Lighter weight than the default heavy SectionTitle —
        // matches the tab-label feel of the screen header above.
        // 16sp keeps the shelf/section header readable but visually
        // quieter than the 32sp serif "your shelves" title and the
        // notebook-row titles in the body below.
        titleStyle = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
        // Shelves stay open by design — the screen-level "your shelves"
        // header is the surface-wide affordance for collapsing.
        showCollapseToggle = false,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                MetaPill(text = countLabel, accent = !archive)
                if (canDeleteShelf) {
                    PageOverflowButton {
                        LeafDropdownItem(
                            label       = "Delete shelf",
                            leadingIcon = Icons.Filled.Delete,
                            destructive = true,
                            onClick     = { onDeleteShelf(shelfId!!) },
                        )
                    }
                }
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
                        // Resolve the per-row palette once: notebook
                        // token wins; otherwise fall back to the
                        // parent shelf's color so all books on the
                        // same shelf read as one visual family.
                        val palette = resolveBookPalette(
                            notebook = summary.entity,
                            shelves  = state.shelves,
                        )
                        SwipeableNotebookListItem(
                            summary = summary,
                            palette = palette,
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

/**
 * Stop-and-select swipe pattern (iOS Mail style). The row docks at one
 * of two anchors:
 *
 *   • Closed — full width, no actions visible.
 *   • Open   — offset left by [actionsWidth], revealing a stacked pair
 *               of trailing action buttons (Archive/Unarchive +
 *               Delete) the user can tap to confirm.
 *
 * Tapping the row body when it's open snaps it shut. A swipe back also
 * closes it. Both action buttons close the row after firing so the
 * caller's snackbar / confirmation dialog sees a tidy resting state.
 *
 * The row is held in the open anchor regardless of how far the user
 * dragged — they don't need to commit to a full-width swipe to reach
 * either action, which keeps the gesture forgiving.
 */
private enum class RowSwipeAnchor { Closed, Open }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableNotebookListItem(
    summary: NotebookSummary,
    palette: app.releaf.mobile.ui.components.ShelfPalette?,
    archive: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val density       = LocalDensity.current
    val actionWidth   = 88.dp
    val actionsWidth  = actionWidth * 2
    val actionsWidthPx = with(density) { actionsWidth.toPx() }

    // The 6-arg AnchoredDraggableState factory is marked @Deprecated
    // in Compose Foundation 1.7+, with the documented migration path
    // pointing at anchoredDraggableFlingBehavior(...). That function
    // is currently `internal` in this Compose-BOM version, so the
    // deprecated factory is still the only way to thread thresholds
    // and animation specs through. Suppression revisits when the
    // public replacement ships.
    @Suppress("DEPRECATION")
    val state = remember(actionsWidthPx) {
        AnchoredDraggableState(
            initialValue        = RowSwipeAnchor.Closed,
            positionalThreshold = { distance: Float -> distance * 0.5f },
            velocityThreshold   = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec   = spring(),
            decayAnimationSpec  = exponentialDecay(),
        ).apply {
            updateAnchors(
                DraggableAnchors {
                    RowSwipeAnchor.Closed at 0f
                    RowSwipeAnchor.Open   at -actionsWidthPx
                },
            )
        }
    }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxWidth()) {
        // Background actions — matchParentSize so this layer takes the
        // foreground row's natural height; horizontalArrangement = End
        // tucks the two action tiles against the trailing edge.
        Row(
            modifier              = Modifier.matchParentSize(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            NotebookActionTile(
                icon         = if (archive) Icons.Filled.Unarchive else Icons.Filled.Archive,
                label        = if (archive) "Unarchive" else "Archive",
                fill         = AppAccent.soft,
                contentColor = AppAccent.deep,
                width        = actionWidth,
                onClick      = {
                    onArchive()
                    scope.launch { state.animateTo(RowSwipeAnchor.Closed) }
                },
            )
            NotebookActionTile(
                icon         = Icons.Filled.Delete,
                label        = "Delete",
                fill         = AppColors.Danger,
                contentColor = AppColors.OnAccent,
                width        = actionWidth,
                onClick      = {
                    onDelete()
                    scope.launch { state.animateTo(RowSwipeAnchor.Closed) }
                },
            )
        }
        // Foreground row — slides over the action tiles. Tapping a
        // closed row opens the notebook; tapping an open row snaps
        // it shut so the body acts as a "cancel" affordance.
        NotebookListItem(
            summary = summary,
            palette = palette,
            onClick = {
                if (state.currentValue == RowSwipeAnchor.Open) {
                    scope.launch { state.animateTo(RowSwipeAnchor.Closed) }
                } else {
                    onOpen()
                }
            },
            modifier = Modifier
                .offset { IntOffset(state.requireOffset().toInt(), 0) }
                .anchoredDraggable(
                    state       = state,
                    orientation = Orientation.Horizontal,
                ),
        )
    }
}

@Composable
private fun NotebookActionTile(
    icon: ImageVector,
    label: String,
    fill: Color,
    contentColor: Color,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s3),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = contentColor,
            modifier           = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(AppSpacing.s1))
        Text(
            text  = label,
            style = AppTypography.Meta,
            color = contentColor,
        )
    }
}

@Composable
private fun NotebookListItem(
    summary: NotebookSummary,
    palette: app.releaf.mobile.ui.components.ShelfPalette?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notebook = summary.entity
    // Opaque fill is required: the action tiles are laid out *behind*
    // the foreground row, so a transparent row would leak the buttons
    // through at rest.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        IconChipSquare(
            icon = Icons.Filled.Book,
            fillColor = palette?.accentSoft,
            iconTint  = palette?.background,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Text(
                text = notebook.title.ifBlank { "Untitled" },
                // Lighter than the default bold SectionTitle — reads
                // as a list row rather than a section head. 16sp
                // drops the visual weight of dense lists.
                style = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        // Active / Archived pill rides the trailing edge of the
        // outer row, top-aligned with the icon and title — pulls
        // the status off the title row and into a dedicated lane on
        // the right so it's easier to spot at a glance.
        if (notebook.archivedAt == null) MetaPill("Active", accent = true)
        else MetaPill("Archived")
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

/**
 * Resolve which palette colors should drive a notebook row's chip.
 * Order: notebook's own color (mapped from hex to a leaf-theme
 * token) → parent shelf's color (same mapping) → null (caller falls
 * back to the generic accent). Returning null lets the chip stay
 * neutral when neither the notebook nor its shelf has been colored.
 */
private fun resolveBookPalette(
    notebook: NotebookEntity,
    shelves: List<app.releaf.mobile.data.domain.Shelf>,
): app.releaf.mobile.ui.components.ShelfPalette? {
    val token = hexToLeafToken(notebook.colorHex)
        ?: shelves.firstOrNull { it.id == notebook.shelfId }
            ?.let { hexToLeafToken(it.colorHex) }
    return token?.let { app.releaf.mobile.ui.components.ShelfTheme.palette(it) }
}

/**
 * Inverse of `themeHex` — maps the four leaf-theme primary hexes
 * back to their token name. Anything that doesn't match returns
 * null so the caller can fall through to the default chrome.
 */
private fun hexToLeafToken(hex: String?): String? {
    val normalized = hex?.uppercase()?.removePrefix("#") ?: return null
    return when (normalized) {
        "7AA874" -> "green"
        "E07856" -> "coral"
        "F4C430" -> "yellow"
        "B8956A" -> "dry"
        else     -> null
    }
}

@Composable
private fun IconChipSquare(
    icon: ImageVector,
    fillColor: Color? = null,
    iconTint: Color? = null,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(fillColor ?: AppAccent.soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: AppAccent.deep,
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
        // Match the 16sp section/card headers used by the shelf
        // cards above so the search-results card reads as part of
        // the same visual rhythm.
        titleStyle = AppTypography.SectionTitle.copy(fontSize = 16.sp),
        // No chevron — keeps parity with the shelf cards on this
        // tab. The card only renders during an active search so
        // hiding the body would leave a dead header strip.
        showCollapseToggle = false,
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
                // Match the 16sp notebook-row title above so search
                // hits sit at the same visual weight as the rest of
                // the list rows.
                style = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
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

/* ---------- create dialog (title + description + shelf) ---------- */

@Composable
private fun CreateNotebookDialog(
    shelves: List<app.releaf.mobile.data.domain.Shelf>,
    onDismiss: () -> Unit,
    onCreateShelf: (String, (String) -> Unit) -> Unit,
    onConfirm: (title: String, description: String, shelfId: String, colorToken: String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var selectedShelfId by rememberSaveable(shelves.firstOrNull()?.id) {
        mutableStateOf(shelves.firstOrNull()?.id ?: "shelf-general")
    }
    var colorToken by rememberSaveable { mutableStateOf("coral") }
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

                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                    Text(
                        "Color",
                        style = AppTypography.Eyebrow,
                        color = AppColors.TextSecondary,
                    )
                    LeafColorPicker(
                        selection   = colorToken,
                        onSelect    = { colorToken = it },
                        showPreview = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(title, description, selectedShelfId, colorToken) },
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

/**
 * Bottom-sheet replacement for the screen-level kebab dropdown.
 * Rolls up create / sort / archived actions into a single thumb-
 * reachable surface — drag to dismiss, tap a row to fire its
 * action and auto-close. Sections (Create / Sort by / More) get
 * an eyebrow label + hairlines so the panel reads as grouped
 * actions rather than a flat list.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LibraryActionsSheet(
    sortMode: NotebookSortMode,
    onDismiss: () -> Unit,
    onNewShelf: () -> Unit,
    onNewNotebook: () -> Unit,
    onPickSort: (NotebookSortMode) -> Unit,
    onArchived: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
        contentColor     = AppColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AppSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            ActionSheetEyebrow("CREATE")
            ActionSheetRow(
                icon    = Icons.Filled.Add,
                label   = "New shelf",
                onClick = onNewShelf,
            )
            HairlineDivider()
            ActionSheetRow(
                icon    = Icons.Filled.Book,
                label   = "New notebook",
                onClick = onNewNotebook,
            )

            ActionSheetEyebrow("SORT BY", topPadding = AppSpacing.s4)
            NotebookSortMode.entries.forEachIndexed { index, mode ->
                ActionSheetRow(
                    icon     = null,
                    label    = mode.label,
                    selected = sortMode == mode,
                    onClick  = { onPickSort(mode) },
                )
                if (index < NotebookSortMode.entries.lastIndex) HairlineDivider()
            }

            ActionSheetEyebrow("MORE", topPadding = AppSpacing.s4)
            ActionSheetRow(
                icon    = Icons.Filled.Archive,
                label   = "Archived pages",
                onClick = onArchived,
            )
        }
    }
}

@Composable
private fun ActionSheetEyebrow(
    text: String,
    topPadding: androidx.compose.ui.unit.Dp = AppSpacing.s2,
) {
    Text(
        text     = text,
        style    = AppTypography.Eyebrow,
        color    = AppColors.TextSecondary,
        modifier = Modifier.padding(
            start  = AppSpacing.s5,
            end    = AppSpacing.s5,
            top    = topPadding,
            bottom = AppSpacing.s1,
        ),
    )
}

@Composable
private fun ActionSheetRow(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        if (icon != null) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = AppAccent.deep,
                modifier           = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Text(
            text     = label,
            style    = AppTypography.Body,
            color    = if (selected) AppAccent.primary else AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text  = "✓",
                style = AppTypography.Body,
                color = AppAccent.primary,
            )
        }
    }
}

/**
 * Cross-notebook archive picker. Mirrors the iOS
 * `ArchivedNotebooksSheet`: fetches archived pages from
 * `DriveRepository.listArchivedPages()` on appear, renders each
 * row with title + breadcrumb (notebook / chapter) + a Restore
 * button. Self-contained — instantiates a FakeDriveRepository
 * directly so NotebookTabViewModel doesn't take on a Drive
 * dependency for one feature.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedPagesSheet(
    onDismiss: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )
    var rows by remember {
        mutableStateOf<List<app.releaf.mobile.data.drive.ArchivedPage>>(emptyList())
    }
    var isLoading by remember { mutableStateOf(true) }
    val repository = remember { app.releaf.mobile.data.drive.FakeDriveRepository() }

    suspend fun reload() {
        isLoading = true
        rows = try { repository.listArchivedPages() } catch (e: Exception) { emptyList() }
        isLoading = false
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }
    val coroutineScope = rememberCoroutineScope()

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
        contentColor     = AppColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s5)
                .padding(bottom = AppSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Text("ARCHIVED",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep)
            Text(
                text  = "nothing's lost",
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Pages you've archived stay here, sorted by when they went in. Restore brings them back to their original chapter.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )

            when {
                isLoading && rows.isEmpty() -> {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(vertical = AppSpacing.s5),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = AppColors.ThemeGreenPrimary,
                        )
                    }
                }
                rows.isEmpty() -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                        Text(
                            text  = "Nothing here",
                            style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp),
                            color = AppColors.TextPrimary,
                        )
                        Text(
                            text  = "Archive a page from its overflow menu and it'll show up here.",
                            style = AppTypography.Body,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                        rows.forEach { row ->
                            ArchivedPageRow(
                                row       = row,
                                onOpen    = { onOpenPage(row.id) },
                                onRestore = {
                                    coroutineScope.launch {
                                        try { repository.restorePage(row.id) } catch (_: Exception) {}
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.s2))
            app.releaf.mobile.ui.components.AppButton(
                text     = "Done",
                onClick  = onDismiss,
                variant  = app.releaf.mobile.ui.components.AppButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun ArchivedPageRow(
    row: app.releaf.mobile.data.drive.ArchivedPage,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.Canvas)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onOpen)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        androidx.compose.material3.Icon(
            imageVector        = Icons.Filled.Archive,
            contentDescription = null,
            tint               = AppColors.GreenText,
            modifier           = Modifier.size(13.dp),
        )
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text     = row.title,
                style    = AppTypography.Button,
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text     = "${row.notebookTitle} / ${row.chapterTitle}",
                style    = AppTypography.Tag,
                color    = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = app.releaf.mobile.ui.components.relativeTimeAgo(row.archivedAt.toString()),
                style = AppTypography.Tag,
                color = AppColors.TextTertiary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.GreenSoft)
                .clickable(onClick = onRestore)
                .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s1),
        ) {
            Text(
                text  = "Restore",
                style = AppTypography.Button,
                color = AppColors.GreenText,
            )
        }
    }
}
