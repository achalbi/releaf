/*
 * NotepadEditorScreen.kt
 *
 * Single-entry WYSIWYG editor. The notes body is a
 * `BasicRichTextEditor` from `richeditor-compose` — bold, italic,
 * underline, and list styles render inline as the user types (no raw
 * `**` / `- ` syntax on screen). On save we serialize the state back to
 * canonical CommonMark via `state.toMarkdown()` so the schema is happy.
 *
 * Below the body the editor carries the same five feature sections the
 * page editor does — photos, scans, contacts, todos, location — so the
 * two surfaces feel identical. Section composables live in
 * `ui/components/editor/EditorSections.kt`.
 *
 * Because the edit view already renders formatting, there's no separate
 * preview mode — the top bar just has Back and Delete.
 *
 * Save semantics:
 *   - The VM holds the draft. Writes are fired on viewModelScope.
 *   - Back tap: we push the latest markdown into the VM, save, then pop.
 *   - DisposableEffect.onDispose does the same as a process-death safety
 *     net. Duplicates are idempotent — the VM's save() no-ops when nothing
 *     changed.
 */

package app.releaf.mobile.features.notepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notepad.NotepadCategory
import app.releaf.mobile.ui.components.AppToastHost
import app.releaf.mobile.ui.components.DotGridBackground
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.LeafDropdownDivider
import app.releaf.mobile.ui.components.LeafDropletGlyph
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.PageOverflowButton
import app.releaf.mobile.ui.components.PageViewMode
import app.releaf.mobile.ui.components.PageViewToggle
import app.releaf.mobile.ui.components.RoundIconButton
import app.releaf.mobile.ui.components.rememberToastState
import app.releaf.mobile.ui.components.editor.ContactsSection
import app.releaf.mobile.ui.components.editor.DrawingColorSaver
import app.releaf.mobile.ui.components.editor.DrawingMode
import app.releaf.mobile.ui.components.editor.DrawingModeSaver
import app.releaf.mobile.ui.components.editor.DrawingPalette
import app.releaf.mobile.ui.components.editor.DrawingThicknesses
import app.releaf.mobile.ui.components.editor.DrawingToolbar
import app.releaf.mobile.ui.components.editor.EditorMode
import app.releaf.mobile.ui.components.editor.LocationSection
import app.releaf.mobile.ui.components.editor.MergeSection
import app.releaf.mobile.ui.components.editor.MoveToNotebookSection
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
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.DailyPlant
import com.mohamedrejeb.richeditor.model.RichTextState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotepadEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /// Optional capture mode the screen should focus on opening. When
    /// non-null the editor opens directly in EDIT mode and scrolls to
    /// the matching feature section (Photos / Scans / Voice / Todo /
    /// Contacts / Location). Drives the "open page details at the
    /// right tab" affordance from the Recents new-entry picker.
    initialMode: app.releaf.mobile.ui.components.CaptureMode? = null,
    viewModel: NotepadEditorViewModel = viewModel(factory = NotepadEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    // Overview (grid) is the default — the at-a-glance tab bar is the
    // expected entry point. Users tap the list icon to drop into the
    // single-scroll rich-text editor when they actually want to write.
    // [initialMode] from the route layer doesn't change this default;
    // it's threaded into OverviewPane to preselect the matching tab.
    var editorMode by rememberSaveable { mutableStateOf(EditorMode.OVERVIEW) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showMergeSheet by rememberSaveable { mutableStateOf(false) }
    var showMoveSheet  by rememberSaveable { mutableStateOf(false) }
    // Category picker — opened by the category chip in the header.
    // Closed by default; tapping the chip opens the dialog with the
    // current category pre-selected.
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }
    // Plant-of-the-page info sheet — opened on tap of the leaf icon
    // next to the title. Reads the page's associated plant from the
    // VM so the modal shows the same plant that seeds the title +
    // description (instead of today's globally-rotating plant, which
    // would mismatch on every reopen).
    var showPlantInfo by rememberSaveable { mutableStateOf(false) }
    val plant = viewModel.pagePlant
    val focusManager = LocalFocusManager.current

    // Drawing overlay controls. Persisted strokes come from the VM;
    // these are the transient toolbar state (active pen vs eraser,
    // chosen color, etc.). rememberSaveable preserves them across
    // config changes.
    var drawingMode by rememberSaveable(stateSaver = DrawingModeSaver) {
        mutableStateOf(DrawingMode.Off)
    }
    var drawColor by rememberSaveable(stateSaver = DrawingColorSaver) {
        mutableStateOf(DrawingPalette[0])
    }
    var drawOpacity by rememberSaveable { mutableStateOf(1f) }
    var drawWidth by rememberSaveable { mutableStateOf(DrawingThicknesses[1].widthDp) }
    var drawNib by rememberSaveable { mutableStateOf(Stroke.NIB_BALLPOINT) }

    // One RichTextState per sub-page. See PageLocalEditorScreen twin
    // for the ownership rationale.
    val richTextStates = remember { mutableMapOf<String, RichTextState>() }
    state.subPages.forEach { sp ->
        richTextStates.getOrPut(sp.id) {
            RichTextState().apply { setMarkdown(sp.notes) }
        }
    }
    val liveIds = state.subPages.map { it.id }.toSet()
    richTextStates.keys.removeAll { it !in liveIds }

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
    // sub-page in the list. That's usually where the user is actively
    // writing (new sub-pages are appended on the right), and it keeps
    // older sub-pages' content immutable from voice actions. Mutates
    // the `RichTextState` directly so Edit mode reflects the append
    // immediately, then pushes through the VM so save() picks it up,
    // and surfaces a toast confirming the action happened.
    val addVoiceTranscriptToNotes: (String) -> Unit = addVoice@{ text ->
        // Walk backwards from the newest sub-page and pick the first one
        // that actually has a notes body. Ruled (ledger) sub-pages have
        // no text editor — appending to one would silently drop the
        // transcript — so we skip them and keep scanning. If the user
        // only has ruled pages in the entry, surface a toast so they
        // know why nothing happened.
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
    // chrome so the whole surface inherits the notebook feel (from the
    // Releaf Branding template). Kept behind `imePadding()` + the tap
    // handler so the keyboard avoidance and focus-clear behavior on
    // the real content layer are unchanged.
    //
    // `pointerInput { detectTapGestures }` on the outer column clears
    // title focus on any tap that reaches an "empty" region — taps on
    // interactive children (buttons, BasicTextField body, etc.) still
    // consume the gesture first, so their own handling isn't affected.
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
            // Flush every live sub-page's rich-text draft back into the VM
            // before triggering the save. The VM diff-checks and no-ops
            // when nothing changed.
            state.subPages.forEach { sp ->
                richTextStates[sp.id]?.let { rts ->
                    viewModel.updateSubPageNotes(sp.id, rts.toMarkdown())
                }
            }
            viewModel.save()
            onBack()
        }

        ComposedTopZone(
            editorMode        = editorMode,
            onChangeMode      = { newMode -> editorMode = newMode },
            // Merge / Move / Delete share the same gate as the prior
            // breadcrumb TopBar — surface them only once there's
            // something worth acting on. `showDelete` is the inner
            // guard for the destructive row; the rest of the menu
            // appears whenever `showActions` is true.
            showActions       = state.canSave || state.entry != null,
            showDelete        = state.entry != null,
            entryDate         = state.entryDate,
            onEntryDateChange = viewModel::updateEntryDate,
            onBack            = popBack,
            onOpenMerge       = { showMergeSheet = true },
            onOpenMove        = { showMoveSheet  = true },
            onDelete          = { showDeleteDialog = true },
        )

        // Title sits flush across the row at screen level so it scans
        // as the dominant editorial element. The plant-of-the-page
        // leaf icon hugs the right edge of this row so it sits
        // directly under the more-button column. Date picker lives on
        // the eyebrow above; category chip is rendered inline on the
        // Overview tab's "AT A GLANCE" row.
        if (!state.isLoading) {
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
                RoundIconButton(
                    icon               = Icons.Filled.Spa,
                    contentDescription = "Plant of the page",
                    onClick            = { showPlantInfo = true },
                    background         = AppColors.GreenSoft,
                    tint               = AppColors.ThemeGreenDeep,
                )
            }

            // Description sits flush against the title — italic serif
            // subtitle bound to state.description. Auto-seeded on
            // create as `(commonName) epithet` from the day's
            // Ayurvedic plant; the user can edit or clear it.
            DescriptionField(
                value         = state.description,
                onValueChange = viewModel::updateDescription,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4)
                    .padding(bottom = AppSpacing.s3),
            )
        }

        // Category picker dialog — driven by `showCategoryPicker`
        // state above. Renders predefined chips + a free-form text
        // field for a custom category, plus a Clear action.
        if (showCategoryPicker) {
            CategoryPickerDialog(
                current  = state.category,
                onPick   = { picked ->
                    viewModel.updateCategory(picked)
                    showCategoryPicker = false
                },
                onDismiss = { showCategoryPicker = false },
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
                        onImportPhotoToNotes      = { pageImageUri ->
                            viewModel.addSubPageFromImage(pageImageUri)
                        },
                        onEditScan                = { id, title, categoryId ->
                            viewModel.updateScan(id, title, categoryId)
                        },
                        // Right-aligned slot on the "AT A GLANCE" row.
                        // Hosts the category chip so the affordance
                        // sits in line with the section header instead
                        // of taking its own row above the body.
                        glanceTrailing = {
                            CategoryChip(
                                category = state.category,
                                onClick  = { showCategoryPicker = true },
                            )
                        },
                        // Forwarded from the route layer — when the
                        // Recents new-entry picker opened this editor
                        // with a specific mode, OverviewPane lands on
                        // the matching CaptureTabBar tab.
                        initialCaptureMode = initialMode,
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
                        onBack                      = onBack,
                    )
                }
                if (currentRts != null) {
                    WordCountFooter(text = currentRts.annotatedString.text)
                }
                if (drawingMode == DrawingMode.Off) {
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
        // Floating toast — layered above all screen content via the
        // same outer Box so it sits over sub-page strokes, the format
        // bar, etc. Padding leaves room for the format bar + bottom
        // sheets pinned to the bottom edge.
        AppToastHost(
            state   = toastState,
            padding = androidx.compose.foundation.layout.PaddingValues(
                start   = AppSpacing.s4,
                end     = AppSpacing.s4,
                bottom  = AppSpacing.s10,
                top     = AppSpacing.s4,
            ),
        )
    } // end outer Box (dot-grid canvas)

    // Destructive-action guard. Tapping Delete in the top bar only
    // opens the dialog; the actual soft-delete + pop only fires when
    // the user confirms.
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title            = { Text("Delete this entry?") },
            text             = {
                Text(
                    "It'll move to the trash and stop showing in the list. " +
                        "You can still undo this from the list screen.",
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

    // Top-bar entry point for Merge pages. Same MergeSection shown
    // inline at the end of the scroll, hoisted into a bottom sheet so
    // it's reachable without scrolling. Dismisses on a successful
    // merge by popping back (the entry is either refreshed or the
    // secondary was soft-deleted — either way the list is the right
    // place to land).
    if (showMergeSheet) {
        val otherEntries by viewModel.otherEntries.collectAsState()
        ModalBottomSheet(
            onDismissRequest = { showMergeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Canvas,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            ) {
                MergeSection(
                    otherEntries = otherEntries,
                    enabled      = state.canSave || state.entry != null,
                    onMerge      = { otherId, keepThisAsPrimary ->
                        viewModel.merge(otherId, keepThisAsPrimary) { _ ->
                            showMergeSheet = false
                            onBack()
                        }
                    },
                )
                Spacer(Modifier.height(AppSpacing.s4))
            }
        }
    }

    // Top-bar entry point for Move-to-notebook. Same shape as the
    // merge sheet — wraps the existing inline section, provides
    // consistent dismiss semantics.
    if (showMoveSheet) {
        val notebooks by viewModel.notebooks.collectAsState()
        val chaptersForPicker by viewModel.chaptersForPicker.collectAsState()
        ModalBottomSheet(
            onDismissRequest = { showMoveSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Canvas,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            ) {
                MoveToNotebookSection(
                    notebooks          = notebooks,
                    chaptersForPicker  = chaptersForPicker,
                    onOpenChaptersFor  = viewModel::openChaptersFor,
                    enabled            = state.canSave || state.entry != null,
                    onMove             = { chapterId ->
                        viewModel.moveToNotebook(chapterId) {
                            showMoveSheet = false
                            onBack()
                        }
                    },
                )
                Spacer(Modifier.height(AppSpacing.s4))
            }
        }
    }

    // Plant-of-the-day info sheet. Surfaced by tapping the title block
    // in the composed top zone. Same content / shape as the equivalent
    // sheet on PageDetailScreen so the editorial layer reads
    // consistently across both editors.
    if (showPlantInfo) {
        NotepadDailyPlantInfoSheet(
            plant     = plant,
            onDismiss = { showPlantInfo = false },
        )
    }
}

/**
 * Composed top zone — slot 1 leaf eyebrow (also tappable as Back),
 * slot 2 view-toggle pill, slot 3 plant-info leaf icon (taps open
 * the daily-plant modal drawer), slot 4 overflow menu.
 *
 * The big serif "plant title" hero that used to sit below this row
 * was removed: its content (plant name + common name) now seeds the
 * entry's actual title + description fields at create time, and the
 * full record (incl. "Used for …") is reachable via the leaf icon's
 * modal drawer. Keeping the hero would have duplicated those fields
 * and tied the daily plant to the editorial layer instead of to the
 * row itself.
 *
 * Behaviour preservation note: the notepad's existing Overview/Edit
 * mode is wired through the same toggle visual that the page-detail
 * header uses for List/Grid. We translate one to the other at the
 * binding (Overview ⇄ Grid, Edit ⇄ List) so the body still flips
 * between OverviewPane and EditorBody exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposedTopZone(
    editorMode: EditorMode,
    onChangeMode: (EditorMode) -> Unit,
    showActions: Boolean,
    showDelete: Boolean,
    entryDate: String,
    onEntryDateChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenMerge: () -> Unit,
    onOpenMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val parsedDate = remember(entryDate) { parseLocalDate(entryDate) }

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s4,
                bottom = AppSpacing.s3,
            ),
    ) {
        // Eyebrow is split into two tap regions:
        //   • leaf glyph + "NOTEPAD"  → back navigation
        //   • date label              → opens the date picker
        // Inlining the LeafEyebrow shape (rather than nesting two
        // LeafEyebrows) keeps the visual identical to other surfaces
        // while letting each half carry its own click handler.
        Row(
            modifier              = Modifier.weight(1f),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
                modifier              = Modifier.clickable { onBack() },
            ) {
                LeafDropletGlyph(
                    tint = AppColors.ThemeGreenPrimary,
                    size = 11.dp,
                )
                Text(
                    text     = "NOTEPAD",
                    style    = AppTypography.Eyebrow,
                    color    = AppColors.ThemeGreenDeep,
                    maxLines = 1,
                )
            }
            Text(
                text     = "·",
                style    = AppTypography.Eyebrow,
                color    = AppColors.ThemeGreenDeep,
            )
            Text(
                text     = formatEntryDateLabel(parsedDate).uppercase(),
                style    = AppTypography.Eyebrow,
                color    = AppColors.ThemeGreenDeep,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { showDatePicker = true },
            )
        }
        // EditorMode <-> PageViewMode translation. PageViewToggle
        // exposes a List/Grid pill; on this surface the pill drives
        // Edit/Overview instead. Visual matches the page-detail
        // header; behaviour matches what the prior
        // EditorModeIconToggle did.
        PageViewToggle(
            selected = if (editorMode == EditorMode.OVERVIEW) PageViewMode.Grid else PageViewMode.List,
            onSelect = { mode ->
                onChangeMode(if (mode == PageViewMode.Grid) EditorMode.OVERVIEW else EditorMode.EDIT)
            },
        )
        if (showActions) {
            PageOverflowButton {
                DropdownMenuItem(
                    text    = { Text("Merge with another page") },
                    onClick = onOpenMerge,
                )
                LeafDropdownDivider()
                DropdownMenuItem(
                    text    = { Text("Move to notebook") },
                    onClick = onOpenMove,
                )
                if (showDelete) {
                    LeafDropdownDivider()
                    DropdownMenuItem(
                        text    = { Text("Delete entry", color = AppColors.Danger) },
                        onClick = onDelete,
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        // M3's DatePicker works in UTC: selectedDateMillis is always
        // midnight-UTC on the picked day. We feed the calendar in at
        // UTC midnight on the current entry_date and convert back out
        // of UTC on confirm so YYYY-MM-DD stays stable across
        // timezones — matches EntryDateRow's picker exactly.
        val initialMillis = remember(entryDate) {
            parsedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        onEntryDateChange(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    }
                    showDatePicker = false
                }) { Text("Set", color = AppAccent.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Bottom-sheet that explains the day's rotated plant. Same shape and
 * copy as the equivalent sheet on PageDetailScreen — the editorial
 * surface should read identically whichever editor surfaced it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotepadDailyPlantInfoSheet(
    plant: DailyPlant,
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
                    style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp),
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
                NotepadPlantInfoBlock(title = "EPITHET",          body = plant.epithet)
                NotepadPlantInfoBlock(title = "TRADITIONAL USES", body = plant.usedFor)
            }

            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Close", color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun NotepadPlantInfoBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(text = title, style = AppTypography.Eyebrow, color = AppColors.TextSecondary)
        Text(text = body,  style = AppTypography.Body,    color = AppColors.TextPrimary)
    }
}

@Composable
private fun EditorBody(
    state: NotepadEditorUiState,
    viewModel: NotepadEditorViewModel,
    pagerState: PagerState,
    richTextStates: MutableMap<String, RichTextState>,
    drawingMode: DrawingMode,
    penConfig: PenConfig,
    onAddVoiceTranscriptToNotes: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()
    val otherEntries by viewModel.otherEntries.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        // Horizontal sub-pages. Pager manages its own side padding;
        // feature sections below are wrapped in a padded Column.
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

        // Feature sections — identical shape to the page editor. Each owns
        // its own capture flow (permissions, intent senders, etc.) and
        // calls back into the VM for the actual state changes.
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
            onImportToNotes = { pageImageUri ->
                viewModel.addSubPageFromImage(pageImageUri)
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
        MergeSection(
            otherEntries = otherEntries,
            // Merging requires a persisted row on this side; a brand-new
            // draft with nothing typed has no id to hand to the merge
            // transaction. The VM's `merge()` flushes drafts first, but we
            // still disable the CTA until there's meaningful content.
            enabled      = state.canSave || state.entry != null,
            onMerge      = { otherId, keepThisAsPrimary ->
                viewModel.merge(otherId, keepThisAsPrimary) { _ ->
                    // When this page was the secondary it's now soft-deleted;
                    // pop back to the list. When it was the primary the same
                    // nav is also correct — the list will refresh via Flow
                    // and the user can re-open the merged entry from there.
                    onBack()
                }
            },
        )

        val notebooks by viewModel.notebooks.collectAsState()
        val chaptersForPicker by viewModel.chaptersForPicker.collectAsState()
        MoveToNotebookSection(
            notebooks         = notebooks,
            chaptersForPicker = chaptersForPicker,
            onOpenChaptersFor = viewModel::openChaptersFor,
            // Same gate as merge — need something worth moving.
            enabled           = state.canSave || state.entry != null,
            onMove            = { chapterId ->
                viewModel.moveToNotebook(chapterId) { _ ->
                    // Source entry was soft-deleted; pop back to the
                    // notepad list. The destination notebook / chapter
                    // will surface the freshly-created page via its own
                    // Flow on the next load.
                    onBack()
                }
            },
        )
        } // end feature-sections Column

        // Extra clearance so the format bar doesn't crowd the last section
        // on short content.
        Spacer(Modifier.height(AppSpacing.s10))
    }
}

/**
 * Tappable row that shows which calendar date the entry is filed under.
 * Opens a Material 3 DatePicker so the user can back-date (the most common
 * reason to change this) or move a future-dated plan.
 *
 * Display uses "Today"/"Yesterday" for the two most common cases, otherwise
 * a short-form date ("Mon, Apr 15, 2026") so the row doesn't grow wide
 * under long locale names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDateRow(
    entryDate: String,
    onEntryDateChange: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val parsed = remember(entryDate) { parseLocalDate(entryDate) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.s2))
            .clickable { showPicker = true }
            .padding(vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            text  = formatEntryDateLabel(parsed),
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }

    if (showPicker) {
        // M3's DatePicker works in UTC: selectedDateMillis is always
        // midnight-UTC on the picked day. We feed the calendar day in as
        // UTC midnight on the current entry_date, and convert back out of
        // UTC on confirm — that keeps YYYY-MM-DD stable regardless of the
        // device timezone.
        val initialMillis = remember(entryDate) {
            parsed.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialMillis,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                        onEntryDateChange(date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    }
                    showPicker = false
                }) { Text("Set", color = AppAccent.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Best-effort — bad strings fall back to today to avoid crashing the UI. */
private fun parseLocalDate(iso: String): LocalDate = try {
    if (iso.isBlank()) LocalDate.now() else LocalDate.parse(iso)
} catch (_: Exception) {
    LocalDate.now()
}

private val shortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatEntryDateLabel(date: LocalDate): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (date) {
        today                    -> "Today"
        today.minusDays(1)       -> "Yesterday"
        else                     -> shortDateFormatter.format(date)
    }
}

