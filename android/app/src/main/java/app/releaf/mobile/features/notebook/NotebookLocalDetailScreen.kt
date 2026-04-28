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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.RoundIconButton
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
    // Soft-delete confirmation for the notebook itself — surfaced from
    // the destructive button on the Edit modal. Splits state from the
    // edit dialog so closing the edit modal doesn't kill the confirm
    // flow it triggered.
    var showDeleteNotebookDialog by rememberSaveable { mutableStateOf(false) }
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
            // Header zone — matches the library tab's rhythm:
            // LeafEyebrow on its own line at 16dp from the top, then
            // the notebook's own title in the same 32sp serif used
            // for "your shelves" so drill-in pages feel like a
            // continuation of the tab rather than a different
            // template. Breadcrumbs were dropped per design — system
            // back gesture covers the back path.
            val notebookTitle = state.notebook?.title?.trim().orEmpty()
                .ifEmpty { "Untitled" }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = AppSpacing.s4, end = AppSpacing.s4,
                        top    = AppSpacing.s4, bottom = AppSpacing.s3,
                    ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                // Tap the eyebrow to walk back to the library tab —
                // restores the breadcrumb-style "back" affordance we
                // dropped, but folded into the existing header chrome
                // instead of a separate row.
                LeafEyebrow(
                    label    = "shelves · notebook",
                    modifier = Modifier.clickable(onClick = onBack),
                )
                // Title + Edit affordance share a row so the edit
                // icon sits in the screen's top-right corner, baseline-
                // aligned with the page title rather than overlaid on
                // the stats card below.
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text     = notebookTitle,
                        style    = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            fontSize   = 32.sp,
                        ),
                        color    = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    RoundIconButton(
                        icon               = Icons.Filled.Edit,
                        contentDescription = "Edit notebook",
                        onClick            = { showEditNotebookDialog = true },
                    )
                }
            }

            when {
                state.isLoading -> Spacer(Modifier.weight(1f))
                state.notFound -> NotFoundState(onBack = onBack, modifier = Modifier.weight(1f))
                else -> {
                    NotebookDetailBody(
                        state             = state,
                        onAddChapter      = { showCreateChapterDialog = true },
                        onOpenChapter     = onOpenChapter,
                        onMoveChapterUp   = { id ->
                            viewModel.moveChapter(id, ChapterMoveDirection.Up)
                        },
                        onMoveChapterDown = { id ->
                            viewModel.moveChapter(id, ChapterMoveDirection.Down)
                        },
                        onDeleteChapter   = { chapter -> pendingChapterDelete = chapter },
                        modifier          = Modifier.weight(1f, fill = true),
                    )
                }
            }
        }
    }

    pendingChapterDelete?.let { chapter ->
        val title     = chapter.title.ifBlank { "Untitled chapter" }
        val pageCount = state.pageCountsByChapter[chapter.id] ?: 0
        if (pageCount > 0) {
            // Chapters are only deletable when empty — pages otherwise
            // lose their parent. Show a single-button info dialog so
            // there's no path to delete by accident.
            AlertDialog(
                onDismissRequest = { pendingChapterDelete = null },
                title = {
                    Text(
                        text  = "Can’t delete chapter",
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                    )
                },
                text = {
                    Text(
                        text  = "“$title” still holds $pageCount " +
                            "${if (pageCount == 1) "page" else "pages"}. " +
                            "Move or delete the " +
                            "${if (pageCount == 1) "page" else "pages"} first, " +
                            "then try again.",
                        style = AppTypography.Body,
                        color = AppColors.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { pendingChapterDelete = null }) {
                        Text("Got it", color = AppAccent.primary)
                    }
                },
                containerColor = AppColors.CardSolid,
            )
            return@let
        }
        DeleteConfirmationDialog(
            title = "Delete chapter?",
            message = "\u201C$title\u201D will be deleted. " +
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
                deleteLabel = "Delete notebook",
                onDelete    = {
                    showEditNotebookDialog = false
                    showDeleteNotebookDialog = true
                },
            )
        }
    }

    if (showDeleteNotebookDialog) {
        val current = state.notebook
        val title = current?.title?.ifBlank { "Untitled" } ?: "this notebook"
        DeleteConfirmationDialog(
            title = "Delete notebook?",
            message = "“$title” and all its chapters and pages will be deleted.",
            onDismiss = { showDeleteNotebookDialog = false },
            onConfirm = {
                showDeleteNotebookDialog = false
                viewModel.softDeleteNotebook(onDeleted = onBack)
            },
        )
    }
}

