/*
 * PageLocalEditorViewModel.kt
 *
 * Backs the Room-backed page editor. Page rows are pre-created by the
 * notebook-detail FAB (see NotebookLocalDetailViewModel.createPage) and this
 * VM then receives an existing id — so there's no "NEW" sentinel here, unlike
 * NotepadEditorViewModel.
 *
 * In addition to title + notes, the VM now owns the four side-channel
 * lists that the page editor's feature sections manipulate:
 *   - contacts    (people tagged on the page)
 *   - todos       (checklist items)
 *   - locations   (places attached to the page)
 *   - attachments (photos + document scans)
 *
 * All four live in JSON columns on the `pages` row; the VM parses them
 * on bootstrap, mutates in-memory lists, and serializes back on save.
 *
 * Save semantics mirror NotepadEditorViewModel: draft is held locally, a
 * single repo write is committed on back-tap and again via onDispose as a
 * process-death safety net. No-ops when nothing changed.
 *
 * Delete: soft-deletes the page and calls `onDeleted` (the screen pops the
 * backstack on that callback).
 */

package app.releaf.mobile.features.notebook

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
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageEntity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PageLocalEditorUiState(
    val isLoading: Boolean = true,
    val page: PageEntity? = null,
    val chapter: ChapterEntity? = null,
    val notebook: NotebookEntity? = null,
    val title: String = "",
    /**
     * Horizontal sub-pages inside this page. Each sub-page owns its own
     * notes body + freehand strokes; the user pager-swipes between them
     * in Edit mode. Always non-empty once `isLoading` flips false — the
     * bootstrap synthesizes a single sub-page for legacy or fresh rows.
     */
    val subPages: List<SubPage> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val locations: List<GeoLocation> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
) {
    /** True if the page id resolved to a live row. */
    val exists: Boolean get() = page != null
}

