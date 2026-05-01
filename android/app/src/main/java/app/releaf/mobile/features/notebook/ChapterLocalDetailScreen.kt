/*
 * ChapterLocalDetailScreen.kt
 *
 * Chapter detail — reached from NotebookLocalDetailScreen. Shares the
 * notebook detail's UI rhythm: tappable LeafEyebrow back-link, serif
 * title with an Edit icon top-right, a stats hero card (ORDER / PAGES /
 * CREATED + "Last edited" footnote), and a Pages list with reorder
 * arrows + swipe-to-delete on each row. Edit dialog hosts rename plus
 * archive (destructive button left of Save / Cancel).
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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseLocations
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.RoundIconButton
import app.releaf.mobile.ui.components.absoluteDate
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.launch

@Composable
fun ChapterLocalDetailScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChapterLocalDetailViewModel = viewModel(factory = ChapterLocalDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val confirmingArchive by viewModel.confirmingArchive.collectAsState()
    val archiveToast by viewModel.archiveToast.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    // Pending delete — holds the page the user swiped until they confirm
    // via the guard dialog.
    var pendingPageDelete by remember { mutableStateOf<PageEntity?>(null) }

    // When the ViewModel emits an archive toast, surface it through
    // the existing snackbar host and clear it so we don't re-fire on
    // recomposition.
    androidx.compose.runtime.LaunchedEffect(archiveToast) {
        archiveToast?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.consumeArchiveToast()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        // Outer SignedInShell Scaffold already consumes the system-bar
        // insets; swallow them here so the header docks flush to the
        // top instead of doubling the status-bar padding.
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Header zone — mirrors the notebook detail screen:
            // tappable LeafEyebrow links back to the parent notebook,
            // serif title carries the chapter name, and an Edit icon
            // sits in the screen's top-right corner. Breadcrumbs and
            // the screen-level overflow have been dropped — the
            // back gesture / eyebrow tap covers navigation; the edit
            // modal hosts rename + archive.
            val chapterTitle = state.chapter?.title?.trim().orEmpty()
                .ifEmpty { "Untitled chapter" }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.s4, end = AppSpacing.s4,
                        top = AppSpacing.s4, bottom = AppSpacing.s3,
                    ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                LeafEyebrow(
                    label    = "notebook · chapter",
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text     = chapterTitle,
                        style    = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize   = 32.sp,
                        ),
                        color    = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    RoundIconButton(
                        icon               = Icons.Filled.Edit,
                        contentDescription = "Edit chapter",
                        onClick            = { showEditDialog = true },
                    )
                }
            }

            when {
                state.isLoading -> Spacer(Modifier.weight(1f))
                state.notFound -> NotFoundState(onBack = onBack, modifier = Modifier.weight(1f))
                else -> ChapterDetailBody(
                    state         = state,
                    onAddPage     = {
                        viewModel.createPage(onCreated = { id -> onOpenPage(id) })
                    },
                    onOpenPage    = onOpenPage,
                    onMovePageUp  = { id -> viewModel.movePage(id, PageMoveDirection.Up) },
                    onMovePageDown= { id -> viewModel.movePage(id, PageMoveDirection.Down) },
                    onDeletePage  = { page -> pendingPageDelete = page },
                    modifier      = Modifier.weight(1f, fill = true),
                )
            }
        }
    }

    pendingPageDelete?.let { page ->
        val title = page.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: page.notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: "Untitled page"
        DeleteConfirmationDialog(
            title = "Delete page?",
            message = "\u201C$title\u201D will be deleted. " +
                "You can undo this immediately after.",
            onDismiss = { pendingPageDelete = null },
            onConfirm = {
                val id = page.id
                pendingPageDelete = null
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

    if (showEditDialog) {
        val current = state.chapter
        if (current != null) {
            EditChapterDialog(
                initialTitle = current.title,
                initialDescription = current.description.orEmpty(),
                onDismiss = { showEditDialog = false },
                onConfirm = { title, description ->
                    viewModel.saveChapter(title, description)
                    showEditDialog = false
                },
                // Archive moves into the edit modal — same pattern as
                // the notebook detail's "Delete notebook" destructive
                // action. Closes the edit dialog and routes through
                // the existing confirm flow on the VM.
                onArchive = {
                    showEditDialog = false
                    viewModel.archiveChapter()
                },
            )
        }
    }

    if (confirmingArchive) {
        AlertDialog(
            onDismissRequest = viewModel::cancelArchive,
            title = { Text("Archive this chapter?") },
            text  = { Text("All its pages move to archive together. You can restore from there.") },
            confirmButton = {
                TextButton(onClick = {
                    // Archive chapter, then return to the parent
                    // notebook — the chapter row no longer exists
                    // in the active list and the screen would
                    // collapse to a not-found state otherwise.
                    viewModel.confirmArchiveChapter(onArchived = onBack)
                }) {
                    Text("Archive")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelArchive) {
                    Text("Cancel")
                }
            },
        )
    }
}

/* ---------- body ---------- */

