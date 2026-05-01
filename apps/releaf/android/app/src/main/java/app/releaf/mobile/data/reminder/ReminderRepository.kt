/*
 * ReminderRepository.kt
 *
 * Data seam for reminders. Wraps the DAO and folds in the alarm-
 * scheduling side-effects so the VM doesn't have to know about
 * AlarmManager at all — create/update/complete/delete all flow through
 * here and the corresponding schedule/cancel calls happen
 * automatically.
 */

package app.releaf.mobile.data.reminder

import android.content.Context
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class ReminderRepository(
    private val context: Context,
    private val dao: ReminderDao,
) {
    fun observeActive(userId: String): Flow<List<ReminderEntity>> =
        dao.observeActive(userId)

    fun observeById(id: String): Flow<ReminderEntity?> =
        dao.observeById(id)

    /**
     * Observe the single active reminder linked to a task, or null if
     * the task has no reminder. Used by the Edit-task sheet to drive
     * its reminder chip reactively.
     */
    fun observeActiveByTaskId(taskId: String): Flow<ReminderEntity?> =
        dao.observeActiveByTaskId(taskId)

    suspend fun findById(id: String): ReminderEntity? =
        dao.findById(id)

    /**
     * Create a new reminder. Schedules the alarm immediately. Returns
     * the persisted row so the caller can navigate / select without a
     * second DB hit. Pass a non-null [taskId] to mark the reminder as
     * owned by that task — used by the Tasks screen. Pass a positive
     * [recursEveryDays] to create a recurring reminder — the alarm
     * receiver will re-arm the next fire N days later.
     */
    suspend fun create(
        userId: String,
        title: String,
        note: String?,
        remindAt: Long,
        taskId: String? = null,
        recursEveryDays: Int? = null,
        perspectiveId: String? = null,
    ): ReminderEntity {
        val now = System.currentTimeMillis()
        val entry = ReminderEntity(
            id              = Uuidv7.generate(),
            userId          = userId,
            taskId          = taskId,
            perspectiveId   = perspectiveId,
            title           = title.trim(),
            note            = note?.trim()?.ifEmpty { null },
            remindAt        = remindAt,
            recursEveryDays = recursEveryDays?.takeIf { it > 0 },
            createdAt       = now,
            updatedAt       = now,
        )
        dao.upsert(entry)
        if (remindAt > now) {
            ReminderScheduler.schedule(context, entry.id, remindAt)
        }
        return entry
    }

    /**
     * Set (or replace) the reminder for a task. If the task already
     * has one, it's soft-deleted first so we never stack two alarms.
     * Title carries the task title so the notification reads right on
     * the lock screen without any extra plumbing.
     */
    suspend fun setForTask(
        userId: String,
        taskId: String,
        title: String,
        remindAt: Long,
    ): ReminderEntity {
        clearForTask(taskId)
        return create(
            userId   = userId,
            title    = title,
            note     = null,
            remindAt = remindAt,
            taskId   = taskId,
        )
    }

    /**
     * Soft-delete any active reminder linked to a task and cancel its
     * alarm. Idempotent — no-op if the task has no reminder.
     */
    suspend fun clearForTask(taskId: String) {
        val existing = dao.findActiveByTaskId(taskId) ?: return
        val now = System.currentTimeMillis()
        dao.softDelete(existing.id, now)
        ReminderScheduler.cancel(context, existing.id)
    }

    /**
     * Edit an existing reminder in place. Re-schedules the alarm
     * whenever `remindAt` moves, so changing the time from the
     * editor picks up the new moment without a manual cancel call.
     * Pass a [recursEveryDays] value (including null to clear) to
     * also update the recurrence interval.
     */
    suspend fun update(
        id: String,
        title: String,
        note: String?,
        remindAt: Long,
        recursEveryDays: Int? = null,
        clearRecurrence: Boolean = false,
        perspectiveId: String? = null,
        clearPerspective: Boolean = false,
    ): ReminderEntity? {
        val existing = dao.findById(id) ?: return null
        val now = System.currentTimeMillis()
        val nextRecurrence = when {
            clearRecurrence           -> null
            recursEveryDays == null   -> existing.recursEveryDays
            recursEveryDays <= 0      -> null
            else                      -> recursEveryDays
        }
        val nextPerspective = when {
            clearPerspective        -> null
            perspectiveId == null   -> existing.perspectiveId
            else                    -> perspectiveId
        }
        val updated = existing.copy(
            title           = title.trim(),
            note            = note?.trim()?.ifEmpty { null },
            remindAt        = remindAt,
            recursEveryDays = nextRecurrence,
            perspectiveId   = nextPerspective,
            // Re-opening an already-fired reminder should re-arm it.
            firedAt         = if (remindAt > now) null else existing.firedAt,
            updatedAt       = now,
        )
        dao.upsert(updated)

        // Always cancel the old alarm first — even when the time didn't
        // change, a title edit shouldn't leave two copies around if a
        // previous call slipped the PendingIntent flag.
        ReminderScheduler.cancel(context, id)
        if (remindAt > now && updated.completedAt == null) {
            ReminderScheduler.schedule(context, id, remindAt)
        }
        return updated
    }

    /**
     * After a recurring reminder fires, advance its `remindAt` by
     * `recurs_every_days × 1 day` and re-schedule. Called by
     * [ReminderAlarmReceiver] instead of [markFired] when the row
     * has a non-null `recursEveryDays`, so the reminder stays in
     * the upcoming section and the user never has to "re-enable" a
     * daily / weekly cadence.
     *
     * No-op if the row has no recurrence set, has been completed,
     * or has been soft-deleted.
     */
    suspend fun advanceRecurring(id: String) {
        val row = dao.findById(id) ?: return
        val interval = row.recursEveryDays ?: return
        if (row.deletedAt != null || row.completedAt != null || interval <= 0) return
        val nextRemindAt = row.remindAt + interval * MS_PER_DAY
        val now = System.currentTimeMillis()
        val updated = row.copy(
            remindAt  = nextRemindAt,
            // Clear firedAt so the row stays "upcoming" — the alarm
            // we re-schedule below will do the next fire.
            firedAt   = null,
            updatedAt = now,
        )
        dao.upsert(updated)
        ReminderScheduler.schedule(context, id, nextRemindAt)
    }

    private companion object {
        const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }

    suspend fun markCompleted(id: String) {
        val now = System.currentTimeMillis()
        dao.markCompleted(id, now)
        // Completion implicitly cancels the future fire.
        ReminderScheduler.cancel(context, id)
    }

    suspend fun markActive(id: String) {
        val existing = dao.findById(id) ?: return
        val now = System.currentTimeMillis()
        dao.markActive(id, now)
        if (existing.remindAt > now) {
            ReminderScheduler.schedule(context, id, existing.remindAt)
        }
    }

    suspend fun softDelete(id: String) {
        val now = System.currentTimeMillis()
        dao.softDelete(id, now)
        ReminderScheduler.cancel(context, id)
    }

    /**
     * Re-register alarms for every pending reminder. Called on boot
     * (alarm queue is cleared by the OS) and on app start (defensive —
     * process death between creating a reminder and OS persisting the
     * alarm leaves an inconsistent state).
     */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val pending = dao.pendingAfter(now)
        pending.forEach { ReminderScheduler.schedule(context, it.id, it.remindAt) }
    }

    /** Called by [ReminderAlarmReceiver] once the notification has been
     *  posted. Stamps `fired_at` so the list UI can split past vs.
     *  upcoming without a separate time comparison on every row. */
    suspend fun markFired(id: String) {
        dao.markFired(id, System.currentTimeMillis())
    }
}
