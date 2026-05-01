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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.components.CaptureTabBar
import app.releaf.mobile.ui.components.StatGrid
import app.releaf.mobile.ui.components.StatItem
import app.releaf.mobile.ui.components.StatTone
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
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
    onAddContact: (
        name: String,
        phone: String?,
        landline: String?,
        email: String?,
        title: String?,
        organization: String?,
        location: String?,
        website: String?,
    ) -> Unit,
    onEditContact: (
        id: String,
        name: String,
        phone: String?,
        landline: String?,
        email: String?,
        title: String?,
        organization: String?,
        location: String?,
        website: String?,
    ) -> Unit,
    onRemoveContact: (String) -> Unit,
    onAddTodo: (String) -> Unit,
    onToggleTodo: (String) -> Unit,
    onRemoveTodo: (String) -> Unit,
    onUpdateTodoPriority: (id: String, priority: Int) -> Unit,
    onReorderTodos: (newList: List<TodoItem>) -> Unit,
    /** Adds a location row and returns the new row's id so the section
     *  can refine its coordinates later via [onUpdateLocationCoords]. */
    onAddLocation: (lat: Double, lng: Double, address: String?) -> String,
    /** Background coordinate refinement — called when a precise GPS
     *  fix arrives after the row was already shown with the fast
     *  cached fix. No-op in the VM when the id has been removed. */
    onUpdateLocationCoords: (id: String, lat: Double, lng: Double) -> Unit,
    onRemoveLocation: (String) -> Unit,
    onAddPhoto: (String) -> Unit,
    onCombinePhotosToPdf: (pdfUri: String, previewUri: String?) -> Unit,
    onAddScan: (String, String?, List<Uri>) -> Unit,
    onAddVoiceNote: (String, Long) -> Unit,
    onTranscribeVoiceNote: (uri: String, transcript: String?, source: String?) -> Unit,
    /** "Add to notes" button on a transcribed voice-note card — the
     *  screen appends the text to whichever sub-page's RichTextState
     *  is currently visible. */
    onAddVoiceNoteTranscriptToNotes: (transcript: String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    /**
     * Full sub-page list + the in-flight RichTextStates keyed by
     * sub-page id. Hoisted from the screen so the fullscreen
     * `NotesEditorSheet` shares drafts with Edit mode (no round-trip
     * through the VM on mode switch).
     */
    subPages: List<SubPage>,
    richTextStates: Map<String, RichTextState>,
    onSubPageStrokesChange: (id: String, strokes: List<Stroke>) -> Unit,
    onSubPageTextBoxesChange: (id: String, textBoxes: List<app.releaf.mobile.data.notebook.TextBox>) -> Unit,
    onSubPageLedgerChange: (id: String, entries: List<app.releaf.mobile.data.notebook.LedgerEntry>) -> Unit = { _, _ -> },
    onSubPageLedgerTitleChange: (id: String, title: String) -> Unit = { _, _ -> },
    onAddSubPage: () -> String,
    onRemoveSubPage: (id: String) -> Unit,
    onSubPageBackgroundChange: (id: String, background: String) -> Unit,
    onSubPageBgScaleChange: (id: String, scale: Float) -> Unit,
    onPhotoExported: (uri: String) -> Unit,
    /** "Import PDF page to notes" — receives a `file://` URI for a JPG
     *  rendered out of the PDF viewer, appends a new sub-page with it
     *  as the drawable background. */
    onImportPageToNotes: (pageImageUri: String) -> Unit,
    /** "Import to notes" affordance inside the fullscreen photo
     *  viewer. Receives a `file://` URI to a fresh local copy of the
     *  tapped photo — the screen routes it to
     *  `viewModel.addSubPageFromImage(uri)` so the photo lands as a
     *  new sub-page ("note") in the same entry. Null leaves the
     *  affordance off (used by surfaces that don't expose this flow,
     *  e.g. the notebook page editor). */
    onImportPhotoToNotes: ((pageImageUri: String) -> Unit)? = null,
    onEditScan: (id: String, title: String?, categoryId: String?) -> Unit,
    /**
     * Initial tab to land on. Non-null when the user arrived via Quick
     * Capture and picked a specific capture mode (e.g. Photos, Voice);
     * null defaults to Overview. Only used for the initial state —
     * switching tabs afterward is purely local.
     */
    initialCaptureMode: CaptureMode? = null,
    /** When non-null, the matching section auto-fires its primary
     *  action on first composition. Drives the Capture-page tile
     *  flows: tapping a tile lands here on the section's tab AND
     *  kicks off the section's action (scan, record, GPS, open
     *  add-contact sheet, focus todo input, open notes sheet) so
     *  the user doesn't take a second tap inside the section. */
    autoLaunch: CaptureMode? = null,
    /** Optional right-aligned slot rendered on the same row as the
     *  "AT A GLANCE" eyebrow. Lets callers (the notepad editor) drop
     *  context-specific affordances (e.g. the category chip) into the
     *  Overview tab without OverviewPane having to know about them. */
    glanceTrailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // All seven modes render; Voice got its own section once
    // `VoiceSection` landed. The tab row takes `CaptureMode.entries`
    // directly.
    val modes = remember { CaptureMode.entries.toList() }

    // Tab state is driven by a HorizontalPager now so horizontal swipes
    // across the body animate between tabs — no separate `selected`
    // var, just `pagerState.currentPage` projected back onto
    // CaptureMode. `initialCaptureMode` resolves to `initialPage`;
    // subsequent state is saved by PagerState itself.
    val initialIndex = remember(initialCaptureMode) {
        (initialCaptureMode ?: CaptureMode.Overview)
            .let(modes::indexOf)
            .coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount   = { modes.size },
    )
    val scope = rememberCoroutineScope()
    val selectedMode = modes[pagerState.currentPage]

    /** Programmatic jump used by both the tab bar and the Overview
     *  stat-card taps. Animated so the transition matches a swipe
     *  gesture visually — no abrupt cuts. */
    val jumpToTab: (CaptureMode) -> Unit = { mode ->
        val idx = modes.indexOf(mode)
        if (idx >= 0 && idx != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(idx) }
        }
    }

    var notesSheetOpen by rememberSaveable { mutableStateOf(false) }

    // Once-per-pane gate around the autoLaunch flag.
    //
    // The HorizontalPager disposes off-screen pages and re-composes
    // them when they swing back into view (or get pre-composed on an
    // adjacent swipe). Without this gate, every "first composition"
    // of a section re-runs its `LaunchedEffect(Unit)` — so opening
    // the editor with `autoLaunch = Voice` could re-fire the Scans
    // scanner the next time the Scans tab is composed (e.g. when
    // swiping Voice → Todo, since Scans is the page after Todo and
    // gets pre-composed). Hoisting consumption to the pane level
    // keeps the section flags reactive: they read `effectiveAutoLaunch`
    // which becomes null after the first pass, so any later
    // re-composition of any section sees `autoLaunch = false`.
    var autoLaunchConsumed by rememberSaveable { mutableStateOf(false) }
    val effectiveAutoLaunch = autoLaunch.takeIf { !autoLaunchConsumed }
    LaunchedEffect(autoLaunch) {
        // The matching section captures `effectiveAutoLaunch == X` by
        // value at composition time and schedules its own
        // LaunchedEffect synchronously, so flipping the consumed flag
        // here doesn't race the section's first fire — but it does
        // null out the flag for any subsequent recomposition.
        if (autoLaunch != null) autoLaunchConsumed = true
    }

    // Auto-open the fullscreen NotesEditorSheet when the caller asked
    // for a Notes auto-launch (Capture page's Notes tile). Keyed by
    // Unit so it fires once per pane instance — config changes don't
    // re-pop the sheet after the user dismisses it.
    if (effectiveAutoLaunch == CaptureMode.Notes) {
        LaunchedEffect(Unit) { notesSheetOpen = true }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // --- CaptureTabBar ---
        CaptureTabBar(
            selected = selectedMode,
            onSelect = jumpToTab,
            modes    = modes,
        )

        // --- Tab body (swipeable) ---
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            val mode = modes[page]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
            ) {
                when (mode) {
                    // Overview + Notes share this tab for now —
                    // CaptureMode.Notes was added in CAPTURE_TAB_PLAN
                    // Phase 4 to give the editor a "type a quick
                    // note" landing tab; the dedicated text-first
                    // surface (DAILY_CAPTURE_UX §2.3) is a separate
                    // follow-up. Falling through to OverviewTab
                    // means the Notes tab shows the same preview
                    // until that lands, with `onEditNotes` already
                    // wired to open the full editor sheet.
                    CaptureMode.Overview,
                    CaptureMode.Notes -> OverviewTab(
                        richTextState  = richTextState,
                        contacts       = contacts,
                        todos          = todos,
                        locations      = locations,
                        attachments    = attachments,
                        subPageCount   = subPages.size,
                        onEditNotes    = { notesSheetOpen = true },
                        onJumpToTab    = jumpToTab,
                        glanceTrailing = glanceTrailing,
                    )
                    CaptureMode.Photos -> PhotosSection(
                        photos          = attachments.filter { it.type == Attachment.TYPE_PHOTO },
                        onAdd           = onAddPhoto,
                        onRemove        = onRemoveAttachment,
                        onCombineToPdf  = onCombinePhotosToPdf,
                        onImportToNotes = onImportPhotoToNotes,
                        autoLaunch      = effectiveAutoLaunch == CaptureMode.Photos,
                    )
                    CaptureMode.Scans -> ScansSection(
                        scans    = attachments.filter { it.type == Attachment.TYPE_SCAN },
                        onAdd    = onAddScan,
                        onRemove = onRemoveAttachment,
                        onImportPageToNotes = onImportPageToNotes,
                        onEditScan = onEditScan,
                        autoLaunch = effectiveAutoLaunch == CaptureMode.Scans,
                    )
                    CaptureMode.Voice -> VoiceSection(
                        notes                  = attachments.filter { it.type == Attachment.TYPE_VOICE },
                        onAdd                  = onAddVoiceNote,
                        onTranscribed          = onTranscribeVoiceNote,
                        onAddTranscriptToNotes = onAddVoiceNoteTranscriptToNotes,
                        onRemove               = onRemoveAttachment,
                        autoLaunch             = effectiveAutoLaunch == CaptureMode.Voice,
                    )
                    CaptureMode.Todo -> TodosSection(
                        todos            = todos,
                        onAdd            = onAddTodo,
                        onToggle         = onToggleTodo,
                        onRemove         = onRemoveTodo,
                        onUpdatePriority = onUpdateTodoPriority,
                        onReorder        = onReorderTodos,
                        autoLaunch       = effectiveAutoLaunch == CaptureMode.Todo,
                    )
                    CaptureMode.Contacts -> ContactsSection(
                        contacts   = contacts,
                        onAdd      = onAddContact,
                        onEdit     = onEditContact,
                        onRemove   = onRemoveContact,
                        autoLaunch = effectiveAutoLaunch == CaptureMode.Contacts,
                    )
                    CaptureMode.Location -> LocationSection(
                        locations      = locations,
                        onAdd          = onAddLocation,
                        onUpdateCoords = onUpdateLocationCoords,
                        onRemove       = onRemoveLocation,
                        autoLaunch     = effectiveAutoLaunch == CaptureMode.Location,
                    )
                }
                Spacer(Modifier.height(AppSpacing.s10))
            }
        }

    }

    // Full-screen editing sheet. Shares the caller's `richTextStates`
    // map, so typing here flows back into Edit mode on dismiss. The
    // sheet hosts its own `SubPageEditorPager` for multi-sub-page
    // navigation.
    if (notesSheetOpen && subPages.isNotEmpty()) {
        NotesEditorSheet(
            subPages                  = subPages,
            richTextStates            = richTextStates,
            onSubPageStrokesChange    = onSubPageStrokesChange,
            onSubPageTextBoxesChange  = onSubPageTextBoxesChange,
            onSubPageLedgerChange     = onSubPageLedgerChange,
            onSubPageLedgerTitleChange = onSubPageLedgerTitleChange,
            onAddSubPage              = onAddSubPage,
            onRemoveSubPage           = onRemoveSubPage,
            onSubPageBackgroundChange = onSubPageBackgroundChange,
            onSubPageBgScaleChange    = onSubPageBgScaleChange,
            onPhotoExported           = onPhotoExported,
            onDismiss                 = { notesSheetOpen = false },
        )
    }
}

