/*
 * NotebookLocalDetailScreen.kt
 *
 * Room-backed notebook detail — drills into a notebook from the Notebooks
 * tab. The layout leads with a serif hero card (icon + title + description
 * + status + stats row), then a "Chapters" section that lists each chapter
 * as a tappable row. Tapping a chapter routes to ChapterLocalDetailScreen
 * for the pages beneath it.
 *
 * Navigation chrome is breadcrumbs under the screen header, not a
 * TopAppBar — keeps parity with the rest of the notebook surfaces.
 */

package app.releaf.mobile.features.notebook

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.ui.components.BreadcrumbSegment
import app.releaf.mobile.ui.components.Breadcrumbs
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.RoundIconButton
import app.releaf.mobile.ui.components.ScreenHeader
import app.releaf.mobile.ui.components.absoluteDate
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.launch

@Composable
fun NotebookLocalDetailScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenChapter: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotebookLocalDetailViewModel = viewModel(factory = NotebookLocalDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCreateChapterDialog by rememberSaveable { mutableStateOf(false) }
    var showEditNotebookDialog by rememberSaveable { mutableStateOf(false) }
    var heroExpanded by rememberSaveable { mutableStateOf(true) }
    var chaptersExpanded by rememberSaveable { mutableStateOf(true) }
    // Pending delete — holds the chapter the user swiped until they confirm
    // via the guard dialog.
    var pendingChapterDelete by remember { mutableStateOf<ChapterEntity?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        // Outer SignedInShell Scaffold already consumes the system-bar
        // insets; swallow them here so the header docks flush to the
        // top instead of doubling the status-bar padding. Same fix
        // the Notepad and Notebook tab screens use.
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ScreenHeader(
                eyebrow = "Notebook",
                title = "Your notebooks",
                // Match the Notebook tab + Notepad screen rhythm —
                // top-level / drill-in headers all dock at s3 above
                // the eyebrow.
                topPadding = AppSpacing.s3,
            )
            Breadcrumbs(
                segments = listOf(
                    BreadcrumbSegment("Home", onTap = onHome),
                    BreadcrumbSegment("Notebook", onTap = onBack),
                    BreadcrumbSegment(state.notebook?.title?.ifBlank { "Notebook" } ?: "Notebook"),
                ),
                modifier = Modifier.padding(horizontal = AppSpacing.s4),
            )

            when {
                state.isLoading -> Spacer(Modifier.weight(1f))
                state.notFound -> NotFoundState(onBack = onBack, modifier = Modifier.weight(1f))
                else -> {
                    NotebookDetailBody(
                        state = state,
                        heroExpanded = heroExpanded,
                        onToggleHero = { heroExpanded = !heroExpanded },
                        chaptersExpanded = chaptersExpanded,
                        onToggleChapters = { chaptersExpanded = !chaptersExpanded },
                        onEditNotebook = { showEditNotebookDialog = true },
                        onAddChapter = { showCreateChapterDialog = true },
                        onOpenChapter = onOpenChapter,
                        onDeleteChapter = { chapter -> pendingChapterDelete = chapter },
                        modifier = Modifier.weight(1f, fill = true),
                    )
                }
            }
        }
    }

    pendingChapterDelete?.let { chapter ->
        val title = chapter.title.ifBlank { "Untitled chapter" }
        DeleteConfirmationDialog(
            title = "Delete chapter?",
            message = "\u201C$title\u201D and its pages will be deleted. " +
                "You can undo this immediately after.",
            onDismiss = { pendingChapterDelete = null },
            onConfirm = {
                val id = chapter.id
                pendingChapterDelete = null
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
        )
    }

    if (showCreateChapterDialog) {
        TitledDialog(
            heading = "New chapter",
            subhead = "Chapters group related pages. You can rename it later.",
            titlePlaceholder = "Chapter title",
            descriptionPlaceholder = "Describe this chapter (optional)",
            onDismiss = { showCreateChapterDialog = false },
            onConfirm = { title, description ->
                viewModel.createChapter(title, description)
                showCreateChapterDialog = false
            },
        )
    }

    if (showEditNotebookDialog) {
        val current = state.notebook
        if (current != null) {
            TitledDialog(
                heading = "Edit notebook",
                subhead = "Tweak the title or the summary that shows on the list.",
                titlePlaceholder = "Notebook title",
                descriptionPlaceholder = "Describe this notebook (optional)",
                initialTitle = current.title,
                initialDescription = current.description.orEmpty(),
                onDismiss = { showEditNotebookDialog = false },
                onConfirm = { title, description ->
                    viewModel.saveNotebook(title, description)
                    showEditNotebookDialog = false
                },
            )
        }
    }
}

/* ---------- body ---------- */

