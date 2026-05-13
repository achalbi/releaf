/*
 * TagDao.kt
 *
 * Room DAO for `tags`. CRUD + observation queries for the Settings
 * → Tags screen, the scan-review picker, the Workspace home tag
 * cloud (Screen 1), and the tag picker bottom sheet (Screen 6).
 *
 * `tags.name` has a UNIQUE (user_id, name) constraint from v2 (now
 * inherited by the renamed `tags` table per v4_workspace.sql);
 * `insert` uses `IGNORE` so a rename-collision is a no-op rather
 * than throwing — Settings UI surfaces the failure via the Int
 * return code.
 *
 * Renamed from `CategoryDao` in Phase A.2 of the Workspace
 * redesign. Query and method semantics are identical; only the
 * type / table names changed.
 */

package app.quickink.mobile.data.tag

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TagEntity): Long

    @Update
    suspend fun update(entity: TagEntity)

    /**
     * Single-row lookup. Used by [upsertFromRemote] to compare local
     * vs remote `updated_at` before updating, and useful elsewhere as
     * a one-shot fetch. Mirrors `CaptureDao.findById`.
     */
    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TagEntity?

    /**
     * Look up a tag by name within a user's namespace. Used by the
     * tag picker sheet to dedupe "type the same name twice" and by
     * the auto-tagging heuristic to map a recognized keyword
     * ("invoice") to an existing tag row.
     */
    @Query("""
        SELECT * FROM tags
        WHERE user_id = :userId
          AND name = :name
          AND deleted_at IS NULL
        LIMIT 1
    """)
    suspend fun findByName(userId: String, name: String): TagEntity?

    /**
     * Upsert from a remote payload, last-write-wins on `updated_at`.
     * See `CategoryDao.upsertFromRemote` (the previous incarnation)
     * for the rationale.
     */
    @Transaction
    suspend fun upsertFromRemote(entity: TagEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Live list of a user's active tags, ordered by `position` then
     * `name` for stable output when positions tie. Bound to the
     * Settings → Tags list, the scan-review picker, and the
     * Workspace home tag cloud.
     */
    @Query("""
        SELECT * FROM tags
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    fun observeActive(userId: String): Flow<List<TagEntity>>

    @Query("""
        SELECT * FROM tags
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    suspend fun listActive(userId: String): List<TagEntity>

    @Query("""
        UPDATE tags
        SET name = :newName, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun rename(id: String, newName: String, timestamp: String)

    @Query("""
        UPDATE tags
        SET color = :color, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setColor(id: String, color: String?, timestamp: String)

    /**
     * Soft-delete: stamp `deleted_at` so the sync worker mirrors
     * the tombstone to Drive on its next pass. Callers must also
     * tombstone the `capture_tags` rows that reference this tag
     * (see CaptureTagDao.softDeleteByTagId) so the per-row sync
     * propagates the removal to other devices.
     */
    @Query("""
        UPDATE tags
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    @Query("""
        UPDATE tags
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    // ─── Sync surface (Phase 4) ───────────────────────────────────

    @Query("SELECT * FROM tags WHERE dirty = 1")
    suspend fun dirtyRows(): List<TagEntity>

    @Query("""
        UPDATE tags
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE tags
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
