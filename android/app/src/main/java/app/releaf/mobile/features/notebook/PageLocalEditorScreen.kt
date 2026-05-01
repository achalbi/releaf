/*
 * PageLocalEditorScreen.kt
 *
 * Room-backed single-page WYSIWYG editor. Structural twin of
 * NotepadEditorScreen — same rich-text body, same save-on-back /
 * save-on-dispose semantics.
 *
 * Pages carry more structure than notepad entries: they live inside a
 * notebook chapter and are the anchor for sibling attachments (photos,
 * scans), people/place metadata (contacts, locations), and task lists
 * (todos). Each of those lives as a dedicated section below the body,
 * backed by a JSON column on the `pages` row. Section composables are
 * defined in `PageSections.kt`; this file wires them up to the VM.
 *
 * Markdown toolbar, rich-text state, and word-count footer are shared
 * with the notepad editor via `ui/components/editor/`.
 */

package app.releaf.mobile.features.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Spa
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.components.AppToastHost
import app.releaf.mobile.ui.components.rememberToastState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.ui.components.BreadcrumbSegment
import app.releaf.mobile.ui.components.Breadcrumbs
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.components.DotGridBackground
import app.releaf.mobile.ui.components.editor.ContactsSection
import app.releaf.mobile.ui.components.editor.DrawingColorSaver
import app.releaf.mobile.ui.components.editor.DrawingMode
import app.releaf.mobile.ui.components.editor.DrawingModeSaver
import app.releaf.mobile.ui.components.editor.DrawingPalette
import app.releaf.mobile.ui.components.editor.DrawingThicknesses
import app.releaf.mobile.ui.components.editor.DrawingToolbar
import app.releaf.mobile.ui.components.editor.EditorMode
import app.releaf.mobile.ui.components.editor.EditorModeIconToggle
import app.releaf.mobile.ui.components.editor.LocationSection
import app.releaf.mobile.ui.components.editor.OverviewPane
import app.releaf.mobile.ui.components.editor.PenConfig
import app.releaf.mobile.ui.components.editor.PhotosSection
import app.releaf.mobile.ui.components.editor.RichTextFormatBar
import app.releaf.mobile.ui.components.editor.ScansSection
import app.releaf.mobile.ui.components.editor.SubPageEditorPager
import app.releaf.mobile.ui.components.editor.TodosSection
import app.releaf.mobile.ui.components.editor.VoiceSection
import app.releaf.mobile.ui.components.editor.WordCountFooter
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import com.mohamedrejeb.richeditor.model.RichTextState

