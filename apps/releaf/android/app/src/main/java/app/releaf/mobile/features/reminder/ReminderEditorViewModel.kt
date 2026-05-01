/*
 * ReminderEditorViewModel.kt
 *
 * Editor-side state holder. Drives both the create path (caller
 * navigates with `reminderId = NEW_REMINDER_ID`) and the edit path
 * (caller passes an existing id). State is a single snapshot — field
 * values + a transient `saved` flag the screen reads to navigate away
 * on success.
 */

package app.releaf.mobile.features.reminder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.perspective.PerspectiveEntity
import app.releaf.mobile.data.perspective.PerspectiveRepository
import app.releaf.mobile.data.reminder.ReminderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ReminderEditorUiState(
    val id: String? = null,
    val title: String = "",
    val note: String = "",
    val remindAt: Long = defaultRemindAt(),
    /** Recurrence interval in days — null = one-shot. */
    val recursEveryDays: Int? = null,
    /** Selected perspective id — null = no tag. */
    val perspectiveId: String? = null,
    /** All perspectives the user has, for the picker chips. */
    val perspectives: List<PerspectiveEntity> = emptyList(),
    val isLoading: Boolean = true,
    val saved: Boolean = false,
) {
    /** Title required and remindAt must still be in the future. */
    val canSave: Boolean
        get() = title.trim().isNotEmpty() && remindAt > System.currentTimeMillis()
}

/** Default target — 15 minutes from now, rounded up to the next
 *  minute so the DateTime picker lands on a clean value. */
private fun defaultRemindAt(): Long =
    (System.currentTimeMillis() / 60_000L + 15L) * 60_000L

class ReminderEditorViewModel(
    application: Application,
    private val repository: ReminderRepository,
    private val perspectiveRepo: PerspectiveRepository,
    private val userId: String,
    private val reminderId: String?,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ReminderEditorUiState())
    val state: StateFlow<ReminderEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed default perspectives so the picker always has
            // Home/Work/Errands on a fresh install.
            perspectiveRepo.ensureSeed(userId)
            val perspectives = perspectiveRepo.observeActive(userId).first()
            val existing = reminderId?.let { repository.findById(it) }
            _state.value = if (existing == null) {
                ReminderEditorUiState(
                    perspectives = perspectives,
                    isLoading    = false,
                )
            } else {
                ReminderEditorUiState(
                    id              = existing.id,
                    title           = existing.title,
                    note            = existing.note.orEmpty(),
                    remindAt        = existing.remindAt,
                    recursEveryDays = existing.recursEveryDays,
                    perspectiveId   = existing.perspectiveId,
                    perspectives    = perspectives,
                    isLoading       = false,
                )
            }
        }
    }

    fun updateTitle(value: String)   { _state.value = _state.value.copy(title = value) }
    fun updateNote(value: String)    { _state.value = _state.value.copy(note = value) }
    fun updateRemindAt(value: Long)  { _state.value = _state.value.copy(remindAt = value) }

    /**
     * Set recurrence interval. [days] null = one-shot (clears any
     * existing recurrence). Positive integers (1, 7, 14, 30 …) turn
     * the reminder into a repeating one.
     */
    fun updateRecursEveryDays(days: Int?) {
        _state.value = _state.value.copy(
            recursEveryDays = days?.takeIf { it > 0 },
        )
    }

    /** Set (or clear with null) the perspective this reminder belongs to. */
    fun updatePerspective(id: String?) {
        _state.value = _state.value.copy(perspectiveId = id)
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave) return
        viewModelScope.launch {
            val existingId = snapshot.id
            if (existingId == null) {
                repository.create(
                    userId          = userId,
                    title           = snapshot.title,
                    note            = snapshot.note.takeIf { it.isNotBlank() },
                    remindAt        = snapshot.remindAt,
                    recursEveryDays = snapshot.recursEveryDays,
                    perspectiveId   = snapshot.perspectiveId,
                )
            } else {
                repository.update(
                    id               = existingId,
                    title            = snapshot.title,
                    note             = snapshot.note.takeIf { it.isNotBlank() },
                    remindAt         = snapshot.remindAt,
                    recursEveryDays  = snapshot.recursEveryDays,
                    clearRecurrence  = snapshot.recursEveryDays == null,
                    perspectiveId    = snapshot.perspectiveId,
                    clearPerspective = snapshot.perspectiveId == null,
                )
            }
            _state.value = snapshot.copy(saved = true)
        }
    }

    fun delete() {
        val id = _state.value.id ?: return
        viewModelScope.launch {
            repository.softDelete(id)
            _state.value = _state.value.copy(saved = true)
        }
    }

    companion object {
        const val ARG_REMINDER_ID = "reminderId"
        /** Sentinel for the create path — matches NotepadEditorViewModel's NEW_ENTRY_ID. */
        const val NEW_REMINDER_ID = "new"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val rawId = checkNotNull(savedState.get<String>(ARG_REMINDER_ID)) {
                    "ReminderEditorViewModel missing $ARG_REMINDER_ID"
                }
                val reminderId: String? = rawId.takeIf { it != NEW_REMINDER_ID }
                val userId = (app.authStore.state.value as? AuthState.SignedIn)
                    ?.session?.userId
                    ?: error("ReminderEditorViewModel created while not signed in")
                ReminderEditorViewModel(
                    application     = app,
                    repository      = app.reminderRepository,
                    perspectiveRepo = app.perspectiveRepository,
                    userId          = userId,
                    reminderId      = reminderId,
                )
            }
        }
    }
}
