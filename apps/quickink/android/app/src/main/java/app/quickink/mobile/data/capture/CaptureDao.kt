/*
 * CaptureDao.kt
 *
 * Room DAO for `captures`. Minimal CRUD + observation queries —
 * the camera+scan flow (Slice 3) extends this with whatever feature-
 * specific queries it needs.
 */

package app.quickink.mobile.data.capture

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
