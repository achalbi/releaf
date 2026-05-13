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
import androidx.room.Transaction
import androidx.room.Update
import app.quickink.mobile.data.ocr.OcrResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CaptureEntity)

    /**
     * Insert if no row with this id exists. Returns the new rowId on
     * success, or `-1L` when a row with the same id already exists.
     * Paired with [update] in [upsertFromRemote] to implement a real
     * UPSERT (no cascade-delete on conflict).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: CaptureEntity): Long

    @Update
    suspend fun update(entity: CaptureEntity)

    /**
     * Upsert from a remote payload, last-write-wins on `updated_at`.
     *
     * Why not `@Insert(REPLACE)`: REPLACE compiles to `INSERT OR REPLACE`,
     * which DELETEs the conflicting row before inserting the new one.
     * `ocr_results.capture_id` has `ON DELETE CASCADE`, so every
     * REPLACE on a capture briefly cascade-deletes its child OCR rows
     * — which is fine when the same restore pass re-inserts them, but
     * a footgun if anything goes wrong mid-restore (network blip, FK
     * failure on a child, app killed). Using `@Update` instead does a
     * real `UPDATE captures SET … WHERE id = ?`, no cascade-delete.
     *
     * Why not just `@Update`: @Update no-ops when the row doesn't
     * exist, so a fresh-device restore wouldn't insert anything.
     * Combining `insertIfAbsent` (returns -1 on conflict) + `update`
     * gives us insert-or-update without REPLACE's destructive
     * conflict resolution.
     *
     * Last-write-wins gate: if a row already exists with `updated_at`
     * equal-or-newer than the incoming payload, we skip the UPDATE.
     * This protects local edits made while a slow restore is in
     * flight. ISO-8601 strings sort lexicographically the same as
     * chronologically, so a string compare is correct here.
     *
     * @Transaction wraps the insert + update in a single SQLite
     * transaction so a concurrent writer can't see a half-applied
     * state. Required for correctness on the cross-thread sync path.
     */
    @Transaction
    suspend fun upsertFromRemote(entity: CaptureEntity) {
        val rowId = insertIfAbsent(entity)
        if (rowId != -1L) {
            // Inserted fresh; nothing more to do.
            return
        }
        // Row already exists. Last-write-wins on `updated_at`.
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

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
     * Live total pages digitised by the user — the sum of `page_count`
     * across every active (non-tombstone) capture. Drives the home
     * sustainability hero ("By going digital — N pages saved"). Returns
     * `null` when the user has no captures yet, which the UI maps to
     * the empty-state copy.
     */
    @Query("""
        SELECT SUM(page_count) FROM captures
        WHERE user_id = :userId AND deleted_at IS NULL
    """)
    fun observeTotalPageCount(userId: String): Flow<Int?>

    /**
     * Soft-delete a capture AND cascade-soft-delete its child
     * `ocr_results` rows in the same transaction.
     *
     * Why the cascade: `ocr_results.capture_id` has `ON DELETE CASCADE`
     * at the schema level, but that only fires on PHYSICAL DELETE.
     * Soft-deleting a capture (UPDATE deleted_at = …) leaves its
     * `ocr_results` rows with `deleted_at IS NULL` and `dirty = 0`.
     * Result: the sync worker pushes ONLY the capture tombstone to
     * Drive — the child ocr_result JSON files stay in `entityChecksums`,
     * orphaned with no parent. Every device that later restores from
     * that manifest sees ocr_result rows pointing at a capture that's
     * not in the manifest anymore, trips a FK constraint, and the
     * pullDelta apply silently swallows it (pre-PR-B/D) or surfaces
     * it as an `applyFailed` (post-PR-B) / orphan-skip (post-PR-D).
     *
     * Cascade-soft-delete here stops new orphans from being born:
     * children get `deleted_at` stamped in the same transaction, so
     * the parent and its OCR rows go away together.
     *
     * The [markChildrenDirty] flag controls whether the cascaded OCR
     * rows are also marked `dirty = 1` (i.e. eligible for the next
     * sync push). Two callers, two answers:
     *
     *   1. User-initiated delete (`CaptureRepository.delete`,
     *      scan-detail delete button) → `markChildrenDirty = true`
     *      (default). The user wants the deletion mirrored to Drive,
     *      and pushing child tombstones alongside the parent's
     *      retires the orphans cleanly.
     *   2. `QuickInkSyncDataSource.applyRemoteTombstone(KIND_CAPTURE)`
     *      → `markChildrenDirty = false`. Drive already has the
     *      parent tombstone (that's how we got here) and either has
     *      child tombstones too (from a PR-E-aware push) or will get
     *      them via the orphan-cleanup pass (PR-E part 3) on the
     *      next restore. Marking the children dirty here would push
     *      the same tombstones back to Drive AND surface a false
     *      "pending to sync" banner on Home immediately after a
     *      Restore-from-Drive run — the children are already in sync
     *      with what we just pulled.
     *
     * @Transaction wraps both UPDATEs so a concurrent reader can't
     * see a half-applied state where the parent is tombstoned but
     * the children look live.
     */
    @Transaction
    suspend fun softDelete(
        id: String,
        timestamp: String,
        markChildrenDirty: Boolean = true,
    ) {
        softDeleteCaptureRow(id, timestamp)
        softDeleteChildOcrRows(id, timestamp, dirty = markChildrenDirty)
    }

    /**
     * @Query helper for [softDelete]'s parent-row UPDATE. Public on
     * the interface (Kotlin pre-1.5 doesn't allow `private` interface
     * members) but callers should use [softDelete] for the cascade —
     * touching only the captures row would re-introduce the orphan
     * leak this PR is fixing.
     */
    @Query("""
        UPDATE captures
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDeleteCaptureRow(id: String, timestamp: String)

    /**
     * Cascade helper for [softDelete]: soft-deletes every active
     * child OCR row of [id]. The [dirty] flag is forwarded into the
     * row's `dirty` column — `true` (default) when the local user
     * deleted the parent (we want the next sync push to mirror the
     * child tombstones to Drive), `false` when we're applying a
     * remote tombstone (Drive already has them, and re-pushing them
     * would surface a false "pending to sync" pill).
     *
     * Filtering on `deleted_at IS NULL` keeps an already-tombstoned
     * child idempotent (no spurious `updated_at` bump).
     */
    @Query("""
        UPDATE ocr_results
        SET deleted_at = :timestamp,
            updated_at = :timestamp,
            dirty      = :dirty
        WHERE capture_id = :id
          AND deleted_at IS NULL
    """)
    suspend fun softDeleteChildOcrRows(id: String, timestamp: String, dirty: Boolean)

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
     * Update the user-editable title on a capture. Same dirty-bit
     * pattern as [setCategory] — the next sync pass mirrors the new
     * value to Drive. Pass `null` to clear the title (which makes
     * the Library card fall back to OCR snippet → category →
     * "Untitled scan").
     */
    @Query("""
        UPDATE captures
        SET title = :title, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setTitle(id: String, title: String?, timestamp: String)

    /**
     * Backfill the reverse-geocoded place name + full address on a
     * capture whose coordinates landed without them at scan time
     * (rate-limited Geocoder, offline, or a remote area the system
     * couldn't resolve). Called from the Details screen on open
     * when the row has lat/lon but missing names — the re-tried
     * geocode persists here and propagates to other devices on the
     * next Drive push (dirty=1 + updated_at bumped).
     */
    @Query("""
        UPDATE captures
        SET locality = :locality, sub_locality = :subLocality,
            address = :address,
            updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setLocation(
        id: String,
        locality: String?,
        subLocality: String?,
        address: String?,
        timestamp: String,
    )

    /**
     * Bulk-update [oldName] → [newName] across every capture for
     * the given user. Used by [TagRepository.renameAndPropagate]
     * so a category rename in Settings doesn't orphan historical
     * tags. Bumps `updated_at` + `dirty` on each touched row.
     */
    @Query("""
        UPDATE captures
        SET category = :newName, updated_at = :timestamp, dirty = 1
        WHERE user_id = :userId AND category = :oldName
    """)
    suspend fun renameCategory(userId: String, oldName: String, newName: String, timestamp: String)

    // ─── Workspace v1 (Phase A.3) ────────────────────────────────

    /**
     * Move every active capture currently in [folderId] to
     * [newFolderId]. Used by [FolderRepository.softDelete] to
     * relocate the folder's contents to Unfiled before tombstoning
     * the folder row. Bumps `updated_at` + `dirty` on each touched
     * row so the move propagates via sync.
     */
    @Query("""
        UPDATE captures
        SET folder_id  = :newFolderId,
            updated_at = :timestamp,
            dirty      = 1
        WHERE folder_id = :folderId
          AND deleted_at IS NULL
    """)
    suspend fun moveCapturesToFolder(folderId: String, newFolderId: String, timestamp: String)

    /**
     * Assign every active capture with `folder_id IS NULL` to the
     * given folder (typically the seeded "Unfiled"). One-time
     * backfill called from [FolderRepository.backfillFolderIdsIfNeeded]
     * on first launch after the v9 upgrade. Bumps `updated_at` +
     * `dirty` so the backfill propagates to other devices.
     */
    @Query("""
        UPDATE captures
        SET folder_id  = :folderId,
            updated_at = :timestamp,
            dirty      = 1
        WHERE user_id = :userId
          AND folder_id IS NULL
          AND deleted_at IS NULL
    """)
    suspend fun assignOrphanCapturesToFolder(userId: String, folderId: String, timestamp: String)

    /**
     * Active captures with a non-null `category` value, for the
     * one-time materialize-into-capture_tags pass. Used by
     * [FolderRepository.materializeCategoryToTagsIfNeeded]. Caller
     * iterates the result and writes a `capture_tags` row per
     * (capture_id, tag_id) pair.
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId
          AND category IS NOT NULL
          AND deleted_at IS NULL
    """)
    suspend fun listWithCategory(userId: String): List<CaptureEntity>

    /**
     * Mark the user's last-opened position on a capture, debounced
     * by the PDF reader (~500ms after the user lands on a page).
     * Single row update; the Workspace home Continue card reads the
     * most-recent across the user via [findContinueCandidate].
     */
    @Query("""
        UPDATE captures
        SET last_opened_at     = :openedAt,
            last_opened_page   = :page,
            last_opened_device = :deviceId,
            updated_at         = :openedAt,
            dirty              = 1
        WHERE id = :id
    """)
    suspend fun setLastOpened(id: String, openedAt: String, page: Int, deviceId: String?)

    /**
     * Most-recently-opened capture for this user, if any. Powers
     * the Workspace home Continue card. Returns NULL when the user
     * has never opened a capture (or after a fresh install).
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId
          AND last_opened_at IS NOT NULL
          AND deleted_at IS NULL
        ORDER BY last_opened_at DESC
        LIMIT 1
    """)
    suspend fun findContinueCandidate(userId: String): CaptureEntity?

    /**
     * Active-capture count per folder for the user. Drives the
     * "N items" badges on the Workspace home folder list. Captures
     * without a folder_id (post-migration backfill should leave
     * none) are excluded.
     */
    @Query("""
        SELECT folder_id AS folder_id, COUNT(*) AS count
        FROM captures
        WHERE user_id = :userId
          AND folder_id IS NOT NULL
          AND deleted_at IS NULL
        GROUP BY folder_id
    """)
    suspend fun countByFolder(userId: String): List<FolderCaptureCount>

    /**
     * Captures whose category contains the substring (case-
     * insensitive, space-insensitive). Used as the "fast" pass of
     * search. Spaces are stripped from both sides of the comparison
     * so a category named "todo" matches a search for "to do" (and
     * vice versa). Returns every match — the UI uses LazyColumn so
     * render cost stays bounded by the visible window, not the
     * total row count.
     */
    @Query("""
        SELECT * FROM captures
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND category IS NOT NULL
          AND replace(lower(category), ' ', '') LIKE replace(lower(:like), ' ', '')
        ORDER BY created_at DESC
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

    /**
     * Rewrite any occurrence of [oldFrag] in `pdf_uri` / `preview_uri`
     * with [newFrag]. Used by the QuickInk attachment-folder rename
     * (`releaf/attachments/` → `quickink/attachments/`) so existing
     * captures keep resolving after the move on disk. Touches only
     * rows that match the substring so unrelated rows aren't dirtied,
     * and deliberately doesn't bump `dirty` — these URIs are local
     * paths, and remote payloads get the URI rewritten on download
     * regardless. Returns the number of rows updated.
     */
    @Query("""
        UPDATE captures
        SET pdf_uri     = REPLACE(pdf_uri,     :oldFrag, :newFrag),
            preview_uri = REPLACE(preview_uri, :oldFrag, :newFrag)
        WHERE pdf_uri     LIKE '%' || :oldFrag || '%'
           OR preview_uri LIKE '%' || :oldFrag || '%'
    """)
    suspend fun rewriteAttachmentPaths(oldFrag: String, newFrag: String): Int

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

/**
 * Projection for [CaptureDao.countByFolder]. One row per folder
 * with active (non-tombstoned) captures.
 */
data class FolderCaptureCount(
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "count")     val count: Int,
)