@Composable
fun PageLocalEditorScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNotebooksTab: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * CaptureTabBar tab to pre-select — wired up for the Quick Capture
     * flow so tapping "Photos" (or any other feature) in the middle-leaf
     * sheet lands the new page already scrolled to that section.
     */
    initialCaptureMode: CaptureMode? = null,
    /**
     * When true, render the colored "hero card" header from the
     * Variant1 viewer instead of the default breadcrumb top bar.
     * Body + behavior are unchanged — only the chrome at the top
     * swaps. Wired from MainActivity off the user's
     * `NotebookListVariant` preference.
     */
    useHeroHeader: Boolean = false,
    viewModel: PageLocalEditorViewModel = viewModel(factory = PageLocalEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    // Overview (grid) is the default — matches the page-detail design
    // on the Home tab. Users flip to the list icon when they want the
    // single-scroll rich-text editor.
    var editorMode by rememberSaveable { mutableStateOf(EditorMode.OVERVIEW) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showPlantInfo by rememberSaveable { mutableStateOf(false) }
    val plant = viewModel.pagePlant
    val focusManager = LocalFocusManager.current

    // Drawing overlay controls. Stored separately from the editor state
    // because the Stroke list (persisted) is owned by the VM while the
    // transient UI — active mode, current color, etc. — belongs to the
    // screen and survives config changes via rememberSaveable.
    var drawingMode by rememberSaveable(stateSaver = DrawingModeSaver) {
        mutableStateOf(DrawingMode.Off)
    }
    var drawColor by rememberSaveable(stateSaver = DrawingColorSaver) {
        mutableStateOf(DrawingPalette[0])
    }
    var drawOpacity by rememberSaveable { mutableStateOf(1f) }
    var drawWidth by rememberSaveable { mutableStateOf(DrawingThicknesses[1].widthDp) }
    var drawNib by rememberSaveable { mutableStateOf(Stroke.NIB_BALLPOINT) }

    // One RichTextState per sub-page. Stays at screen level so Edit mode
    // and the Overview sheet both read/write the same in-flight text (no
    // round-trip through the VM on mode switch). The map is a plain
    // `mutableMapOf` remembered once — getOrPut below makes the fill
    // idempotent, and prune clears entries for removed sub-pages.
    val richTextStates = remember { mutableMapOf<String, RichTextState>() }
    state.subPages.forEach { sp ->
        richTextStates.getOrPut(sp.id) {
            RichTextState().apply { setMarkdown(sp.notes) }
        }
    }
    val liveIds = state.subPages.map { it.id }.toSet()
    richTextStates.keys.removeAll { it !in liveIds }

    // Pager state owned by the screen so the bottom format bar / word
    // count can read the currently-visible sub-page. Re-keyed on the
    // loaded-state flip so we don't seed pageCount with 0 and wedge the
    // initial scroll.
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount   = { state.subPages.size },
    )

    val currentSubPage = state.subPages.getOrNull(pagerState.currentPage)
    val currentRts = currentSubPage?.let { richTextStates[it.id] }

    val context = LocalContext.current
    val toastScope = androidx.compose.runtime.rememberCoroutineScope()
    val toastState = rememberToastState(toastScope)
    // "Add to notes" on a voice-note card — always append to the *last*
    // sub-page. Same rationale as NotepadEditorScreen: that's where
    // the user is usually writing, older sub-pages stay untouched.
    val addVoiceTranscriptToNotes: (String) -> Unit = addVoice@{ text ->
        // Skip ruled (ledger) sub-pages when picking the target — they
        // have no text body, so an append would be dropped on the
        // floor. Fall back to the latest non-ruled sub-page; surface a
        // toast if there isn't one so the user knows why the action
        // no-op'd.
        val target = state.subPages.lastOrNull { it.background != app.releaf.mobile.data.notebook.SubPage.BG_RULED }
        if (target == null) {
            toastState.show("Add a notes page first")
            return@addVoice
        }
        val rts = richTextStates[target.id] ?: return@addVoice
        val existing = rts.toMarkdown()
        val separator = if (existing.isBlank()) "" else "\n\n"
        rts.setMarkdown(existing + separator + text.trim())
        viewModel.updateSubPageNotes(target.id, rts.toMarkdown())
        toastState.show("Added to notes")
    }

    // Capture the latest state for dispose-time flush. DisposableEffect
    // keyed on `viewModel` doesn't re-run per state emit, so we read
    // through rememberUpdatedState to get the most recent snapshot.
    val stateRef by rememberUpdatedState(state)
    DisposableEffect(viewModel) {
        onDispose {
            stateRef.subPages.forEach { sp ->
                richTextStates[sp.id]?.let { rts ->
                    viewModel.updateSubPageNotes(sp.id, rts.toMarkdown())
                }
            }
            viewModel.save()
        }
    }

    // Dot-grid canvas sits as the backmost layer behind all editor
    // chrome so the whole surface inherits the notebook feel (from
    // the Releaf Branding template). Content layer keeps its
    // imePadding + focus-clear tap handler.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        DotGridBackground()
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                }
        ) {
        val popBack: () -> Unit = {
            state.subPages.forEach { sp ->
                richTextStates[sp.id]?.let { rts ->
                    viewModel.updateSubPageNotes(sp.id, rts.toMarkdown())
                }
            }
            viewModel.save()
            onBack()
        }

        val pageTitleLabel = state.title.ifBlank {
            if (state.exists) "Untitled page" else "Page"
        }

        val notebook = state.notebook
        val crumbs = buildList {
            add(BreadcrumbSegment(label = "Home", onTap = onHome))
            add(BreadcrumbSegment(label = "Notebook", onTap = onNotebooksTab))
            if (notebook != null) {
                val nbId = notebook.id
                add(
                    BreadcrumbSegment(
                        label = notebook.title.ifBlank { "Notebook" },
                        onTap = {
                            // Flush in-flight notes before we route away — the
                            // editor saves on popBack, but breadcrumb taps
                            // bypass that path.
                            state.subPages.forEach { sp ->
                                richTextStates[sp.id]?.let { rts ->
                                    viewModel.updateSubPageNotes(sp.id, rts.toMarkdown())
                                }
                            }
                            viewModel.save()
                            onOpenNotebook(nbId)
                        },
                    ),
                )
            }
            add(BreadcrumbSegment(label = pageTitleLabel))
        }

        if (useHeroHeader) {
            // Variant1 (hero card) chrome — colored band carries the
            // back arrow + chapter / page eyebrow + page counter, with
            // the same edit-mode toggle and overflow menu the classic
            // top bar exposes so the action surface stays equivalent.
            HeroTopBar(
                state        = state,
                editorMode   = editorMode,
                onChangeMode = { newMode -> editorMode = newMode },
                onBack       = popBack,
                onDelete     = { showDeleteDialog = true },
            )
        } else {
            TopBar(
                notebookLabel = state.notebook?.title?.ifBlank { "Notebook" } ?: "Notebook",
                chapterLabel  = state.chapter?.title?.ifBlank { "" } ?: "",
                showDelete    = state.exists,
                editorMode    = editorMode,
                onChangeMode  = { newMode -> editorMode = newMode },
                onBack        = popBack,
                onDelete      = { showDeleteDialog = true },
            )
        }

        // Title sits at screen level directly under the top bar so it
        // stays sticky above the mode content (both Edit scroll and
        // Overview's CaptureTabBar).
        if (!state.isLoading && state.exists) {
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4)
                    .padding(bottom = AppSpacing.s2),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                TitleField(
                    value         = state.title,
                    onValueChange = viewModel::updateTitle,
                    modifier      = Modifier.weight(1f),
                )
                // Plant-of-the-page affordance — same Spa icon as the
                // notepad editor. Tapping opens a bottom sheet with
                // the plant's name, common name, epithet, and uses.
                app.releaf.mobile.ui.components.RoundIconButton(
                    icon               = Icons.Filled.Spa,
                    contentDescription = "Plant of the page",
                    onClick            = { showPlantInfo = true },
                    background         = AppColors.GreenSoft,
                    tint               = AppColors.ThemeGreenDeep,
                )
            }
            DescriptionField(
                value         = state.description,
                onValueChange = viewModel::updateDescription,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4)
                    .padding(bottom = AppSpacing.s3),
            )
        }

        when {
            state.isLoading -> {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AppAccent.primary)
                }
            }

            !state.exists -> {
                Box(Modifier.weight(1f)) {
                    MissingPageState(onBack = onBack)
                }
            }

            editorMode == EditorMode.OVERVIEW -> {
                // Overview's preview + fullscreen sheet always bind to
                // the *last* sub-page — the user's latest entry, where
                // "Add to notes" also lands. Multi-sub-page navigation
                // lives in Edit mode.
                val lastSp  = state.subPages.lastOrNull()
                val lastRts = lastSp?.let { richTextStates[it.id] }
                Box(Modifier.weight(1f)) {
                    OverviewPane(
                        richTextState = lastRts ?: RichTextState(),
                        contacts      = state.contacts,
                        todos         = state.todos,
                        locations     = state.locations,
                        attachments   = state.attachments,
                        onAddContact    = { name, phone, landline, email, title, organization, location, website ->
                            viewModel.addContact(
                                name         = name,
                                phone        = phone,
                                landline     = landline,
                                email        = email,
                                title        = title,
                                organization = organization,
                                location     = location,
                                website      = website,
                            )
                        },
                        onEditContact   = { id, name, phone, landline, email, title, organization, location, website ->
                            viewModel.updateContact(
                                id           = id,
                                name         = name,
                                phone        = phone,
                                landline     = landline,
                                email        = email,
                                title        = title,
                                organization = organization,
                                location     = location,
                                website      = website,
                            )
                        },
                        onRemoveContact = viewModel::removeContact,
                        onAddTodo       = viewModel::addTodo,
                        onToggleTodo    = viewModel::toggleTodo,
                        onRemoveTodo    = viewModel::removeTodo,
                        onUpdateTodoPriority = viewModel::updateTodoPriority,
                        onReorderTodos  = viewModel::reorderTodos,
                        onAddLocation   = { lat, lng, address -> viewModel.addLocation(lat, lng, address) },
                        onUpdateLocationCoords = viewModel::updateLocationCoords,
                        onRemoveLocation = viewModel::removeLocation,
                        onAddPhoto      = { uri -> viewModel.addAttachment(Attachment.TYPE_PHOTO, uri) },
                        onCombinePhotosToPdf = { pdfUri, previewUri ->
                            viewModel.addAttachment(Attachment.TYPE_SCAN, pdfUri, previewUri)
                        },
                        onAddScan       = { uri, preview, pageUris ->
                            viewModel.addScan(uri, preview, pageUris)
                        },
                        onAddVoiceNote  = { uri, durationMs ->
                            viewModel.addVoiceNote(uri, durationMs)
                        },
                        onTranscribeVoiceNote = { uri, transcript, source ->
                            viewModel.updateVoiceTranscript(uri, transcript, source)
                        },
                        onAddVoiceNoteTranscriptToNotes = addVoiceTranscriptToNotes,
                        onRemoveAttachment = viewModel::removeAttachment,
                        subPages                  = state.subPages,
                        richTextStates            = richTextStates,
                        onSubPageStrokesChange    = viewModel::updateSubPageStrokes,
                        onSubPageTextBoxesChange  = viewModel::updateSubPageTextBoxes,
                        onSubPageLedgerChange     = viewModel::updateSubPageLedger,
                        onSubPageLedgerTitleChange = viewModel::updateSubPageLedgerTitle,
                        onAddSubPage              = viewModel::addSubPage,
                        onRemoveSubPage           = viewModel::removeSubPage,
                        onSubPageBackgroundChange = viewModel::updateSubPageBackground,
                        onSubPageBgScaleChange    = viewModel::updateSubPageBgScale,
                        onPhotoExported           = { uri ->
                            viewModel.addAttachment(Attachment.TYPE_PHOTO, uri)
                        },
                        onImportPageToNotes       = { pageImageUri ->
                            viewModel.addSubPageFromImage(pageImageUri)
                        },
                        onEditScan                = { id, title, categoryId ->
                            viewModel.updateScan(id, title, categoryId)
                        },
                        initialCaptureMode        = initialCaptureMode,
                    )
                }
            }

            else -> {
                val penConfig = PenConfig(
                    color    = drawColor,
                    opacity  = drawOpacity,
                    widthDp  = drawWidth,
                    nib      = drawNib,
                )
                Box(Modifier.weight(1f)) {
                    EditorBody(
                        state                       = state,
                        viewModel                   = viewModel,
                        pagerState                  = pagerState,
                        richTextStates              = richTextStates,
                        drawingMode                 = drawingMode,
                        penConfig                   = penConfig,
                        onAddVoiceTranscriptToNotes = addVoiceTranscriptToNotes,
                    )
                }
                if (currentRts != null) {
                    WordCountFooter(text = currentRts.annotatedString.text)
                }
                if (drawingMode == DrawingMode.Off) {
                    // Only render the format bar when there's a live
                    // RichTextState to bind to — on the first frame (or
                    // while sub-pages are still being rehydrated) that
                    // can briefly be null.
                    if (currentRts != null) {
                        RichTextFormatBar(
                            state          = currentRts,
                            onEnterDrawing = {
                                focusManager.clearFocus()
                                drawingMode = DrawingMode.Pen
                            },
                        )
                    }
                } else {
                    DrawingToolbar(
                        mode            = drawingMode,
                        onModeChange    = { drawingMode = it },
                        color           = drawColor,
                        onColorChange   = { drawColor = it },
                        opacity         = drawOpacity,
                        onOpacityChange = { drawOpacity = it },
                        widthDp         = drawWidth,
                        onWidthChange   = { drawWidth = it },
                        nib             = drawNib,
                        onNibChange     = { drawNib = it },
                        onClose         = { drawingMode = DrawingMode.Off },
                    )
                }
            }
        }
        } // end inner Column (content layer)
        // Floating in-app toast — token-styled cream pill that
        // replaces the platform Toast. Same usage as the notepad
        // editor.
        AppToastHost(
            state   = toastState,
            padding = androidx.compose.foundation.layout.PaddingValues(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                bottom = AppSpacing.s10,
                top    = AppSpacing.s4,
            ),
        )
    } // end outer Box (dot-grid canvas)

    // Plant-of-the-page bottom sheet — opened by the Spa icon next
    // to the title. Same shared component as the notepad editor.
    if (showPlantInfo) {
        app.releaf.mobile.ui.components.DailyPlantInfoSheet(
            plant     = plant,
            onDismiss = { showPlantInfo = false },
        )
    }

    // Destructive-action guard — same pattern as the notepad editor.
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title            = { Text("Delete this page?") },
            text             = {
                Text(
                    "The page and its attachments move to the trash. " +
                        "Other pages in this notebook stay put.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onBack)
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun TopBar(
    notebookLabel: String,
    chapterLabel: String,
    showDelete: Boolean,
    editorMode: EditorMode,
    onChangeMode: (EditorMode) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    // Leaf-eyebrow chrome — visual + behavioral parity with
    // NotepadEditorScreen's TopBar so the page editor reads like the
    // same surface across the notebook + notepad flows. Pattern:
    // [🌱 NOTEBOOK · {chapter}]   [List/Grid toggle]   [⋮]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s3,
                bottom = AppSpacing.s3,
            ),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Row(
            modifier              = Modifier
                .weight(1f)
                .clickable(onClick = onBack),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            app.releaf.mobile.ui.components.LeafDropletGlyph(
                tint = AppColors.ThemeGreenPrimary,
                size = 11.dp,
            )
            Text(
                text     = notebookLabel.uppercase(),
                style    = AppTypography.Eyebrow,
                color    = AppColors.ThemeGreenDeep,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (chapterLabel.isNotBlank()) {
                Text(
                    text  = "·",
                    style = AppTypography.Eyebrow,
                    color = AppColors.ThemeGreenDeep,
                )
                Text(
                    text     = chapterLabel.uppercase(),
                    style    = AppTypography.Eyebrow,
                    color    = AppColors.ThemeGreenDeep,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        app.releaf.mobile.ui.components.PageViewToggle(
            selected = if (editorMode == EditorMode.OVERVIEW)
                app.releaf.mobile.ui.components.PageViewMode.Grid
            else
                app.releaf.mobile.ui.components.PageViewMode.List,
            onSelect = { mode ->
                onChangeMode(
                    if (mode == app.releaf.mobile.ui.components.PageViewMode.Grid)
                        EditorMode.OVERVIEW
                    else
                        EditorMode.EDIT,
                )
            },
        )
        if (showDelete) {
            app.releaf.mobile.ui.components.PageOverflowButton {
                androidx.compose.material3.DropdownMenuItem(
                    text    = { Text("Delete page", color = AppColors.Danger) },
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * Variant1 hero header — colored band lifted from
 * `PageDetailScreenVariant1.Header`, repurposed for the Room-backed
 * editor. Carries the back arrow on the leading edge, an eyebrow
 * (notebook → chapter) in the middle, and the editor mode toggle +
 * overflow menu on the trailing edge so the same actions reachable
 * from the classic [TopBar] stay reachable here.
 *
 * Palette is keyed off the notebook's `colorHex` if we eventually
 * map hex → token; today it falls back to "green" so the header has
 * a stable look until that mapping lands.
 */
@Composable
private fun HeroTopBar(
    state: PageLocalEditorUiState,
    editorMode: EditorMode,
    onChangeMode: (EditorMode) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = app.releaf.mobile.ui.components.ShelfTheme.palette("green")
    val notebookLabel = state.notebook?.title?.ifBlank { null } ?: "Notebook"
    val chapterLabel  = state.chapter?.title?.ifBlank  { null } ?: ""
    val eyebrow = if (chapterLabel.isNotBlank()) {
        "${notebookLabel.uppercase()} / ${chapterLabel.uppercase()}"
    } else {
        notebookLabel.uppercase()
    }

    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = palette.onBackground,
            modifier = Modifier
                .size(20.dp)
                .clickable { onBack() },
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            eyebrow,
            style = AppTypography.Eyebrow,
            color = palette.onBackground,
            modifier = Modifier.weight(1f),
        )
        // Inline mode toggle + overflow — both painted in onBackground
        // so they read against the colored band.
        EditorModeIconToggle(mode = editorMode, onChange = onChangeMode)
        if (state.exists) {
            Spacer(Modifier.size(AppSpacing.s2))
            Box {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.MoreVert,
                        contentDescription = "More actions",
                        tint               = palette.onBackground,
                        modifier           = Modifier.size(22.dp),
                    )
                }
                if (menuOpen) {
                    Popup(
                        alignment = Alignment.TopEnd,
                        offset = IntOffset(0, with(LocalDensity.current) {
                            (40.dp + AppSpacing.s1).roundToPx()
                        }),
                        onDismissRequest = { menuOpen = false },
                        properties = PopupProperties(focusable = true),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(260.dp)
                                .shadow(8.dp, RoundedCornerShape(AppRadius.md))
                                .clip(RoundedCornerShape(AppRadius.md))
                                .background(AppColors.CardSolid)
                                .border(
                                    width = 1.dp,
                                    color = AppColors.BorderDefault,
                                    shape = RoundedCornerShape(AppRadius.md),
                                )
                                .padding(vertical = AppSpacing.s2),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        menuOpen = false
                                        onDelete()
                                    }
                                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
                            ) {
                                Text(
                                    text  = "Delete page",
                                    style = AppTypography.Body,
                                    color = AppColors.Danger,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorBody(
    state: PageLocalEditorUiState,
    viewModel: PageLocalEditorViewModel,
    pagerState: PagerState,
    richTextStates: MutableMap<String, RichTextState>,
    drawingMode: DrawingMode,
    penConfig: PenConfig,
    onAddVoiceTranscriptToNotes: (String) -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        // Horizontal sub-pages. The pager owns its own horizontal
        // padding + card styling; the screen's vertical scroll wraps
        // it + the feature sections below.
        SubPageEditorPager(
            subPages           = state.subPages,
            pagerState         = pagerState,
            richTextStates     = richTextStates,
            drawingMode        = drawingMode,
            penConfig          = penConfig,
            onStrokesChange    = viewModel::updateSubPageStrokes,
            onTextBoxesChange  = viewModel::updateSubPageTextBoxes,
            onLedgerChange     = viewModel::updateSubPageLedger,
            onLedgerTitleChange = viewModel::updateSubPageLedgerTitle,
            onAddSubPage       = viewModel::addSubPage,
            onRemoveSubPage    = viewModel::removeSubPage,
            onBackgroundChange = viewModel::updateSubPageBackground,
            onBgScaleChange    = viewModel::updateSubPageBgScale,
            onPhotoExported    = { uri ->
                viewModel.addAttachment(Attachment.TYPE_PHOTO, uri)
            },
        )

        // Feature sections below the pager. Each owns its own capture flow
        // (permissions, intent senders) and calls back into the VM for
        // the actual state changes. Horizontal padding here to match the
        // old NotesField layout, since the pager handles its own.
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
        PhotosSection(
            photos   = state.attachments.filter { it.type == Attachment.TYPE_PHOTO },
            onAdd    = { uri -> viewModel.addAttachment(Attachment.TYPE_PHOTO, uri) },
            onRemove = viewModel::removeAttachment,
            onCombineToPdf = { pdfUri, previewUri ->
                viewModel.addAttachment(Attachment.TYPE_SCAN, pdfUri, previewUri)
            },
        )
        ScansSection(
            scans    = state.attachments.filter { it.type == Attachment.TYPE_SCAN },
            onAdd    = { uri, preview, pageUris ->
                viewModel.addScan(uri, preview, pageUris)
            },
            onRemove = viewModel::removeAttachment,
            onImportPageToNotes = { pageImageUri ->
                viewModel.addSubPageFromImage(pageImageUri)
            },
            onEditScan = { id, title, categoryId ->
                viewModel.updateScan(id, title, categoryId)
            },
        )
        VoiceSection(
            notes                  = state.attachments.filter { it.type == Attachment.TYPE_VOICE },
            onAdd                  = { uri, durationMs -> viewModel.addVoiceNote(uri, durationMs) },
            onTranscribed          = { uri, transcript, source ->
                viewModel.updateVoiceTranscript(uri, transcript, source)
            },
            onAddTranscriptToNotes = onAddVoiceTranscriptToNotes,
            onRemove               = viewModel::removeAttachment,
        )
        ContactsSection(
            contacts = state.contacts,
            onAdd    = { name, phone, landline, email, title, organization, location, website ->
                viewModel.addContact(
                    name         = name,
                    phone        = phone,
                    landline     = landline,
                    email        = email,
                    title        = title,
                    organization = organization,
                    location     = location,
                    website      = website,
                )
            },
            onEdit   = { id, name, phone, landline, email, title, organization, location, website ->
                viewModel.updateContact(
                    id           = id,
                    name         = name,
                    phone        = phone,
                    landline     = landline,
                    email        = email,
                    title        = title,
                    organization = organization,
                    location     = location,
                    website      = website,
                )
            },
            onRemove = viewModel::removeContact,
        )
        TodosSection(
            todos            = state.todos,
            onAdd            = viewModel::addTodo,
            onToggle         = viewModel::toggleTodo,
            onRemove         = viewModel::removeTodo,
            onUpdatePriority = viewModel::updateTodoPriority,
            onReorder        = viewModel::reorderTodos,
        )
        LocationSection(
            locations      = state.locations,
            onAdd          = { lat, lng, address -> viewModel.addLocation(lat, lng, address) },
            onUpdateCoords = viewModel::updateLocationCoords,
            onRemove       = viewModel::removeLocation,
        )
        } // end feature-sections Column

        Spacer(Modifier.height(AppSpacing.s10))
    }
}

@Composable
private fun TitleField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bump past `PageTitle` (24sp) so the page title reads as the
    // primary heading. Keeps the serif treatment + weight.
    val titleStyle = AppTypography.PageTitleLight.copy(
        color    = AppColors.TextPrimary,
        fontSize = 34.sp,
    )
    val placeholderStyle = AppTypography.PageTitleLight.copy(
        color    = AppColors.TextTertiary,
        fontSize = 34.sp,
    )
    Box(modifier) {
        if (value.isEmpty()) {
            Text("Title", style = placeholderStyle)
        }
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = true,
            textStyle     = titleStyle,
            cursorBrush   = SolidColor(AppAccent.primary),
            modifier      = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DescriptionField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Italic serif at 14sp — visually subordinate to the 34sp title
    // above. Mirrors the notepad editor's DescriptionField.
    val descStyle = androidx.compose.ui.text.TextStyle(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
        fontStyle  = androidx.compose.ui.text.font.FontStyle.Italic,
        fontSize   = 14.sp,
        color      = AppColors.TextSecondary,
    )
    val placeholderStyle = descStyle.copy(color = AppColors.TextTertiary)
    Box(modifier) {
        if (value.isEmpty()) {
            Text("Description", style = placeholderStyle)
        }
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = false,
            maxLines      = 2,
            textStyle     = descStyle,
            cursorBrush   = SolidColor(AppAccent.primary),
            modifier      = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MissingPageState(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Page not found",
            style = AppTypography.SectionTitleLight,
            color = AppColors.TextSecondary,
        )
        Text(
            "It may have been deleted.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
        Spacer(Modifier.height(AppSpacing.s4))
        Text(
            "Back",
            style = AppTypography.Button,
            color = AppAccent.primary,
            modifier = Modifier.clickable { onBack() },
        )
    }
}
