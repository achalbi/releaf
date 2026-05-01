/*
 * PageDetailScreen.kt
 * One page, seven capture modes. Uses the design-system `CaptureTabBar`
 * (icon-pill row) and `StatGrid` (3-up dashboard glance) rather than
 * hand-rolled variants.
 */

package app.releaf.mobile.features.page

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.domain.Contact
import app.releaf.mobile.data.domain.LocationPin
import app.releaf.mobile.data.domain.Note
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.Photo
import app.releaf.mobile.data.domain.ScannedDocument
import app.releaf.mobile.data.domain.TodoItem
import app.releaf.mobile.data.domain.VoiceNote
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.components.CaptureTabBar
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.LeafDropdownDivider
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.PageOverflowButton
import app.releaf.mobile.ui.components.PageViewMode
import app.releaf.mobile.ui.components.PageViewToggle
import app.releaf.mobile.ui.components.PaperSavedSheet
import app.releaf.mobile.ui.components.ReLeafStrip
import app.releaf.mobile.ui.components.StatGrid
import app.releaf.mobile.ui.components.StatItem
import app.releaf.mobile.ui.components.StatList
import app.releaf.mobile.ui.components.StatTone
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.Card
import app.releaf.mobile.ui.theme.DailyPlants
import app.releaf.mobile.ui.theme.ReleafImpact
import kotlinx.coroutines.delay

@Composable
fun PageDetailScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PageDetailViewModel = viewModel(factory = PageDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val overflow by viewModel.overflow.collectAsState()
    val context = LocalContext.current

    // Daily-plant info sheet — opened on tap of the title block.
    // Sheet content is purely the resolved DailyPlant, so the
    // dismissal toggles only this local flag.
    var showPlantInfo by rememberSaveable { mutableStateOf(false) }

    // Share submenu — opened from the overflow's "Share…" entry.
    // Contains Share / Export PDF / Copy page link, surfaced as a
    // bottom sheet because cascading dropdown menus are awkward
    // in Material3.
    var showShareSheet by rememberSaveable { mutableStateOf(false) }

    // Side-effect: when the ViewModel emits a share intent, launch
    // the system share sheet and immediately mark the intent
    // consumed so we don't re-launch on recomposition. When a
    // fileUri is present (PDF export path) share the file with its
    // declared mime; otherwise fall back to plain text.
    LaunchedEffect(overflow.pendingShare) {
        val pending = overflow.pendingShare ?: return@LaunchedEffect
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (pending.fileUri != null) {
                type = pending.fileMime
                putExtra(Intent.EXTRA_STREAM, pending.fileUri)
                putExtra(Intent.EXTRA_SUBJECT, pending.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, pending.title)
                putExtra(Intent.EXTRA_TEXT, "${pending.title}\n\n${pending.body}")
            }
        }
        context.startActivity(Intent.createChooser(intent, "Share page"))
        viewModel.consumeShareIntent()
    }

    // Toast auto-dismiss — clear after ~2.4s.
    LaunchedEffect(overflow.toast) {
        if (overflow.toast != null) {
            delay(2_400)
            viewModel.consumeToast()
        }
    }

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            PageDetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent.primary)
                }
            }
            is PageDetailUiState.Failed -> {
                Column(
                    Modifier.fillMaxSize().padding(AppSpacing.s4),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, style = AppTypography.Body, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton(
                        "Try again",
                        onClick = viewModel::load,
                        variant = AppButtonVariant.Secondary,
                        fillWidth = false,
                    )
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton("Back", onClick = onBack, variant = AppButtonVariant.Text, fillWidth = false)
                }
            }
            is PageDetailUiState.Loaded -> Loaded(
                page = s.page,
                parentNotebook = s.parentNotebook,
                onBack = onBack,
                onArchive = viewModel::archivePage,
                onDuplicate = viewModel::duplicatePage,
                onShare = viewModel::presentShareSheet,
                onExportPDF = viewModel::exportPDF,
                onMoveToNotebook = viewModel::presentMoveToNotebook,
                onApplyTemplate = viewModel::presentTemplatePicker,
                onRestore = viewModel::restorePage,
                onEditTags = viewModel::presentTagEditor,
                onCopyTag = viewModel::copyTagToClipboard,
                onCopyLink = viewModel::copyPageLinkToClipboard,
                onShareGroup = { showShareSheet = true },
                onShowPlantInfo = { showPlantInfo = true },
            )
        }

        // Dialogs / sheets / toast — kept at the screen root so they
        // overlay regardless of which capture-mode tab is selected.
        if (overflow.confirmingArchive) {
            AlertDialog(
                onDismissRequest = viewModel::cancelArchive,
                confirmButton = {
                    TextButton(onClick = viewModel::confirmArchive) {
                        Text("Archive")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelArchive) {
                        Text("Cancel")
                    }
                },
                title = { Text("Archive this page?") },
                text = {
                    Text("It will move to the archive. You can restore it from there.")
                },
            )
        }

        if (overflow.presentingMoveSheet) {
            val currentNotebookId = (state as? PageDetailUiState.Loaded)?.page?.notebookId.orEmpty()
            MoveToNotebookSheet(
                notebooks            = overflow.availableNotebooks,
                isLoading            = overflow.loadingNotebooks,
                currentNotebookId    = currentNotebookId,
                chaptersByNotebookId = overflow.chaptersByNotebookId,
                chaptersLoadingFor   = overflow.chaptersLoadingFor,
                onExpand             = viewModel::loadChaptersFor,
                onSelect             = viewModel::selectNotebook,
                onDismiss            = viewModel::dismissMoveSheet,
            )
        }
        if (overflow.presentingTemplateSheet) {
            ApplyTemplateSheet(
                templates = overflow.availableTemplates,
                isLoading = overflow.loadingTemplates,
                onSelect  = viewModel::selectTemplate,
                onDismiss = viewModel::dismissTemplateSheet,
            )
        }

        if (overflow.presentingTagEditor) {
            val initialTags = (state as? PageDetailUiState.Loaded)?.page?.tags.orEmpty()
            EditTagsSheet(
                initialTags = initialTags,
                onSave      = viewModel::saveTags,
                onDismiss   = viewModel::dismissTagEditor,
                onCopyAll   = viewModel::copyTagsToClipboard,
            )
        }

        if (showPlantInfo) {
            val plant = remember { DailyPlants.forToday() }
            DailyPlantInfoSheet(
                plant     = plant,
                onCopy    = {
                    viewModel.copyPlantHeadlineToClipboard(plant)
                    showPlantInfo = false
                },
                onDismiss = { showPlantInfo = false },
            )
        }

        if (showShareSheet) {
            ShareGroupSheet(
                onShare       = {
                    showShareSheet = false
                    viewModel.presentShareSheet()
                },
                onExportPDF   = {
                    showShareSheet = false
                    viewModel.exportPDF()
                },
                onCopyLink    = {
                    showShareSheet = false
                    viewModel.copyPageLinkToClipboard()
                },
                onDismiss     = { showShareSheet = false },
            )
        }

        overflow.toast?.let { toast ->
            ToastView(
                message     = toast.message,
                actionLabel = toast.actionLabel,
                onAction    = toast.actionKind?.let { kind ->
                    { viewModel.performToastAction(kind) }
                },
                modifier    = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = AppSpacing.s4),
            )
        }
    }
}