@Composable
private fun NotebookDetailBody(
    state: NotebookLocalDetailUiState,
    heroExpanded: Boolean,
    onToggleHero: () -> Unit,
    chaptersExpanded: Boolean,
    onToggleChapters: () -> Unit,
    onEditNotebook: () -> Unit,
    onAddChapter: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onDeleteChapter: (ChapterEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notebook = state.notebook ?: return
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start  = AppSpacing.s4,
            end    = AppSpacing.s4,
            top    = AppSpacing.s3,
            bottom = AppSpacing.s10,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        item(key = "hero") {
            NotebookHeroCard(
                notebook = notebook,
                chapterCount = state.totalChapterCount,
                pageCount = state.totalPageCount,
                expanded = heroExpanded,
                onToggle = onToggleHero,
                onEdit = onEditNotebook,
            )
        }
        item(key = "chapters_card") {
            ChaptersCard(
                state = state,
                expanded = chaptersExpanded,
                onToggle = onToggleChapters,
                onAdd = onAddChapter,
                onOpen = onOpenChapter,
                onDelete = onDeleteChapter,
            )
        }
        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

/* ---------- hero ---------- */

@Composable
private fun NotebookHeroCard(
    notebook: NotebookEntity,
    chapterCount: Int,
    pageCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg)),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                verticalAlignment = Alignment.Top,
            ) {
                HeroIconChip()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    Text(
                        text = notebook.title.ifBlank { "Untitled" },
                        style = AppTypography.EditorialTitleLight,
                        color = AppColors.TextPrimary,
                    )
                    if (!notebook.description.isNullOrBlank()) {
                        Text(
                            text = notebook.description,
                            style = AppTypography.Body,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    if (notebook.archivedAt == null) MetaPill("Active", accent = true)
                    else MetaPill("Archived")
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                        RoundIconButton(
                            icon = Icons.Filled.Edit,
                            contentDescription = "Edit notebook",
                            onClick = onEdit,
                        )
                        RoundIconButton(
                            icon = if (expanded) Icons.Filled.KeyboardArrowUp
                                   else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            onClick = onToggle,
                        )
                    }
                }
            }
        }
        if (expanded) {
            HairlineDivider()
            StatsRow(
                chapterCount = chapterCount,
                pageCount = pageCount,
                createdAt = notebook.createdAt,
            )
        }
    }
}

@Composable
private fun HeroIconChip() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppAccent.soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Book,
            contentDescription = null,
            tint = AppAccent.deep,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun StatsRow(
    chapterCount: Int,
    pageCount: Int,
    createdAt: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        StatCell(
            value = chapterCount.toString(),
            label = if (chapterCount == 1) "Chapter" else "Chapters",
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        StatCell(
            value = pageCount.toString(),
            label = if (pageCount == 1) "Page" else "Pages",
            modifier = Modifier.weight(1f),
        )
        VerticalRule()
        StatCell(
            value = absoluteDate(createdAt),
            label = "Created",
            modifier = Modifier.weight(1f),
            valueStyle = AppTypography.SectionTitleLight,
        )
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueStyle: androidx.compose.ui.text.TextStyle = AppTypography.StatNumber,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = valueStyle,
            color = AppColors.TextPrimary,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(56.dp)
            .background(AppColors.BorderDefault),
    )
}

/* ---------- chapters ---------- */

@Composable
private fun ChaptersCard(
    state: NotebookLocalDetailUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (ChapterEntity) -> Unit,
) {
    CollapsibleCard(
        title = "Chapters",
        subtitle = "Main sections of this notebook.",
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            RoundIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "New chapter",
                onClick = onAdd,
            )
        },
    ) {
        if (state.chapters.isEmpty()) {
            EmptyChapterBody(onAdd = onAdd)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.chapters.forEachIndexed { index, chapter ->
                    if (index > 0) HairlineDivider()
                    SwipeableChapterRow(
                        chapter = chapter,
                        pageCount = state.pageCountsByChapter[chapter.id] ?: 0,
                        position = index + 1,
                        onOpen = { onOpen(chapter.id) },
                        onDelete = { onDelete(chapter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChapterBody(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            "No chapters yet",
            style = AppTypography.SectionTitleLight,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Chapters are how you group pages inside a notebook.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AppSpacing.s1))
        TextButton(onClick = onAdd) {
            Text("Create a chapter", color = AppAccent.primary, style = AppTypography.Body)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableChapterRow(
    chapter: ChapterEntity,
    pageCount: Int,
    position: Int,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // Don't auto-dismiss — hand off to the screen's guard dialog.
            // Returning false snaps the row back while the dialog is open.
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false
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
        ChapterRow(
            chapter = chapter,
            pageCount = pageCount,
            position = position,
            onClick = onOpen,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    pageCount: Int,
    position: Int,
    onClick: () -> Unit,
) {
    // Opaque fill is required: SwipeToDismissBox lays the delete background
    // *behind* the foreground row, so a transparent row would leak the red
    // strip through at rest.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        RowIconChip(icon = Icons.AutoMirrored.Filled.MenuBook)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Text(
                text = chapter.title.ifBlank { "Untitled chapter" },
                style = AppTypography.SectionTitleLight,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaPill(text = "Ch. $position")
            if (!chapter.description.isNullOrBlank()) {
                Text(
                    text = chapter.description,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (pageCount == 1) "1 page" else "$pageCount pages",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
                Text(
                    text = "\u00B7",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
                Text(
                    text = relativeTimeAgo(chapter.updatedAt),
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RowIconChip(icon: androidx.compose.ui.graphics.vector.ImageVector) {
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

@Composable
private fun SwipeDeleteBackground() {
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

/* ---------- not-found state ---------- */

@Composable
private fun NotFoundState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Notebook not found",
            style = AppTypography.SectionTitleLight,
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
            Text("Back", color = AppAccent.primary, style = AppTypography.Body)
        }
    }
}

/* ---------- shared titled-create / edit dialog ---------- */

@Composable
private fun TitledDialog(
    heading: String,
    subhead: String,
    titlePlaceholder: String,
    descriptionPlaceholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    initialTitle: String = "",
    initialDescription: String = "",
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    val canConfirm = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                heading,
                style = AppTypography.SectionTitleLight,
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
                DialogTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = titlePlaceholder,
                    singleLine = true,
                )
                DialogTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = descriptionPlaceholder,
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(title, description) },
                enabled = canConfirm,
            ) {
                Text("Save", color = AppAccent.primary)
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
