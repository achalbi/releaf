/*
 * ReminderDao.kt
 *
 * Queries exposed by the Reminder feature. Soft-deletes filtered at the
 * DAO layer so callers never need to remember `deleted_at IS NULL`.
 * Flow-returning queries observe writes transparently — the list
 * screen re-renders when the alarm receiver marks a reminder fired.
 */

package app.releaf.mobile.data.reminder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    /** All active (non-deleted) reminders for a user, newest `remind_at` last
     *  so the list naturally reads top-to-bottom as "happening next". */
    @Query(
        """
        SELECT * FROM reminders
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY remind_at ASC
        """
    )
    fun observeActive(userId: String): Flow<List<ReminderEntity>>

    @Query(
        """
        SELECT * FROM reminders
        WHERE id = :id AND deleted_at IS NULL
        LIMIT 1
        """
    )
    fun observeById(id: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ReminderEntity?

    /**
     * Observe the single active reminder linked to a task — null when
     * the task has no reminder set or the previous one was cleared.
     * The Edit-task sheet collects this to drive its reminder chip.
     */
    @Query(
        """
        SELECT * FROM reminders
        WHERE task_id = :taskId AND deleted_at IS NULL
        ORDER BY remind_at ASC
        LIMIT 1
        """
    )
    fun observeActiveByTaskId(taskId: String): Flow<ReminderEntity?>

    /** One-shot variant of [observeActiveByTaskId]. */
    @Query(
        """
        SELECT * FROM reminders
        WHERE task_id = :taskId AND deleted_at IS NULL
        ORDER BY remind_at ASC
        LIMIT 1
        """
    )
    suspend fun findActiveByTaskId(taskId: String): ReminderEntity?

    /** Rows whose scheduled time is in the future — the scheduler
     *  re-registers these with AlarmManager after a boot. */
    @Query(
        """
        SELECT * FROM reminders
        WHERE deleted_at IS NULL
          AND completed_at IS NULL
          AND remind_at > :nowMs
        """
    )
    suspend fun pendingAfter(nowMs: Long): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ReminderEntity)

    @Query(
        """
        UPDATE reminders
        SET completed_at = :nowMs, updated_at = :nowMs
        WHERE id = :id
        """
    )
    suspend fun markCompleted(id: String, nowMs: Long)

    @Query(
        """
        UPDATE reminders
        SET completed_at = NULL, updated_at = :nowMs
        WHERE id = :id
        """
    )
    suspend fun markActive(id: String, nowMs: Long)

    @Query(
        """
        UPDATE reminders
        SET fired_at = :nowMs, updated_at = :nowMs
        WHERE id = :id
        """
    )
    suspend fun markFired(id: String, nowMs: Long)

    @Query(
        """
        UPDATE reminders
        SET deleted_at = :nowMs, updated_at = :nowMs
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowMs: Long)
}