@Composable
private fun ChapterDetailBody(
    state: ChapterLocalDetailUiState,
    onAddPage: () -> Unit,
    onOpenPage: (String) -> Unit,
    onMovePageUp: (String) -> Unit,
    onMovePageDown: (String) -> Unit,
    onDeletePage: (PageEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapter = state.chapter ?: return
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
            ChapterHeroCard(
                chapter        = chapter,
                order          = state.orderInNotebook,
                pageCount      = state.pages.size,
                lastEditedIso  = chapterLastEditedAt(chapter, state.pages),
            )
        }
        item(key = "pages_card") {
            PagesCard(
                pages         = state.pages,
                onAdd         = onAddPage,
                onOpen        = onOpenPage,
                onMoveUp      = onMovePageUp,
                onMoveDown    = onMovePageDown,
                onDelete      = onDeletePage,
            )
        }
        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

private fun chapterLastEditedAt(
    chapter: app.releaf.mobile.data.notebook.ChapterEntity,
    pages: List<PageEntity>,
): String {
    val candidates = sequence {
        yield(chapter.updatedAt)
        pages.forEach { yield(it.updatedAt) }
    }
    return candidates.maxOrNull() ?: chapter.updatedAt
}

/* ---------- hero stats card ---------- */

/**
 * Stats hero — mirrors the notebook detail's hero. Three stats columns
 * (ORDER / PAGES / CREATED) split by vertical rules; a horizontal
 * hairline cuts the card; a coral-dot "Last edited" footnote rolls up
 * activity across the chapter and its pages.
 */
@Composable
private fun ChapterHeroCard(
    chapter: app.releaf.mobile.data.notebook.ChapterEntity,
    order: Int,
    pageCount: Int,
    lastEditedIso: String,
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
            ChapterStatCell(
                value = order.toString(),
                label = "ORDER",
                modifier = Modifier.weight(1f),
            )
            ChapterVerticalRule()
            ChapterStatCell(
                value = pageCount.toString(),
                label = if (pageCount == 1) "PAGE" else "PAGES",
                modifier = Modifier.weight(0.7f),
            )
            ChapterVerticalRule()
            ChapterCreatedCell(
                createdAtIso = chapter.createdAt,
                modifier     = Modifier.weight(1f),
            )
        }
        HairlineDivider()
        ChapterLastEditedRow(
            updatedAtIso = lastEditedIso,
            modifier     = Modifier.padding(
                horizontal = AppSpacing.s4,
                vertical   = AppSpacing.s1,
            ),
        )
    }
}

@Composable
private fun ChapterStatCell(
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
            style    = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp),
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

@Composable
private fun ChapterCreatedCell(
    createdAtIso: String,
    modifier: Modifier = Modifier,
) {
    val (dayMonth, yearTail) = remember(createdAtIso) { dayMonthYearTail(createdAtIso) }
    val primary = AppColors.TextPrimary
    val muted   = AppColors.Neutral
    val display = remember(dayMonth, yearTail, primary, muted) {
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
            style    = TextStyle(fontFamily = FontFamily.Serif, fontSize = 18.sp),
            maxLines = 2,
            softWrap = true,
        )
        Text(
            text  = "CREATED",
            style = AppTypography.Eyebrow,
            color = AppColors.Neutral,
        )
    }
}

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
private fun ChapterVerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(48.dp)
            .background(AppColors.BorderDefault),
    )
}

@Composable
private fun ChapterLastEditedRow(
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
                    append("Last edited ")
                }
                withStyle(
                    SpanStyle(
                        color      = primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                ) { append(rel) }
            }
        }
        Text(
            text  = display,
            style = AppTypography.Meta,
        )
    }
}

/* ---------- pages card ---------- */

