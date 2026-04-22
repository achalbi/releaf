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
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseStrokes
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.notepad.NotepadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NotepadEditorUiState(
    val isLoading: Boolean = true,
    /** Null until the VM has loaded (or created) the backing row. */
    val entry: NotepadEntry? = null,
    val title: String = "",
    val notes: String = "",
    /**
     * Local-calendar date (YYYY-MM-DD) the entry is filed under. Defaults
     * to today for fresh drafts; mirrors `entry.entry_date` once loaded.
     * Editable via the date chip in the editor UI.
     */
    val entryDate: String = "",
    val contacts: List<Contact> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val locations: List<GeoLocation> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val strokes: List<Stroke> = emptyList(),
) {
    /**
     * Is there anything worth persisting on back-nav? Title/notes are the
     * common case; any of the feature-section lists being non-empty is
     * also reason enough to create the row — otherwise someone who adds
     * a photo to a new draft and taps back would lose it.
     */
    val canSave: Boolean
        get() = notes.isNotBlank() || title.isNotBlank() ||
            contacts.isNotEmpty() || todos.isNotEmpty() ||
            locations.isNotEmpty() || attachments.isNotEmpty() ||
            strokes.isNotEmpty()
}

class NotepadEditorViewModel(
    application: Application,
    private val entryId: String,
    private val userId: String,
    private val repository: NotepadRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(NotepadEditorUiState())
    val state: StateFlow<NotepadEditorUiState> = _state.asStateFlow()

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
                // list shouldn't show a blank entry if they back out.
                _state.value = NotepadEditorUiState(
                    isLoading = false,
                    entryDate = IsoClock.todayLocalDate(),
                )
            } else {
                val loaded = repository.findById(entryId)
                _state.value = NotepadEditorUiState(
                    isLoading   = false,
                    entry       = loaded,
                    title       = loaded?.title.orEmpty(),
                    notes       = loaded?.notes.orEmpty(),
                    entryDate   = loaded?.entryDate ?: IsoClock.todayLocalDate(),
                    contacts    = loaded?.contacts?.parseContacts().orEmpty(),
                    todos       = loaded?.todos?.parseTodos().orEmpty(),
                    locations   = loaded?.locations?.parseLocations().orEmpty(),
                    attachments = loaded?.attachments?.parseAttachments().orEmpty(),
                    strokes     = loaded?.sketchStrokes?.parseStrokes().orEmpty(),
                )
            }
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

    /** Local YYYY-MM-DD; callers get the string back via the date picker. */
    fun updateEntryDate(value: String) {
        _state.value = _state.value.copy(entryDate = value)
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
     * a `---` separator between pages (matches how users mentally
     * bucket multi-page scans) and patched onto the attachment once
     * inference completes. Blank joins are dropped — empty pages stay
     * empty rather than landing a stray separator string on the row.
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
        val strokesJson     = snapshot.strokes.toJsonString()

        if (existing == null) {
            if (!snapshot.canSave) return
            if (hasPersistedNewEntry) return
            hasPersistedNewEntry = true
            viewModelScope.launch {
                val created = repository.create(
                    userId        = userId,
                    title         = snapshot.title,
                    notes         = snapshot.notes,
                    entryDate     = snapshot.entryDate.ifBlank { IsoClock.todayLocalDate() },
                    contacts      = contactsJson,
                    locations     = locationsJson,
                    todos         = todosJson,
                    attachments   = attachmentsJson,
                    sketchStrokes = strokesJson,
                )
                // Once the row exists, route any subsequent edits through the
                // update branch below. State fields are preserved as-is.
                _state.value = _state.value.copy(entry = created)
            }
            return
        }

        val titleChanged       = (existing.title.orEmpty()) != snapshot.title
        val notesChanged       = existing.notes != snapshot.notes
        val entryDateChanged   = snapshot.entryDate.isNotBlank() &&
            existing.entryDate != snapshot.entryDate
        val contactsChanged    = existing.contacts != contactsJson
        val todosChanged       = existing.todos != todosJson
        val locationsChanged   = existing.locations != locationsJson
        val attachmentsChanged = existing.attachments != attachmentsJson
        val strokesChanged     = existing.sketchStrokes != strokesJson
        if (!titleChanged && !notesChanged && !entryDateChanged &&
            !contactsChanged && !todosChanged &&
            !locationsChanged && !attachmentsChanged &&
            !strokesChanged
        ) return

        val updated = existing.copy(
            title         = snapshot.title.ifBlank { null },
            notes         = snapshot.notes,
            entryDate     = snapshot.entryDate.ifBlank { existing.entryDate },
            contacts      = contactsJson,
            todos         = todosJson,
            locations     = locationsJson,
            attachments   = attachmentsJson,
            sketchStrokes = strokesJson,
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
                NotepadEditorViewModel(app, entryId, userId, app.notepadRepository)
            }
        }
    }
}