@Composable
private fun TitleField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bump well beyond `PageTitle` (24sp) so the entry title reads as
    // the primary heading on the screen. `PageTitle.copy(fontSize)`
    // keeps the same serif treatment and weight; only the size grows.
    val titleStyle = AppTypography.PageTitle.copy(
        color    = AppColors.TextPrimary,
        fontSize = 34.sp,
    )
    val placeholderStyle = AppTypography.PageTitle.copy(
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
    // above, matching the "(cinnamon) the sweet bark" subtitle the
    // hero block used to render. Stays a free-text field so the user
    // can edit or clear the auto-seeded value.
    val descStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontStyle  = FontStyle.Italic,
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

/**
 * Compact pill that surfaces the entry's current category — predefined
 * names (Home / Work / …) get the GreenSoft accent; custom user-typed
 * names get a neutral tint so the predefined set reads as the
 * "official" options. Tapping the pill opens [CategoryPickerDialog].
 *
 * When `category` is null, falls back to a dashed-style "Add category"
 * placeholder. The picker still opens on tap.
 */
@Composable
private fun CategoryChip(
    category: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val display = NotepadCategory.displayName(category)
    // Custom and predefined chips share the same green-soft styling
    // — the user wanted both kinds to read identically rather than
    // the predefined set looking "official" and customs faded.
    val background = if (display == null) AppColors.CardSolid else AppColors.GreenSoft
    val tint       = if (display == null) AppColors.TextTertiary else AppColors.ThemeGreenDeep
    Row(
        modifier          = modifier
            .clip(RoundedCornerShape(AppSpacing.s3))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.Label,
            contentDescription = null,
            tint               = tint,
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text  = display ?: "Add category",
            style = AppTypography.Meta,
            color = tint,
        )
    }
}

