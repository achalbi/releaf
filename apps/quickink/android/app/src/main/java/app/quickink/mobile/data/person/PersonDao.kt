/*
 * PersonDao.kt
 *
 * Room DAO for `people`. CRUD + observation for the Home chip rail
 * and the people picker. Mirror of LocationDao.
 */

package app.quickink.mobile.data.person

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PersonEntity): Long

    @Update
    suspend fun update(entity: PersonEntity)

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PersonEntity?

    @Query("""
        SELECT * FROM people
        WHERE user_id = :userId
          AND name = :name
          AND deleted_at IS NULL
        LIMIT 1
    """)
    suspend fun findByName(userId: String, name: String): PersonEntity?

    @Transaction
    suspend fun upsertFromRemote(entity: PersonEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    @Query("""
        SELECT * FROM people
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    fun observeActive(userId: String): Flow<List<PersonEntity>>

    @Query("""
        SELECT * FROM people
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    suspend fun listActive(userId: String): List<PersonEntity>

    @Query("""
        UPDATE people
        SET name = :newName, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun rename(id: String, newName: String, timestamp: String)

    @Query("""
        UPDATE people
        SET color = :color, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setColor(id: String, color: String?, timestamp: String)

    /**
     * Set or clear the linked device contact. Pass null on every
     * column to unlink. Phone + email are cached snapshots taken at
     * link time; refresh by calling this again after a fresh
     * ContactsContract lookup.
     */
    @Query("""
        UPDATE people
        SET contact_lookup_key = :lookupKey,
            contact_phone = :phone,
            contact_email = :email,
            contact_photo_uri = :photoUri,
            updated_at = :timestamp,
            dirty = 1
        WHERE id = :id
    """)
    suspend fun setContact(
        id: String,
        lookupKey: String?,
        phone: String?,
        email: String?,
        photoUri: String?,
        timestamp: String,
    )

    @Query("""
        UPDATE people
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    @Query("""
        UPDATE people
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM people WHERE dirty = 1")
    suspend fun dirtyRows(): List<PersonEntity>

    @Query("""
        UPDATE people
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE people
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