@Composable
private fun PagesCard(
    pages: List<PageEntity>,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onDelete: (PageEntity) -> Unit,
) {
    CollapsibleCard(
        title = "Pages",
        subtitle = "Notes, photos, and scans in this chapter.",
        expanded = true,
        onToggle = {},
        showCollapseToggle = false,
        // Match the 16sp card header used by the notebook detail
        // ChaptersCard so drill-in screens stay in the same rhythm.
        titleStyle = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
        trailing = {
            RoundIconButton(
                icon = Icons.Filled.Add,
                contentDescription = "New page",
                onClick = onAdd,
            )
        },
    ) {
        if (pages.isEmpty()) {
            EmptyPagesBody(onAdd = onAdd)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                pages.forEachIndexed { index, page ->
                    if (index > 0) HairlineDivider()
                    SwipeablePageRow(
                        page         = page,
                        order        = index + 1,
                        canMoveUp    = index > 0,
                        canMoveDown  = index < pages.lastIndex,
                        onOpen       = { onOpen(page.id) },
                        onMoveUp     = { onMoveUp(page.id) },
                        onMoveDown   = { onMoveDown(page.id) },
                        onDelete     = { onDelete(page) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPagesBody(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            "No pages yet",
            style = AppTypography.SectionTitleLight,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Pages hold the actual notes, photos, and scans for this chapter.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AppSpacing.s1))
        TextButton(onClick = onAdd) {
            Text("Create a page", color = AppAccent.primary, style = AppTypography.Body)
        }
    }
}

/* ---------- page row ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeablePageRow(
    page: PageEntity,
    order: Int,
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
        PageRow(
            page        = page,
            order       = order,
            canMoveUp   = canMoveUp,
            canMoveDown = canMoveDown,
            onClick     = onOpen,
            onMoveUp    = onMoveUp,
            onMoveDown  = onMoveDown,
        )
    }
}

@Composable
private fun PageRow(
    page: PageEntity,
    order: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val attachments = remember(page.attachments) { page.attachments.parseAttachments() }
    val locations = remember(page.locations) { page.locations.parseLocations() }
    val photoCount = attachments.count { it.type == Attachment.TYPE_PHOTO }
    val voiceCount = attachments.count { it.type == Attachment.TYPE_VOICE }
    val firstLocation = locations.firstOrNull()?.address
    val titleText = derivedPageTitle(page, order)

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
        RowIconChip(icon = Icons.Filled.Description)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            // Title row — title takes the lead, "Pg. N" pill rides
            // the trailing edge so the page index sits next to the
            // up-arrow column for an at-a-glance read.
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Text(
                    text     = titleText,
                    // Match the 16sp notebook-row title used elsewhere on
                    // notebook + chapter list rows for visual rhythm.
                    style    = AppTypography.SectionTitleLight.copy(fontSize = 16.sp),
                    color    = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MetaPill(text = "Pg. $order")
            }
            // Page description (italic serif subtitle from the plant
            // seed, or whatever the user's typed). Mirrors the editor's
            // DescriptionField so the list and the detail screen agree
            // on what counts as the page's "subtitle".
            page.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text     = desc,
                    style    = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontStyle  = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontSize   = 14.sp,
                    ),
                    color    = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            pagePreviewBody(page)?.let { body ->
                Text(
                    text = body,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (photoCount > 0 || voiceCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaRow(
                        icon = Icons.Filled.PhotoCamera,
                        text = "$photoCount photo${if (photoCount == 1) "" else "s"}",
                    )
                    MetaRow(
                        icon = Icons.Filled.Mic,
                        text = "$voiceCount voice note${if (voiceCount == 1) "" else "s"}",
                    )
                }
            }
            if (!firstLocation.isNullOrBlank()) {
                MetaRow(icon = Icons.Filled.LocationOn, text = firstLocation)
            }
            // Created date + last-edited relative time on one line —
            // separated by a thin middle dot so the row reads as a
            // single timeline footnote instead of two stacked meta
            // rows.
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                MetaRow(
                    icon = Icons.Filled.CalendarToday,
                    text = absoluteDate(page.createdAt),
                )
                Text(
                    text  = "·",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
                MetaRow(
                    icon = Icons.Filled.Schedule,
                    text = relativeTimeAgo(page.updatedAt),
                )
            }
        }
        // Reorder arrows — same shape and disabled-edge behavior as
        // the chapter rows on the notebook detail screen.
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PageReorderArrow(
                icon        = Icons.Filled.KeyboardArrowUp,
                description = "Move page up",
                enabled     = canMoveUp,
                onClick     = onMoveUp,
            )
            PageReorderArrow(
                icon        = Icons.Filled.KeyboardArrowDown,
                description = "Move page down",
                enabled     = canMoveDown,
                onClick     = onMoveDown,
            )
        }
    }
}

@Composable
private fun PageReorderArrow(
    icon: ImageVector,
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
private fun MetaRow(icon: ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowIconChip(icon: ImageVector) {
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

/* ---------- not-found ---------- */

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
            "Chapter not found",
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


/* ---------- edit chapter dialog ---------- */

@Composable
private fun EditChapterDialog(
    initialTitle: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onArchive: (() -> Unit)? = null,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    val canConfirm = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Edit chapter",
                style = AppTypography.SectionTitleLight,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Text(
                    "Tweak the title or describe what this chapter holds.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
                DialogTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "Chapter title",
                    singleLine = true,
                )
                DialogTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "Describe this chapter (optional)",
                    singleLine = false,
                )
            }
        },
        // Single action row — Archive on the left (when provided),
        // Cancel + Save on the right. Mirrors the notebook detail
        // edit modal's three-button layout.
        confirmButton = {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onArchive != null) {
                    TextButton(onClick = onArchive) {
                        Text("Archive chapter", color = AppColors.Danger)
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

/* ---------- helpers ---------- */

/**
 * Page row title. If the user set a title, use it. Otherwise fall back to
 * "{date} — Page N" so rows stay visually distinct even when empty.
 */
private fun derivedPageTitle(page: PageEntity, order: Int): String {
    page.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val datePart = absoluteDate(page.createdAt).takeIf { it.isNotBlank() }
    return if (datePart != null) "$datePart - Page $order" else "Page $order"
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
