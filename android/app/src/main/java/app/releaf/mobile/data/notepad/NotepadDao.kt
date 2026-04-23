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
import androidx.room.SkipQueryVerification
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
     * Full-text search over `notes`, scoped to one user and to live rows.
     * `query` must already be in FTS5 MATCH syntax — the repository does the
     * sanitization (see NotepadRepository.buildFtsQuery). Results come back
     * ranked best-match first via SQLite's built-in `rank` column.
     *
     * The FTS5 virtual table (`fts_notepad_notes`) and its maintenance
     * triggers are created in ReleafDatabase.SchemaCallback — Room's KSP
     * verifier doesn't know about them (they're not @Entity-declared), so
     * @SkipQueryVerification is required here. Room still maps the cursor
     * to NotepadEntry because the row selector is `notepad_entries.*`.
     *
     * Returned as a one-shot `suspend` (not `Flow`) because Room 2.7's
     * invalidation tracker validates every observed table up-front, and
     * `fts_notepad_notes` isn't registered with Room (it's a virtual
     * table installed out-of-band by SchemaCallback), which would throw
     * `IllegalArgumentException: There is no table with name
     * fts_notepad_notes` at query time. The repository composes a Flow
     * on top of `observeActive`, which IS tracked — and since the FTS
     * triggers mirror every notepad_entries change, re-running the
     * search on each notepad_entries emission yields identical
     * reactivity without the tracker problem.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT n.* FROM notepad_entries n
        JOIN fts_notepad_notes fts ON fts.notepad_entry_id = n.id
        WHERE n.user_id = :userId
          AND n.deleted_at IS NULL
          AND fts_notepad_notes MATCH :query
        ORDER BY rank
        """
    )
    suspend fun searchActive(userId: String, query: String): List<NotepadEntry>

    /** Insert-or-replace. Callers are responsible for bumping updated_at. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: NotepadEntry)

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
