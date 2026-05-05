/*
 * CategoryDao.kt
 *
 * Room DAO for `categories`. CRUD + observation queries for the
 * Settings → Categories screen and the scan-review picker.
 *
 * `categories.name` has a UNIQUE (user_id, name) constraint per the
 * v2 migration; `insert` uses `IGNORE` so a rename-collision is a
 * no-op rather than throwing — Settings UI surfaces the failure
 * via the Int return code.
 */

package app.quickink.mobile.data.category

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CategoryEntity): Long

    @Update
    suspend fun update(entity: CategoryEntity)

    /**
     * Single-row lookup. Used by [upsertFromRemote] to compare local
     * vs remote `updated_at` before updating, and useful elsewhere as
     * a one-shot fetch. Mirrors [CaptureDao.findById].
     */
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CategoryEntity?

    /**
     * Upsert from a remote payload, last-write-wins on `updated_at`.
     *
     * Why this exists: the existing [insert] uses
     * `OnConflictStrategy.IGNORE`, which means a remote payload with
     * the same id as an existing local row is **silently dropped on
     * the floor**. That works for the user-facing rename / move flows
     * (callers want a no-op on UNIQUE-name collision), but it silently
     * loses cross-device updates on the restore path. This method
     * inserts when missing AND updates when remote is newer, in a
     * single transaction.
     */
    @Transaction
    suspend fun upsertFromRemote(entity: CategoryEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Live list of a user's active categories, ordered by `position`
     * then `name` for stable output when positions tie. Bound to the
     * Settings → Categories list and the scan-review picker.
     */
    @Query("""
        SELECT * FROM categories
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    fun observeActive(userId: String): Flow<List<CategoryEntity>>

    @Query("""
        SELECT * FROM categories
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    suspend fun listActive(userId: String): List<CategoryEntity>

    @Query("""
        UPDATE categories
        SET name = :newName, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun rename(id: String, newName: String, timestamp: String)

    /**
     * Soft-delete: stamp `deleted_at` so the sync worker mirrors
     * the tombstone to Drive on its next pass. Already-assigned
     * `captures.category` strings keep working — captures.category
     * is a value, not an FK.
     */
    @Query("""
        UPDATE categories
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    @Query("""
        UPDATE categories
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    // ─── Sync surface (Phase 4) ───────────────────────────────────

    @Query("SELECT * FROM categories WHERE dirty = 1")
    suspend fun dirtyRows(): List<CategoryEntity>

    @Query("""
        UPDATE categories
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE categories
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
