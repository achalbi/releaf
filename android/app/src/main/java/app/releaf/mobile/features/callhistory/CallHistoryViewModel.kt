/*
 * CallHistoryViewModel.kt
 *
 * Observes the local call-history log for the signed-in user and
 * exposes a flat `state.entries` list, newest first. Handles the
 * "Clear all" destructive action as well.
 */

package app.releaf.mobile.features.callhistory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.callhistory.CallHistoryRecord
import app.releaf.mobile.data.callhistory.CallHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CallHistoryUiState(
    val isLoading: Boolean = true,
    val entries: List<CallHistoryRecord> = emptyList(),
) {
    val isEmpty: Boolean get() = !isLoading && entries.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryViewModel(
    application: Application,
    private val userIdProvider: () -> String?,
    private val repository: CallHistoryRepository,
) : AndroidViewModel(application) {

    private val _userIdSignal = MutableStateFlow(userIdProvider())

    val state: StateFlow<CallHistoryUiState> = _userIdSignal
        .flatMapLatest { userId ->
            if (userId.isNullOrBlank()) flowOf(emptyList())
            else repository.observeAll(userId)
        }
        .map { CallHistoryUiState(isLoading = false, entries = it) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = CallHistoryUiState(),
        )

    fun deleteEntry(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAll() {
        val userId = userIdProvider() ?: return
        viewModelScope.launch { repository.deleteAll(userId) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                CallHistoryViewModel(
                    application    = app,
                    userIdProvider = {
                        (app.authStore.state.value as? AuthState.SignedIn)?.session?.userId
                            ?: "local"
                    },
                    repository     = app.callHistoryRepository,
                )
            }
        }
    }
}
