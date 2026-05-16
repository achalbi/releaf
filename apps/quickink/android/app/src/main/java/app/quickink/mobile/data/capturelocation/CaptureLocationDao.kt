/*
 * CaptureLocationDao.kt
 *
 * Room DAO for `capture_locations`. Attach / detach goes through
 * [attachLocation] / [detachLocation] so the unique-active soft-delete
 * dance (re-attach after detach) stays in one place. Mirror of
 * CaptureTagDao.
 */

package app.quickink.mobile.data.capturelocation

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureLocationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CaptureLocationEntity): Long

    @Update
    suspend fun update(entity: CaptureLocationEntity)

    @Query("SELECT * FROM capture_locations WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CaptureLocationEntity?

    @Transaction
    suspend fun upsertFromRemote(entity: CaptureLocationEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Idempotent attach. If an active join row already exists for the
     * pair, no-op. If a tombstoned row exists, revive it. Otherwise
     * insert a fresh row. Preserves the existing row's id so the
     * Drive payload filename doesn't churn across attach/detach.
     */
    @Transaction
    suspend fun attachLocation(
        joinId: String,
        captureId: String,
        locationId: String,
        source: String,
        timestamp: String,
    ) {
        val existing = findPair(captureId, locationId)
        if (existing == null) {
            insert(
                CaptureLocationEntity(
                    id          = joinId,
                    captureId   = captureId,
                    locationId  = locationId,
                    source      = source,
                    driveFileId = null,
                    createdAt   = timestamp,
                    updatedAt   = timestamp,
                    dirty       = true,
                    deletedAt   = null,
                ),
            )
            return
        }
        if (existing.deletedAt != null) {
            update(
                existing.copy(
                    deletedAt = null,
                    updatedAt = timestamp,
                    source    = source,
                    dirty     = true,
                ),
            )
        }
    }

    @Transaction
    suspend fun detachLocation(captureId: String, locationId: String, timestamp: String) {
        val existing = findPair(captureId, locationId) ?: return
        if (existing.deletedAt != null) return
        softDeleteById(existing.id, timestamp)
    }

    @Query("""
        SELECT * FROM capture_locations
        WHERE capture_id = :captureId
          AND location_id = :locationId
        ORDER BY deleted_at IS NULL DESC, updated_at DESC
        LIMIT 1
    """)
    suspend fun findPair(captureId: String, locationId: String): CaptureLocationEntity?

    @Query("""
        SELECT location_id FROM capture_locations
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    fun observeLocationIdsForCapture(captureId: String): Flow<List<String>>

    @Query("""
        SELECT location_id FROM capture_locations
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun listLocationIdsForCapture(captureId: String): List<String>

    @Query("""
        SELECT capture_id FROM capture_locations
        WHERE location_id = :locationId AND deleted_at IS NULL
        ORDER BY created_at DESC
    """)
    fun observeCaptureIdsForLocation(locationId: String): Flow<List<String>>

    @Query("""
        SELECT capture_locations.location_id AS location_id, COUNT(*) AS doc_count
        FROM capture_locations
        JOIN captures ON captures.id = capture_locations.capture_id
        WHERE capture_locations.deleted_at IS NULL
          AND captures.deleted_at IS NULL
          AND captures.user_id = :userId
        GROUP BY capture_locations.location_id
    """)
    fun observeLocationCounts(userId: String): Flow<List<LocationCount>>

    @Query("""
        UPDATE capture_locations
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDeleteById(id: String, timestamp: String)

    /**
     * Soft-delete every join row pointing at a location (used when a
     * location itself is deleted — analogous to cascading the tombstone).
     */
    @Query("""
        UPDATE capture_locations
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE location_id = :locationId AND deleted_at IS NULL
    """)
    suspend fun softDeleteByLocationId(locationId: String, timestamp: String)

    @Query("""
        UPDATE capture_locations
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE capture_id = :captureId AND deleted_at IS NULL
    """)
    suspend fun softDeleteByCaptureId(captureId: String, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM capture_locations WHERE dirty = 1")
    suspend fun dirtyRows(): List<CaptureLocationEntity>

    @Query("""
        UPDATE capture_locations
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE capture_locations
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}

data class LocationCount(
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(name = "doc_count")   val docCount: Int,
)
