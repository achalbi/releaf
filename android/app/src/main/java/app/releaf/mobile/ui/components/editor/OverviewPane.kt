/*
 * OverviewPane.kt
 *
 * Alternative layout for the notepad and notebook-page editors — the
 * "Overview" side of the EditorModeToggle. Looks like the
 * `PageDetailView` (home → notebook → page) on purpose: same
 * `CaptureTabBar` + section-per-tab structure, but fed by the editor's
 * own mutable state so add/remove + body edits still persist through
 * the VM.
 *
 * Editable Overview tab: the notes body binds to the same `RichTextState`
 * Edit mode uses, so a change in either mode shows up live in the
 * other. The formatting toolbar pins to the bottom of the pane but is
 * only visible while the Overview tab is selected — the other tabs
 * manage plain-text / list / grid interactions that don't need inline
 * formatting.
 *
 * All seven capture modes are surfaced now — Voice was re-enabled once
 * `VoiceSection` landed. Each tab gets its own section body; Overview
 * shows the stat grid + notes preview card.
 */

package app.releaf.mobile.ui.components.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.components.CaptureTabBar
import app.releaf.mobile.ui.components.StatGrid
import app.releaf.mobile.ui.components.StatItem
import app.releaf.mobile.ui.components.StatTone
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import com.mikepenz.markdown.m3.Markdown
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Overview-mode editor pane. `CaptureTabBar` + per-tab scrolling
 * content. Title, eyebrow, and date chip all live at screen level
 * now (above this pane), so the pane itself only owns the tab row
 * and its content.
 */
@Composable
fun OverviewPane(
    richTextState: RichTextState,
    contacts: List<Contact>,
    todos: List<TodoItem>,
    locations: List<GeoLocation>,
    attachments: List<Attachment>,
    onAddContact: (String) -> Unit,
    onRemoveContact: (String) -> Unit,
    onAddTodo: (String) -> Unit,
    onToggleTodo: (String) -> Unit,
    onRemoveTodo: (String) -> Unit,
    onAddLocation: (Double, Double, String?) -> Unit,
    onRemoveLocation: (String) -> Unit,
    onAddPhoto: (String) -> Unit,
    onAddScan: (String, String?, List<Uri>) -> Unit,
    onAddVoiceNote: (String, Long) -> Unit,
    onTranscribeVoiceNote: (String, String?) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // All seven modes render; Voice got its own section once
    // `VoiceSection` landed. The tab row takes `CaptureMode.entries`
    // directly.
    val modes = remember { CaptureMode.entries.toList() }

    var selected by rememberSaveable(stateSaver = captureModeSaver) {
        mutableStateOf(CaptureMode.Overview)
    }
    var notesSheetOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // --- CaptureTabBar ---
        CaptureTabBar(
            selected = selected,
            onSelect = { selected = it },
            modes    = modes,
        )

        // --- Tab body ---
        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
            ) {
                when (selected) {
                    CaptureMode.Overview -> OverviewTab(
                        richTextState = richTextState,
                        contacts      = contacts,
                        todos         = todos,
                        locations     = locations,
                        attachments   = attachments,
                        onEditNotes   = { notesSheetOpen = true },
                    )
                    CaptureMode.Photos -> PhotosSection(
                        photos   = attachments.filter { it.type == Attachment.TYPE_PHOTO },
                        onAdd    = onAddPhoto,
                        onRemove = onRemoveAttachment,
                    )
                    CaptureMode.Scans -> ScansSection(
                        scans    = attachments.filter { it.type == Attachment.TYPE_SCAN },
                        onAdd    = onAddScan,
                        onRemove = onRemoveAttachment,
                    )
                    CaptureMode.Voice -> VoiceSection(
                        notes         = attachments.filter { it.type == Attachment.TYPE_VOICE },
                        onAdd         = onAddVoiceNote,
                        onTranscribed = onTranscribeVoiceNote,
                        onRemove      = onRemoveAttachment,
                    )
                    CaptureMode.Todo -> TodosSection(
                        todos    = todos,
                        onAdd    = onAddTodo,
                        onToggle = onToggleTodo,
                        onRemove = onRemoveTodo,
                    )
                    CaptureMode.Contacts -> ContactsSection(
                        contacts = contacts,
                        onAdd    = onAddContact,
                        onRemove = onRemoveContact,
                    )
                    CaptureMode.Location -> LocationSection(
                        locations = locations,
                        onAdd     = onAddLocation,
                        onRemove  = onRemoveLocation,
                    )
                }
                Spacer(Modifier.height(AppSpacing.s10))
            }
        }

    }

    // Full-screen editing sheet. Binds to the same RichTextState, so
    // anything typed here flows back into the Overview preview on
    // dismiss and into Edit mode automatically.
    if (notesSheetOpen) {
        NotesEditorSheet(
            richTextState = richTextState,
            onDismiss     = { notesSheetOpen = false },
        )
    }
}

