/*
 * TaskDao.kt
 *
 * All task queries the UI needs. Flows where reactivity matters,
 * suspend fns for one-shots. Soft-deletes are filtered here
 * (deleted_at IS NULL) so callers never have to remember.
 *
 * Ordering rule: open tasks first (completed = 0), then by due date
 * ascending (nulls last), then by updated_at desc. Rendering this at
 * the DAO layer keeps the Tasks screen's ViewModel simple — it just
 * collects and displays.
 */

package app.releaf.mobile.data.task

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /** Active (not-deleted) tasks for a user. */
    @Query(
        """
        SELECT * FROM tasks
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY
            completed ASC,
            CASE WHEN due_date IS NULL THEN 1 ELSE 0 END ASC,
            due_date ASC,
            updated_at DESC
        """
    )
    fun observeActive(userId: String): Flow<List<TaskEntity>>

    /** One-shot lookup used by the detail sheet / edit flow. */
    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): TaskEntity?

    /**
     * Count of open (not-completed, not-deleted) tasks for a user.
     * Powers the home-screen card's "N open" badge without forcing
     * the caller to collect the whole list.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tasks
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND completed = 0
        """
    )
    fun observeOpenCount(userId: String): Flow<Int>

    /** Insert-or-replace. Callers bump updated_at. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    /**
     * Toggle the completed flag. Bumps updated_at + dirty so sync
     * picks it up, and keeps `status` in sync so the Boards view
     * reflects list-view tick changes without a re-query elsewhere:
     *
     *   • completed = true  → status = "done"
     *   • completed = false → status = "todo" (re-opening goes back
     *                         to To do, not Doing — "Doing" is only
     *                         reached by explicit Start action)
     */
    @Query(
        """
        UPDATE tasks
        SET completed    = :completed,
            completed_at = :completedAt,
            status       = :status,
            updated_at   = :nowIso,
            dirty        = 1
        WHERE id = :id
        """
    )
    suspend fun setCompleted(
        id: String,
        completed: Boolean,
        completedAt: String?,
        status: String,
        nowIso: String,
    )

    /**
     * Move a task between Kanban columns. Writes the status column
     * directly AND reconciles `completed` / `completed_at` so the
     * existing queries keep working:
     *
     *   • status = "done"  → completed = 1, completed_at = nowIso
     *   • status = "doing" → completed = 0, completed_at = null
     *   • status = "todo"  → completed = 0, completed_at = null
     *
     * The repository is the only correct caller — it picks the right
     * (completed, completedAt) pair for the target status.
     */
    @Query(
        """
        UPDATE tasks
        SET status       = :status,
            completed    = :completed,
            completed_at = :completedAt,
            updated_at   = :nowIso,
            dirty        = 1
        WHERE id = :id
        """
    )
    suspend fun setStatus(
        id: String,
        status: String,
        completed: Boolean,
        completedAt: String?,
        nowIso: String,
    )

    /** Soft delete. Flips deleted_at + dirty. */
    @Query(
        """
        UPDATE tasks
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)

    /** Undo — clears deleted_at + re-dirties. */
    @Query(
        """
        UPDATE tasks
        SET deleted_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun restore(id: String, nowIso: String)

    /* ---------- sync worker ---------- */

    /** Every task row that needs to be pushed to Drive (live + tombstoned). */
    @Query("SELECT * FROM tasks WHERE dirty = 1")
    suspend fun dirtyRows(): List<TaskEntity>

    /** Active task rows for manifest reconstruction. */
    @Query("SELECT * FROM tasks WHERE deleted_at IS NULL")
    suspend fun activeRows(): List<TaskEntity>

    /** Lookup-by-ids for the pull path. */
    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<TaskEntity>

    /** Count of live tasks — feeds the sync manifest's entity counts. */
    @Query("SELECT COUNT(*) FROM tasks WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    /**
     * Race-safe clear-dirty. Only flips to dirty=0 when the row's
     * `updated_at` still matches the snapshot the sync pass read —
     * a concurrent edit bumps updated_at, the WHERE misses, and
     * the row stays dirty for the next pass.
     */
    @Query(
        """
        UPDATE tasks
        SET dirty = 0
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
        """
    )
    suspend fun markSynced(id: String, updatedAtSnapshot: String): Int

    /** Clear-dirty for a synced tombstone. */
    @Query(
        """
        UPDATE tasks
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
        """
    )
    suspend fun markTombstoneSynced(id: String): Int
}
