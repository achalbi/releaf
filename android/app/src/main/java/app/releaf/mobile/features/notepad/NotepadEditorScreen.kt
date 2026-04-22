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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun NotepadEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotepadEditorViewModel = viewModel(factory = NotepadEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val richTextState = rememberRichTextState()
    // Overview (grid) is the default — the at-a-glance tab bar is the
    // expected entry point. Users tap the list icon to drop into the
    // single-scroll rich-text editor when they actually want to write.
    var editorMode by rememberSaveable { mutableStateOf(EditorMode.OVERVIEW) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
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

    // Hydrate once on bootstrap. Both editor modes share `richTextState`,
    // so no mode-triggered re-hydration is needed — `setMarkdown` would
    // clobber in-flight edits.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            richTextState.setMarkdown(state.notes)
        }
    }

    // Save on backgrounding / process death / nav-away without a Back tap.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.updateNotes(richTextState.toMarkdown())
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
            // Both modes share the rich-text state, so flush is
            // unconditional.
            viewModel.updateNotes(richTextState.toMarkdown())
            viewModel.save()
            onBack()
        }

        TopBar(
            segments = listOf(
                BreadcrumbSegment(label = "Notepad", onTap = popBack),
                BreadcrumbSegment(label = formatEntryDateLabel(parseLocalDate(state.entryDate))),
            ),
            showDelete = state.entry != null,
            editorMode = editorMode,
            onChangeMode = { newMode -> editorMode = newMode },
            onDelete = { showDeleteDialog = true },
        )

        // Title + date share one row at screen level: title takes the
        // leading space, date chip sits flush right (directly under
        // the Delete button when shown). Living outside the mode
        // branches keeps both Edit and Overview showing the same
        // title surface sticky above their content.
        if (!state.isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4)
                    .padding(bottom = AppSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                TitleField(
                    value         = state.title,
                    onValueChange = viewModel::updateTitle,
                    modifier      = Modifier.weight(1f),
                )
                EntryDateRow(
                    entryDate         = state.entryDate,
                    onEntryDateChange = viewModel::updateEntryDate,
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
    state: NotepadEditorUiState,
    viewModel: NotepadEditorViewModel,
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
        // Title + date are rendered at screen level, sticky above the
        // scrolling body. Keeping them out of this scroll container
        // means they stay visible even when the user scrolls deep
        // into the notes or sections. The drawing overlay stacks
        // directly above the rich-text editor — when mode is Off it
        // doesn't install a pointerInput modifier so text editing is
        // unaffected. `defaultMinSize` gives a usable drawing area
        // even before the user has typed anything.
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

        // Feature sections — identical shape to the page editor. Each owns
        // its own capture flow (permissions, intent senders, etc.) and
        // calls back into the VM for the actual state changes.
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
            tint               = AppColors.Coral,
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
                }) { Text("Set", color = AppColors.Coral) }
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
