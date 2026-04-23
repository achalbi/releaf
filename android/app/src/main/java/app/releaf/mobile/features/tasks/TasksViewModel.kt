/*
 * TasksViewModel.kt
 *
 * Backs the workspace-level Tasks screen. Holds two live streams:
 *
 *   • tasks        — via [TaskRepository.observeActive]
 *   • perspectives — via [PerspectiveRepository.observeActive]
 *
 * They're combined into a single [TasksUiState] so the screen only
 * collects once and re-renders atomically when either source
 * changes (e.g. a task is added whose @tag doesn't yet have a tile —
 * we auto-ensure the perspective in the same coroutine, so both
 * streams emit within the same recomposition).
 *
 * Exposes simple mutation actions — add / toggle / delete / status
 * / create-perspective / delete-perspective — that the screen calls
 * directly without threading through additional state.
 */

package app.releaf.mobile.features.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.perspective.PerspectiveEntity
import app.releaf.mobile.data.perspective.PerspectiveRepository
import app.releaf.mobile.data.perspective.extractContext
import app.releaf.mobile.data.perspective.stripContext
import app.releaf.mobile.data.reminder.ReminderEntity
import app.releaf.mobile.data.reminder.ReminderRepository
import app.releaf.mobile.data.task.TaskEntity
import app.releaf.mobile.data.task.TaskRepository
import app.releaf.mobile.data.task.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TasksUiState(
    val isLoading: Boolean = true,
    val tasks: List<TaskEntity> = emptyList(),
    val perspectives: List<PerspectiveEntity> = emptyList(),
) {
    val openCount: Int get() = tasks.count { !it.completed }
    val doneCount: Int get() = tasks.count { it.completed }
}

class TasksViewModel(
    application: Application,
    private val userId: String,
    private val taskRepo: TaskRepository,
    private val perspectiveRepo: PerspectiveRepository,
    private val reminderRepo: ReminderRepository,
) : AndroidViewModel(application) {

    init {
        // Seed Home / Work / Errands once per user. No-op if the user
        // already has any perspectives — see `ensureSeed` for rules.
        viewModelScope.launch { perspectiveRepo.ensureSeed(userId) }
    }

    val state: StateFlow<TasksUiState> =
        combine(
            taskRepo.observeActive(userId),
            perspectiveRepo.observeActive(userId),
        ) { tasks, perspectives ->
            TasksUiState(
                isLoading    = false,
                tasks        = tasks,
                perspectives = perspectives,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TasksUiState())

    // ── Task mutations ─────────────────────────────────────────────

    fun addTask(title: String, dueDate: String? = null, priority: Int = 0) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // If the title carries an @tag we don't have a tile for
            // yet, ensure the perspective so the new task appears in
            // a real perspective column rather than silently landing
            // in "All" without a home. Idempotent — no-op if the
            // perspective already exists.
            extractContext(trimmed)?.let { tag ->
                perspectiveRepo.ensure(userId, tag)
            }
            taskRepo.create(
                userId   = userId,
                title    = trimmed,
                dueDate  = dueDate,
                priority = priority,
            )
        }
    }

    fun toggleCompleted(id: String, completed: Boolean) {
        viewModelScope.launch { taskRepo.setCompleted(id, completed) }
    }

    /**
     * Move a task between Kanban columns from the Perspectives +
     * Boards view. The repository keeps `completed` and `status`
     * consistent so the list view stays authoritative.
     */
    fun setStatus(id: String, status: TaskStatus) {
        viewModelScope.launch { taskRepo.setStatus(id, status) }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch {
            // Clear any attached reminder so the alarm doesn't fire
            // after the task has been removed. The reminder row is
            // soft-deleted, mirroring the task — an undoDelete of the
            // task won't automatically restore the reminder, which is
            // the right trade-off: reminders are time-anchored and
            // whatever time was set probably isn't relevant anymore.
            reminderRepo.clearForTask(id)
            taskRepo.softDelete(id)
        }
    }

    fun undoDelete(id: String) {
        viewModelScope.launch { taskRepo.undoSoftDelete(id) }
    }

    // ── Reminder mutations ─────────────────────────────────────────

    /**
     * Observe the single active reminder for a task — null when none
     * is set. The Edit-task sheet collects this to drive its reminder
     * chip (so the chip stays in sync if the user has the sheet open
     * when an alarm fires or is cleared elsewhere).
     */
    fun observeReminderForTask(taskId: String): Flow<ReminderEntity?> =
        reminderRepo.observeActiveByTaskId(taskId)

    /**
     * Set (or replace) the reminder on a task. Title is pulled from
     * the task itself, stripped of any `@tag` prefix so the
     * notification reads cleanly on the lock screen. `remindAt` is
     * an epoch-millis wall-clock time.
     */
    fun setTaskReminder(task: TaskEntity, remindAtMs: Long) {
        viewModelScope.launch {
            reminderRepo.setForTask(
                userId   = userId,
                taskId   = task.id,
                title    = stripContext(task.title).ifBlank { task.title.trim() },
                remindAt = remindAtMs,
            )
        }
    }

    fun clearTaskReminder(taskId: String) {
        viewModelScope.launch { reminderRepo.clearForTask(taskId) }
    }

    fun updateTask(task: TaskEntity) {
        viewModelScope.launch { taskRepo.save(task) }
    }

    // ── Perspective mutations ───────────────────────────────────────

    /**
     * Create a new perspective tile. Name is normalised (lower-cased,
     * slug-safe) in the repository. No-op if the normalised name is
     * empty or already exists.
     */
    fun createPerspective(rawName: String, iconKey: String = "label") {
        viewModelScope.launch {
            perspectiveRepo.create(userId, rawName, iconKey)
        }
    }

    /**
     * Soft-delete a perspective tile. Does NOT touch tasks — they
     * keep their @tag in the title, just lose their tile until the
     * user re-creates a perspective by that name (or types the tag
     * into a fresh task, which auto-ensures).
     */
    fun deletePerspective(id: String) {
        viewModelScope.launch { perspectiveRepo.softDelete(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val userId = (app.authStore.state.value as? AuthState.SignedIn)
                    ?.session?.userId
                    ?: error("TasksViewModel created while not signed in")
                TasksViewModel(
                    application     = app,
                    userId          = userId,
                    taskRepo        = app.taskRepository,
                    perspectiveRepo = app.perspectiveRepository,
                    reminderRepo    = app.reminderRepository,
                )
            }
        }
    }
}
