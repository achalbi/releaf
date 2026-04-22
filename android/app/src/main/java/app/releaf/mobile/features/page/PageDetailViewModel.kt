/*
 * PageDetailViewModel.kt
 * Loads a single Page (full payload with all seven capture modes).
 */

package app.releaf.mobile.features.page

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.drive.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PageDetailUiState {
    data object Loading : PageDetailUiState
    data class Loaded(val page: Page) : PageDetailUiState
    data class Failed(val message: String) : PageDetailUiState
}

class PageDetailViewModel(
    application: Application,
    private val pageId: String,
    private val repository: DriveRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<PageDetailUiState>(PageDetailUiState.Loading)
    val state: StateFlow<PageDetailUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = PageDetailUiState.Loading
        viewModelScope.launch {
            try {
                _state.value = PageDetailUiState.Loaded(repository.loadPage(pageId))
            } catch (e: Exception) {
                _state.value = PageDetailUiState.Failed(
                    e.localizedMessage ?: "Couldn't load page"
                )
            }
        }
    }

    companion object {
        const val ARG_PAGE_ID = "pageId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val id = checkNotNull(savedState.get<String>(ARG_PAGE_ID)) {
                    "PageDetailViewModel missing $ARG_PAGE_ID"
                }
                PageDetailViewModel(app, id, app.driveRepository)
            }
        }
    }
}
