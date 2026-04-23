/*
 * NotebookTabViewModel.kt
 *
 * Backs the top-level Notebooks tab. Observes the Room-backed
 * NotebookRepository for the list, fans out to an FTS5 page search across
 * every notebook when the user types, and joins in per-notebook chapter /
 * page counts so each row in the list can show "2 chapters · 3 pages"
 * without a second round-trip.
 *
 * The screen toggles between the Current and Archive tabs — we swap the
 * source flow via `flatMapLatest` on the tab selection so only one
 * observation is active at a time.
 */

package app.releaf.mobile.features.notebook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookCountRow
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NotebookListTab { Current, Archive }

/** Notebook row with chapter / page counts pre-computed. */
data class NotebookSummary(
    val entity: NotebookEntity,
    val chapterCount: Int,
    val pageCount: Int,
)

data class NotebookTabUiState(
    val query: String = "",
    val tab: NotebookListTab = NotebookListTab.Current,
    val notebooks: List<NotebookSummary> = emptyList(),
    /** Only non-empty when searching; FTS hits across every live notebook. */
    val matchingPages: List<PageEntity> = emptyList(),
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = notebooks.isEmpty() && matchingPages.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotebookTabViewModel(
    application: Application,
    private val notebookRepository: NotebookRepository,
    private val chapterRepository: ChapterRepository,
    private val pageRepository: PageRepository,
) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _tab = MutableStateFlow(NotebookListTab.Current)

    private val notebooksFlow: Flow<List<NotebookEntity>> = _tab.flatMapLatest { tab ->
        when (tab) {
            NotebookListTab.Current -> notebookRepository.observeActive()
            NotebookListTab.Archive -> notebookRepository.observeArchived()
        }
    }

    private val chapterCountsFlow: Flow<Map<String, Int>> =
        chapterRepository.observeChapterCounts().mapToCountMap()

    private val pageCountsFlow: Flow<Map<String, Int>> =
        pageRepository.observePageCountsByNotebook().mapToCountMap()

    // Page FTS. Blank query → empty flow (no round-trip to SQLite). Only
    // debounce non-blank input so the first paint isn't delayed.
    private val pageResultsFlow: Flow<List<PageEntity>> = _query
        .debounce { q -> if (q.isBlank()) 0L else 150L }
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else pageRepository.searchAll(q)
        }

    // Pre-combine to fit inside the 5-arg `combine` overload without
    // giving up compile-time types. The pair destructuring below keeps
    // the final combine readable.
    private val tabAndNotebooksFlow: Flow<Pair<NotebookListTab, List<NotebookEntity>>> =
        combine(_tab, notebooksFlow) { tab, notebooks -> tab to notebooks }

    private val countsFlow: Flow<Pair<Map<String, Int>, Map<String, Int>>> =
        combine(chapterCountsFlow, pageCountsFlow) { ch, pg -> ch to pg }

    val state: StateFlow<NotebookTabUiState> = combine(
        _query,
        tabAndNotebooksFlow,
        countsFlow,
        pageResultsFlow,
    ) { q, (tab, notebooks), (chapterCounts, pageCounts), matchingPages ->
        val filteredNotebooks = if (q.isBlank()) {
            notebooks
        } else {
            notebooks.filter { nb ->
                nb.title.contains(q, ignoreCase = true) ||
                    (nb.description?.contains(q, ignoreCase = true) == true)
            }
        }
        val summaries = filteredNotebooks.map { nb ->
            NotebookSummary(
                entity       = nb,
                chapterCount = chapterCounts[nb.id] ?: 0,
                pageCount    = pageCounts[nb.id] ?: 0,
            )
        }
        NotebookTabUiState(
            query         = q,
            tab           = tab,
            notebooks     = summaries,
            matchingPages = matchingPages,
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

    fun setTab(tab: NotebookListTab) {
        _tab.value = tab
    }

    /**
     * Create a notebook from the FAB dialog. Blank titles are rejected at the
     * screen layer; we still trim defensively here.
     */
    fun createNotebook(
        title: String,
        description: String? = null,
        colorHex: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val created = notebookRepository.createNotebook(
                title = trimmed,
                colorHex = colorHex,
                description = description,
            )
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

    fun archive(id: String) {
        viewModelScope.launch { notebookRepository.archiveNotebook(id) }
    }

    fun unarchive(id: String) {
        viewModelScope.launch { notebookRepository.unarchiveNotebook(id) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                NotebookTabViewModel(
                    application        = app,
                    notebookRepository = app.notebookRepository,
                    chapterRepository  = app.chapterRepository,
                    pageRepository     = app.pageRepository,
                )
            }
        }
    }
}

/** Collapse a repo-count feed into the `notebookId → count` lookup each row needs. */
private fun Flow<List<NotebookCountRow>>.mapToCountMap(): Flow<Map<String, Int>> =
    map { rows -> rows.associateBy({ it.notebookId }, { it.count }) }
