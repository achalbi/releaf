/*
 * CapturePersonDao.kt
 *
 * Room DAO for `capture_people`. Attach / detach goes through
 * [attachPerson] / [detachPerson] so the unique-active soft-delete
 * dance (re-attach after detach) stays in one place. Mirror of
 * CaptureLocationDao.
 */

package app.quickink.mobile.data.captureperson

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CapturePersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CapturePersonEntity): Long

    @Update
    suspend fun update(entity: CapturePersonEntity)

    @Query("SELECT * FROM capture_people WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CapturePersonEntity?

    @Transaction
    suspend fun upsertFromRemote(entity: CapturePersonEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Idempotent attach. If an active join row already exists for
     * the pair, no-op. If a tombstoned row exists, revive it.
     * Otherwise insert a fresh row.
     */
    @Transaction
    suspend fun attachPerson(
        joinId: String,
        captureId: String,
        personId: String,
        source: String,
        timestamp: String,
    ) {
        val existing = findPair(captureId, personId)
        if (existing == null) {
            insert(
                CapturePersonEntity(
                    id          = joinId,
                    captureId   = captureId,
                    personId    = personId,
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
    suspend fun detachPerson(captureId: String, personId: String, timestamp: String) {
        val existing = findPair(captureId, personId) ?: return
        if (existing.deletedAt != null) return
        softDeleteById(existing.id, timestamp)
    }

    @Query("""
        SELECT * FROM capture_people
        WHERE capture_id = :captureId
          AND person_id = :personId
        ORDER BY deleted_at IS NULL DESC, updated_at DESC
        LIMIT 1
    """)
    suspend fun findPair(captureId: String, personId: String): CapturePersonEntity?

    @Query("""
        SELECT person_id FROM capture_people
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    fun observePersonIdsForCapture(captureId: String): Flow<List<String>>

    @Query("""
        SELECT person_id FROM capture_people
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun listPersonIdsForCapture(captureId: String): List<String>

    @Query("""
        SELECT capture_id FROM capture_people
        WHERE person_id = :personId AND deleted_at IS NULL
        ORDER BY created_at DESC
    """)
    fun observeCaptureIdsForPerson(personId: String): Flow<List<String>>

    @Query("""
        SELECT capture_people.person_id AS person_id, COUNT(*) AS doc_count
        FROM capture_people
        JOIN captures ON captures.id = capture_people.capture_id
        WHERE capture_people.deleted_at IS NULL
          AND captures.deleted_at IS NULL
          AND captures.user_id = :userId
        GROUP BY capture_people.person_id
    """)
    fun observePersonCounts(userId: String): Flow<List<PersonCount>>

    @Query("""
        UPDATE capture_people
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDeleteById(id: String, timestamp: String)

    /**
     * Soft-delete every join row pointing at a person (used when a
     * person itself is deleted — analogous to cascading the
     * tombstone).
     */
    @Query("""
        UPDATE capture_people
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE person_id = :personId AND deleted_at IS NULL
    """)
    suspend fun softDeleteByPersonId(personId: String, timestamp: String)

    @Query("""
        UPDATE capture_people
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE capture_id = :captureId AND deleted_at IS NULL
    """)
    suspend fun softDeleteByCaptureId(captureId: String, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM capture_people WHERE dirty = 1")
    suspend fun dirtyRows(): List<CapturePersonEntity>

    @Query("""
        UPDATE capture_people
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE capture_people
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}

data class PersonCount(
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "doc_count") val docCount: Int,
)
