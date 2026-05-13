/*
 * FolderDao.kt
 *
 * Room DAO for `folders`. CRUD + observation queries for the
 * Workspace home folder list, the move-capture-to-folder action,
 * and the Settings → Folders surface (TBD).
 *
 * Mirrors the [CategoryDao] / [TagDao] patterns — INSERT uses
 * IGNORE for rename-collision-as-no-op, while
 * [upsertFromRemote] does insert-or-update for the sync path.
 */

package app.quickink.mobile.data.folder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FolderEntity): Long

    @Update
    suspend fun update(entity: FolderEntity)

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): FolderEntity?

    /**
     * The seeded "Unfiled" folder for this user. Used as the
     * fallback destination when a user-folder is deleted (captures
     * move here rather than cascade-delete — per brief §10 #2).
     * Returns null only if seeding hasn't run yet, which should
     * never happen after first launch.
     */
    @Query("""
        SELECT * FROM folders
        WHERE user_id = :userId
          AND is_default = 1
          AND deleted_at IS NULL
        LIMIT 1
    """)
    suspend fun findDefault(userId: String): FolderEntity?

    /**
     * Last-write-wins upsert for the sync restore path. See
     * [CategoryDao.upsertFromRemote] for the rationale.
     */
    @Transaction
    suspend fun upsertFromRemote(entity: FolderEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Live list of a user's active folders, ordered by `position`
     * then `name` for stable output when positions tie. Bound to
     * the Workspace home folder list (Screen 1).
     */
    @Query("""
        SELECT * FROM folders
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    fun observeActive(userId: String): Flow<List<FolderEntity>>

    @Query("""
        SELECT * FROM folders
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    suspend fun listActive(userId: String): List<FolderEntity>

    @Query("""
        UPDATE folders
        SET name = :newName, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun rename(id: String, newName: String, timestamp: String)

    @Query("""
        UPDATE folders
        SET color = :color, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setColor(id: String, color: String, timestamp: String)

    @Query("""
        UPDATE folders
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    /**
     * Soft-delete a folder. The UI must call
     * [moveCapturesToFolder] beforehand to relocate every capture
     * out of this folder (typically into Unfiled). The
     * `is_default = 1` row is non-deletable; the Settings UI
     * suppresses the affordance, and a stray call here would
     * orphan every capture.
     */
    @Query("""
        UPDATE folders
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id AND is_default = 0
    """)
    suspend fun softDelete(id: String, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM folders WHERE dirty = 1")
    suspend fun dirtyRows(): List<FolderEntity>

    @Query("""
        UPDATE folders
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE folders
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