/**
 * Overview-tab content: at-a-glance stat grid + tappable notes card.
 * Tapping the card opens the full-screen `NotesEditorSheet` — the
 * inline editor-in-ScrollView pattern proved too cramped alongside
 * the stats. All six cells derive from the VM's section lists —
 * photos/scans/voice from `attachments`, the rest from their own
 * lists — so they stay in sync with every add/remove tick.
 */
@Composable
private fun OverviewTab(
    richTextState: RichTextState,
    contacts: List<Contact>,
    todos: List<TodoItem>,
    locations: List<GeoLocation>,
    attachments: List<Attachment>,
    subPageCount: Int,
    onEditNotes: () -> Unit,
    /** Stat-card tap → jump the containing pager to the matching
     *  capture tab. Plumbed down from `OverviewPane` so each cell can
     *  route to the right destination without this composable knowing
     *  about `PagerState`. */
    onJumpToTab: (CaptureMode) -> Unit,
    glanceTrailing: (@Composable () -> Unit)? = null,
) {
    val photos = attachments.count { it.type == Attachment.TYPE_PHOTO }
    val scans  = attachments.count { it.type == Attachment.TYPE_SCAN }
    // Count voice-note attachments — surfaces alongside the other
    // capture counts so the user can see, at a glance, whether this
    // page has any audio. Replaces the older "Words" cell because
    // word-count already lives in the bottom-of-screen `WordCountFooter`
    // and duplicating it at the top added noise without new info.
    val voice  = attachments.count { it.type == Attachment.TYPE_VOICE }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            "AT A GLANCE",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        if (glanceTrailing != null) {
            Spacer(Modifier.weight(1f))
            glanceTrailing()
        }
    }

    StatGrid(items = listOf(
        StatItem(
            label   = "Photos",
            value   = "$photos",
            tone    = StatTone.Coral,
            onClick = { onJumpToTab(CaptureMode.Photos) },
        ),
        StatItem(
            label   = "Scans",
            value   = "$scans",
            tone    = StatTone.Neutral,
            onClick = { onJumpToTab(CaptureMode.Scans) },
        ),
        StatItem(
            label   = "To-do",
            value   = "${todos.size}",
            tone    = StatTone.Green,
            onClick = { onJumpToTab(CaptureMode.Todo) },
        ),
    ))
    StatGrid(items = listOf(
        StatItem(
            label   = "Contacts",
            value   = "${contacts.size}",
            tone    = StatTone.Info,
            onClick = { onJumpToTab(CaptureMode.Contacts) },
        ),
        StatItem(
            label   = "Places",
            value   = "${locations.size}",
            tone    = StatTone.Neutral,
            onClick = { onJumpToTab(CaptureMode.Location) },
        ),
        StatItem(
            label   = "Voice",
            value   = "$voice",
            tone    = StatTone.Neutral,
            onClick = { onJumpToTab(CaptureMode.Voice) },
        ),
    ))

    NotesPreviewCard(
        richTextState = richTextState,
        subPageCount  = subPageCount,
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
    subPageCount: Int,
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
            if (subPageCount > 1) {
                Spacer(Modifier.size(AppSpacing.s2))
                // Small pill showing how many sub-pages live behind this
                // preview, so users know there's more than what's
                // rendered (we only render the first sub-page here).
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.sm))
                        .background(AppAccent.primary.copy(alpha = 0.15f))
                        .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
                ) {
                    Text(
                        text  = "$subPageCount pages",
                        style = AppTypography.Meta,
                        color = AppAccent.primary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector        = Icons.Filled.Edit,
                contentDescription = "Edit notes",
                tint               = AppAccent.primary,
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
//
// The previous `captureModeSaver` was also removed: the selected tab
// is now held inside `PagerState`, which provides its own Saver, so
// the hand-rolled CaptureMode saver has no remaining callers.
