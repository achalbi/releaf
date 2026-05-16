/*
 * LocationDao.kt
 *
 * Room DAO for `locations`. CRUD + observation for the Home chip rail
 * and the location picker. Mirror of TagDao.
 */

package app.quickink.mobile.data.location

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LocationEntity): Long

    @Update
    suspend fun update(entity: LocationEntity)

    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LocationEntity?

    @Query("""
        SELECT * FROM locations
        WHERE user_id = :userId
          AND name = :name
          AND deleted_at IS NULL
        LIMIT 1
    """)
    suspend fun findByName(userId: String, name: String): LocationEntity?

    @Transaction
    suspend fun upsertFromRemote(entity: LocationEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    @Query("""
        SELECT * FROM locations
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    fun observeActive(userId: String): Flow<List<LocationEntity>>

    @Query("""
        SELECT * FROM locations
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    suspend fun listActive(userId: String): List<LocationEntity>

    @Query("""
        UPDATE locations
        SET name = :newName, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun rename(id: String, newName: String, timestamp: String)

    @Query("""
        UPDATE locations
        SET color = :color, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setColor(id: String, color: String?, timestamp: String)

    /**
     * Set / clear the physical place attached to a location row.
     * Pass null lat/lng/address to clear; pass all three together
     * after a "Use current location" or "Search address" pick.
     */
    @Query("""
        UPDATE locations
        SET latitude = :latitude,
            longitude = :longitude,
            address = :address,
            updated_at = :timestamp,
            dirty = 1
        WHERE id = :id
    """)
    suspend fun setCoordinates(
        id: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        timestamp: String,
    )

    @Query("""
        UPDATE locations
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    @Query("""
        UPDATE locations
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM locations WHERE dirty = 1")
    suspend fun dirtyRows(): List<LocationEntity>

    @Query("""
        UPDATE locations
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE locations
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