@Composable
private fun Loaded(
    page: Page,
    parentNotebook: app.releaf.mobile.data.domain.Notebook?,
    onBack: () -> Unit,
    onArchive: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onExportPDF: () -> Unit,
    onMoveToNotebook: () -> Unit,
    onApplyTemplate: () -> Unit,
    onRestore: () -> Unit,
    onEditTags: () -> Unit,
    onCopyTag: (String) -> Unit,
    onCopyLink: () -> Unit,
    onShareGroup: () -> Unit,
    onShowPlantInfo: () -> Unit,
) {
    var selected by remember { mutableStateOf(CaptureMode.Overview) }
    val scroll = rememberScrollState()

    val context = LocalContext.current
    val prefs   = remember(context) { app.releaf.mobile.ui.theme.UiPreferences.get(context) }
    val prefsState by prefs.state.collectAsState()
    // Bridge the persisted PageDetailViewMode (Grid/List) onto the
    // local PageViewMode the toggle component expects. Mutating the
    // toggle goes back through prefs so the choice survives a cold
    // start.
    val viewMode = when (prefsState.pageViewMode) {
        app.releaf.mobile.ui.theme.PageDetailViewMode.Grid -> PageViewMode.Grid
        app.releaf.mobile.ui.theme.PageDetailViewMode.List -> PageViewMode.List
    }
    val plant = remember { DailyPlants.forToday() }

    // Parent-notebook tint resolved once and threaded down to the
    // eyebrow, tag pills, capture tab bar, and overview accent so
    // the page reads as part of the same visual family as the
    // notebook it lives in. A blank/null token keeps the default
    // green chrome — `usesCustomTint` gates every tint switch.
    val parentToken = parentNotebook?.colorToken
    val parentPalette = app.releaf.mobile.ui.components.ShelfTheme.palette(parentToken)
    val usesCustomTint = !parentToken.isNullOrBlank()

    Column(Modifier.fillMaxSize()) {
        // Composed top zone — slot 1 leaf eyebrow, slot 2 view toggle
        // + overflow, slot 3 daily plant title (auto-rotated). Slot 4
        // (date pill) is intentionally absent: the day's plant
        // carries the editorial weight of the date, and the
        // eyebrow's "TODAY" states the calendar context.
        Column(
            modifier = Modifier.padding(
                start = AppSpacing.s4, end = AppSpacing.s4,
                top = AppSpacing.s4, bottom = AppSpacing.s3,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                modifier              = Modifier.fillMaxWidth(),
            ) {
                // Eyebrow tint follows the parent-notebook palette
                // hoisted at the function level so the same colour
                // also drives the tag pills, capture tab bar, and
                // overview accent below.
                LeafEyebrow(
                    label    = "notepad · today",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onBack() },
                    glyphTint = if (usesCustomTint) parentPalette.background else null,
                    labelTint = if (usesCustomTint) parentPalette.background else null,
                )
                PageViewToggle(
                    selected = viewMode,
                    onSelect = { mode ->
                        prefs.setPageViewMode(
                            when (mode) {
                                PageViewMode.Grid -> app.releaf.mobile.ui.theme.PageDetailViewMode.Grid
                                PageViewMode.List -> app.releaf.mobile.ui.theme.PageDetailViewMode.List
                            }
                        )
                    },
                )
                PageOverflowButton {
                    // Order top-to-bottom by frequency: tag +
                    // duplicate are the lightweight everyday
                    // actions; move / template are heavier
                    // restructuring tools and sit below.
                    DropdownMenuItem(text = { Text("Edit tags") },        onClick = onEditTags)
                    LeafDropdownDivider()
                    DropdownMenuItem(text = { Text("Duplicate") },        onClick = onDuplicate)
                    LeafDropdownDivider()
                    DropdownMenuItem(text = { Text("Move to notebook") }, onClick = onMoveToNotebook)
                    LeafDropdownDivider()
                    DropdownMenuItem(text = { Text("Apply template") },   onClick = onApplyTemplate)
                    LeafDropdownDivider()
                    // Share group — would be a true submenu on iOS
                    // (SwiftUI Menu nests cleanly), but Material3
                    // DropdownMenu doesn't have a native nested
                    // mode and cascading menus from a dropdown row
                    // is fiddly enough that the cleaner mobile
                    // pattern here is the Share bottom-sheet
                    // (`onShareGroup`) — see below.
                    DropdownMenuItem(text = { Text("Share…") },           onClick = onShareGroup)
                    LeafDropdownDivider()
                    DropdownMenuItem(text = { Text("Archive page") },     onClick = onArchive)
                }
            }
            // Title line composes Sanskrit (32sp) + English
            // parenthetical (16sp italic muted) into one
            // AnnotatedString so they share a baseline and wrap as
            // one block on the longer entries (e.g.
            // "krishnanimba (curry leaf)").
            val textPrimary = AppColors.TextPrimary
            val textSecondary = AppColors.TextSecondary
            val titleText = buildAnnotatedString {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize   = 32.sp,
                    color      = textPrimary,
                )) {
                    append(plant.name)
                }
                if (plant.commonName.isNotEmpty()) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize   = 16.sp,
                        fontStyle  = FontStyle.Italic,
                        color      = textSecondary,
                    )) {
                        append("  (${plant.commonName})")
                    }
                }
            }
            // Tappable title block — the whole title + subtitle is
            // the hit target so users curious about the Sanskrit
            // name (or the descriptor line) land on the same
            // canonical surface that explains the plant in full.
            Column(
                modifier = Modifier
                    .clickable { onShowPlantInfo() }
                    .padding(top = 0.dp),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
            ) {
                Text(
                    text     = titleText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = "${plant.epithet}  ·  ${plant.usedFor}",
                    style    = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize   = 14.sp,
                        fontStyle  = FontStyle.Italic,
                    ),
                    color    = AppColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // Reading-time + word-count chip. Computed off the
                // loaded page's notes; hidden when the page has no
                // text content yet so we don't clutter empty pages
                // with "0 min read".
                app.releaf.mobile.ui.theme.ReleafReadEstimate(
                    noteBodies = page.notes.map { it.body }
                ).summary?.let { summary ->
                    Text(
                        text     = summary,
                        style    = AppTypography.Meta,
                        color    = AppColors.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // Tag pills under the plant title. Tap a pill to
                // open the tag editor — same surface the
                // overflow's "Edit tags" uses, just zero-friction
                // from the tag itself.
                if (page.tags.isNotEmpty()) {
                    // Reuse the parent-notebook palette resolved
                    // for the eyebrow above so tag pills tint to
                    // match. Nil token → falls back to default
                    // soft-green inside TagsRow.
                    val tagPalette = if (usesCustomTint) parentPalette else null
                    TagsRow(
                        tags        = page.tags,
                        onTap       = { onEditTags() },
                        onLongPress = { tag -> onCopyTag(tag) },
                        palette     = tagPalette,
                    )
                }
            }
        }

        page.archivedAt?.let { archivedAt ->
            ArchivedBanner(
                archivedAt = archivedAt,
                onRestore  = onRestore,
                modifier   = Modifier
                    .padding(horizontal = AppSpacing.s4)
                    .padding(bottom = AppSpacing.s3),
            )
        }

        // Reuse the parent-notebook palette resolved for the
        // header eyebrow above so the active-segment indicator on
        // the tab bar tints to match. Nil token → bar falls back
        // to the global accent.
        val tabBarOverride = if (usesCustomTint) parentPalette.background else null
        CaptureTabBar(
            selected       = selected,
            onSelect       = { selected = it },
            accentOverride = tabBarOverride,
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            when (selected) {
                // Overview + Notes share this section. CaptureMode.Notes
                // was added in CAPTURE_TAB_PLAN Phase 4 as a text-first
                // landing tab; until the dedicated Notes UI lands the
                // tab falls back to the Overview surface, which already
                // shows the page's notes preview.
                CaptureMode.Overview,
                CaptureMode.Notes    -> OverviewSection(
                    page          = page,
                    viewMode      = viewMode,
                    accentOverride = if (usesCustomTint) parentPalette.background else null,
                )
                CaptureMode.Photos   -> PhotosSection(page.photos)
                CaptureMode.Voice    -> VoiceSection(page.voiceNotes)
                CaptureMode.Todo     -> TodoSection(page.todoItems)
                CaptureMode.Scans    -> ScansSection(page.scannedDocuments)
                CaptureMode.Contacts -> ContactsSection(page.contacts)
                CaptureMode.Location -> LocationsSection(page.locations)
            }
            Spacer(Modifier.height(AppSpacing.s10))
        }
    }
}

