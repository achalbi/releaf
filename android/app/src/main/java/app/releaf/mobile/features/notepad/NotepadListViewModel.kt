/*
 * NotepadListViewModel.kt
 *
 * Observes the signed-in user's active notepad entries from the local DB.
 * Soft-deletes are filtered in the DAO — this VM only ever sees live rows.
 *
 * Search: driven by a single MutableStateFlow<String>. A blank query yields
 * the full list; a non-blank one is sanitized into FTS5 MATCH syntax by the
 * repository and fans out to the FTS5 virtual table. The query is debounced
 * by 150ms only when non-empty, so the initial load isn't delayed.
 */

package app.releaf.mobile.features.notepad

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.notepad.NotepadRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Top-level Notepad screen state. There's no explicit "loading" variant —
 * Room's Flow emits an empty list immediately when the table has no rows,
 * so the empty and still-loading states look identical anyway. Collapsing
 * them simplifies the UI layer.
 */
data class NotepadListUiState(
    val query: String = "",
    val entries: List<NotepadEntry> = emptyList(),
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

class NotepadListViewModel(
    application: Application,
    private val repository: NotepadRepository,
    private val userId: String,
) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Note: no explicit distinctUntilChanged — _query is a StateFlow, which
    // already dedupes emissions (and Kotlin errors on the redundant call).
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val entriesFlow: Flow<List<NotepadEntry>> = _query
        // Only debounce when the user is actually typing — blank queries
        // (including the initial "") pass through so the list paints on
        // launch with no artificial delay.
        .debounce { q -> if (q.isBlank()) 0L else 150L }
        .flatMapLatest { q ->
            if (q.isBlank()) {
                repository.observeActive(userId)
            } else {
                repository.search(userId, q)
            }
        }

    val state: StateFlow<NotepadListUiState> =
        combine(_query, entriesFlow) { q, entries ->
            NotepadListUiState(query = q, entries = entries)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotepadListUiState(),
        )

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun clearQuery() {
        _query.value = ""
    }

    fun softDelete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    /**
     * Restore a previously soft-deleted entry. Bound to the "Undo" action on
     * the list's swipe-to-delete snackbar. Safe to call on an id that's not
     * currently deleted — the DAO just re-bumps updated_at + dirty, which is
     * exactly what we want anyway.
     */
    fun undoDelete(id: String) {
        viewModelScope.launch { repository.undoSoftDelete(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val userId = (app.authStore.state.value as? AuthState.SignedIn)
                    ?.session?.userId
                    ?: error("NotepadListViewModel created while not signed in")
                NotepadListViewModel(app, app.notepadRepository, userId)
            }
        }
    }
}
