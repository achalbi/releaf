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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.ui.components.BreadcrumbSegment
import app.releaf.mobile.ui.components.Breadcrumbs
import app.releaf.mobile.ui.components.DotGridBackground
import app.releaf.mobile.ui.components.editor.ContactsSection
import app.releaf.mobile.ui.components.editor.DrawingColorSaver
import app.releaf.mobile.ui.components.editor.DrawingMode
import app.releaf.mobile.ui.components.editor.DrawingModeSaver
import app.releaf.mobile.ui.components.editor.DrawingOverlay
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
import app.releaf.mobile.ui.components.editor.TodosSection
import app.releaf.mobile.ui.components.editor.VoiceSection
import app.releaf.mobile.ui.components.editor.WordCountFooter
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

@Composable
fun PageLocalEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PageLocalEditorViewModel = viewModel(factory = PageLocalEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val richTextState = rememberRichTextState()
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

    // Hydrate once on bootstrap. Both editor modes share `richTextState`,
    // so no mode-triggered re-hydration is needed.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            richTextState.setMarkdown(state.notes)
        }
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.updateNotes(richTextState.toMarkdown())
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
            viewModel.updateNotes(richTextState.toMarkdown())
            viewModel.save()
            onBack()
        }

        val pageTitleLabel = state.title.ifBlank {
            if (state.exists) "Untitled page" else "Page"
        }

        TopBar(
            segments = listOf(
                BreadcrumbSegment(label = "Notebook", onTap = popBack),
                BreadcrumbSegment(label = pageTitleLabel),
            ),
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
                    CircularProgressIndicator(color = AppColors.Coral)
                }
            }

            !state.exists -> {
                Box(Modifier.weight(1f)) {
                    MissingPageState(onBack = onBack)
                }
            }

            editorMode == EditorMode.OVERVIEW -> {
                Box(Modifier.weight(1f)) {
                    OverviewPane(
                        richTextState = richTextState,
                        contacts      = state.contacts,
                        todos         = state.todos,
                        locations     = state.locations,
                        attachments   = state.attachments,
                        onAddContact    = { name -> viewModel.addContact(name) },
                        onRemoveContact = viewModel::removeContact,
                        onAddTodo       = viewModel::addTodo,
                        onToggleTodo    = viewModel::toggleTodo,
                        onRemoveTodo    = viewModel::removeTodo,
                        onAddLocation   = { lat, lng, address -> viewModel.addLocation(lat, lng, address) },
                        onRemoveLocation = viewModel::removeLocation,
                        onAddPhoto      = { uri -> viewModel.addAttachment(Attachment.TYPE_PHOTO, uri) },
                        onAddScan       = { uri, preview, pageUris ->
                            viewModel.addScan(uri, preview, pageUris)
                        },
                        onAddVoiceNote  = { uri, durationMs ->
                            viewModel.addVoiceNote(uri, durationMs)
                        },
                        onTranscribeVoiceNote = { uri, transcript ->
                            viewModel.updateVoiceTranscript(uri, transcript)
                        },
                        onRemoveAttachment = viewModel::removeAttachment,
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
                        state         = state,
                        viewModel     = viewModel,
                        richTextState = richTextState,
                        drawingMode   = drawingMode,
                        penConfig     = penConfig,
                    )
                }
                WordCountFooter(text = richTextState.annotatedString.text)
                if (drawingMode == DrawingMode.Off) {
                    RichTextFormatBar(
                        state          = richTextState,
                        onEnterDrawing = {
                            focusManager.clearFocus()
                            drawingMode = DrawingMode.Pen
                        },
                    )
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
    richTextState: RichTextState,
    drawingMode: DrawingMode,
    penConfig: PenConfig,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        // Title is rendered at screen level (above this scroll) so it
        // stays pinned while the body scrolls. The drawing overlay
        // stacks directly above the rich-text editor — when mode is
        // Off it doesn't install a pointerInput modifier so text
        // editing stays unaffected. `defaultMinSize` gives a usable
        // drawing area even before the user has typed anything.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 360.dp),
        ) {
            NotesField(state = richTextState)
            DrawingOverlay(
                strokes         = state.strokes,
                mode            = drawingMode,
                penConfig       = penConfig,
                onStrokesChange = viewModel::updateStrokes,
                modifier        = Modifier.matchParentSize(),
            )
        }

        // Feature sections below the body. Each owns its own capture flow
        // (permissions, intent senders) and calls back into the VM for
        // the actual state changes.
        PhotosSection(
            photos   = state.attachments.filter { it.type == Attachment.TYPE_PHOTO },
            onAdd    = { uri -> viewModel.addAttachment(Attachment.TYPE_PHOTO, uri) },
            onRemove = viewModel::removeAttachment,
        )
        ScansSection(
            scans    = state.attachments.filter { it.type == Attachment.TYPE_SCAN },
            onAdd    = { uri, preview, pageUris ->
                viewModel.addScan(uri, preview, pageUris)
            },
            onRemove = viewModel::removeAttachment,
        )
        VoiceSection(
            notes    = state.attachments.filter { it.type == Attachment.TYPE_VOICE },
            onAdd          = { uri, durationMs -> viewModel.addVoiceNote(uri, durationMs) },
            onTranscribed  = { uri, transcript -> viewModel.updateVoiceTranscript(uri, transcript) },
            onRemove = viewModel::removeAttachment,
        )
        ContactsSection(
            contacts = state.contacts,
            onAdd    = { name -> viewModel.addContact(name) },
            onRemove = viewModel::removeContact,
        )
        TodosSection(
            todos    = state.todos,
            onAdd    = viewModel::addTodo,
            onToggle = viewModel::toggleTodo,
            onRemove = viewModel::removeTodo,
        )
        LocationSection(
            locations = state.locations,
            onAdd     = { lat, lng, address -> viewModel.addLocation(lat, lng, address) },
            onRemove  = viewModel::removeLocation,
        )

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
            cursorBrush   = SolidColor(AppColors.Coral),
            modifier      = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NotesField(state: RichTextState) {
    Box(Modifier.fillMaxWidth()) {
        if (state.annotatedString.text.isEmpty()) {
            Text(
                "Start typing…",
                style = AppTypography.Body,
                color = AppColors.TextTertiary,
            )
        }
        BasicRichTextEditor(
            state       = state,
            textStyle   = AppTypography.Body.copy(color = AppColors.TextPrimary),
            cursorBrush = SolidColor(AppColors.Coral),
            modifier    = Modifier.fillMaxWidth(),
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
            style = AppTypography.SectionTitle,
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
            color = AppColors.Coral,
            modifier = Modifier.clickable { onBack() },
        )
    }
}
