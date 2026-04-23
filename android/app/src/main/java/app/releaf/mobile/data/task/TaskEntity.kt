/*
 * TaskEntity.kt
 *
 * Room entity mirroring the `tasks` table in
 * design-system/migrations/v1_initial.sql (§5). Only the columns the
 * v1 UI actually reads are materialised here — project_id, position,
 * and the subtask/reminder tables are parked until the features that
 * need them land.
 *
 * Tasks are a workspace-level concept (distinct from the inline todos
 * that live on a page or notepad entry). A todo is "promoted" to a
 * task when it needs tracking outside its originating page; the
 * promote path will copy the todo text into a new `tasks` row and
 * keep the two in sync. For now the Tasks screen only supports
 * directly-created tasks — the promote path is tracked as follow-up.
 */

package app.releaf.mobile.data.task

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Kanban-style lifecycle marker, distinct from [TaskEntity.completed].
 *
 *   • [Todo]  — captured, not started. Default for new tasks.
 *   • [Doing] — actively in progress. Surfaces in the "Doing" column
 *               of the Boards view and as a visible badge on the row.
 *   • [Done]  — completed. Kept in sync with [TaskEntity.completed]
 *               (and [TaskEntity.completedAt]) at the repository
 *               layer so existing "open count" / "completed filter"
 *               queries keep working unchanged.
 *
 * Stored as the lowercase [wire] string — the default column value
 * in the schema is `'todo'`, so pre-v7 rows that don't have the
 * column migrated get materialised as [Todo] on next read.
 */
enum class TaskStatus(val wire: String) {
    Todo("todo"),
    Doing("doing"),
    Done("done");

    companion object {
        /** Lenient parse — anything we don't recognise falls back to [Todo]. */
        fun parse(raw: String?): TaskStatus = when (raw) {
            "doing" -> Doing
            "done"  -> Done
            else    -> Todo
        }
    }
}

@Entity(
    tableName = "tasks",
    indices = [
        Index("user_id"),
        Index("due_date"),
        Index("completed"),
        Index("status"),
        Index("updated_at"),
        Index("deleted_at"),
    ],
)
data class TaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Scoping column — tasks are per-user, same as notepad entries. */
    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Longer free-form notes. Optional. */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /**
     * Local YYYY-MM-DD the task is due on. Null = no due date. The
     * schema CHECK constraint gates the GLOB shape; Room doesn't
     * reproduce that here but every write path in this module goes
     * through IsoClock / format helpers that preserve it.
     */
    @ColumnInfo(name = "due_date")
    val dueDate: String? = null,

    /** Room stores Bool as INTEGER 0/1, matching the SQL shape. */
    @ColumnInfo(name = "completed", defaultValue = "0")
    val completed: Boolean = false,

    /** ISO-8601 UTC with ms; set at the moment the user ticks the box. */
    @ColumnInfo(name = "completed_at")
    val completedAt: String? = null,

    /**
     * 0 = none, 1 = low, 2 = medium, 3 = high — mirrors
     * `TodoItem.priority` so a promoted todo keeps its level.
     */
    @ColumnInfo(name = "priority", defaultValue = "0")
    val priority: Int = 0,

    /**
     * Kanban lifecycle marker — see [TaskStatus]. Stored as its wire
     * string (`"todo"` / `"doing"` / `"done"`). Kept in sync with
     * [completed] at the repository layer — setting status=done also
     * flips [completed] + [completedAt] so the existing sort / count
     * queries still work without knowing about status.
     */
    @ColumnInfo(name = "status", defaultValue = "todo")
    val status: String = TaskStatus.Todo.wire,

    /** ISO-8601 UTC with ms. See IsoClock. */
    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** 1 = needs upload to Drive; cleared by sync worker. */
    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    /** ISO-8601 UTC when soft-deleted; null = active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
