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
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseStrokes
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PageLocalEditorUiState(
    val isLoading: Boolean = true,
    val page: PageEntity? = null,
    val title: String = "",
    val notes: String = "",
    val contacts: List<Contact> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val locations: List<GeoLocation> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
) {
    /** True if the page id resolved to a live row. */
    val exists: Boolean get() = page != null
}

class PageLocalEditorViewModel(
    application: Application,
    private val pageId: String,
    private val repository: PageRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(PageLocalEditorUiState())
    val state: StateFlow<PageLocalEditorUiState> = _state.asStateFlow()

    init { bootstrap() }

    private fun bootstrap() {
        viewModelScope.launch {
            val loaded = repository.findById(pageId)
            _state.value = PageLocalEditorUiState(
                isLoading   = false,
                page        = loaded,
                title       = loaded?.title.orEmpty(),
                notes       = loaded?.notes.orEmpty(),
                contacts    = loaded?.contacts?.parseContacts().orEmpty(),
                todos       = loaded?.todos?.parseTodos().orEmpty(),
                locations   = loaded?.locations?.parseLocations().orEmpty(),
                attachments = loaded?.attachments?.parseAttachments().orEmpty(),
                strokes     = loaded?.sketchStrokes?.parseStrokes().orEmpty(),
            )
        }
    }

    fun updateTitle(value: String) {
        _state.value = _state.value.copy(title = value)
    }

    fun updateNotes(value: String) {
        _state.value = _state.value.copy(notes = value)
    }

    /** Freehand-drawing strokes overlaying the notes body. */
    fun updateStrokes(value: List<Stroke>) {
        _state.value = _state.value.copy(strokes = value)
    }

    // ------------------------- Contacts -------------------------

    fun addContact(name: String, role: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val contact = Contact(
            id   = Uuidv7.generate(),
            name = trimmed,
            role = role?.trim()?.ifEmpty { null },
        )
        _state.value = _state.value.copy(contacts = _state.value.contacts + contact)
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

    // ------------------------- Locations ------------------------

    fun addLocation(lat: Double, lng: Double, address: String? = null) {
        val loc = GeoLocation(
            id         = Uuidv7.generate(),
            lat        = lat,
            lng        = lng,
            address    = address,
            capturedAt = IsoClock.nowIso(),
        )
        _state.value = _state.value.copy(locations = _state.value.locations + loc)
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
    fun updateVoiceTranscript(uri: String, transcript: String?) {
        val cleaned = transcript?.takeIf { it.isNotBlank() }
        _state.value = _state.value.copy(
            attachments = _state.value.attachments.map { existing ->
                if (existing.uri == uri) existing.copy(transcript = cleaned) else existing
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
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(
                        Uri.parse(att.uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
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
        val strokesJson     = snapshot.strokes.toJsonString()

        val titleChanged       = (existing.title.orEmpty()) != snapshot.title
        val notesChanged       = existing.notes != snapshot.notes
        val contactsChanged    = existing.contacts != contactsJson
        val todosChanged       = existing.todos != todosJson
        val locationsChanged   = existing.locations != locationsJson
        val attachmentsChanged = existing.attachments != attachmentsJson
        val strokesChanged     = existing.sketchStrokes != strokesJson
        if (!titleChanged && !notesChanged &&
            !contactsChanged && !todosChanged &&
            !locationsChanged && !attachmentsChanged &&
            !strokesChanged
        ) return

        val updated = existing.copy(
            title         = snapshot.title.ifBlank { null },
            notes         = snapshot.notes,
            contacts      = contactsJson,
            todos         = todosJson,
            locations     = locationsJson,
            attachments   = attachmentsJson,
            sketchStrokes = strokesJson,
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
                    application = app,
                    pageId      = pageId,
                    repository  = app.pageRepository,
                )
            }
        }
    }
}