/* ---------- body ---------- */

@Composable
private fun NotebookDetailBody(
    state: NotebookLocalDetailUiState,
    onAddChapter: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onMoveChapterUp: (String) -> Unit,
    onMoveChapterDown: (String) -> Unit,
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
            )
        }
        item(key = "chapters_card") {
            ChaptersCard(
                state      = state,
                onAdd      = onAddChapter,
                onOpen     = onOpenChapter,
                onMoveUp   = onMoveChapterUp,
                onMoveDown = onMoveChapterDown,
                onDelete   = onDeleteChapter,
            )
        }
        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

/* ---------- hero ---------- */

/**
 * Light-polish stats — wrapped in a rounded card again per design,
 * but with vertical dividers swapped for whitespace. Numbers are
 * rendered in a 34sp serif; labels are warm-tan small caps; the
 * Created date splits its year off in muted tan so the line never
 * wraps. A horizontal hairline cuts the card in two: stats above,
 * a smaller coral-dot "Last opened" footnote below.
 */
@Composable
private fun NotebookHeroCard(
    notebook: NotebookEntity,
    chapterCount: Int,
    pageCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .padding(vertical = AppSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4)
                .padding(top = AppSpacing.s2),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            verticalAlignment     = Alignment.Top,
        ) {
            StatCell(
                value = chapterCount.toString(),
                label = if (chapterCount == 1) "CHAPTER" else "CHAPTERS",
                modifier = Modifier.weight(1f),
            )
            VerticalRule()
            StatCell(
                value = pageCount.toString(),
                label = if (pageCount == 1) "PAGE" else "PAGES",
                modifier = Modifier.weight(1f),
            )
            VerticalRule()
            CreatedStatCell(
                createdAtIso = notebook.createdAt,
                modifier     = Modifier.weight(1f),
            )
        }
        HairlineDivider()
        LastOpenedRow(
            updatedAtIso = notebook.updatedAt,
            modifier     = Modifier.padding(
                horizontal = AppSpacing.s4,
                vertical   = AppSpacing.s1,
            ),
        )
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text     = value,
            style    = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontSize   = 24.sp,
            ),
            color    = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text  = label,
            style = AppTypography.Eyebrow,
            color = AppColors.Neutral,
        )
    }
}

/**
 * Created-date cell — splits "28 Apr '26" into "28 Apr" (TextPrimary)
 * + "'26" (Neutral muted tan) so the year reads as a footnote. Keeps
 * the whole line single-row even on narrow screens.
 */
@Composable
private fun CreatedStatCell(
    createdAtIso: String,
    modifier: Modifier = Modifier,
) {
    val (dayMonth, yearTail) = remember(createdAtIso) { dayMonthYearTail(createdAtIso) }
    val primary  = AppColors.TextPrimary
    val muted    = AppColors.Neutral
    val display  = remember(dayMonth, yearTail, primary, muted) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = primary)) { append(dayMonth) }
            withStyle(SpanStyle(color = muted))   { append(" $yearTail") }
        }
    }
    Column(
        modifier            = modifier,
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text     = display,
            style    = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontSize   = 18.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text  = "CREATED",
            style = AppTypography.Eyebrow,
            color = AppColors.Neutral,
        )
    }
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(AppColors.BorderDefault),
    )
}

/** Returns ("28 Apr", "'26") for an ISO-8601 instant. Empty pair on parse failure. */
private fun dayMonthYearTail(iso: String): Pair<String, String> {
    val instant = runCatching { java.time.Instant.parse(iso) }.getOrNull()
        ?: return "" to ""
    val date     = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val dayMonth = java.time.format.DateTimeFormatter
        .ofPattern("d MMM", java.util.Locale.getDefault())
        .format(date)
    val yearTail = "'" + date.year.toString().takeLast(2)
    return dayMonth to yearTail
}