/* ---------- mode sections ---------- */

// Layout is the Re-Leaf redesign: a two-tile RE-LEAF strip at the
// top, AT A GLANCE 3×2 stat grid where each tile carries its
// capture-mode droplet glyph in the corner, then a NOTES preview
// card at the bottom carrying a green count pill and an italic
// placeholder when nothing has been written. Tapping the RE-LEAF
// eyebrow opens a [PaperSavedSheet] explaining the math.
@Composable
private fun OverviewSection(
    page: Page,
    viewMode: PageViewMode,
    accentOverride: androidx.compose.ui.graphics.Color? = null,
) {
    val c = page.counts
    val impact = remember(c, page.notes.size) {
        ReleafImpact.from(
            photos = c.photos,
            voiceNotes = c.voiceNotes,
            todoItems = c.todoItems,
            scans = c.scannedDocuments,
            contacts = c.contacts,
            places = c.locations,
            notes = page.notes.size,
        )
    }
    var showPaperSavedSheet by remember { mutableStateOf(false) }

    val stats = listOf(
        StatItem("Photos",   "${c.photos}",            StatTone.Green,   mode = CaptureMode.Photos),
        StatItem("Scans",    "${c.scannedDocuments}",  StatTone.Neutral, mode = CaptureMode.Scans),
        StatItem("To-do",    "${c.todoItems}",         StatTone.Green,   mode = CaptureMode.Todo),
        StatItem("Contacts", "${c.contacts}",          StatTone.Info,    mode = CaptureMode.Contacts),
        StatItem("Places",   "${c.locations}",         StatTone.Neutral, mode = CaptureMode.Location),
        StatItem("Voice",    "${c.voiceNotes}",        StatTone.Neutral, mode = CaptureMode.Voice),
    )

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
        ReLeafStrip(
            impact         = impact,
            onShowDetail   = { showPaperSavedSheet = true },
            accentOverride = accentOverride,
        )

        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            // AT A GLANCE eyebrow tints to the parent-notebook
            // color when one is set so the overview surface reads
            // as one family. Nil → default themeGreenDeep.
            Text(
                "AT A GLANCE",
                style = AppTypography.Eyebrow,
                color = accentOverride ?: AppColors.ThemeGreenDeep,
            )
            when (viewMode) {
                PageViewMode.Grid -> {
                    StatGrid(items = stats.subList(0, 3), valueFamily = FontFamily.Serif)
                    StatGrid(items = stats.subList(3, 6), valueFamily = FontFamily.Serif)
                }
                PageViewMode.List -> {
                    StatList(items = stats, valueFamily = FontFamily.Serif)
                }
            }
        }

        NotesPreviewCard(notes = page.notes)
    }

    if (showPaperSavedSheet) {
        PaperSavedSheet(
            photos         = c.photos,
            voiceNotes     = c.voiceNotes,
            todoItems      = c.todoItems,
            scans          = c.scannedDocuments,
            contacts       = c.contacts,
            places         = c.locations,
            notes          = page.notes.size,
            accentOverride = accentOverride,
            onDismiss      = { showPaperSavedSheet = false },
        )
    }
}

