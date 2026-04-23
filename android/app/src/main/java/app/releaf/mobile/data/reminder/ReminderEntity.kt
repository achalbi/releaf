/*
 * ReminderEntity.kt
 *
 * Room entity backing the `reminders` table. Schema is intentionally flat
 * and local-first — reminders don't sync to Drive today because the
 * notification firing is strictly a device concern (alarms don't survive
 * account transfers in a useful way). The same row shape lets us
 * layer sync on later if we decide to.
 */

package app.releaf.mobile.data.reminder

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
    indices = [
        Index("user_id"),
        Index("task_id"),
        Index("perspective_id"),
        Index("remind_at"),
        Index("completed_at"),
        Index("deleted_at"),
    ],
)
data class ReminderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * Foreign-key-style link to [app.releaf.mobile.data.task.TaskEntity.id]
     * for reminders owned by a task. Null = standalone reminder
     * (the existing free-floating Reminders screen). Indexed for the
     * "find reminder for this task" lookup the Edit-task sheet does.
     */
    @ColumnInfo(name = "task_id")
    val taskId: String? = null,

    /**
     * FK-style link to [app.releaf.mobile.data.perspective.PerspectiveEntity.id].
     * Null = no perspective assigned. When set, the list UI pulls
     * the icon + colour from the matching perspective tile, which
     * is more accurate than parsing `@tag` out of the title (the
     * title is free-form and can have `@email.com` false positives).
     * The @tag-in-title route still works as a fallback for rows
     * that pre-date this column.
     */
    @ColumnInfo(name = "perspective_id")
    val perspectiveId: String? = null,

    /** Free-form title, required and always non-blank. */
    @ColumnInfo(name = "title")
    val title: String,

    /** Optional long-form note body. */
    @ColumnInfo(name = "note")
    val note: String? = null,

    /**
     * UTC epoch millis at which the notification should fire. Stored as
     * a bare long so AlarmManager can consume it without re-parsing on
     * every schedule call.
     */
    @ColumnInfo(name = "remind_at")
    val remindAt: Long,

    /** Epoch millis when the user marked the reminder done; null = active. */
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    /** Epoch millis when the reminder fired and posted a notification.
     *  Used by the list to collapse "past" reminders under a header. */
    @ColumnInfo(name = "fired_at")
    val firedAt: Long? = null,

    /**
     * Recurrence interval in days. Null = one-shot reminder (the
     * default, matches all pre-v10 rows). Non-null = every N days —
     * after firing the alarm receiver advances `remind_at` by this
     * many days and re-schedules, keeping the row in the upcoming
     * section without a second DB round-trip from the caller.
     *
     * Common values used by the editor's preset chips:
     *   1 → Daily · 7 → Weekly · 14 → Every 2 weeks · 30 → Every month
     * Any positive integer is valid; the UI just exposes a short list.
     */
    @ColumnInfo(name = "recurs_every_days")
    val recursEveryDays: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /** Soft-delete timestamp. Null = active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)
