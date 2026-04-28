/*
 * ChapterLocalDetailScreen.kt
 *
 * Chapter detail — reached from NotebookLocalDetailScreen. Shows the
 * chapter's overview card (title + description + order / page-count pills)
 * and a Pages card with the pages beneath it. Tapping a page routes into
 * the page editor; the "+ New page" button on the Pages card header
 * creates a fresh page and drops the user straight into the editor.
 *
 * Breadcrumbs: Home › Notebook › {notebook} › {chapter}.
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseLocations
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.components.BreadcrumbSegment
import app.releaf.mobile.ui.components.Breadcrumbs
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.LeafDropdownDivider
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.PageOverflowButton
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
    var overviewExpanded by rememberSaveable { mutableStateOf(true) }
    var pagesExpanded by rememberSaveable { mutableStateOf(true) }
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
            // Composed top zone — leaf eyebrow on the left, overflow
            // menu on the right, big serif title (the chapter name)
            // below. Breadcrumbs sit underneath as a thin row so the
            // user still sees the full Home › Notebook › Chapter path.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.s4, end = AppSpacing.s4,
                        top = AppSpacing.s3, bottom = AppSpacing.s3,
                    ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier              = Modifier.fillMaxWidth(),
                ) {
                    // Eyebrow tint follows the parent notebook's
                    // color so the chapter screen reads as part of
                    // the same visual family as the notebook it
                    // lives in. Default green stays for notebooks
                    // without a recognized color.
                    val parentToken = chapterEyebrowToken(state.notebook?.colorHex)
                    val parentPalette = app.releaf.mobile.ui.components.ShelfTheme
                        .palette(parentToken)
                    val usesCustomTint = parentToken != null
                    LeafEyebrow(
                        label     = "releaf · chapter",
                        modifier  = Modifier
                            .weight(1f)
                            .clickable { onBack() },
                        glyphTint = if (usesCustomTint) parentPalette.background else null,
                        labelTint = if (usesCustomTint) parentPalette.background else null,
                    )
                    PageOverflowButton {
                        DropdownMenuItem(
                            text    = { Text("Rename chapter") },
                            onClick = { showEditDialog = true },
                        )
                        LeafDropdownDivider()
                        DropdownMenuItem(
                            text    = { Text("New page") },
                            onClick = {
                                viewModel.createPage(onCreated = { id -> onOpenPage(id) })
                            },
                        )
                        LeafDropdownDivider()
                        DropdownMenuItem(
                            text    = { Text("Archive chapter") },
                            onClick = { viewModel.archiveChapter() },
                        )
                    }
                }
                // Chapter title — tappable shortcut to the rename
                // dialog. Avoids forcing users through the
                // overflow for the most common chapter edit; the
                // overflow still hosts the full action set so
                // discoverability isn't lost.
                Text(
                    text     = (state.chapter?.title?.ifBlank { "Untitled chapter" } ?: "Chapter").lowercase(),
                    style    = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize   = 32.sp,
                    ),
                    color    = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = state.chapter != null) {
                            showEditDialog = true
                        },
                )
                Breadcrumbs(
                    segments = listOf(
                        BreadcrumbSegment("Home", onTap = onHome),
                        BreadcrumbSegment("Notebook", onTap = onBack),
                        BreadcrumbSegment(
                            label = state.notebook?.title?.ifBlank { "Notebook" } ?: "Notebook",
                            onTap = onOpenNotebook,
                        ),
                        BreadcrumbSegment(state.chapter?.title?.ifBlank { "Chapter" } ?: "Chapter"),
                    ),
                )
            }

            when {
                state.isLoading -> Spacer(Modifier.weight(1f))
                state.notFound -> NotFoundState(onBack = onBack, modifier = Modifier.weight(1f))
                else -> ChapterDetailBody(
                    state = state,
                    overviewExpanded = overviewExpanded,
                    onToggleOverview = { overviewExpanded = !overviewExpanded },
                    pagesExpanded = pagesExpanded,
                    onTogglePages = { pagesExpanded = !pagesExpanded },
                    onEditChapter = { showEditDialog = true },
                    onAddPage = {
                        viewModel.createPage(onCreated = { id -> onOpenPage(id) })
                    },
                    onOpenPage = onOpenPage,
                    onDeletePage = { page -> pendingPageDelete = page },
                    modifier = Modifier.weight(1f, fill = true),
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
    overviewExpanded: Boolean,
    onToggleOverview: () -> Unit,
    pagesExpanded: Boolean,
    onTogglePages: () -> Unit,
    onEditChapter: () -> Unit,
    onAddPage: () -> Unit,
    onOpenPage: (String) -> Unit,
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
        item(key = "overview") {
            ChapterOverviewCard(
                title = chapter.title.ifBlank { "Untitled chapter" },
                description = chapter.description,
                order = state.orderInNotebook,
                pageCount = state.pages.size,
                expanded = overviewExpanded,
                onToggle = onToggleOverview,
                onEdit = onEditChapter,
            )
        }
        item(key = "pages_card") {
            PagesCard(
                pages = state.pages,
                expanded = pagesExpanded,
                onToggle = onTogglePages,
                onAdd = onAddPage,
                onOpen = onOpenPage,
                onDelete = onDeletePage,
            )
        }
        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

/* ---------- overview card ---------- */