/**
 * Overview-tab content: at-a-glance stat grid + tappable notes card.
 * Tapping the card opens the full-screen `NotesEditorSheet` — the
 * inline editor-in-ScrollView pattern proved too cramped alongside
 * the stats. Counts still update live from the current
 * `RichTextState` so the "Words" stat reflects the live buffer.
 */
@Composable
private fun OverviewTab(
    richTextState: RichTextState,
    contacts: List<Contact>,
    todos: List<TodoItem>,
    locations: List<GeoLocation>,
    attachments: List<Attachment>,
    onEditNotes: () -> Unit,
) {
    val photos = attachments.count { it.type == Attachment.TYPE_PHOTO }
    val scans  = attachments.count { it.type == Attachment.TYPE_SCAN }
    val bodyText = richTextState.annotatedString.text
    val words  = if (bodyText.isBlank()) 0 else bodyText.trim().split(Regex("\\s+")).size

    Text(
        "AT A GLANCE",
        style = AppTypography.Eyebrow,
        color = AppColors.TextSecondary,
    )

    StatGrid(items = listOf(
        StatItem(label = "Photos",   value = "$photos",         tone = StatTone.Coral),
        StatItem(label = "Scans",    value = "$scans",          tone = StatTone.Neutral),
        StatItem(label = "To-do",    value = "${todos.size}",   tone = StatTone.Green),
    ))
    StatGrid(items = listOf(
        StatItem(label = "Contacts", value = "${contacts.size}",  tone = StatTone.Info),
        StatItem(label = "Places",   value = "${locations.size}", tone = StatTone.Neutral),
        StatItem(label = "Words",    value = "$words",            tone = StatTone.Neutral),
    ))

    NotesPreviewCard(
        richTextState = richTextState,
        onTap         = onEditNotes,
    )
}

/**
 * Tap target that shows a rendered-markdown preview of the current
 * notes body (or an empty-state call to action). Tapping fires
 * `onTap` which the caller uses to open `NotesEditorSheet`. A small
 * pencil icon in the corner emphasises the affordance.
 */
@Composable
private fun NotesPreviewCard(
    richTextState: RichTextState,
    onTap: () -> Unit,
) {
    // Subscribe explicitly to `annotatedString` so the preview
    // recomposes when the sheet or Edit-mode editor mutates the state.
    // `toMarkdown()` alone doesn't register a Compose state read,
    // which is why the preview showed stale content after the user
    // first dismissed the editing sheet.
    val annotated = richTextState.annotatedString
    val markdown = remember(annotated) { richTextState.toMarkdown() }
    val isEmpty = annotated.text.trim().isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onTap)
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NOTES",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector        = Icons.Filled.Edit,
                contentDescription = "Edit notes",
                tint               = AppColors.Coral,
                modifier           = Modifier.size(18.dp),
            )
        }

        if (isEmpty) {
            Text(
                "Tap to write notes…",
                style = AppTypography.Body,
                color = AppColors.TextTertiary,
            )
        } else {
            Markdown(content = markdown)
        }
    }
}

// `OverviewTitleField` was removed — the title now lives in a row at
// screen level, shared between Edit and Overview modes. Keeping it
// here would duplicate the title surface when the user switches modes.

private val captureModeSaver: Saver<CaptureMode, String> = Saver(
    save    = { it.name },
    restore = { name -> runCatching { CaptureMode.valueOf(name) }.getOrDefault(CaptureMode.Overview) },
)