@Composable
private fun LastOpenedRow(
    updatedAtIso: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(AppAccent.primary),
        )
        val secondary = AppColors.TextSecondary
        val primary   = AppColors.TextPrimary
        val rel       = relativeTimeAgo(updatedAtIso)
        val display = remember(rel, primary, secondary) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = secondary)) {
                    append("Last opened ")
                }
                withStyle(
                    SpanStyle(
                        color      = primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                ) { append(rel) }
            }
        }
        // Footnote-sized — Meta (13sp) so the row reads as a quieter
        // afterthought beneath the 34sp stats above.
        Text(
            text  = display,
            style = AppTypography.Meta,
        )
    }
}

/* ---------- chapters ---------- */

@Composable
private fun ChaptersCard(
    state: NotebookLocalDetailUiState,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onDelete: (ChapterEntity) -> Unit,
) {
    CollapsibleCard(
        title = "Chapters",
        subtitle = "Main sections of this notebook.",
        // Hero stats and chapters list are both always visible —
        // collapsing in the middle of the screen would only chop the
        // page in half. The "+" trailing affordance stays for adding
        // a chapter.
        expanded = true,
        onToggle = {},
        showCollapseToggle = false,
        // Match the 16sp section header used by the library tab's
        // shelf cards so this drill-in stays in the same type rhythm.
        titleStyle = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
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
                        chapter     = chapter,
                        pageCount   = state.pageCountsByChapter[chapter.id] ?: 0,
                        position    = index + 1,
                        canMoveUp   = index > 0,
                        canMoveDown = index < state.chapters.lastIndex,
                        onOpen      = { onOpen(chapter.id) },
                        onMoveUp    = { onMoveUp(chapter.id) },
                        onMoveDown  = { onMoveDown(chapter.id) },
                        onDelete    = { onDelete(chapter) },
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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
            chapter     = chapter,
            pageCount   = pageCount,
            position    = position,
            canMoveUp   = canMoveUp,
            canMoveDown = canMoveDown,
            onClick     = onOpen,
            onMoveUp    = onMoveUp,
            onMoveDown  = onMoveDown,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    pageCount: Int,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
                // Matches the 16sp notebook-row title on the library
                // tab so list rows across both screens read at the
                // same visual weight.
                style = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
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
        // Reorder arrows — tap to swap position with the immediate
        // neighbor in the requested direction. Each tap target wraps
        // the icon in a 28dp Box so it's reliably hit-friendly even
        // for the 18dp glyph. Disabled (tertiary tint, no clickable)
        // when the row is already at the corresponding edge.
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChapterReorderArrow(
                icon         = Icons.Filled.KeyboardArrowUp,
                description  = "Move chapter up",
                enabled      = canMoveUp,
                onClick      = onMoveUp,
            )
            ChapterReorderArrow(
                icon         = Icons.Filled.KeyboardArrowDown,
                description  = "Move chapter down",
                enabled      = canMoveDown,
                onClick      = onMoveDown,
            )
        }
    }
}

@Composable
private fun ChapterReorderArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) AppColors.TextSecondary else AppColors.BorderDefault
    Box(
        modifier = Modifier
            .size(28.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = description,
            tint               = tint,
            modifier           = Modifier.size(18.dp),
        )
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
    /**
     * Optional destructive action — renders a "Delete" button pinned
     * to the bottom-left of the dialog's content area. Only the edit
     * variant uses it; create variants pass null.
     */
    onDelete: (() -> Unit)? = null,
    deleteLabel: String = "Delete",
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
        // All three actions live in the confirmButton slot as a
        // single Row so Delete (left), Cancel + Save (right) share
        // one line. dismissButton is nulled out — M3 would otherwise
        // try to lay it out alongside confirmButton and we want full
        // control of the row.
        confirmButton = {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(deleteLabel, color = AppColors.Danger)
                    }
                } else {
                    Spacer(Modifier.size(0.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = AppColors.TextSecondary)
                    }
                    TextButton(
                        onClick = { if (canConfirm) onConfirm(title, description) },
                        enabled = canConfirm,
                    ) {
                        Text("Save", color = AppAccent.primary)
                    }
                }
            }
        },
        dismissButton = null,
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