@Composable
private fun NotesPreviewCard(notes: List<Note>) {
    val pageCount = if (notes.isEmpty()) 1 else notes.size
    val pageCountLabel = "$pageCount page" + if (pageCount == 1) "" else "s"
    val preview = notes.firstOrNull { it.body.trim().isNotEmpty() }?.body?.trim()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Text("NOTES", style = AppTypography.Eyebrow, color = AppColors.TextSecondary)
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.GreenSoft)
                        .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
                ) {
                    Text(pageCountLabel, style = AppTypography.Tag, color = AppColors.GreenText)
                }
                Box(modifier = Modifier.weight(1f))
                Icon(
                    imageVector        = Icons.Filled.Edit,
                    contentDescription = "Edit notes",
                    tint               = AppColors.ThemeGreenPrimary,
                    modifier           = Modifier.size(14.dp),
                )
            }
            if (preview != null) {
                Text(
                    text     = preview,
                    style    = AppTypography.Body,
                    color    = AppColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text  = "tap to write notes…",
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize   = 15.sp,
                        fontStyle  = FontStyle.Italic,
                    ),
                    color = AppColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun PhotosSection(photos: List<Photo>) {
    if (photos.isEmpty()) { EmptyState("No photos on this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        photos.forEach { PhotoTile(it) }
    }
}

@Composable
private fun PhotoTile(photo: Photo) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            // Placeholder tile — real thumb comes with Drive.downloadBytes later.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.md))
                    .border(
                        width = 1.dp,
                        color = AppColors.BorderDefault,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(AppRadius.md)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    photo.caption ?: "Photo",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
            }
            photo.caption?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun VoiceSection(notes: List<VoiceNote>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
        // Existing notes (most-recent first kept by parent ordering).
        if (notes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                notes.forEach { VoiceCard(it) }
            }
        }

        // Recording control. Always present so the user can capture
        // a new note without leaving the tab. Persistence is wired
        // upstream — the view model translates the recorded clip
        // into a real VoiceNote and writes it to the page.
        VoicePageRecorder(
            isEmpty = notes.isEmpty(),
            onSave = { _ ->
                // TODO: route to the view model so a new VoiceNote
                // is appended to this page. The clip URI + duration
                // are persisted; transcription happens async via
                // SpeechTranscriber after the file write settles.
            },
            onCancel = { /* no-op — cancelled clip already discarded */ },
        )
    }
}

