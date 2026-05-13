/*
 * SmartCollectionDao.kt
 *
 * Room DAO for `smart_collections`. CRUD + observation queries
 * for the Workspace home smart-collection strip (Screen 1) and
 * the smart-collection view (Screen 3).
 *
 * Stats (count, $total, "2 overdue") are recomputed on read — see
 * brief §3. This DAO only manages the collection rows themselves;
 * the query that returns the matching captures lives in
 * SmartCollectionRepository (built on top of the rule JSON).
 */

package app.quickink.mobile.data.smartcollection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartCollectionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SmartCollectionEntity): Long

    @Update
    suspend fun update(entity: SmartCollectionEntity)

    @Query("SELECT * FROM smart_collections WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SmartCollectionEntity?

    @Transaction
    suspend fun upsertFromRemote(entity: SmartCollectionEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Live list of a user's active smart collections, ordered by
     * `position` then `name`. Bound to the Workspace home strip
     * (Screen 1) — seeded collections render first because their
     * positions are reserved 0..N.
     */
    @Query("""
        SELECT * FROM smart_collections
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    fun observeActive(userId: String): Flow<List<SmartCollectionEntity>>

    @Query("""
        SELECT * FROM smart_collections
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY position ASC, name ASC
    """)
    suspend fun listActive(userId: String): List<SmartCollectionEntity>

    /**
     * Seeded rows look-up — used by the seeder to detect whether
     * a given seed collection name already exists for this user
     * (idempotent re-seed). Excludes tombstones so a deleted-and-
     * re-seeded cycle works.
     */
    @Query("""
        SELECT * FROM smart_collections
        WHERE user_id = :userId
          AND is_seeded = 1
          AND deleted_at IS NULL
    """)
    suspend fun listSeeded(userId: String): List<SmartCollectionEntity>

    @Query("""
        UPDATE smart_collections
        SET name = :newName, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun rename(id: String, newName: String, timestamp: String)

    @Query("""
        UPDATE smart_collections
        SET rule_json = :ruleJson, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setRule(id: String, ruleJson: String, timestamp: String)

    /**
     * Persist a user's icon + color pick from the editor's
     * appearance pickers. Either argument can be `NULL` to clear
     * back to the card's defaults. Bumps `updated_at` + `dirty`
     * so the change rides the next sync.
     */
    @Query("""
        UPDATE smart_collections
        SET icon = :icon, color = :color, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setAppearance(
        id: String,
        icon: String?,
        color: String?,
        timestamp: String,
    )

    @Query("""
        UPDATE smart_collections
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    @Query("""
        UPDATE smart_collections
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM smart_collections WHERE dirty = 1")
    suspend fun dirtyRows(): List<SmartCollectionEntity>

    @Query("""
        UPDATE smart_collections
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE smart_collections
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
