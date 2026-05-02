/*
 * CaptureDao.kt
 *
 * Room DAO for `captures`. Minimal CRUD + observation queries —
 * the camera+scan flow (Slice 3) extends this with whatever feature-
 * specific queries it needs.
 */

package app.quickink.mobile.data.capture

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Update
import app.quickink.mobile.data.ocr.OcrResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CaptureEntity)

    @Update
    suspend fun update(entity: CaptureEntity)

    @Query("SELECT * FROM captures WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CaptureEntity?

    /**
     * Live list of a user's captures, newest first, tombstones
     * filtered out. Bound to the camera-first Home's recents shelf.
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY created_at DESC
    """)
    fun observeActive(userId: String): Flow<List<CaptureEntity>>

    /**
     * Live, capped feed for the home "Recent" rail. Same shape as
     * [observeActive] but limited so the rail stays cheap to render
     * even after thousands of captures accumulate.
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY created_at DESC
        LIMIT :limit
    """)
    fun observeRecent(userId: String, limit: Int = 30): Flow<List<CaptureEntity>>

    /**
     * Soft-delete: stamp `deleted_at` and bump `dirty` so the sync
     * worker mirrors the tombstone to Drive on its next pass. Real
     * row removal happens on Drive-confirmed cascade.
     */
    @Query("""
        UPDATE captures
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    /**
     * Update an existing capture's `category` to the user's pick
     * from the scan-review screen. Bumps `updated_at` + `dirty`
     * so the next sync pass uploads the change.
     */
    @Query("""
        UPDATE captures
        SET category = :category, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setCategory(id: String, category: String?, timestamp: String)

    /**
     * Bulk-update [oldName] → [newName] across every capture for
     * the given user. Used by [CategoryRepository.renameAndPropagate]
     * so a category rename in Settings doesn't orphan historical
     * tags. Bumps `updated_at` + `dirty` on each touched row.
     */
    @Query("""
        UPDATE captures
        SET category = :newName, updated_at = :timestamp, dirty = 1
        WHERE user_id = :userId AND category = :oldName
    """)
    suspend fun renameCategory(userId: String, oldName: String, newName: String, timestamp: String)

    /**
     * Captures whose category contains the substring (case-
     * insensitive). Used as the "fast" pass of search. Limited so
     * the result list stays bounded on a query like "i".
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND category IS NOT NULL
          AND lower(category) LIKE lower(:like)
        ORDER BY created_at DESC
        LIMIT 50
    """)
    suspend fun searchByCategory(userId: String, like: String): List<CaptureEntity>

    /**
     * FTS5-backed OCR search joined to captures. `@RawQuery` skips
     * Room's compile-time SQL verification — required because the
     * `fts_ocr_text` virtual table is created at runtime by the
     * SchemaCallback, so the KSP-time schema doesn't know about it.
     * Returns one row per matching ocr_result with its capture's
     * fields + the FTS `snippet()` excerpt. Caller dedupes by
     * `capture_id` and keeps the strongest (first / highest-ranked)
     * snippet per capture.
     */
    @RawQuery(observedEntities = [CaptureEntity::class, OcrResultEntity::class])
    suspend fun searchByOcr(query: RoomRawQuery): List<CaptureSearchRow>

    // ─── Binary attachments (Drive backup) ────────────────────────

    /**
     * Captures with at least one binary still pending — either a
     * live row missing a Drive upload, or a tombstoned row whose
     * Drive ids haven't been trashed yet. Drives the
     * `QuickInkBinarySync` upload+cascade pass.
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId
          AND (
            (deleted_at IS NULL AND (
                pdf_drive_file_id IS NULL
                OR (preview_uri IS NOT NULL AND preview_drive_file_id IS NULL)
            ))
            OR (deleted_at IS NOT NULL AND (
                pdf_drive_file_id IS NOT NULL
                OR preview_drive_file_id IS NOT NULL
            ))
          )
        ORDER BY created_at DESC
        LIMIT 50
    """)
    suspend fun pendingBinaryRows(userId: String): List<CaptureEntity>

    @Query("UPDATE captures SET pdf_drive_file_id = :driveFileId WHERE id = :id")
    suspend fun setPdfDriveFileId(id: String, driveFileId: String?)

    @Query("UPDATE captures SET preview_drive_file_id = :driveFileId WHERE id = :id")
    suspend fun setPreviewDriveFileId(id: String, driveFileId: String?)

    @Query("UPDATE captures SET pdf_uri = :pdfUri WHERE id = :id")
    suspend fun setPdfUri(id: String, pdfUri: String)

    @Query("UPDATE captures SET preview_uri = :previewUri WHERE id = :id")
    suspend fun setPreviewUri(id: String, previewUri: String?)

    // ─── Sync surface (Slice 4.2a) ────────────────────────────────────

    /**
     * All locally-dirty rows — both edits (`deleted_at IS NULL`) and
     * tombstones (`deleted_at IS NOT NULL`). The sync data source
     * partitions them by `deletedAt` to decide upload vs tombstone.
     */
    @Query("SELECT * FROM captures WHERE dirty = 1")
    suspend fun dirtyRows(): List<CaptureEntity>

    /** All active (non-tombstone) rows for [userId]. */
    @Query("SELECT * FROM captures WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun activeRows(userId: String): List<CaptureEntity>

    /**
     * Race-safe clear of the dirty bit on an upload-acked row. Only
     * flips `dirty = 0` when `updated_at` still matches the snapshot
     * captured at upload time — a concurrent edit bumped `updated_at`
     * and the next pass picks the row up fresh.
     */
    @Query("""
        UPDATE captures
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    /**
     * Clear-dirty for a synced tombstone. No `updated_at` guard —
     * tombstones don't get re-edited, and we want the undo path
     * (clears `deleted_at`, re-dirties) to re-upload as a live row
     * on the next pass.
     */
    @Query("""
        UPDATE captures
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}

/**
 * Projection for [CaptureDao.searchByOcr] — a captures row plus
 * the FTS5 `snippet()` excerpt for the OCR text that matched.
 */
data class CaptureSearchRow(
    @ColumnInfo(name = "id")          val id: String,
    @ColumnInfo(name = "user_id")     val userId: String,
    @ColumnInfo(name = "preview_uri") val previewUri: String?,
    @ColumnInfo(name = "pdf_uri")     val pdfUri: String,
    @ColumnInfo(name = "category")    val category: String?,
    @ColumnInfo(name = "page_count")  val pageCount: Int,
    @ColumnInfo(name = "created_at")  val createdAt: String,
    @ColumnInfo(name = "ocr_snippet") val ocrSnippet: String?,
)
