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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    viewModel: PageLocalEditorViewModel = viewModel(factory = PageLocalEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    // Overview (grid) is the default — matches the page-detail design
    // on the Home tab. Users flip to the list icon when they want the
    // single-scroll rich-text editor.
    var editorMode by rememberSaveable { mutableStateOf(EditorMode.OVERVIEW) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
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
    // "Add to notes" on a voice-note card — always append to the *last*
    // sub-page. Same rationale as NotepadEditorScreen: that's where
    // the user is usually writing, older sub-pages stay untouched.
    val addVoiceTranscriptToNotes: (String) -> Unit = addVoice@{ text ->
        // Skip ruled (ledger) sub-pages when picking the target — they
        // have no text body, so an append would be dropped on the
        // floor. Fall back to the latest non-ruled sub-page; Toast if
        // there isn't one so the user knows why the action no-op'd.
        val target = state.subPages.lastOrNull { it.background != app.releaf.mobile.data.notebook.SubPage.BG_RULED }
        if (target == null) {
            Toast.makeText(context, "Add a notes page first", Toast.LENGTH_SHORT).show()
            return@addVoice
        }
        val rts = richTextStates[target.id] ?: return@addVoice
        val existing = rts.toMarkdown()
        val separator = if (existing.isBlank()) "" else "\n\n"
        rts.setMarkdown(existing + separator + text.trim())
        viewModel.updateSubPageNotes(target.id, rts.toMarkdown())
        Toast.makeText(context, "Added to notes", Toast.LENGTH_SHORT).show()
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

        TopBar(
            segments = crumbs,
            showDelete = state.exists,
            editorMode = editorMode,
            onChangeMode = { newMode -> editorMode = newMode },
            onDelete = { showDeleteDialog = true },
        )

        // Title sits at screen level directly under the top bar so it
        // stays sticky above the mode content (both Edit scroll and
        // Overview's CaptureTabBar).
        if (!state.isLoading && state.exists) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4)
                    .padding(bottom = AppSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TitleField(
                    value         = state.title,
                    onValueChange = viewModel::updateTitle,
                    modifier      = Modifier.weight(1f),
                )
            }
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
    } // end outer Box (dot-grid canvas)

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
    segments: List<BreadcrumbSegment>,
    showDelete: Boolean,
    editorMode: EditorMode,
    onChangeMode: (EditorMode) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s3,
                bottom = AppSpacing.s3,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Breadcrumbs(segments = segments, modifier = Modifier.weight(1f))
        EditorModeIconToggle(mode = editorMode, onChange = onChangeMode)
        if (showDelete) {
            Spacer(Modifier.size(AppSpacing.s3))
            Text(
                "Delete",
                style = AppTypography.Button,
                color = AppColors.Danger,
                modifier = Modifier.clickable { onDelete() },
            )
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
