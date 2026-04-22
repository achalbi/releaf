/*
 * NotebookLocalDetailScreen.kt
 *
 * Room-backed notebook detail — shows a notebook's chapters with the pages
 * grouped beneath each. Distinct from the older NotebookDetailScreen (which
 * reads from the Drive fake repo and will be retired when phase 3 lands).
 *
 * Layout:
 *   - TopAppBar with a back button and the notebook's title (editable
 *     treatment deferred; same decision as the notepad editor which shows
 *     the editable surface inside the body).
 *   - Single LazyColumn with sticky chapter headers. Each chapter section
 *     includes an inline "Add page" row at the tail so page creation is
 *     local to the chapter and doesn't need a modal picker.
 *   - Chapter header rows AND page rows are both swipe-to-delete
 *     (EndToStart). Committed swipes tombstone immediately and surface an
 *     Undo snackbar. Note: undoing a chapter restores only the chapter row;
 *     its cascaded page tombstones stay deleted (see the VM doc).
 *   - FAB: "+ New chapter" — opens a titled dialog. When the notebook has no
 *     chapters yet, an empty state also shows a "Create chapter" CTA so the
 *     FAB isn't the only discovery path.
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.Card
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookLocalDetailScreen(
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotebookLocalDetailViewModel = viewModel(factory = NotebookLocalDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCreateChapterDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.notebook?.title?.ifBlank { "Untitled" } ?: " ",
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppColors.TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Only expose the FAB once the notebook has loaded — creating a
            // chapter under a stale/missing id would be a bug.
            if (state.notebook != null) {
                FloatingActionButton(
                    onClick        = { showCreateChapterDialog = true },
                    containerColor = AppColors.Coral,
                    contentColor   = AppColors.OnAccent,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New chapter")
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
            when {
                state.isLoading -> {
                    // Blank — the empty-state will render a beat later if
                    // there's really nothing here. No spinner for such a
                    // cheap local query.
                }
                state.notFound -> NotFoundState(onBack)
                state.chapters.isEmpty() -> EmptyChapterState(
                    onCreateChapter = { showCreateChapterDialog = true },
                )
                else -> ChapterList(
                    chapters = state.chapters,
                    pagesByChapter = state.pagesByChapter,
                    onOpenPage = onOpenPage,
                    onAddPage = { chapterId ->
                        viewModel.createPage(chapterId) { newId ->
                            onOpenPage(newId)
                        }
                    },
                    onDeleteChapter = { chapter ->
                        val id = chapter.id
                        viewModel.softDeleteChapter(id)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message     = "Chapter deleted",
                                actionLabel = "Undo",
                                duration    = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.undoDeleteChapter(id)
                            }
                        }
                    },
                    onDeletePage = { page ->
                        val id = page.id
                        viewModel.softDeletePage(id)
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message     = "Page deleted",
                                actionLabel = "Undo",
                                duration    = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.undoDeletePage(id)
                            }
                        }
                    },
                )
            }
        }
    }

    if (showCreateChapterDialog) {
        CreateTitledDialog(
            heading = "New chapter",
            subhead = "Chapters group related pages. You can rename it later.",
            placeholder = "Chapter title",
            onDismiss = { showCreateChapterDialog = false },
            onConfirm = { title ->
                viewModel.createChapter(title)
                showCreateChapterDialog = false
            },
        )
    }
}

/* ---------- empty states ---------- */

@Composable
private fun EmptyChapterState(onCreateChapter: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No chapters yet",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Chapters are how you group pages inside a notebook.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
        Spacer(Modifier.height(AppSpacing.s4))
        TextButton(onClick = onCreateChapter) {
            Text("Create a chapter", color = AppColors.Coral, style = AppTypography.Body)
        }
    }
}

@Composable
private fun NotFoundState(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Notebook not found",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "It may have been deleted.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
        Spacer(Modifier.height(AppSpacing.s4))
        TextButton(onClick = onBack) {
            Text("Back", color = AppColors.Coral, style = AppTypography.Body)
        }
    }
}

/* ---------- list ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterList(
    chapters: List<ChapterEntity>,
    pagesByChapter: Map<String, List<PageEntity>>,
    onOpenPage: (String) -> Unit,
    onAddPage: (String) -> Unit,
    onDeleteChapter: (ChapterEntity) -> Unit,
    onDeletePage: (PageEntity) -> Unit,
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
        chapters.forEach { chapter ->
            stickyHeader(key = "ch_${chapter.id}") {
                SwipeableChapterHeader(
                    chapter = chapter,
                    onDelete = { onDeleteChapter(chapter) },
                )
            }
            val pages = pagesByChapter[chapter.id].orEmpty()
            items(items = pages, key = { "pg_${it.id}" }) { page ->
                SwipeablePageRow(
                    page = page,
                    onOpen = { onOpenPage(page.id) },
                    onDelete = { onDeletePage(page) },
                )
            }
            item(key = "addpage_${chapter.id}") {
                AddPageRow(onClick = { onAddPage(chapter.id) })
            }
        }
        item(key = "tail_spacer") { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

/* ---------- chapter header row ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableChapterHeader(
    chapter: ChapterEntity,
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
        ChapterHeader(chapter)
    }
}

@Composable
private fun ChapterHeader(chapter: ChapterEntity) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Canvas)
            .padding(
                top    = AppSpacing.s3,
                bottom = AppSpacing.s1,
            ),
    ) {
        Text(
            chapter.title.ifBlank { "Untitled chapter" },
            style = AppTypography.Eyebrow,
            color = AppColors.Coral,
        )
    }
}

/* ---------- page row ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeablePageRow(
    page: PageEntity,
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
        PageRow(page, onClick = onOpen)
    }
}

@Composable
private fun PageRow(page: PageEntity, onClick: () -> Unit) {
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
        }
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

/* ---------- add page row ---------- */

@Composable
private fun AddPageRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = AppColors.Coral,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            "Add page",
            style = AppTypography.Body,
            color = AppColors.Coral,
        )
    }
}

/* ---------- shared titled-create dialog ---------- */

@Composable
private fun CreateTitledDialog(
    heading: String,
    subhead: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    val canConfirm = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                heading,
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Text(
                    subhead,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
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
                        if (title.isEmpty()) {
                            Text(
                                placeholder,
                                style = AppTypography.Body,
                                color = AppColors.TextTertiary,
                            )
                        }
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                            cursorBrush = SolidColor(AppColors.Coral),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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

/* ---------- small helpers mirroring the notepad list ---------- */

private fun displayPageTitle(page: PageEntity): String {
    page.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val firstLine = page.notes
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
    return firstLine?.takeIf { it.isNotEmpty() } ?: "Untitled page"
}

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