class PageLocalEditorViewModel(
    application: Application,
    private val pageId: String,
    private val repository: PageRepository,
    private val chapterRepository: ChapterRepository,
    private val notebookRepository: NotebookRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PageLocalEditorUiState())
    val state: StateFlow<PageLocalEditorUiState> = _state.asStateFlow()

    init { bootstrap() }

    private fun bootstrap() {
        viewModelScope.launch {
            val loaded = repository.findById(pageId)
            val chapter = loaded?.let { chapterRepository.findById(it.chapterId) }
            val notebook = chapter?.let { notebookRepository.findById(it.notebookId) }
            val parsed = loaded?.subPages?.parseSubPages().orEmpty()
            val effectiveSubPages = if (parsed.isNotEmpty()) {
                parsed
            } else {
                // Legacy row (pre-v3): synthesize one sub-page from the flat
                // `notes` + `sketch_strokes` columns. Fresh rows land here
                // too with empty content, which gives us the guaranteed
                // "always at least one sub-page" invariant.
                listOf(
                    SubPage(
                        id      = Uuidv7.generate(),
                        notes   = loaded?.notes.orEmpty(),
                        strokes = loaded?.sketchStrokes?.parseStrokes().orEmpty(),
                    )
                )
            }
            _state.value = PageLocalEditorUiState(
                isLoading   = false,
                page        = loaded,
                chapter     = chapter,
                notebook    = notebook,
                title       = loaded?.title.orEmpty(),
                subPages    = effectiveSubPages,
                contacts    = loaded?.contacts?.parseContacts().orEmpty(),
                todos       = loaded?.todos?.parseTodos().orEmpty(),
                locations   = loaded?.locations?.parseLocations().orEmpty(),
                attachments = loaded?.attachments?.parseAttachments().orEmpty(),
            )
        }
    }

    fun updateTitle(value: String) {
        _state.value = _state.value.copy(title = value)
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

    /**
     * Append a new sub-page whose background is the image at [imageUri].
     * Used by the "Import PDF page to notes" flow so the user lands on
     * a fresh surface with the scanned document behind the cursor,
     * ready for pen / highlighter markup.
     */
    fun addSubPageFromImage(imageUri: String): String {
        val new = SubPage(
            id                 = Uuidv7.generate(),
            backgroundImageUri = imageUri,
        )
        _state.value = _state.value.copy(subPages = _state.value.subPages + new)
        return new.id
    }

    /**
     * Remove a sub-page. No-ops when it would leave the page with zero
     * sub-pages — the invariant is "always at least one" so the editor
     * body always has something to render.
     */
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
    }

    /** Twin of NotepadEditorViewModel.updateContact — keeps id + role
     *  and overwrites the user-facing fields. No-op for unknown ids. */
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

    /** See the twin on NotepadEditorViewModel for the full explainer —
     *  returns the new id so the caller can refine coordinates later. */
    fun addLocation(lat: Double, lng: Double, address: String? = null): String {
        val loc = GeoLocation(
            id         = Uuidv7.generate(),
            lat        = lat,
            lng        = lng,
            address    = address,
            capturedAt = IsoClock.nowIso(),
        )
        _state.value = _state.value.copy(locations = _state.value.locations + loc)
        return loc.id
    }

    /** Replace the coordinates on a previously-saved location — the
     *  "refine precise coords in background" half of the two-stage
     *  LocationSection capture flow. */
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
    }

    /**
     * Scan-specific capture path. Persists the attachment up front with
     * no recognized text so the grid updates immediately, then fans out
     * ML Kit Text Recognition v2 across every scanned page on
     * `viewModelScope` so OCR outlives nav-away. Results are joined with
     * a `---` separator between pages and patched onto the attachment
     * once inference completes. Mirrors the twin on
     * NotepadEditorViewModel.
     */
    /**
     * Overwrite the title and / or category on a scan attachment.
     * Both are user-set overrides — blank title or null categoryId
     * clears that override so the section falls back to the derived
     * value (OCR first-line for title, `fromFirstWord` for category).
     */
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
     * Voice-note variant that stamps the clip duration on the
     * attachment at capture time so list rows can render "0:42"
     * without having to probe the file. Mirrors the twin on
     * NotepadEditorViewModel.
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
    }

    /**
     * Patch the transcript on an existing voice-note attachment. See
     * the twin on NotepadEditorViewModel for the full explainer.
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

        // Only release the URI permission if no other attachment in the list
        // still points at the same URI. Defensive against the rare case where
        // the same photo is picked into the page twice.
        val stillReferenced = remaining.any {
            it.uri == target.uri || it.previewUri == target.uri
        }
        if (!stillReferenced) releaseAttachmentUri(target)

        _state.value = snapshot.copy(attachments = remaining)
    }

    /**
     * Release whatever the attachment still owns off-disk. The exact
     * action depends on where its bytes live:
     *
     * - PHOTO: we took a persistable URI permission on the MediaStore
     *   URI when picking; release it. Android 13+ picker URIs sometimes
     *   ship with auto-managed grants that throw on release, so wrap in
     *   `runCatching`.
     * - SCAN: we copied ML Kit's cached artifacts into filesDir and
     *   stored a file:// URI. Delete those files now. The preview JPEG
     *   is a separate file so we clean it up too.
     */
    private fun releaseAttachmentUri(att: Attachment) {
        val app = getApplication<Application>()
        when (att.type) {
            Attachment.TYPE_PHOTO -> {
                // Photos can come from two places now:
                //   - MediaStore picker → content:// URI with a
                //     persistable-read grant we need to release.
                //   - "Save to Photos" export → file:// URI we own
                //     in `filesDir/releaf/attachments/`.
                // `releasePersistableUriPermission` throws on file://
                // URIs (IllegalArgumentException) and the MediaStore
                // picker URI on older OEM ROMs, so we swallow either
                // failure. `deleteIfLocal` is scheme-gated and no-ops
                // on content:// — safe to call unconditionally.
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
                // Voice notes are M4A files the recorder wrote into our
                // attachments dir — same ownership model as scans, so
                // cleanup routes through the same helper.
                AttachmentStorage.deleteIfLocal(att.uri)
            }
        }
    }

    /**
     * Persist the current draft. Fire-and-forget on the VM scope. Safe to
     * call multiple times — no-ops when the page is missing or unchanged.
     *
     * Back-tap → `save()` and `onDispose` → `save()` fire back-to-back on the
     * main thread, so this function advances `_state.page` to the target row
     * synchronously *before* launching the IO. The second call then sees an
     * up-to-date baseline, diffs clean, and returns without enqueuing a
     * duplicate write.
     */
    fun save() {
        val snapshot = _state.value
        val existing = snapshot.page ?: return

        val contactsJson    = snapshot.contacts.toJsonString()
        val todosJson       = snapshot.todos.toJsonString()
        val locationsJson   = snapshot.locations.toJsonString()
        val attachmentsJson = snapshot.attachments.toJsonString()
        val subPagesJson    = snapshot.subPages.toJsonString()

        // Keep the legacy flat columns in sync so FTS keeps working
        // (fts_page_notes indexes `notes`) and so any pre-v3 readers
        // round-trip without crashing. The join separator is a blank
        // line — FTS tokenization ignores whitespace so sub-page
        // boundaries don't create spurious matches.
        val joinedNotes     = snapshot.subPages.joinToString("\n\n") { it.notes }
        val firstStrokesJson = snapshot.subPages.firstOrNull()
            ?.strokes.orEmpty().toJsonString()

        val titleChanged       = (existing.title.orEmpty()) != snapshot.title
        val notesChanged       = existing.notes != joinedNotes
        val contactsChanged    = existing.contacts != contactsJson
        val todosChanged       = existing.todos != todosJson
        val locationsChanged   = existing.locations != locationsJson
        val attachmentsChanged = existing.attachments != attachmentsJson
        val strokesChanged     = existing.sketchStrokes != firstStrokesJson
        val subPagesChanged    = existing.subPages != subPagesJson
        if (!titleChanged && !notesChanged &&
            !contactsChanged && !todosChanged &&
            !locationsChanged && !attachmentsChanged &&
            !strokesChanged && !subPagesChanged
        ) return

        val updated = existing.copy(
            title         = snapshot.title.ifBlank { null },
            notes         = joinedNotes,
            contacts      = contactsJson,
            todos         = todosJson,
            locations     = locationsJson,
            attachments   = attachmentsJson,
            sketchStrokes = firstStrokesJson,
            subPages      = subPagesJson,
        )
        // Advance the baseline synchronously — see KDoc above.
        _state.value = snapshot.copy(page = updated)

        viewModelScope.launch {
            repository.savePage(updated)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val existing = _state.value.page ?: run { onDeleted(); return }
        viewModelScope.launch {
            repository.softDeletePage(existing.id)
            onDeleted()
        }
    }

    companion object {
        const val ARG_PAGE_ID = "pageId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val pageId = checkNotNull(savedState.get<String>(ARG_PAGE_ID)) {
                    "PageLocalEditorViewModel missing $ARG_PAGE_ID"
                }
                PageLocalEditorViewModel(
                    application        = app,
                    pageId             = pageId,
                    repository         = app.pageRepository,
                    chapterRepository  = app.chapterRepository,
                    notebookRepository = app.notebookRepository,
                )
            }
        }
    }
}
