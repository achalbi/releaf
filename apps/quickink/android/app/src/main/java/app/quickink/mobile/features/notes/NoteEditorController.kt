/*
 * NoteEditorController.kt
 *
 * Thin state holder for QuickInk's note editor on Android. Mirror
 * of iOS's `NotepadEditorViewModel` from `ReleafCoreNotes`, but
 * lives in the QuickInk app target because Releaf's Android
 * `NotepadEditorViewModel.kt` extends `AndroidViewModel` and
 * casts `applicationContext as ReleafApp` — extracting it into
 * `:shared:notes` requires a DI refactor (see the file header on
 * `:shared:notes`'s build.gradle.kts).
 *
 * Surface area is minimal vs. the iOS shared VM:
 *   - `entryId` / `userId` constructor args, plus the DAO
 *   - `bootstrap()` loads the existing entry (or sets up draft mode)
 *   - bound `title` / `notes` / `entryDate` Compose state
 *   - `save()` upserts on demand
 *   - `delete()` soft-deletes
 *
 * Side-channel arrays (contacts / todos / locations / attachments)
 * are intentionally out of scope — those are Releaf-flavored
 * sections; QuickInk's editor stays text-focused.
 *
 * The future "extract Android VMs into :shared:notes" PR replaces
 * this controller with the shared VM.
 */

package app.quickink.mobile.features.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NoteEditorController(
    private val entryId: String,
    private val userId: String,
    private val dao: NotepadDao,
    private val scope: CoroutineScope,
    /**
     * Fired once per successful save / soft-delete. Callers use this
     * to run QuickInk's once-per-day app-initiated sync check.
     * Defaults to a no-op for tests / preview construction sites.
     *
     * Save and delete are user-driven (low frequency), so each one
     * is worth its own kick. WorkManager's `ExistingWorkPolicy.KEEP`
     * dedupes if the previous unique work is still queued, and the
     * worker no-ops when Drive backup is off (see
     * `QuickInkSyncWorker`'s gate), so firing unconditionally is
     * safe.
     */
    private val onMutated: () -> Unit = {},
) {

    var isLoading by mutableStateOf(true)
        private set

    /** Backing row when editing an existing entry. Null in draft mode. */
    var entry by mutableStateOf<NotepadEntry?>(null)
        private set

    var title     by mutableStateOf("")
    var notes     by mutableStateOf("")
    var entryDate by mutableStateOf("")

    /**
     * Load the entry behind `entryId`, or set up draft mode when
     * `entryId == NEW_ENTRY_ID`. Idempotent — calling twice on the
     * same controller leaves state stable.
     */
    suspend fun bootstrap() {
        if (entryId == NEW_ENTRY_ID) {
            entryDate = IsoClock.todayLocalDate()
            isLoading = false
            return
        }

        val loaded = dao.findById(entryId)
        if (loaded != null) {
            entry     = loaded
            title     = loaded.title.orEmpty()
            notes     = loaded.notes
            entryDate = loaded.entryDate
        } else {
            // Caller passed an unknown id — treat as draft mode
            // for the same id so save() upserts a row with that
            // identity. Matches iOS NotepadEditorViewModel.
            entryDate = IsoClock.todayLocalDate()
        }
        isLoading = false
    }

    /**
     * Commit a save. Creates a fresh row in draft mode (when
     * `entry == null`) or updates the existing one. No-op when
     * the editor has nothing worth committing — empty title +
     * empty notes.
     */
    fun save() {
        if (!canSave) return
        scope.launch {
            val now = IsoClock.nowIso()
            val current = entry
            if (current == null) {
                // Draft → create. Use the controller's `entryId`
                // when it's a real id, or generate one for fresh
                // drafts (NEW_ENTRY_ID sentinel).
                val newId = if (entryId == NEW_ENTRY_ID) Uuidv7.generate() else entryId
                val row = NotepadEntry(
                    id          = newId,
                    userId      = userId,
                    entryDate   = entryDate,
                    title       = title.takeIf { it.isNotBlank() },
                    notes       = notes,
                    description = null,
                    category    = null,
                    contacts    = "[]",
                    locations   = "[]",
                    todos       = "[]",
                    attachments = "[]",
                    createdAt   = now,
                    updatedAt   = now,
                    dirty       = true,
                )
                dao.upsert(row)
                entry = row
            } else {
                val updated = current.copy(
                    title     = title.takeIf { it.isNotBlank() },
                    notes     = notes,
                    entryDate = entryDate,
                    updatedAt = now,
                    dirty     = true,
                )
                dao.upsert(updated)
                entry = updated
            }
            // Slice 4.2c — kick an immediate sync now that the row
            // is dirty. Inside the same coroutine as the upsert so
            // we never enqueue the worker before the row exists.
            onMutated()
        }
    }

    /**
     * Soft-delete the persisted row (if any). `onDeleted` fires
     * once the DAO write completes so the caller can navigate
     * back. No-op for unsaved drafts — there's nothing to remove.
     */
    fun delete(onDeleted: () -> Unit) {
        val current = entry
        if (current == null) {
            onDeleted()
            return
        }
        scope.launch {
            dao.softDelete(current.id, IsoClock.nowIso())
            // Slice 4.2c — soft-delete also marks the row dirty
            // (deleted_at + updated_at), so the next sync pass
            // pushes the tombstone. Kick an immediate one for the
            // same reason save() does.
            onMutated()
            onDeleted()
        }
    }

    /** Mirror of iOS `canSave`: any non-empty content commits. */
    val canSave: Boolean
        get() = title.isNotBlank() || notes.isNotBlank()

    companion object {
        /** Sentinel for "compose a new entry" — same convention as iOS. */
        const val NEW_ENTRY_ID = "new"
    }
}
