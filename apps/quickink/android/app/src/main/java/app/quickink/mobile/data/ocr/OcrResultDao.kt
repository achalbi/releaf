/*
 * OcrResultDao.kt
 *
 * Room DAO for `ocr_results`. Minimal CRUD + per-capture
 * observation + an FTS5 search query against `fts_ocr_text`.
 * The OCR-review screen (Slice 3) extends this with whatever
 * feature-specific queries it needs.
 */

package app.quickink.mobile.data.ocr

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Projection: first-page OCR snippet keyed by capture id. Used by
 * the Library screen to preload all snippets in one query so cards
 * render in their final state without a per-card swap.
 */
data class CaptureFirstSnippet(
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "text")       val text: String?,
)

@Dao
interface OcrResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OcrResultEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<OcrResultEntity>)

    /**
     * Insert if no row with this id exists. Returns rowId on insert,
     * `-1L` on id conflict. Paired with [update] in [upsertFromRemote]
     * for a real UPSERT without REPLACE's clobber-everything semantics.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: OcrResultEntity): Long

    @Update
    suspend fun update(entity: OcrResultEntity)

    /**
     * Upsert from a remote payload, last-write-wins on `updated_at`.
     *
     * Mirror of [CaptureDao.upsertFromRemote] — see that doc for the
     * full rationale. Short version: `@Insert(REPLACE)` was a stale
     * footgun (DELETE-then-INSERT cascades unrelated FKs and silently
     * clobbers newer local edits); this two-step pattern does a real
     * UPDATE under the hood and gates on `updated_at` so a slow
     * restore can't overwrite a faster local edit.
     */
    @Transaction
    suspend fun upsertFromRemote(entity: OcrResultEntity) {
        val rowId = insertIfAbsent(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

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

    /**
     * One-shot lookup of the first page's OCR text for a capture.
     * Used by the Library card to render the handwritten preview
     * snippet without spinning up a per-card Flow. Returns `null`
     * when the capture has no OCR rows yet (in-flight scan or
     * blank pages).
     */
    @Query("""
        SELECT text FROM ocr_results
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY page_index ASC
        LIMIT 1
    """)
    suspend fun findFirstTextForCapture(captureId: String): String?

    /**
     * Replace the OCR text for a single page row. Used by the scan
     * detail editor when the user corrects the recognised text. Sets
     * the dirty bit + bumps `updated_at` so the next sync pass mirrors
     * the change to Drive. The FTS5 virtual table watches the same
     * column and rebuilds its index automatically.
     */
    @Query("""
        UPDATE ocr_results
        SET text = :text, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setText(id: String, text: String, timestamp: String)

    /**
     * Live first-page snippets for every active capture belonging to
     * [userId]. Returned as one row per capture (the row whose
     * `page_index` is the minimum among that capture's non-deleted
     * OCR results). Captures with no OCR rows yet are simply absent
     * from the result — callers treat absence as "no snippet".
     *
     * Wins over the per-card one-shot fetch the Library card used to
     * do: cards render in their final state on first frame instead
     * of swapping when each card's `LaunchedEffect` resolves.
     */
    @Query("""
        SELECT o.capture_id AS capture_id, o.text AS text
        FROM ocr_results o
        INNER JOIN captures c ON c.id = o.capture_id
        WHERE c.user_id = :userId
          AND c.deleted_at IS NULL
          AND o.deleted_at IS NULL
          AND o.page_index = (
              SELECT MIN(page_index) FROM ocr_results
              WHERE capture_id = o.capture_id AND deleted_at IS NULL
          )
    """)
    fun observeFirstSnippetsForUser(userId: String): Flow<List<CaptureFirstSnippet>>

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
