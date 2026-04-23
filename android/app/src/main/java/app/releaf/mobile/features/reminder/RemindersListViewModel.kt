/*
 * RemindersListViewModel.kt
 *
 * List-side state holder for the Reminders screen.
 *
 * Exposes three live streams folded into one [RemindersListUiState]:
 *   • upcoming / past  — partitioned and sorted reminder rows
 *   • perspectives     — so the list UI can colour `@tag` chips on a
 *                        reminder with the matching tile's icon +
 *                        accent background (same source of truth the
 *                        Tasks screen uses).
 *
 * Mutations: mark done, re-open, delete, snooze (+N minutes), and
 * a natural-language quick-create that delegates to [ReminderParser].
 */

package app.releaf.mobile.features.reminder

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
import app.releaf.mobile.data.reminder.ReminderEntity
import app.releaf.mobile.data.reminder.ReminderParser
import app.releaf.mobile.data.reminder.ReminderRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RemindersListUiState(
    val upcoming: List<ReminderEntity> = emptyList(),
    val past: List<ReminderEntity> = emptyList(),
    val perspectives: List<PerspectiveEntity> = emptyList(),
)

class RemindersListViewModel(
    application: Application,
    private val repository: ReminderRepository,
    private val perspectiveRepo: PerspectiveRepository,
    private val userId: String,
) : AndroidViewModel(application) {

    val state: StateFlow<RemindersListUiState> =
        combine(
            repository.observeActive(userId),
            perspectiveRepo.observeActive(userId),
        ) { rows, perspectives ->
            val now = System.currentTimeMillis()
            // A reminder is "past" once it has either fired, been
            // marked complete, or its scheduled time has slipped
            // without firing (e.g. battery optimisation killed the
            // alarm). The UI shows past items collapsed below.
            val (past, upcoming) = rows.partition { row ->
                row.completedAt != null ||
                    row.firedAt != null ||
                    row.remindAt <= now
            }
            RemindersListUiState(
                upcoming     = upcoming.sortedBy { it.remindAt },
                past         = past.sortedByDescending { it.remindAt },
                perspectives = perspectives,
            )
        }.stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = RemindersListUiState(),
        )

    fun markCompleted(id: String) {
        viewModelScope.launch { repository.markCompleted(id) }
    }

    fun markActive(id: String) {
        viewModelScope.launch { repository.markActive(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    /**
     * Bump a reminder's fire time by [minutes] minutes and
     * re-arm the alarm. Uses [ReminderRepository.update] so the
     * cancel + re-schedule happens atomically.
     */
    fun snooze(id: String, minutes: Int) {
        viewModelScope.launch {
            val row = repository.findById(id) ?: return@launch
            val next = System.currentTimeMillis() + minutes * 60_000L
            repository.update(
                id       = id,
                title    = row.title,
                note     = row.note,
                remindAt = next,
            )
        }
    }

    /**
     * Create a reminder from free-text. Routes through
     * [ReminderParser] to split the text into title + remindAt, then
     * calls the standard create path so the alarm is scheduled the
     * same way as any other reminder. If the user typed an `@tag`
     * that doesn't yet have a matching perspective tile, we ensure
     * one in the same coroutine — keeps the tile row in sync with
     * what's actually in the title.
     */
    fun quickCreate(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val parsed = ReminderParser.parse(trimmed)
            // If the title contains an @tag that isn't yet a
            // perspective, make it so — keeps tag chips clickable
            // across Tasks + Reminders.
            extractContext(parsed.title)?.let { tag ->
                perspectiveRepo.ensure(userId, tag)
            }
            repository.create(
                userId   = userId,
                title    = parsed.title,
                note     = null,
                remindAt = parsed.remindAt,
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val userId = (app.authStore.state.value as? AuthState.SignedIn)
                    ?.session?.userId
                    ?: error("RemindersListViewModel created while not signed in")
                RemindersListViewModel(
                    application     = app,
                    repository      = app.reminderRepository,
                    perspectiveRepo = app.perspectiveRepository,
                    userId          = userId,
                )
            }
        }
    }
}
