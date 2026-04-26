/*
 * NotepadEditorViewModel.kt
 *
 * Backs the single-entry notepad editor. Pulls an existing row from the
 * repo on first load (or starts blank for a new-entry route), tracks
 * draft state locally, and commits on demand. No debounced autosave yet
 * — the editor screen calls `save()` on back-press and again via
 * `DisposableEffect.onDispose` as a process-death safety net.
 *
 * The entryId argument is either a UUIDv7 or the sentinel `NEW_ENTRY_ID`.
 * Sentinel pattern keeps the NavHost arg type a plain non-nullable String.
 *
 * In addition to title + notes + entry date, the VM now owns the four
 * side-channel lists the page editor already uses — contacts, todos,
 * locations, attachments — so the notepad editor can show the same five
 * feature sections. All four live as JSON columns on `notepad_entries`;
 * the VM parses them on bootstrap, mutates in-memory lists, and
 * serializes back on save.
 */

package app.releaf.mobile.features.notepad

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.TextRecognizer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseStrokes
import app.releaf.mobile.data.notebook.parseSubPages
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import app.releaf.mobile.data.notepad.AyurvedicCatalog
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.notepad.NotepadRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotepadEditorUiState(
    val isLoading: Boolean = true,
    /** Null until the VM has loaded (or created) the backing row. */
    val entry: NotepadEntry? = null,
    val title: String = "",
    /**
     * Optional free-text subtitle shown directly below [title] in the
     * editor. Auto-seeded on create from the day's Ayurvedic plant in
     * `(commonName) epithet` form (e.g. `(cinnamon) the sweet bark`);
     * the user can edit or clear it freely. Mirrors `entry.description`.
     */
    val description: String = "",
    /**
     * Local-calendar date (YYYY-MM-DD) the entry is filed under. Defaults
     * to today for fresh drafts; mirrors `entry.entry_date` once loaded.
     * Editable via the date chip in the editor UI.
     */
    val entryDate: String = "",
    /**
     * Horizontal sub-pages for this entry. Always non-empty once
     * `isLoading` flips false — bootstrap seeds a single empty sub-page
     * for new drafts and synthesizes one from legacy columns on load.
     */
    val subPages: List<SubPage> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val locations: List<GeoLocation> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
) {
    /**
     * Is there anything worth persisting on back-nav? Title + any
     * non-empty sub-page content is the common case; any of the
     * feature-section lists being non-empty is also reason enough to
     * create the row — otherwise someone who adds a photo to a new
     * draft and taps back would lose it.
     */
    val canSave: Boolean
        get() = title.isNotBlank() ||
            subPages.any { it.notes.isNotBlank() || it.strokes.isNotEmpty() } ||
            contacts.isNotEmpty() || todos.isNotEmpty() ||
            locations.isNotEmpty() || attachments.isNotEmpty()
}