/**
 * Picker dialog for the entry's category. Shows the six predefined
 * categories (NotepadCategory.Predefined) as tappable chips at the
 * top, then a free-form text field for typing a custom category. The
 * Clear action wipes the category back to null (uncategorised) and
 * dismisses; tapping a chip or typing + Save commits the choice.
 *
 * Predefined chips highlight when they match `current`; the custom
 * field pre-fills with `current` when it's a non-predefined value so
 * the user can edit-and-save instead of retyping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPickerDialog(
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val canonicalCurrent = NotepadCategory.displayName(current)
    val customSeed = if (canonicalCurrent != null && !NotepadCategory.isPredefined(canonicalCurrent)) {
        canonicalCurrent
    } else ""
    var customText by rememberSaveable { mutableStateOf(customSeed) }

    // Honour the user's display-order preference (Settings →
    // Categories) for the predefined chips so the picker matches the
    // chip row at the top of the notepad. `applyOrder(..., emptyList())`
    // returns the predefined-only ordered list — customs aren't
    // shown here (they're surfaced via the free-form text field
    // below).
    val pickerContext = androidx.compose.ui.platform.LocalContext.current
    val pickerPrefs = remember(pickerContext) {
        app.releaf.mobile.ui.theme.UiPreferences.get(pickerContext)
    }
    val pickerPrefsState by pickerPrefs.state.collectAsState()
    val predefinedOrdered = remember(pickerPrefsState.notepadCategoryOrder) {
        NotepadCategory.applyOrder(pickerPrefsState.notepadCategoryOrder, emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Choose category") },
        text             = {
            Column {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    verticalArrangement   = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    predefinedOrdered.forEach { name ->
                        val active = canonicalCurrent.equals(name, ignoreCase = true)
                        val bg   = if (active) AppAccent.primary       else AppColors.GreenSoft
                        val tint = if (active) androidx.compose.ui.graphics.Color.White else AppColors.ThemeGreenDeep
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppSpacing.s3))
                                .background(bg)
                                .clickable { onPick(name) }
                                .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = name, style = AppTypography.Meta, color = tint)
                        }
                    }
                }
                Spacer(Modifier.size(AppSpacing.s4))
                OutlinedTextField(
                    value         = customText,
                    onValueChange = { customText = it },
                    singleLine    = true,
                    label         = { Text("Custom category") },
                    placeholder   = { Text("e.g. Garden, Recipes") },
                    modifier      = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton    = {
            // Saves the typed custom category. Trimmed-empty falls
            // through to a no-op on the VM (updateCategory clamps to
            // null); use Clear to deliberately uncategorise.
            TextButton(
                onClick = {
                    val trimmed = customText.trim()
                    if (trimmed.isNotEmpty()) onPick(trimmed) else onDismiss()
                },
                enabled = customText.trim().isNotEmpty(),
            ) {
                Text("Save", color = AppAccent.primary)
            }
        },
        dismissButton    = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canonicalCurrent != null) {
                    TextButton(onClick = { onPick(null) }) {
                        Text("Clear", color = AppColors.Danger)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            }
        },
    )
}

