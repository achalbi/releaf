/*
 * NotebookTabViewModel.kt
 *
 * Backs the top-level Notebooks tab. Observes the Room-backed
 * NotebookRepository for the list, and (when the user types in the header
 * search field) also runs an FTS5 page search across every notebook so
 * content hits are surfaced alongside matching notebook titles.
 *
 * Scope decision: search filters notebooks by substring on `title` (cheap,
 * deterministic) AND fans out to the page FTS index. Both surfaces are
 * rendered side-by-side by the screen. An empty query yields the full
 * notebook list and an empty page list.
 */

package app.releaf.mobile.features.notebook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.PageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Observable state for the Notebooks tab. No explicit "loading" flag — Room's
 * Flow emits an empty list immediately when the table is empty, and the
 * empty-list UI doubles as the loading UI.
 */
data class NotebookTabUiState(
    val query: String = "",
    val notebooks: List<NotebookEntity> = emptyList(),
    /** Only non-empty when searching; FTS hits across every live notebook. */
    val matchingPages: List<PageEntity> = emptyList(),
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = notebooks.isEmpty() && matchingPages.isEmpty()
}

class NotebookTabViewModel(
    application: Application,
    private val notebookRepository: NotebookRepository,
    private val pageRepository: PageRepository,
) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Unfiltered list of live notebooks — filtering happens in the combine
    // below so the DAO doesn't have to re-run on every keystroke.
    private val notebooksFlow: Flow<List<NotebookEntity>> =
        notebookRepository.observeActive()

    // Page FTS. Blank query → empty flow (no network to SQLite). Only
    // debounce non-blank input so the first paint isn't delayed.
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val pageResultsFlow: Flow<List<PageEntity>> = _query
        .debounce { q -> if (q.isBlank()) 0L else 150L }
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else pageRepository.searchAll(q)
        }

    val state: StateFlow<NotebookTabUiState> =
        combine(_query, notebooksFlow, pageResultsFlow) { q, notebooks, pages ->
            // Title-substring filter is fine here — notebook titles are short
            // and the list is small. If we grow past a few dozen notebooks we
            // can move this to a DAO-level LIKE/FTS query.
            val filteredNotebooks = if (q.isBlank()) {
                notebooks
            } else {
                notebooks.filter { it.title.contains(q, ignoreCase = true) }
            }
            NotebookTabUiState(
                query         = q,
                notebooks     = filteredNotebooks,
                matchingPages = pages,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotebookTabUiState(),
        )

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun clearQuery() {
        _query.value = ""
    }

    /**
     * Create a notebook from the FAB dialog. Blank titles are rejected at the
     * screen layer; we still trim defensively here.
     */
    fun createNotebook(title: String, colorHex: String? = null, onCreated: (String) -> Unit = {}) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val created = notebookRepository.createNotebook(trimmed, colorHex)
            onCreated(created.id)
        }
    }

    fun softDelete(id: String) {
        viewModelScope.launch { notebookRepository.softDeleteNotebook(id) }
    }

    /**
     * Restore a previously soft-deleted notebook. Only restores the notebook
     * row itself — cascaded chapter/page tombstones stay deleted. See
     * NotebookRepository.undoSoftDeleteNotebook for rationale.
     */
    fun undoDelete(id: String) {
        viewModelScope.launch { notebookRepository.undoSoftDeleteNotebook(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                NotebookTabViewModel(
                    application        = app,
                    notebookRepository = app.notebookRepository,
                    pageRepository     = app.pageRepository,
                )
            }
        }
    }
}
