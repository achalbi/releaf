/*
 * TaskRepository.kt
 *
 * Thin wrapper over [TaskDao]. Same pattern as NotepadRepository —
 * handles id/timestamp generation so the VM layer stays free of
 * clock/uuid concerns, and exposes typed Flows the UI can collect
 * directly.
 */

package app.releaf.mobile.data.task

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class TaskRepository(
    private val dao: TaskDao,
) {
    fun observeActive(userId: String): Flow<List<TaskEntity>> =
        dao.observeActive(userId)

    fun observeOpenCount(userId: String): Flow<Int> =
        dao.observeOpenCount(userId)

    suspend fun findById(id: String): TaskEntity? = dao.findById(id)

    /**
     * Create a fresh task. `title` is the only required field —
     * dueDate, description, and priority are optional. The new row
     * lands with `dirty = 1` so the sync worker uploads it on its
     * next pass.
     */
    suspend fun create(
        userId: String,
        title: String,
        description: String? = null,
        dueDate: String? = null,
        priority: Int = 0,
    ): TaskEntity {
        val now = IsoClock.nowIso()
        val task = TaskEntity(
            id          = Uuidv7.generate(),
            userId      = userId,
            title       = title.trim(),
            description = description?.trim()?.ifEmpty { null },
            dueDate     = dueDate,
            priority    = priority,
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
        )
        dao.upsert(task)
        return task
    }

    /**
     * Persist edits. Callers pass the modified entity; this bumps
     * `updated_at` + `dirty` unconditionally so the sync worker
     * always sees the row.
     */
    suspend fun save(task: TaskEntity) {
        dao.upsert(
            task.copy(
                title     = task.title.trim(),
                updatedAt = IsoClock.nowIso(),
                dirty     = true,
            )
        )
    }

    /**
     * Flip completed. Stamps `completed_at` on transition to true,
     * clears it on transition back to false. Also keeps the Kanban
     * `status` column in sync — true maps to "done", false to "todo"
     * (re-opening goes to To do, not Doing; the Perspectives view
     * reaches Doing only via explicit Start action).
     */
    suspend fun setCompleted(id: String, completed: Boolean) {
        val now = IsoClock.nowIso()
        dao.setCompleted(
            id          = id,
            completed   = completed,
            completedAt = if (completed) now else null,
            status      = if (completed) TaskStatus.Done.wire else TaskStatus.Todo.wire,
            nowIso      = now,
        )
    }

    /**
     * Move a task between Kanban columns — the write-path the
     * Perspectives + Boards view calls when the user taps Start /
     * Finish / Back. Reconciles `completed` + `completed_at` so the
     * old list-view queries stay authoritative without learning
     * about status.
     */
    suspend fun setStatus(id: String, status: TaskStatus) {
        val now = IsoClock.nowIso()
        val isDone = status == TaskStatus.Done
        dao.setStatus(
            id          = id,
            status      = status.wire,
            completed   = isDone,
            completedAt = if (isDone) now else null,
            nowIso      = now,
        )
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id = id, nowIso = IsoClock.nowIso())
    }

    suspend fun undoSoftDelete(id: String) {
        dao.restore(id = id, nowIso = IsoClock.nowIso())
    }
}
