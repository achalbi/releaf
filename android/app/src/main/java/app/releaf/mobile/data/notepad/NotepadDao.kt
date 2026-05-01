/*
 * NotepadDao.kt
 *
 * All notepad queries the UI needs, exposed as Flows where reactivity matters
 * and suspend fns for one-shots. Soft-deletes are filtered here (deleted_at
 * IS NULL) so callers never have to remember.
 */

package app.releaf.mobile.data.notepad

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface NotepadDao {

    /** Active (not-deleted) entries for a user, newest first. */
    @Query(
        """
        SELECT * FROM notepad_entries
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY updated_at DESC
        """
    )
    fun observeActive(userId: String): Flow<List<NotepadEntry>>

    /** Observe a single entry (null when not found or soft-deleted). */
    @Query(
        """
        SELECT * FROM notepad_entries
        WHERE id = :id AND deleted_at IS NULL
        LIMIT 1
        """
    )
    fun observeById(id: String): Flow<NotepadEntry?>

    /** One-shot lookup used by the editor on first load. */
    @Query("SELECT * FROM notepad_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NotepadEntry?

    /**
     * Most recently updated active entry for [userId] filed under
     * [date]. Returns null when the day has no entries yet — Quick
     * Capture uses this as the "latest page of the day" target so a
     * tile tap always lands on whichever notepad page the user most
     * recently touched today (or creates one when none exists).
     */
    @Query(
        """
        SELECT * FROM notepad_entries
        WHERE user_id = :userId
          AND entry_date = :date
          AND deleted_at IS NULL
        ORDER BY updated_at DESC
        LIMIT 1
        """
    )
    suspend fun findLatestForDate(userId: String, date: String): NotepadEntry?

    /**
     * Full-text search over `notes`, scoped to one user and to live rows.
     * The query is built by the repository as a [RoomRawQuery] (see
     * `NotepadRepository.search`); the bound MATCH expression must already
     * be in FTS5 MATCH syntax (see `FtsQuery.build`). Results come back
     * ranked best-match first via SQLite's built-in `rank` column.
     *
     * The FTS5 virtual table (`fts_notepad_notes`) and its maintenance
     * triggers are created in `ReleafDatabase.SchemaCallback` — it isn't
     * declared as an @Entity. Room 2.7's invalidation tracker validates
     * every observed table at query time, including for `suspend` queries
     * (the generated DAO_Impl routes them through `FlowUtil.createFlow`
     * for thread management), and would throw `IllegalArgumentException:
     * There is no table with name fts_notepad_notes` if the table name
     * appeared in a `@Query` SQL — even with `@SkipQueryVerification`,
     * which only silences compile-time SQL verification, not runtime
     * table-name validation. `@RawQuery(observedEntities = [...])` skips
     * SQL parsing entirely and only watches the entities listed; the
     * repository wraps this in a Flow on top of `observeActive` so the
     * UI still reactively re-runs the search on every write.
     */
    @RawQuery(observedEntities = [NotepadEntry::class])
    suspend fun searchActive(query: RoomRawQuery): List<NotepadEntry>

    /** Insert-or-replace. Callers are responsible for bumping updated_at. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: NotepadEntry)

    /**
     * Bulk rename — sets `category = :newName` on every active row
     * for [userId] whose current category matches [oldName] case-
     * insensitively. Bumps `updated_at` + sets `dirty = 1` so the
     * sync worker picks each row up on its next pass.
     *
     * Returns the number of rows updated so the caller can surface
     * a useful "renamed N entries" toast.
     */
    @Query(
        """
        UPDATE notepad_entries
        SET category = :newName, updated_at = :nowIso, dirty = 1
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND category IS NOT NULL
          AND TRIM(category) <> ''
          AND LOWER(TRIM(category)) = LOWER(TRIM(:oldName))
        """
    )
    suspend fun renameCategory(
        userId: String,
        oldName: String,
        newName: String,
        nowIso: String,
    ): Int

    /**
     * Bulk uncategorise — sets `category = NULL` on every active row
     * for [userId] whose current category matches [name] case-
     * insensitively. Bumps `updated_at` + sets `dirty = 1`.
     *
     * "Delete category" semantically means "drop this label from
     * every entry that currently carries it" — the entries
     * themselves stay live (just uncategorised), the category just
     * stops surfacing in the picker / filter row.
     */
    @Query(
        """
        UPDATE notepad_entries
        SET category = NULL, updated_at = :nowIso, dirty = 1
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND category IS NOT NULL
          AND TRIM(category) <> ''
          AND LOWER(TRIM(category)) = LOWER(TRIM(:name))
        """
    )
    suspend fun deleteCategory(userId: String, name: String, nowIso: String): Int

    /**
     * Soft delete. Flips deleted_at + dirty so the sync worker can propagate
     * the tombstone to Drive on its next pass.
     */
    @Query(
        """
        UPDATE notepad_entries
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)

    /**
     * Undo a soft delete. Clears deleted_at and bumps updated_at + dirty so
     * the sync worker re-asserts the row on Drive as a live entity. Used by
     * the list's "Undo" snackbar — the row was already deleted in Room by the
     * swipe handler, and this restores it if the user taps the action.
     */
    @Query(
        """
        UPDATE notepad_entries
        SET deleted_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun restore(id: String, nowIso: String)

    /* ---------- sync worker ---------- */

    /**
     * All rows needing upload — live edits AND tombstones. The caller uses
     * `deleted_at IS NOT NULL` to decide between upload and Drive-trash.
     */
    @Query("SELECT * FROM notepad_entries WHERE dirty = 1")
    suspend fun dirtyRows(): List<NotepadEntry>

    /**
     * One-shot list of every active row, scoped by user. Used by the
     * v2 sync pass to rebuild `manifest.entity_checksums` from scratch
     * each pass — simpler than tracking per-row upload history, at the
     * cost of an O(N) re-hash per pass.
     */
    @Query("SELECT * FROM notepad_entries WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun activeRows(userId: String): List<NotepadEntry>

    /**
     * All locally-known rows matching [ids], regardless of soft-delete
     * state. Pull path uses this to decide insert-vs-update during
     * reconciliation.
     */
    @Query("SELECT * FROM notepad_entries WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<NotepadEntry>

    /**
     * Count of live entries for a user — fed into the sync manifest's
     * `entity_counts` map so a future pull can sanity-check Drive vs local.
     */
    @Query(
        """
        SELECT COUNT(*) FROM notepad_entries
        WHERE user_id = :userId AND deleted_at IS NULL
        """
    )
    suspend fun countActive(userId: String): Int

    /**
     * Race-safe clear: only marks the row synced if its `updated_at` still
     * matches the snapshot we read. A concurrent edit bumps updated_at, so
     * the next sync pass picks the row up fresh. Also stamps the
     * drive_file_id (first sync) or re-stamps it (Drive's fileId is stable
     * on updates, but we write it anyway for robustness).
     */
    @Query(
        """
        UPDATE notepad_entries
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
        """
    )
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    /**
     * Clear-dirty for a synced tombstone. No updated_at guard — tombstones
     * don't get re-edited, and we don't want to race with an undo (which
     * clears deleted_at and re-dirties, so the next pass sees it as a live
     * row and re-uploads).
     */
    @Query(
        """
        UPDATE notepad_entries
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
        """
    )
    suspend fun markTombstoneSynced(id: String): Int
}