@Composable
private fun VoiceCard(note: VoiceNote) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Voice note · ${formatDuration(note.durationMs)}",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text("▶︎ Play", style = AppTypography.Button, color = AppAccent.primary)
            }
            note.transcription?.let {
                Text(
                    "\u201C$it\u201D",
                    style = AppTypography.Body.copy(fontStyle = FontStyle.Italic),
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun TodoSection(items: List<TodoItem>) {
    if (items.isEmpty()) { EmptyState("Nothing on the to-do list."); return }
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            items.sortedBy { it.position }.forEach { TodoRow(it) }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (item.done) "Done" else "Not done",
            tint = if (item.done) AppAccent.primary else AppColors.TextTertiary,
            modifier = Modifier.padding(end = AppSpacing.s2),
        )
        Text(
            item.body,
            style = AppTypography.Body.copy(
                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
            ),
            color = if (item.done) AppColors.TextTertiary else AppColors.TextPrimary,
        )
    }
}

@Composable
private fun ScansSection(scans: List<ScannedDocument>) {
    if (scans.isEmpty()) { EmptyState("No scanned documents."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        scans.forEach { ScanRow(it) }
    }
}

@Composable
private fun ScanRow(scan: ScannedDocument) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .padding(AppSpacing.s0)
            ) {
                Text(
                    "📄",
                    style = AppTypography.StatNumber,
                    color = AppAccent.primary,
                    modifier = Modifier.padding(end = AppSpacing.s3),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(scan.title, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
                Text(
                    "${scan.pageCount} page${if (scan.pageCount == 1) "" else "s"}",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ContactsSection(contacts: List<Contact>) {
    if (contacts.isEmpty()) { EmptyState("No contacts pinned to this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        contacts.forEach { ContactCard(it) }
    }
}

@Composable
private fun ContactCard(contact: Contact) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(contact.name, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
            contact.phone?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
            }
            contact.email?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
            }
            contact.notes?.let {
                Text(it, style = AppTypography.Meta, color = AppColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun LocationsSection(pins: List<LocationPin>) {
    if (pins.isEmpty()) { EmptyState("No places on this page."); return }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        pins.forEach { LocationCard(it) }
    }
}

@Composable
private fun LocationCard(pin: LocationPin) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(pin.name, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
            Text(
                "%.4f, %.4f".format(pin.latitude, pin.longitude),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            pin.notes?.let {
                Text(it, style = AppTypography.Body, color = AppColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, style = AppTypography.Body, color = AppColors.TextTertiary)
    }
}

/** Real Move-to-notebook picker. Renders the current
 *  [overflow.availableNotebooks] as a vertical list of rows; the
 *  row for the page's current notebook is dimmed and unclickable
 *  so users don't no-op-move into the same place. A spinner row
 *  shows while the list is loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToNotebookSheet(
    notebooks: List<app.releaf.mobile.data.domain.Notebook>,
    isLoading: Boolean,
    currentNotebookId: String,
    chaptersByNotebookId: Map<String, List<app.releaf.mobile.data.domain.Chapter>>,
    chaptersLoadingFor: Set<String>,
    onExpand: (String) -> Unit,
    onSelect: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var expandedNotebookId by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text("MOVE",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep)
            Text(
                text  = "move to notebook",
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Pick the notebook this page should live under. The current notebook is dimmed.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )

            when {
                isLoading && notebooks.isEmpty() -> {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(vertical = AppSpacing.s5),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AppColors.ThemeGreenPrimary)
                    }
                }
                notebooks.isEmpty() -> {
                    Text(
                        text  = "No notebooks yet.",
                        style = AppTypography.Body,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.padding(vertical = AppSpacing.s4),
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                        notebooks.forEach { notebook ->
                            val isCurrent  = notebook.id == currentNotebookId
                            val isExpanded = expandedNotebookId == notebook.id
                            NotebookPickerRow(
                                notebook         = notebook,
                                isCurrent        = isCurrent,
                                isExpanded       = isExpanded,
                                chapters         = chaptersByNotebookId[notebook.id].orEmpty(),
                                isLoadingChapters = chaptersLoadingFor.contains(notebook.id),
                                onTap = {
                                    if (isCurrent) return@NotebookPickerRow
                                    expandedNotebookId = if (isExpanded) null else notebook.id
                                    if (!isExpanded) onExpand(notebook.id)
                                },
                                onMoveToTop      = { onSelect(notebook.id, null) },
                                onSelectChapter  = { chapterId -> onSelect(notebook.id, chapterId) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.s2))
            AppButton(
                text     = "Cancel",
                onClick  = onDismiss,
                variant  = AppButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun NotebookPickerRow(
    notebook: app.releaf.mobile.data.domain.Notebook,
    isCurrent: Boolean,
    isExpanded: Boolean,
    chapters: List<app.releaf.mobile.data.domain.Chapter>,
    isLoadingChapters: Boolean,
    onTap: () -> Unit,
    onMoveToTop: () -> Unit,
    onSelectChapter: (String) -> Unit,
) {
    val palette = app.releaf.mobile.ui.components.ShelfTheme.palette(notebook.colorToken)
    val rowAlpha = if (isCurrent) 0.55f else 1f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.Canvas)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .alpha(rowAlpha),
    ) {
        // Header row — tap toggles expansion. Current notebook is
        // dimmed and locked.
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            modifier              = Modifier
                .fillMaxWidth()
                .then(if (isCurrent) Modifier else Modifier.clickable(onClick = onTap))
                .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(palette.background),
            )
            Column(
                modifier              = Modifier.weight(1f),
                verticalArrangement   = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    Text(
                        text  = notebook.title,
                        style = AppTypography.Button,
                        color = AppColors.TextPrimary,
                    )
                    if (isCurrent) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .background(AppColors.GreenSoft)
                                .padding(horizontal = AppSpacing.s2, vertical = 1.dp),
                        ) {
                            Text(
                                text  = "CURRENT",
                                style = AppTypography.Tag,
                                color = AppColors.GreenText,
                            )
                        }
                    }
                }
                Text(
                    text  = "${notebook.chapterCount} chapter${if (notebook.chapterCount == 1) "" else "s"} · ${notebook.pageCount} page${if (notebook.pageCount == 1) "" else "s"}",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            }
            if (!isCurrent) {
                Icon(
                    imageVector        = if (isExpanded) Icons.Filled.KeyboardArrowDown
                                         else            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint               = AppColors.TextTertiary,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }

        if (isExpanded && !isCurrent) {
            ExpandedChaptersBlock(
                chapters         = chapters,
                isLoading        = isLoadingChapters,
                onMoveToTop      = onMoveToTop,
                onSelectChapter  = onSelectChapter,
            )
        }
    }
}

@Composable
private fun ExpandedChaptersBlock(
    chapters: List<app.releaf.mobile.data.domain.Chapter>,
    isLoading: Boolean,
    onMoveToTop: () -> Unit,
    onSelectChapter: (String) -> Unit,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(AppColors.BorderDefault),
        )
        // "Move to top" — preserves the v1 default of letting users
        // skip chapter selection and dump the page into the
        // destination's first chapter.
        ChapterRow(
            title = "Move to top of notebook",
            meta  = "Lands in the first chapter",
            onTap = onMoveToTop,
        )
        when {
            isLoading -> {
                Box(
                    modifier         = Modifier.fillMaxWidth().padding(vertical = AppSpacing.s3),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color    = AppColors.ThemeGreenPrimary,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            chapters.isEmpty() -> {
                Text(
                    text     = "No chapters in this notebook yet.",
                    style    = AppTypography.Meta,
                    color    = AppColors.TextTertiary,
                    modifier = Modifier
                        .padding(start = AppSpacing.s3 + 32.dp + AppSpacing.s3)
                        .padding(vertical = AppSpacing.s3),
                )
            }
            else -> {
                chapters.forEach { chapter ->
                    Box(
                        modifier = Modifier
                            .padding(start = AppSpacing.s3 + 32.dp + AppSpacing.s3)
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(AppColors.BorderDefault.copy(alpha = 0.5f)),
                    )
                    ChapterRow(
                        title = chapter.title,
                        meta  = "${chapter.pages.size} page${if (chapter.pages.size == 1) "" else "s"}",
                        onTap = { onSelectChapter(chapter.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    title: String,
    meta: String,
    onTap: () -> Unit,
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint               = AppColors.ThemeGreenDeep,
            modifier           = Modifier.size(13.dp).padding(start = 0.dp),
        )
        Box(modifier = Modifier.size(width = 19.dp, height = 1.dp)) // align with notebook colour chip
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text     = title,
                style    = AppTypography.Body,
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = meta,
                style = AppTypography.Tag,
                color = AppColors.TextTertiary,
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = AppColors.TextTertiary,
            modifier           = Modifier.size(14.dp),
        )
    }
}

/**
 * Bottom-sheet that explains the day's rotated plant. The page
 * header surfaces it on tap of the title — so users curious about
 * the Sanskrit name (or the descriptor line) land on the same
 * canonical surface that names the plant in full and lists what
 * it's traditionally used for.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DailyPlantInfoSheet(
    plant: app.releaf.mobile.ui.theme.DailyPlant,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s5,
                    end    = AppSpacing.s5,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s6,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                Text(
                    text  = "PLANT OF THE PAGE",
                    style = AppTypography.Eyebrow,
                    color = AppColors.ThemeGreenDeep,
                )
                Text(
                    text  = plant.name,
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize   = 36.sp,
                    ),
                    color = AppColors.TextPrimary,
                )
                if (plant.commonName.isNotEmpty()) {
                    Text(
                        text  = plant.commonName,
                        style = TextStyle(
                            fontFamily = FontFamily.Serif,
                            fontSize   = 18.sp,
                            fontStyle  = FontStyle.Italic,
                        ),
                        color = AppColors.TextSecondary,
                    )
                }
            }

            HairlineDivider()

            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
                PlantInfoBlock(title = "EPITHET",            body = plant.epithet)
                PlantInfoBlock(title = "TRADITIONAL USES",   body = plant.usedFor)
            }

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Copy pill — pulls the plant's headline (name +
                // epithet) onto the clipboard so users can paste
                // it into their own notes, search, or share text.
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AppColors.GreenSoft)
                        .clickable { onCopy() }
                        .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    Text(
                        text  = "Copy",
                        style = AppTypography.Button,
                        color = AppColors.ThemeGreenDeep,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Close", color = AppColors.TextSecondary)
                }
            }
        }
    }
}

/**
 * Bottom sheet that surfaces the share-related actions grouped
 * under a single overflow entry. iOS nests these under a
 * SwiftUI Menu submenu; Material3's DropdownMenu doesn't support
 * cascades cleanly so we use a bottom sheet here — a more
 * material pattern that scales as we add more share targets.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ShareGroupSheet(
    onShare: () -> Unit,
    onExportPDF: () -> Unit,
    onCopyLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s5,
                    end    = AppSpacing.s5,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Text(
                text  = "SHARE",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.padding(bottom = AppSpacing.s2),
            )
            ShareGroupRow(
                icon  = Icons.Filled.Share,
                title = "Share",
                body  = "Open the system share sheet with this page's contents.",
                onClick = onShare,
            )
            HairlineDivider()
            ShareGroupRow(
                icon  = Icons.Filled.PictureAsPdf,
                title = "Export PDF",
                body  = "Render the page as a PDF and share it.",
                onClick = onExportPDF,
            )
            HairlineDivider()
            ShareGroupRow(
                icon  = Icons.Filled.Link,
                title = "Copy page link",
                body  = "releaf://page/{id} — paste anywhere.",
                onClick = onCopyLink,
            )
        }
    }
}

@Composable
private fun ShareGroupRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = AppSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = AppAccent.deep,
            modifier           = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text  = title,
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = body,
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun PlantInfoBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(
            text  = title,
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        Text(
            text  = body,
            style = AppTypography.Body,
            color = AppColors.TextPrimary,
        )
    }
}

/** Modal bottom sheet for editing the loaded page's tags. Holds
 *  its own draft state so the parent only seeds initial tags and
 *  hands back the final list on Save.
 *
 *  Submission rules in the input field:
 *  - Comma → commits the current text as a tag, clears input
 *  - Done / IME action → commits the current text */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EditTagsSheet(
    initialTags: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onCopyAll: ((List<String>) -> Unit)? = null,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
    )
    var tags  by remember(initialTags) { mutableStateOf(initialTags) }
    var draft by remember { mutableStateOf("") }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    fun appendTag(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        if (tags.none { it.equals(trimmed, ignoreCase = true) }) {
            tags = tags + trimmed
        }
    }

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
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "EDIT TAGS",
                    style = AppTypography.Eyebrow,
                    color = AppColors.ThemeGreenDeep,
                )
                if (onCopyAll != null && tags.isNotEmpty()) {
                    // Copy-all pill — pulls the in-progress tag
                    // list (not just `initialTags`) onto the
                    // clipboard so users get what they're
                    // looking at, including unsaved additions.
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AppColors.GreenSoft)
                            .clickable { onCopyAll(tags) }
                            .padding(horizontal = AppSpacing.s2, vertical = 4.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text  = "Copy all",
                            style = AppTypography.Tag,
                            color = AppColors.ThemeGreenDeep,
                        )
                    }
                }
            }
            Text(
                text  = "tag this page",
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Type and press comma or Done to add. Tap × to remove.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )

            // Current tags row — wraps to new lines on overflow via
            // FlowRow (compose.foundation.layout 1.5+ ships it).
            if (tags.isEmpty()) {
                Text(
                    text  = "No tags yet.",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    verticalArrangement   = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    tags.forEach { tag ->
                        EditableTagPill(label = tag, onRemove = {
                            tags = tags.filterNot { it == tag }
                        })
                    }
                }
            }

            // Input field — comma in the value commits, IME Done commits.
            androidx.compose.material3.OutlinedTextField(
                value         = draft,
                onValueChange = { newValue ->
                    if (newValue.contains(',')) {
                        val parts = newValue.split(',')
                        for (p in parts.dropLast(1)) appendTag(p)
                        draft = parts.last()
                    } else {
                        draft = newValue
                    }
                },
                label = { Text("Add a tag") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        appendTag(draft)
                        draft = ""
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                AppButton(
                    text     = "Cancel",
                    onClick  = onDismiss,
                    variant  = AppButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                AppButton(
                    text     = "Save",
                    onClick  = {
                        appendTag(draft)
                        keyboard?.hide()
                        onSave(if (draft.isBlank()) tags else tags + draft.trim())
                    },
                    variant  = AppButtonVariant.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Read-only row of tag pills shown under the page title. Same
 * soft-green chrome as the editable pill but without the remove
 * affordance. Short-tap → `onTap(label)` (header wires this to
 * open the tag editor). Long-press → `onLongPress(label)` (header
 * wires this to copy the tag string to the clipboard with a
 * confirming toast). Both gestures live on the same pill so
 * users can pick an action without a context menu. Optional
 * `palette` tints the pills to the parent notebook so even tags
 * read as part of the notebook color family.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TagsRow(
    tags: List<String>,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
    palette: app.releaf.mobile.ui.components.ShelfPalette? = null,
    modifier: Modifier = Modifier,
) {
    val pillFill: Color = palette?.background?.copy(alpha = 0.16f) ?: AppColors.GreenSoft
    val pillText: Color = palette?.background ?: AppColors.GreenText
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.padding(top = AppSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalArrangement   = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        tags.forEach { tag ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(pillFill)
                    .combinedClickable(
                        onClick     = { onTap(tag) },
                        onLongClick = { onLongPress(tag) },
                    )
                    .padding(
                        start  = AppSpacing.s3,
                        end    = AppSpacing.s3,
                        top    = AppSpacing.s1,
                        bottom = AppSpacing.s1,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = tag,
                    style = AppTypography.Tag,
                    color = pillText,
                )
            }
        }
    }
}

@Composable
private fun EditableTagPill(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.GreenSoft)
            .padding(start = AppSpacing.s3, end = AppSpacing.s2, top = AppSpacing.s1, bottom = AppSpacing.s1),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text  = label,
            style = AppTypography.Tag,
            color = AppColors.GreenText,
        )
        Box(
            modifier = Modifier
                .size(14.dp)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Close,
                contentDescription = "Remove $label",
                tint               = AppColors.GreenText,
                modifier           = Modifier.size(11.dp),
            )
        }
    }
}

/** Soft-green banner shown at the top of an archived page. Reuses
 *  the GreenSoft / GreenText semantic pair so it reads as a state
 *  indicator rather than an alert. The Restore button is the
 *  primary affordance — undoing archive should be one tap. */
@Composable
private fun ArchivedBanner(
    archivedAt: java.time.Instant,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.GreenSoft)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Icon(
            imageVector        = Icons.Filled.Archive,
            contentDescription = null,
            tint               = AppColors.GreenText,
            modifier           = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text  = "ARCHIVED",
                style = AppTypography.Eyebrow,
                color = AppColors.GreenText,
            )
            Text(
                text  = relativeTimeAgo(archivedAt.toString()),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.CardSolid)
                .border(
                    width = 1.dp,
                    color = AppColors.GreenText.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(AppRadius.pill),
                )
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

/** Real Apply-template picker. Renders the curated set of
 *  [overflow.availableTemplates] as a vertical list of rows, each
 *  showing the template's icon, name, description, and a small
 *  "summary" line that surfaces what the template will add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplyTemplateSheet(
    templates: List<app.releaf.mobile.data.domain.PageTemplate>,
    isLoading: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text("TEMPLATE",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep)
            Text(
                text  = "apply a template",
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Pick a scaffold to add to this page. Template content concats onto your current notes and to-dos — nothing gets overwritten.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )

            when {
                isLoading && templates.isEmpty() -> {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(vertical = AppSpacing.s5),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AppColors.ThemeGreenPrimary)
                    }
                }
                templates.isEmpty() -> {
                    Text(
                        text  = "No templates yet.",
                        style = AppTypography.Body,
                        color = AppColors.TextTertiary,
                        modifier = Modifier.padding(vertical = AppSpacing.s4),
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                        templates.forEach { template ->
                            TemplatePickerRow(
                                template = template,
                                onTap    = { onSelect(template.id) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.s2))
            AppButton(
                text     = "Cancel",
                onClick  = onDismiss,
                variant  = AppButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun TemplatePickerRow(
    template: app.releaf.mobile.data.domain.PageTemplate,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.Canvas)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onTap)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
    ) {
        Row(
            verticalAlignment     = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(AppColors.ThemeGreenPrimary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = templateIconFor(template.iconKey),
                    contentDescription = null,
                    tint               = AppColors.ThemeGreenDeep,
                    modifier           = Modifier.size(16.dp),
                )
            }
            Column(
                modifier              = Modifier.weight(1f),
                verticalArrangement   = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text  = template.title,
                    style = AppTypography.Button,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text     = template.description,
                    style    = AppTypography.Meta,
                    color    = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text     = template.summary,
                    style    = AppTypography.Tag,
                    color    = AppColors.GreenText,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint               = AppColors.TextTertiary,
                modifier           = Modifier
                    .size(16.dp)
                    .padding(top = AppSpacing.s1),
            )
        }
    }
}

/** Map a template's icon key to a Material icon. Mirrors the iOS
 *  ShelfTheme.iconSystemName lookup; if a new key is added on iOS,
 *  mirror it here. */
@Composable
private fun templateIconFor(key: String?): androidx.compose.ui.graphics.vector.ImageVector =
    when (key?.lowercase()) {
        "plant"  -> Icons.Filled.Eco
        "chart"  -> Icons.Filled.BarChart
        "sun"    -> Icons.Filled.WbSunny
        "book"   -> Icons.AutoMirrored.Filled.MenuBook
        "coffee" -> Icons.Filled.LocalCafe
        else     -> Icons.Filled.Eco
    }

/** Tall-but-empty bottom sheet with the page-style chrome. Kept as
 *  the fallback shape for any future action whose picker hasn't
 *  been designed yet. Currently unused — both Move-to-notebook and
 *  Apply-template have real pickers above. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderPickerSheet(
    eyebrow: String,
    title: String,
    copy: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = AppColors.CardSolid,
        contentColor     = AppColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s5)
                .padding(bottom = AppSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Text(eyebrow, style = AppTypography.Eyebrow, color = AppColors.ThemeGreenDeep)
            Text(
                text  = title,
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                color = AppColors.TextPrimary,
            )
            Text(
                text  = copy,
                style = AppTypography.Body,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(AppSpacing.s3))
            AppButton(
                text     = "Close",
                onClick  = onDismiss,
                variant  = AppButtonVariant.Primary,
            )
        }
    }
}

/** Brief auto-dismissing pill at the top of the screen. Plain
 *  surface, hairline border, serif body so it doesn't read as a
 *  system-Material toast. The screen-root `LaunchedEffect` clears
 *  `viewModel.toast` after a 2.4s display. Optional `actionLabel`
 *  + `onAction` add a trailing pill (e.g. "Undo" after archive)
 *  so the user can act on the toast without chasing a separate
 *  menu. */
@Composable
private fun ToastView(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(AppColors.CardSolid)
            .border(
                1.dp,
                AppColors.BorderDefault,
                CircleShape,
            )
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Text(
            text  = message,
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize   = 14.sp,
            ),
            color = AppColors.TextPrimary,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text     = actionLabel.uppercase(),
                style    = AppTypography.Tag,
                color    = AppAccent.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onAction() }
                    .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
            )
        }
    }
}
