/*
 * NotepadRepository.kt
 *
 * Thin wrapper over NotepadDao. A pure data layer today, but the seam lives
 * here so that when Drive sync lands the ViewModels don't change — the repo
 * will just fan-out to a sync worker alongside each write.
 */

package app.releaf.mobile.data.notepad

import app.releaf.mobile.data.common.FtsQuery
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class NotepadRepository(
    private val dao: NotepadDao,
) {
    fun observeActive(userId: String): Flow<List<NotepadEntry>> =
        dao.observeActive(userId)

    fun observeById(id: String): Flow<NotepadEntry?> =
        dao.observeById(id)

    suspend fun findById(id: String): NotepadEntry? = dao.findById(id)

    /**
     * Free-form search. The raw user query is sanitized into FTS5 MATCH
     * syntax here so the DAO stays simple and the UI layer doesn't leak
     * search-language details.
     *
     * Empty / all-noise queries short-circuit to an empty flow rather than
     * bubbling a MATCH error out of SQLite.
     */
    fun search(userId: String, rawQuery: String): Flow<List<NotepadEntry>> {
        val match = FtsQuery.build(rawQuery) ?: return flowOf(emptyList())
        return dao.searchActive(userId, match)
    }

    /**
     * Create a fresh entry. Caller supplies user + initial content; id,
     * timestamps, and defaults are filled in here so the editor VM isn't
     * duplicating this logic.
     *
     * The JSON payloads (`contacts`, `locations`, `todos`, `attachments`)
     * are opt-in — the editor passes them explicitly when a brand-new
     * draft has already accumulated section items before the first save.
     * Without these arguments a user who added a photo to a fresh entry
     * and tapped back would lose the photo (create path wouldn't see it).
     */
    suspend fun create(
        userId: String,
        title: String?,
        notes: String,
        entryDate: String = IsoClock.todayLocalDate(),
        contacts: String = "[]",
        locations: String = "[]",
        todos: String = "[]",
        attachments: String = "[]",
        sketchStrokes: String = "[]",
    ): NotepadEntry {
        val now = IsoClock.nowIso()
        val entry = NotepadEntry(
            id            = Uuidv7.generate(),
            userId        = userId,
            entryDate     = entryDate,
            title         = title?.trim()?.ifEmpty { null },
            notes         = notes,
            contacts      = contacts,
            locations     = locations,
            todos         = todos,
            attachments   = attachments,
            sketchStrokes = sketchStrokes,
            createdAt     = now,
            updatedAt     = now,
            dirty         = true,
        )
        dao.upsert(entry)
        return entry
    }

    /**
     * Persist edits. Bumps updated_at + sets dirty=1 unconditionally — the
     * sync worker is the only thing allowed to clear dirty.
     */
    suspend fun save(entry: NotepadEntry) {
        dao.upsert(
            entry.copy(
                title     = entry.title?.trim()?.ifEmpty { null },
                updatedAt = IsoClock.nowIso(),
                dirty     = true,
            )
        )
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id = id, nowIso = IsoClock.nowIso())
    }

    /**
     * Inverse of [softDelete] — clears deleted_at, re-dirties for sync. Used
     * by the Notepad list's "Undo" snackbar after a swipe-to-delete.
     */
    suspend fun undoSoftDelete(id: String) {
        dao.restore(id = id, nowIso = IsoClock.nowIso())
    }
}