@Composable
private fun ChapterOverviewCard(
    title: String,
    description: String?,
    order: Int,
    pageCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    CollapsibleCard(
        title = "Chapter overview",
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            RoundIconButton(
                icon = Icons.Filled.Edit,
                contentDescription = "Edit chapter",
                onClick = onEdit,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Text(
                text = title,
                style = AppTypography.EditorialTitleLight,
                color = AppColors.TextPrimary,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                MetaPill(text = "Order: $order")
                MetaPill(text = if (pageCount == 1) "1 page" else "$pageCount pages")
            }
        }
    }
}

/* ---------- pages card ---------- */

@Composable
private fun PagesCard(
    pages: List<PageEntity>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (PageEntity) -> Unit,
) {
    CollapsibleCard(
        title = "Pages",
        expanded = expanded,
        onToggle = onToggle,
        trailing = { NewPageButton(onClick = onAdd) },
    ) {
        if (pages.isEmpty()) {
            EmptyPagesBody(onAdd = onAdd)
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                pages.forEachIndexed { index, page ->
                    if (index > 0) HairlineDivider()
                    SwipeablePageRow(
                        page = page,
                        order = index + 1,
                        onOpen = { onOpen(page.id) },
                        onDelete = { onDelete(page) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewPageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.TextPrimary)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = AppColors.CardSolid,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "New page",
            style = AppTypography.Button,
            color = AppColors.CardSolid,
        )
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
        PageRow(page = page, order = order, onClick = onOpen)
    }
}

@Composable
private fun PageRow(page: PageEntity, order: Int, onClick: () -> Unit) {
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
            Text(
                text = titleText,
                style = AppTypography.SectionTitleLight,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaPill(text = "Pg. $order")
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
            MetaRow(
                icon = Icons.Filled.CalendarToday,
                text = absoluteDate(page.createdAt),
            )
            if (!firstLocation.isNullOrBlank()) {
                MetaRow(icon = Icons.Filled.LocationOn, text = firstLocation)
            }
            MetaRow(
                icon = Icons.Filled.Schedule,
                text = relativeTimeAgo(page.updatedAt),
            )
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

/**
 * Inverse of `themeHex` — maps the four leaf-theme primary hexes
 * back to their token name. Anything that doesn't match returns
 * null so the caller can fall through to the default chrome.
 *
 * Kept as a private file-local helper here (and mirrored on
 * NotebookTabScreen) rather than promoting to a shared util — the
 * mapping is tiny and only used in two places that already deal in
 * raw hex strings from the Room schema.
 */
private fun chapterEyebrowToken(hex: String?): String? {
    val normalized = hex?.uppercase()?.removePrefix("#") ?: return null
    return when (normalized) {
        "7AA874" -> "green"
        "E07856" -> "coral"
        "F4C430" -> "yellow"
        "B8956A" -> "dry"
        else     -> null
    }
}

/* ---------- edit chapter dialog ---------- */

@Composable
private fun EditChapterDialog(
    initialTitle: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
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
