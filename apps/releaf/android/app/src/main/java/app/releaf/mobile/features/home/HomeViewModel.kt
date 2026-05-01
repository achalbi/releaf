/*
 * HomeViewModel.kt
 * Loads the signed-in user's notebook list via DriveRepository.
 */

package app.releaf.mobile.features.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.drive.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Idle    : HomeUiState
    data object Loading : HomeUiState
    data class Loaded(val notebooks: List<Notebook>) : HomeUiState
    data class Failed(val message: String) : HomeUiState
}

class HomeViewModel(
    application: Application,
    private val repository: DriveRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (_state.value is HomeUiState.Loading) return
        _state.value = HomeUiState.Loading
        viewModelScope.launch {
            try {
                _state.value = HomeUiState.Loaded(repository.listNotebooks())
            } catch (e: Exception) {
                _state.value = HomeUiState.Failed(e.localizedMessage ?: "Couldn't load notebooks")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                HomeViewModel(app, app.driveRepository)
            }
        }
    }
}