class NotepadEditorViewModel(
    application: Application,
    private val entryId: String,
    private val userId: String,
    private val repository: NotepadRepository,
    private val notebookRepository: NotebookRepository,
    private val chapterRepository: ChapterRepository,
    private val pageRepository: PageRepository,
    /** Phase-3.5 granular event capture. Nullable so tests + the
     *  rare caller without DI can still build the VM. */
    private val auditLogger: app.releaf.mobile.data.activity.AuditLogger? = null,
) : AndroidViewModel(application) {

    /** Breadcrumb for sub-event captures: notepad entries don't have
     *  a notebook/chapter parent, so the crumb is just the entry's
     *  title (or its date when untitled). Computed at log-time so
     *  it snapshots the live state. */
    private fun captureContext(): String {
        val s = _state.value
        return s.title.takeIf { it.isNotBlank() }
            ?: s.entry?.title?.takeIf { it.isNotBlank() }
            ?: s.entryDate.takeIf { it.isNotBlank() }
            ?: "Notepad"
    }

    /** Fire-and-forget granular audit. Tagged with the captured
     *  item's label and the parent breadcrumb so the timeline can
     *  render "Photo · Today's notepad". The parent entityId is
     *  the notepad row id when one exists; for a brand-new draft
     *  we drop the event (the row doesn't exist yet, the audit
     *  would orphan). */
    private fun logCapture(entityType: String, label: String?) {
        val parentId = _state.value.entry?.id ?: return
        val logger = auditLogger ?: return
        viewModelScope.launch {
            logger.log(
                action     = app.releaf.mobile.data.activity.AuditAction.Created,
                entityType = entityType,
                entityId   = parentId,
                title      = label,
                userId     = userId,
                context    = captureContext(),
            )
        }
    }

    private val _state = MutableStateFlow(NotepadEditorUiState())
    val state: StateFlow<NotepadEditorUiState> = _state.asStateFlow()

    /** Live list of the user's active notebooks. Feeds the destination
     *  picker in the "Move to notebook" card — never closes, so the
     *  modal opens to a fresh list every time. */
    val notebooks: StateFlow<List<NotebookEntity>> =
        notebookRepository.observeActive()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Chapters of the notebook the user just tapped inside the
     *  picker. Empty until a notebook is selected, then flips to that
     *  notebook's active chapter list. */
    private val _chapterPickerNotebookId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val chaptersForPicker: StateFlow<List<ChapterEntity>> =
        _chapterPickerNotebookId
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList())
                else chapterRepository.observeForNotebook(id)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun openChaptersFor(notebookId: String?) {
        _chapterPickerNotebookId.value = notebookId
    }

    /**
     * Guard for the create branch. Back-tap → `save()` and `onDispose` →
     * `save()` fire back-to-back on the main thread, and on a new-entry
     * draft `_state.entry` is still null when the second call runs (the
     * first coroutine hasn't completed yet). Without this flag we'd launch
     * a second `repository.create(...)` and end up with two rows for one
     * compose session.
     */
    private var hasPersistedNewEntry: Boolean = false

    init { bootstrap() }

    private fun bootstrap() {
        viewModelScope.launch {
            if (entryId == NEW_ENTRY_ID) {
                // Don't create the row until the user types something — the
                // list shouldn't show a blank entry if they back out. Seed
                // with one empty sub-page so the editor body always has
                // something to render. Title + description prepopulate
                // from today's plant so the user always sees something
                // meaningful in the header — repository.create() will
                // skip its own seeding because we now hand it non-blank
                // values, so the same plant ends up in the row.
                val (seedTitle, seedDescription) = seedTitleAndDescription("", "")
                _state.value = NotepadEditorUiState(
                    isLoading   = false,
                    title       = seedTitle,
                    description = seedDescription,
                    entryDate   = IsoClock.todayLocalDate(),
                    subPages    = listOf(SubPage(id = Uuidv7.generate())),
                )
            } else {
                val loaded = repository.findById(entryId)
                val parsed = loaded?.subPages?.parseSubPages().orEmpty()
                val effectiveSubPages = if (parsed.isNotEmpty()) {
                    parsed
                } else {
                    // Legacy row (pre-v3): synthesize one sub-page from the
                    // flat `notes` + `sketch_strokes` columns.
                    listOf(
                        SubPage(
                            id      = Uuidv7.generate(),
                            notes   = loaded?.notes.orEmpty(),
                            strokes = loaded?.sketchStrokes?.parseStrokes().orEmpty(),
                        )
                    )
                }
                // Backfill: when both fields load blank (older rows
                // created before the auto-seed landed, or rows the
                // user explicitly emptied), present today's plant as
                // the starting values. Lazy — we don't write them
                // back to the row until the user makes any other
                // edit that triggers save().
                val (seedTitle, seedDescription) = seedTitleAndDescription(
                    loaded?.title.orEmpty(),
                    loaded?.description.orEmpty(),
                )
                _state.value = NotepadEditorUiState(
                    isLoading   = false,
                    entry       = loaded,
                    title       = seedTitle,
                    description = seedDescription,
                    entryDate   = loaded?.entryDate ?: IsoClock.todayLocalDate(),
                    subPages    = effectiveSubPages,
                    contacts    = loaded?.contacts?.parseContacts().orEmpty(),
                    todos       = loaded?.todos?.parseTodos().orEmpty(),
                    locations   = loaded?.locations?.parseLocations().orEmpty(),
                    attachments = loaded?.attachments?.parseAttachments().orEmpty(),
                )
            }
        }
    }

    /**
     * Title + description prepopulation rule, applied at bootstrap on
     * both the new-draft and load paths.
     *
     * If BOTH fields arrive blank, fall back to the day's Ayurvedic
     * plant: title gets the Sanskrit/Hindi `name`, description gets
     * `(commonName) epithet · usedFor`. If either field carries
     * authored text we keep both verbatim — partial backfill would
     * mismatch the pair (e.g., a user-typed title alongside an
     * unrelated plant description).
     */
    private fun seedTitleAndDescription(
        loadedTitle: String,
        loadedDescription: String,
    ): Pair<String, String> {
        if (loadedTitle.isNotBlank() || loadedDescription.isNotBlank()) {
            return loadedTitle to loadedDescription
        }
        val plant = AyurvedicCatalog.forNewEntry()
        return plant.name to AyurvedicCatalog.formatDescription(plant)
    }

    fun updateTitle(value: String) {
        _state.value = _state.value.copy(title = value)
    }

    fun updateDescription(value: String) {
        _state.value = _state.value.copy(description = value)
    }

    /** Local YYYY-MM-DD; callers get the string back via the date picker. */
    fun updateEntryDate(value: String) {
        _state.value = _state.value.copy(entryDate = value)
    }

    // ------------------------- Sub-pages -------------------------

    /** Patch the notes body on a specific sub-page. */
    fun updateSubPageNotes(id: String, notes: String) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(notes = notes) else it
            },
        )
    }

    /** Patch the freehand strokes on a specific sub-page. */
    fun updateSubPageStrokes(id: String, strokes: List<Stroke>) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(strokes = strokes) else it
            },
        )
    }

    /** Replace the free-form text-box list on a specific sub-page. */
    fun updateSubPageTextBoxes(id: String, textBoxes: List<app.releaf.mobile.data.notebook.TextBox>) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(textBoxes = textBoxes) else it
            },
        )
    }

    /** Swap the background pattern on a specific sub-page. */
    fun updateSubPageBackground(id: String, background: String) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(background = background) else it
            },
        )
    }

    /** Adjust the background pattern's scale (0.5..2.0). */
    fun updateSubPageBgScale(id: String, scale: Float) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(bgScale = scale) else it
            },
        )
    }

    /** Replace the ledger-entry list on a specific sub-page. Used when
     *  the sub-page is in `BG_RULED` mode and its body renders the
     *  ledger form instead of the rich-text editor. */
    fun updateSubPageLedger(
        id: String,
        entries: List<app.releaf.mobile.data.notebook.LedgerEntry>,
    ) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(ledgerEntries = entries) else it
            },
        )
    }

    /** Update the free-form title shown above the ledger rows. */
    fun updateSubPageLedgerTitle(id: String, title: String) {
        _state.value = _state.value.copy(
            subPages = _state.value.subPages.map {
                if (it.id == id) it.copy(ledgerTitle = title) else it
            },
        )
    }

    /** Append a fresh empty sub-page. Returns the new id so the UI can flip to it. */
    fun addSubPage(): String {
        val new = SubPage(id = Uuidv7.generate())
        _state.value = _state.value.copy(subPages = _state.value.subPages + new)
        return new.id
    }

    /** Append a new sub-page with [imageUri] as its background. See the
     *  twin on PageLocalEditorViewModel for the full rationale. */
    fun addSubPageFromImage(imageUri: String): String {
        val new = SubPage(
            id                 = Uuidv7.generate(),
            backgroundImageUri = imageUri,
        )
        _state.value = _state.value.copy(subPages = _state.value.subPages + new)
        return new.id
    }

    /** Remove a sub-page. Keeps at least one so the editor body always has content. */
    fun removeSubPage(id: String) {
        val snapshot = _state.value
        if (snapshot.subPages.size <= 1) return
        _state.value = snapshot.copy(
            subPages = snapshot.subPages.filterNot { it.id == id },
        )
    }

    // ------------------------- Contacts -------------------------

    fun addContact(
        name: String,
        role: String? = null,
        phone: String? = null,
        landline: String? = null,
        email: String? = null,
        title: String? = null,
        organization: String? = null,
        location: String? = null,
        website: String? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val contact = Contact(
            id           = Uuidv7.generate(),
            name         = trimmed,
            role         = role?.trim()?.ifEmpty { null },
            phone        = phone?.trim()?.ifEmpty { null },
            landline     = landline?.trim()?.ifEmpty { null },
            email        = email?.trim()?.ifEmpty { null },
            title        = title?.trim()?.ifEmpty { null },
            organization = organization?.trim()?.ifEmpty { null },
            location     = location?.trim()?.ifEmpty { null },
            website      = website?.trim()?.ifEmpty { null },
        )
        _state.value = _state.value.copy(contacts = _state.value.contacts + contact)
        logCapture(app.releaf.mobile.data.activity.AuditEntity.Contact, contact.name)
    }

    /**
     * In-place edit of an existing contact. Keeps `id` + `role` (role is a
     * legacy artifact we don't expose in the editor) and overwrites the
     * user-facing fields with the sheet's new values. No-op if `id`
     * doesn't match any current contact.
     */
    fun updateContact(
        id: String,
        name: String,
        phone: String? = null,
        landline: String? = null,
        email: String? = null,
        title: String? = null,
        organization: String? = null,
        location: String? = null,
        website: String? = null,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.copy(
            contacts = _state.value.contacts.map { existing ->
                if (existing.id != id) existing else existing.copy(
                    name         = trimmed,
                    phone        = phone?.trim()?.ifEmpty { null },
                    landline     = landline?.trim()?.ifEmpty { null },
                    email        = email?.trim()?.ifEmpty { null },
                    title        = title?.trim()?.ifEmpty { null },
                    organization = organization?.trim()?.ifEmpty { null },
                    location     = location?.trim()?.ifEmpty { null },
                    website      = website?.trim()?.ifEmpty { null },
                )
            },
        )
    }

    fun removeContact(id: String) {
        _state.value = _state.value.copy(
            contacts = _state.value.contacts.filterNot { it.id == id },
        )
    }

    // --------------------------- Todos --------------------------

    fun addTodo(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val todo = TodoItem(id = Uuidv7.generate(), text = trimmed, done = false)
        _state.value = _state.value.copy(todos = _state.value.todos + todo)
        logCapture(app.releaf.mobile.data.activity.AuditEntity.Todo, trimmed)
    }

    fun toggleTodo(id: String) {
        _state.value = _state.value.copy(
            todos = _state.value.todos.map {
                if (it.id == id) it.copy(done = !it.done) else it
            },
        )
    }

    fun removeTodo(id: String) {
        _state.value = _state.value.copy(
            todos = _state.value.todos.filterNot { it.id == id },
        )
    }

    /** Set a todo's priority level (0 = none, 1 = low, 2 = medium, 3 = high). */
    fun updateTodoPriority(id: String, priority: Int) {
        _state.value = _state.value.copy(
            todos = _state.value.todos.map {
                if (it.id == id) it.copy(priority = priority) else it
            },
        )
    }

    /** Replace the todo list wholesale — used by drag-to-reorder. */
    fun reorderTodos(newList: List<TodoItem>) {
        _state.value = _state.value.copy(todos = newList)
    }

    // ------------------------- Locations ------------------------

    /**
     * Append a saved location. Returns the newly-assigned uuidv7 so
     * the caller can hand it back to [updateLocationCoords] once a
     * more precise GPS fix arrives — the LocationSection uses
     * `lastLocation` for the fast path (near-instant cached fix) and
     * refines the coordinates in the background via `getCurrentLocation`.
     */
    fun addLocation(lat: Double, lng: Double, address: String? = null): String {
        val loc = GeoLocation(
            id         = Uuidv7.generate(),
            lat        = lat,
            lng        = lng,
            address    = address,
            capturedAt = IsoClock.nowIso(),
        )
        _state.value = _state.value.copy(locations = _state.value.locations + loc)
        logCapture(
            app.releaf.mobile.data.activity.AuditEntity.Location,
            address ?: "%.4f, %.4f".format(lat, lng),
        )
        return loc.id
    }

    /**
     * Replace the coordinates on a previously-saved location. Used by
     * the "capture now, refine later" flow: LocationSection adds a
     * row from the cached `lastLocation` immediately, then patches
     * the precise coordinates in once `getCurrentLocation` resolves.
     * No-op when the id doesn't match (row deleted mid-fetch).
     */
    fun updateLocationCoords(id: String, lat: Double, lng: Double) {
        _state.value = _state.value.copy(
            locations = _state.value.locations.map {
                if (it.id == id) it.copy(lat = lat, lng = lng) else it
            },
        )
    }

    fun removeLocation(id: String) {
        _state.value = _state.value.copy(
            locations = _state.value.locations.filterNot { it.id == id },
        )
    }

    // ------------------------ Attachments -----------------------

    fun addAttachment(type: String, uri: String, previewUri: String? = null) {
        val att = Attachment(
            id         = Uuidv7.generate(),
            type       = type,
            uri        = uri,
            previewUri = previewUri,
            capturedAt = IsoClock.nowIso(),
        )
        _state.value = _state.value.copy(attachments = _state.value.attachments + att)
        // Map the attachment type → audit entity bucket so the
        // timeline shows "Photo" / "Scan" / "Voice" rather than a
        // generic "Attachment".
        val entityType = when (type) {
            Attachment.TYPE_PHOTO -> app.releaf.mobile.data.activity.AuditEntity.Photo
            Attachment.TYPE_SCAN  -> app.releaf.mobile.data.activity.AuditEntity.Scan
            Attachment.TYPE_VOICE -> app.releaf.mobile.data.activity.AuditEntity.Voice
            else                  -> null
        } ?: return
        logCapture(entityType, label = null)
    }

    /**
     * Scan-specific capture path. Persists the attachment up front with
     * no recognized text so the grid updates immediately, then fans out
     * ML Kit Text Recognition v2 across every scanned page on
     * `viewModelScope` so OCR outlives nav-away. Results are joined with
     * a `---` separator between pages (matches how users mentally
     * bucket multi-page scans) and patched onto the attachment once
     * inference completes. Blank joins are dropped — empty pages stay
     * empty rather than landing a stray separator string on the row.
     */
    /** Overwrite the title + category overrides on a scan. See the
     *  twin on PageLocalEditorViewModel for the full contract. */
    fun updateScan(id: String, title: String?, categoryId: String?) {
        val cleanedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(
            attachments = _state.value.attachments.map { att ->
                if (att.id == id && att.type == Attachment.TYPE_SCAN) {
                    att.copy(title = cleanedTitle, categoryId = categoryId)
                } else att
            },
        )
    }

    fun addScan(primaryUri: String, previewUri: String?, pageUrisForOcr: List<Uri>) {
        val id = Uuidv7.generate()
        val att = Attachment(
            id         = id,
            type       = Attachment.TYPE_SCAN,
            uri        = primaryUri,
            previewUri = previewUri,
            capturedAt = IsoClock.nowIso(),
        )
        _state.value = _state.value.copy(attachments = _state.value.attachments + att)
        logCapture(app.releaf.mobile.data.activity.AuditEntity.Scan, label = null)
        if (pageUrisForOcr.isEmpty()) return

        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val joined = pageUrisForOcr
                .mapNotNull { TextRecognizer.recognize(ctx, it) }
                .joinToString("\n\n---\n\n")
                .takeIf { it.isNotBlank() }
                ?: return@launch
            _state.value = _state.value.copy(
                attachments = _state.value.attachments.map { existing ->
                    if (existing.id == id) existing.copy(recognizedText = joined) else existing
                },
            )
        }
    }

    /**
     * Voice-note variant that also stamps the clip duration on the
     * attachment. Separate from [addAttachment] so the more common
     * photo/scan call site doesn't grow a trailing nullable. Duration
     * is captured by the recorder sheet via `MediaRecorder.resume()`
     * timing so we don't have to re-probe the file to render list
     * rows.
     */
    fun addVoiceNote(uri: String, durationMs: Long) {
        val att = Attachment(
            id         = Uuidv7.generate(),
            type       = Attachment.TYPE_VOICE,
            uri        = uri,
            previewUri = null,
            capturedAt = IsoClock.nowIso(),
            durationMs = durationMs,
        )
        _state.value = _state.value.copy(attachments = _state.value.attachments + att)
        // Tag with the clip duration so the timeline can render
        // "Voice note · 1:24" as the title.
        val seconds = (durationMs / 1000).toInt()
        val label = "%d:%02d".format(seconds / 60, seconds % 60)
        logCapture(app.releaf.mobile.data.activity.AuditEntity.Voice, label)
    }

    /**
     * Patch the transcript on an existing voice-note attachment.
     * Called from `VoiceSection` once `SpeechRecognizer` has flushed
     * its final `onResults` — recognition runs asynchronously alongside
     * the recorder, so we persist the attachment up front and swap in
     * the transcript once it's ready. Keyed by `uri` (which embeds a
     * uuidv7 and is unique) so the section doesn't need to track the
     * newly-assigned id across the async hop. Mirrors the iOS twin.
     */
    fun updateVoiceTranscript(uri: String, transcript: String?, source: String?) {
        val cleaned = transcript?.takeIf { it.isNotBlank() }
        val cleanedSource = if (cleaned != null) source else null
        _state.value = _state.value.copy(
            attachments = _state.value.attachments.map { existing ->
                if (existing.uri == uri) {
                    existing.copy(transcript = cleaned, transcriptSource = cleanedSource)
                } else existing
            },
        )
    }

    fun removeAttachment(id: String) {
        val snapshot = _state.value
        val target = snapshot.attachments.find { it.id == id } ?: return
        val remaining = snapshot.attachments.filterNot { it.id == id }
        val stillReferenced = remaining.any {
            it.uri == target.uri || it.previewUri == target.uri
        }
        if (!stillReferenced) releaseAttachmentUri(target)
        _state.value = snapshot.copy(attachments = remaining)
    }

    /**
     * Release whatever the attachment still owns off-disk. Photos live
     * in MediaStore (release the persistable URI permission we took on
     * pick); scans live in our own filesDir (delete the PDF + preview
     * JPEG files we copied in from ML Kit's cache). See the twin in
     * PageLocalEditorViewModel for the matching logic on the other
     * editor surface.
     */
    private fun releaseAttachmentUri(att: Attachment) {
        val app = getApplication<Application>()
        when (att.type) {
            Attachment.TYPE_PHOTO -> {
                // Photos can be MediaStore content:// (picker) or
                // file:// (our "Save to Photos" export). Release
                // permission if possible, then delete the local
                // file if we own it — see the twin comment in
                // PageLocalEditorViewModel for the full rationale.
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(
                        Uri.parse(att.uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                AttachmentStorage.deleteIfLocal(att.uri)
            }
            Attachment.TYPE_SCAN -> {
                AttachmentStorage.deleteIfLocal(att.uri)
                att.previewUri?.let { AttachmentStorage.deleteIfLocal(it) }
            }
            Attachment.TYPE_VOICE -> {
                // Voice notes are MediaRecorder-written M4As sitting in our
                // own attachments dir — same ownership as scans, so cleanup
                // goes through the same helper.
                AttachmentStorage.deleteIfLocal(att.uri)
            }
        }
    }

    /**
     * Persist the current draft on the VM's scope. Fire-and-forget: callers
     * don't need a coroutine. The screen invokes this both on back-press and
     * on `onDispose`, and the list screen picks up the new row through its
     * Room Flow subscription — so there's no timing coordination needed.
     *
     * Safe to call repeatedly; no-ops when there's nothing to save, and
     * collapses the back-tap → onDispose double-fire into a single write by
     * advancing `_state.entry` to the target row synchronously before
     * launching the IO (for updates) or gating on [hasPersistedNewEntry] (for
     * creates). See the KDoc on [hasPersistedNewEntry] for the create path.
     */
    fun save() {
        val snapshot = _state.value
        val existing = snapshot.entry

        val contactsJson    = snapshot.contacts.toJsonString()
        val todosJson       = snapshot.todos.toJsonString()
        val locationsJson   = snapshot.locations.toJsonString()
        val attachmentsJson = snapshot.attachments.toJsonString()
        val subPagesJson    = snapshot.subPages.toJsonString()

        // Flatten sub-pages into the legacy flat columns so FTS indexing
        // and any pre-v3 readers keep working. See the twin comment in
        // PageLocalEditorViewModel.
        val joinedNotes      = snapshot.subPages.joinToString("\n\n") { it.notes }
        val firstStrokesJson = snapshot.subPages.firstOrNull()
            ?.strokes.orEmpty().toJsonString()

        if (existing == null) {
            if (!snapshot.canSave) return
            if (hasPersistedNewEntry) return
            hasPersistedNewEntry = true
            viewModelScope.launch {
                val created = repository.create(
                    userId        = userId,
                    title         = snapshot.title,
                    notes         = joinedNotes,
                    entryDate     = snapshot.entryDate.ifBlank { IsoClock.todayLocalDate() },
                    description   = snapshot.description,
                    contacts      = contactsJson,
                    locations     = locationsJson,
                    todos         = todosJson,
                    attachments   = attachmentsJson,
                    sketchStrokes = firstStrokesJson,
                    subPages      = subPagesJson,
                )
                // Once the row exists, route any subsequent edits through the
                // update branch below. State fields are preserved as-is.
                _state.value = _state.value.copy(entry = created)
            }
            return
        }

        val titleChanged       = (existing.title.orEmpty()) != snapshot.title
        val descriptionChanged = (existing.description.orEmpty()) != snapshot.description
        val notesChanged       = existing.notes != joinedNotes
        val entryDateChanged   = snapshot.entryDate.isNotBlank() &&
            existing.entryDate != snapshot.entryDate
        val contactsChanged    = existing.contacts != contactsJson
        val todosChanged       = existing.todos != todosJson
        val locationsChanged   = existing.locations != locationsJson
        val attachmentsChanged = existing.attachments != attachmentsJson
        val strokesChanged     = existing.sketchStrokes != firstStrokesJson
        val subPagesChanged    = existing.subPages != subPagesJson
        if (!titleChanged && !descriptionChanged && !notesChanged && !entryDateChanged &&
            !contactsChanged && !todosChanged &&
            !locationsChanged && !attachmentsChanged &&
            !strokesChanged && !subPagesChanged
        ) return

        val updated = existing.copy(
            title         = snapshot.title.ifBlank { null },
            description   = snapshot.description.ifBlank { null },
            notes         = joinedNotes,
            entryDate     = snapshot.entryDate.ifBlank { existing.entryDate },
            contacts      = contactsJson,
            todos         = todosJson,
            locations     = locationsJson,
            attachments   = attachmentsJson,
            sketchStrokes = firstStrokesJson,
            subPages      = subPagesJson,
        )
        // Advance the baseline synchronously so a second save() in this tick
        // (back-tap → onDispose) diffs against the target and no-ops.
        _state.value = snapshot.copy(entry = updated)

        viewModelScope.launch {
            repository.save(updated)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val existing = _state.value.entry ?: run { onDeleted(); return }
        viewModelScope.launch {
            repository.softDelete(existing.id)
            onDeleted()
        }
    }

    /**
     * Convert this notepad entry into a notebook page inside [chapterId].
     * Fires [onMoved] with the new page's id once the DB rows have been
     * committed — callers use it to navigate the editor over to the
     * freshly-created notebook page. No-op when the entry is still a
     * fresh unsaved draft with nothing worth keeping.
     *
     * Flow:
     *   1. Flush the draft via [save] so the latest edits are in the DB
     *      (`save()` is idempotent — a no-op when there's nothing new).
     *   2. Re-read the persisted row so the copy picks up whatever the
     *      save just wrote (including the id assigned during create).
     *   3. Copy every field the two surfaces share (sub-pages, todos,
     *      contacts, locations, attachments, strokes) into a new
     *      `PageEntity` under the chosen chapter.
     *   4. Soft-delete the source notepad entry so the list screen
     *      doesn't show both the notebook page and the original row.
     */
    fun moveToNotebook(chapterId: String, onMoved: (newPageId: String) -> Unit) {
        val snapshot = _state.value
        if (snapshot.entry == null && !snapshot.canSave) {
            // Fresh empty draft — nothing to move.
            return
        }
        save()
        viewModelScope.launch {
            val sourceId = _state.value.entry?.id ?: return@launch
            val fresh = repository.findById(sourceId) ?: return@launch
            val newPage = pageRepository.createFromNotepadEntry(
                chapterId = chapterId,
                source    = fresh,
            )
            repository.softDelete(sourceId)
            onMoved(newPage.id)
        }
    }

    /**
     * Live list of saved notepad entries that could serve as the
     * "other page" in a merge — every active row except the one this
     * editor is currently showing. Kept as a hot StateFlow so the
     * MergeSection and its picker render synchronously on first open.
     * New/unsaved drafts still surface every other entry (nothing to
     * exclude yet).
     */
    val otherEntries: StateFlow<List<NotepadEntry>> =
        combine(
            repository.observeActive(userId),
            _state.map { it.entry?.id },
        ) { entries, selfId ->
            if (selfId == null) entries else entries.filterNot { it.id == selfId }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Merge this entry with [otherId]. When [keepThisAsPrimary] is true,
     * the other entry is folded into this one (this page stays open,
     * just refreshed). When false, this entry is folded into the other
     * and removed — the caller should navigate away because the editor
     * is now showing a soft-deleted row. [onDone] fires on the main
     * thread with the surviving row's id so callers can route.
     *
     * Flushes the current draft first so unsaved edits on this page
     * are included in the merge rather than being dropped on the floor.
     */
    fun merge(otherId: String, keepThisAsPrimary: Boolean, onDone: (String) -> Unit) {
        val snapshot = _state.value
        // The merge targets persisted rows; if this side is a fresh
        // draft, flush it first. `save()` is idempotent so calling it
        // here is safe even for already-saved entries (usually a no-op).
        save()
        viewModelScope.launch {
            // `save()` is fire-and-forget; re-read state after it
            // advances `_state.entry` so we have the real primary id.
            val selfId = _state.value.entry?.id ?: return@launch
            val primaryId   = if (keepThisAsPrimary) selfId  else otherId
            val secondaryId = if (keepThisAsPrimary) otherId else selfId
            val ok = repository.merge(primaryId, secondaryId)
            if (ok) onDone(primaryId)
        }
    }

    companion object {
        const val ARG_ENTRY_ID = "entryId"

        /** Sentinel passed as the path arg when creating a new entry. */
        const val NEW_ENTRY_ID = "new"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val entryId = checkNotNull(savedState.get<String>(ARG_ENTRY_ID)) {
                    "NotepadEditorViewModel missing $ARG_ENTRY_ID"
                }
                val userId = (app.authStore.state.value as? AuthState.SignedIn)
                    ?.session?.userId
                    ?: error("NotepadEditorViewModel created while not signed in")
                NotepadEditorViewModel(
                    application        = app,
                    entryId            = entryId,
                    userId             = userId,
                    repository         = app.notepadRepository,
                    notebookRepository = app.notebookRepository,
                    chapterRepository  = app.chapterRepository,
                    pageRepository     = app.pageRepository,
                    auditLogger        = app.auditLogger,
                )
            }
        }
    }
}
