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
    /**
     * Optional audit logger — supplied by ReleafApp wiring, nullable
     * so unit tests + callers without the audit feature can still
     * construct the repo. Every successful mutation logs one event;
     * read paths skip the logger entirely.
     */
    private val auditLogger: app.releaf.mobile.data.activity.AuditLogger? = null,
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
        description: String? = null,
        category: String? = null,
        contacts: String = "[]",
        locations: String = "[]",
        todos: String = "[]",
        attachments: String = "[]",
        sketchStrokes: String = "[]",
        subPages: String = "[]",
    ): NotepadEntry {
        val now = IsoClock.nowIso()
        val newId = Uuidv7.generate()

        // Auto-seed title + description as a *pair* from an Ayurvedic
        // plant picked deterministically by entry id (UUIDv7) — title
        // gets the Sanskrit/Hindi name, the description gets
        // "(commonName) epithet · usedFor". We only seed when BOTH
        // fields were left blank: mixing an authored title with an
        // unrelated auto-description (or vice-versa) would produce
        // internally mismatched rows. Per-id selection means two
        // entries created on the same day land on different plants
        // (UUIDv7 random tail differs), which is the intended
        // behaviour now that the notepad allows multiple pages per
        // day.
        val cleanedTitle       = title?.trim()?.ifEmpty { null }
        val cleanedDescription = description?.trim()?.ifEmpty { null }
        val seededTitle: String?
        val seededDescription: String?
        if (cleanedTitle == null && cleanedDescription == null) {
            val plant = AyurvedicCatalog.forNewEntry(entryId = newId)
            seededTitle       = plant.name
            seededDescription = AyurvedicCatalog.formatDescription(plant)
        } else {
            seededTitle       = cleanedTitle
            seededDescription = cleanedDescription
        }

        val entry = NotepadEntry(
            id            = newId,
            userId        = userId,
            entryDate     = entryDate,
            title         = seededTitle,
            description   = seededDescription,
            category      = category?.trim()?.ifEmpty { null },
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
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.NotepadEntry,
            entityId   = entry.id,
            title      = entry.title ?: entry.entryDate,
            userId     = entry.userId,
        )
        return entry
    }

    /**
     * Persist edits. Bumps updated_at + sets dirty=1 unconditionally — the
     * sync worker is the only thing allowed to clear dirty.
     */
    suspend fun save(entry: NotepadEntry) {
        val updated = entry.copy(
            title       = entry.title?.trim()?.ifEmpty { null },
            description = entry.description?.trim()?.ifEmpty { null },
            category    = entry.category?.trim()?.ifEmpty { null },
            updatedAt   = IsoClock.nowIso(),
            dirty       = true,
        )
        dao.upsert(updated)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Updated,
            entityType = app.releaf.mobile.data.activity.AuditEntity.NotepadEntry,
            entityId   = updated.id,
            title      = updated.title ?: updated.entryDate,
            userId     = updated.userId,
        )
    }

    /**
     * Bulk rename the category label across every active entry for
     * [userId] currently filed under [oldName] (case-insensitive).
     * Returns the row count updated so callers can show a "renamed
     * N entries" toast. No-op (returns 0) when the trimmed names are
     * empty or identical.
     *
     * Predefined names (Home / Work / …) are accepted on either side
     * — the canonical-cased form is what gets persisted, so renaming
     * "garden" → "Home" lands every matching row under the predefined
     * "Home" and the chip row deduplicates naturally.
     */
    suspend fun renameCategory(userId: String, oldName: String, newName: String): Int {
        val cleanedOld = oldName.trim()
        val cleanedNew = newName.trim()
        if (cleanedOld.isEmpty() || cleanedNew.isEmpty()) return 0
        if (cleanedOld.equals(cleanedNew, ignoreCase = true)) return 0
        // Normalise to the canonical display form so a user typing
        // "home" lands rows under the predefined "Home" rather than
        // forking "Home" + "home" chips.
        val canonical = NotepadCategory.displayName(cleanedNew) ?: cleanedNew
        return dao.renameCategory(
            userId  = userId,
            oldName = cleanedOld,
            newName = canonical,
            nowIso  = IsoClock.nowIso(),
        )
    }

    /**
     * Bulk drop the category label from every active entry for
     * [userId] currently filed under [name] (case-insensitive). The
     * entries themselves stay live — they're just uncategorised
     * after this. Returns the row count updated.
     *
     * Predefined names also accepted: "deleting" Home means the
     * Home chip stops surfacing in the picker / filter row until
     * the user types Home onto a new entry.
     */
    suspend fun deleteCategory(userId: String, name: String): Int {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return 0
        return dao.deleteCategory(
            userId = userId,
            name   = cleaned,
            nowIso = IsoClock.nowIso(),
        )
    }

    suspend fun softDelete(id: String) {
        // Snapshot the title before the row is gone so the audit
        // event keeps a useful label after the source is tombstoned.
        val snapshot = dao.findById(id)
        dao.softDelete(id = id, nowIso = IsoClock.nowIso())
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Deleted,
            entityType = app.releaf.mobile.data.activity.AuditEntity.NotepadEntry,
            entityId   = id,
            title      = snapshot?.title ?: snapshot?.entryDate,
            userId     = snapshot?.userId,
        )
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

        // Description: prefer primary's; fall back to secondary's only
        // when primary has none, so a merge never silently overwrites
        // text the user authored on the surviving row.
        val mergedDescription = primary.description?.trim()?.ifEmpty { null }
            ?: secondary.description?.trim()?.ifEmpty { null }

        // Category: same prefer-primary rule. Categories are
        // user-meaningful labels, so if the primary already has one
        // we keep it; otherwise inherit from the secondary so the
        // merge doesn't silently un-categorise a row that was
        // categorised pre-merge.
        val mergedCategory = primary.category?.trim()?.ifEmpty { null }
            ?: secondary.category?.trim()?.ifEmpty { null }

        val now = IsoClock.nowIso()
        val merged = primary.copy(
            description   = mergedDescription,
            category      = mergedCategory,
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
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Merged,
            entityType = app.releaf.mobile.data.activity.AuditEntity.NotepadEntry,
            entityId   = primary.id,
            title      = primary.title ?: primary.entryDate,
            userId     = primary.userId,
        )
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Deleted,
            entityType = app.releaf.mobile.data.activity.AuditEntity.NotepadEntry,
            entityId   = secondaryId,
            title      = secondary.title ?: secondary.entryDate,
            userId     = secondary.userId,
        )
        return true
    }

    /**
     * Inverse of [softDelete] — clears deleted_at, re-dirties for sync. Used
     * by the Notepad list's "Undo" snackbar after a swipe-to-delete.
     */
    suspend fun undoSoftDelete(id: String) {
        dao.restore(id = id, nowIso = IsoClock.nowIso())
        val snapshot = dao.findById(id)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Restored,
            entityType = app.releaf.mobile.data.activity.AuditEntity.NotepadEntry,
            entityId   = id,
            title      = snapshot?.title ?: snapshot?.entryDate,
            userId     = snapshot?.userId,
        )
    }
}
