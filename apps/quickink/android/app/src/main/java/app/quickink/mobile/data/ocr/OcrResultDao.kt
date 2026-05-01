/*
 * OcrResultDao.kt
 *
 * Room DAO for `ocr_results`. Minimal CRUD + per-capture
 * observation + an FTS5 search query against `fts_ocr_text`.
 * The OCR-review screen (Slice 3) extends this with whatever
 * feature-specific queries it needs.
 */

package app.quickink.mobile.data.ocr

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OcrResultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<OcrResultEntity>)

    @Update
    suspend fun update(entity: OcrResultEntity)

    @Query("SELECT * FROM ocr_results WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): OcrResultEntity?

    /**
     * Live page-ordered list of OCR results for one capture.
     * Page-ordered (not insertion-ordered) so the OCR review UI's
     * scroll position stays stable as later pages finish.
     */
    @Query("""
        SELECT * FROM ocr_results
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY page_index ASC
    """)
    fun observeForCapture(captureId: String): Flow<List<OcrResultEntity>>

    // FTS5 search over `fts_ocr_text` lands with the search-using
    // screen (Slice 4 — Notes list / OCR review). Room's KSP
    // validates `@Query` SQL against the @Entity-derived schema at
    // compile time, and `fts_ocr_text` only exists at runtime via
    // QuickInkDatabase.SchemaCallback. The same pattern Releaf's
    // NotepadDao uses — `@RawQuery(observedEntities = [...])` — is
    // the way through; building it now without a consuming surface
    // would just create dead code. See NotepadDao.searchActive in
    // :shared:notes for the canonical shape when we add it.

    // ─── Sync surface (Slice 4.2a) ────────────────────────────────────

    /**
     * All locally-dirty rows. `ocr_results` rows aren't user-scoped
     * directly — they hang off `captures(userId)` via the FK — so
     * the data source filters by joining against captures when
     * needed.
     */
    @Query("SELECT * FROM ocr_results WHERE dirty = 1")
    suspend fun dirtyRows(): List<OcrResultEntity>

    /** All active rows; user filtering happens at the data source. */
    @Query("SELECT * FROM ocr_results WHERE deleted_at IS NULL")
    suspend fun activeRows(): List<OcrResultEntity>

    /**
     * Race-safe clear of the dirty bit on an upload-acked row.
     * Same `updated_at` guard semantics as `CaptureDao.markSynced`
     * / `NotepadDao.markSynced` — see those for the rationale.
     */
    @Query("""
        UPDATE ocr_results
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    /** Clear-dirty for a synced tombstone — see `markTombstoneSynced` peers. */
    @Query("""
        UPDATE ocr_results
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int

    /**
     * Soft-delete a single OCR row. Used by
     * `QuickInkSyncDataSource.applyRemoteTombstone` for
     * `KIND_OCR_RESULT` — when another device tombstones a page
     * (e.g. user manually deleted a single page from a capture
     * via a remote-only flow that lands later), the orchestrator
     * applies the tombstone here.
     *
     * Sets `dirty = 0` on the same write so applying a remote
     * tombstone doesn't re-enqueue the row to push back. Mirror
     * of `NotepadDao.softDelete` / `CaptureDao.softDelete`.
     *
     * Note: in v1, the FK `captures(id) ON DELETE CASCADE` plus
     * Drive's tombstone-by-id model means the typical remote→local
     * delete path is "capture is tombstoned → cascade deletes
     * children locally", so this row-level path is rare. It still
     * exists for completeness — and for the future search-from-
     * trash / undo flows that might tombstone a single page.
     */
    @Query("""
        UPDATE ocr_results
        SET deleted_at = :deletedAt,
            updated_at = :deletedAt,
            dirty      = 0
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, deletedAt: String): Int
}
