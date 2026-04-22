/*
 * NotebookDetailViewModel.kt
 * Loads a single notebook's chapters + page summaries.
 */

package app.releaf.mobile.features.notebook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.data.drive.DriveRepository
import app.releaf.mobile.data.drive.NotebookDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NotebookDetailUiState {
    data object Loading : NotebookDetailUiState
    data class Loaded(val detail: NotebookDetail) : NotebookDetailUiState
    data class Failed(val message: String) : NotebookDetailUiState
}

class NotebookDetailViewModel(
    application: Application,
    private val notebookId: String,
    private val repository: DriveRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<NotebookDetailUiState>(NotebookDetailUiState.Loading)
    val state: StateFlow<NotebookDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = NotebookDetailUiState.Loading
        viewModelScope.launch {
            try {
                _state.value = NotebookDetailUiState.Loaded(repository.loadNotebook(notebookId))
            } catch (e: Exception) {
                _state.value = NotebookDetailUiState.Failed(
                    e.localizedMessage ?: "Couldn't load notebook"
                )
            }
        }
    }

    companion object {
        const val ARG_NOTEBOOK_ID = "notebookId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val id = checkNotNull(savedState.get<String>(ARG_NOTEBOOK_ID)) {
                    "NotebookDetailViewModel missing $ARG_NOTEBOOK_ID"
                }
                NotebookDetailViewModel(app, id, app.driveRepository)
            }
        }
    }
}
