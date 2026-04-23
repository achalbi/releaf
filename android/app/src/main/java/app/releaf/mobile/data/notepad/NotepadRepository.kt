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
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseSubPages
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

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
     *
     * Reactivity is driven off `observeActive` — every write to
     * `notepad_entries` fires an emission, and the FTS5 maintenance
     * triggers keep `fts_notepad_notes` in sync, so re-running the
     * FTS MATCH on each tick produces exactly-current results. This
     * indirection exists because Room 2.7's invalidation tracker
     * refuses to observe the virtual FTS table directly (see the
     * docstring on `NotepadDao.searchActive`).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun search(userId: String, rawQuery: String): Flow<List<NotepadEntry>> {
        val match = FtsQuery.build(rawQuery) ?: return flowOf(emptyList())
        return dao.observeActive(userId)
            .mapLatest { dao.searchActive(userId, match) }
            .distinctUntilChanged()
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
        subPages: String = "[]",
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
            subPages      = subPages,
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
     * Merge [secondaryId]'s content into [primaryId] and soft-delete the
     * secondary. The primary keeps its title, entry-date, user, and
     * created-at; notes (sub-pages) + todos + contacts + locations +
     * attachments from the secondary are appended in order.
     *
     * Returns `true` when both rows existed and the merge committed.
     * Returns `false` for any no-op (missing row, same id, already-deleted
     * secondary) so the caller can surface an error.
     *
     * Merge is purely local — the sync worker picks both sides up on its
     * next pass: the primary as a dirty-edit upload, the secondary as a
     * tombstone.
     */
    suspend fun merge(primaryId: String, secondaryId: String): Boolean {
        if (primaryId == secondaryId) return false
        val primary   = dao.findById(primaryId)   ?: return false
        val secondary = dao.findById(secondaryId) ?: return false
        if (primary.deletedAt != null || secondary.deletedAt != null) return false

        val mergedSubPages    = primary.subPages.parseSubPages()    + secondary.subPages.parseSubPages()
        val mergedTodos       = primary.todos.parseTodos()          + secondary.todos.parseTodos()
        val mergedContacts    = primary.contacts.parseContacts()    + secondary.contacts.parseContacts()
        val mergedLocations   = primary.locations.parseLocations()  + secondary.locations.parseLocations()
        val mergedAttachments = primary.attachments.parseAttachments() + secondary.attachments.parseAttachments()

        // Keep the flat columns in sync with the sub-page canonical form
        // — see the twin comment in NotepadEditorViewModel.save().
        val mergedNotes      = mergedSubPages.joinToString("\n\n") { it.notes }
        val firstStrokesJson = mergedSubPages.firstOrNull()
            ?.strokes.orEmpty().toJsonString()

        val now = IsoClock.nowIso()
        val merged = primary.copy(
            notes         = mergedNotes,
            contacts      = mergedContacts.toJsonString(),
            locations     = mergedLocations.toJsonString(),
            todos         = mergedTodos.toJsonString(),
            attachments   = mergedAttachments.toJsonString(),
            sketchStrokes = firstStrokesJson,
            subPages      = mergedSubPages.toJsonString(),
            updatedAt     = now,
            dirty         = true,
        )
        dao.upsert(merged)
        dao.softDelete(id = secondaryId, nowIso = now)
        return true
    }

    /**
     * Inverse of [softDelete] — clears deleted_at, re-dirties for sync. Used
     * by the Notepad list's "Undo" snackbar after a swipe-to-delete.
     */
    suspend fun undoSoftDelete(id: String) {
        dao.restore(id = id, nowIso = IsoClock.nowIso())
    }
}
