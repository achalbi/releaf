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
import app.releaf.mobile.data.domain.Shelf
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookCountRow
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageSearchHit
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.shelf.ShelfEntity
import app.releaf.mobile.data.shelf.ShelfRepository
import app.releaf.mobile.ui.theme.NotebookSortPreference
import app.releaf.mobile.ui.theme.UiPreferences
import java.time.Instant
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

/** How the notebooks list is ordered. The user's choice is
 *  persisted via [UiPreferences] (see [NotebookSortPreference]) so
 *  it survives a cold start. The picker lives in the notebook-tab
 *  overflow menu. */
enum class NotebookSortMode(val label: String) {
    Recent("Recent activity"),
    Name("Name (A → Z)"),
    Pages("Most pages"),
}

/** Bridge between the persistence shape and the in-memory shape.
 *  Kept top-level so they're callable during property
 *  initialization (Kotlin doesn't let you call class methods
 *  inside property initializers). */
private fun NotebookSortMode.toPref(): NotebookSortPreference = when (this) {
    NotebookSortMode.Recent -> NotebookSortPreference.Recent
    NotebookSortMode.Name   -> NotebookSortPreference.Name
    NotebookSortMode.Pages  -> NotebookSortPreference.Pages
}

/** Map a leaf-theme token to the hex string the shelf entity
 *  stores. Source of truth for the four primaries lives in the
 *  generated `AppColors`; this lookup keeps any color-token
 *  change in `design-tokens.json` flowing through. */
private fun themeHex(token: String): String = when (token.lowercase()) {
    "coral"  -> "#E07856"
    "green"  -> "#7AA874"
    "yellow" -> "#F4C430"
    "dry"    -> "#B8956A"
    else     -> "#E07856"
}

private fun NotebookSortPreference.toUi(): NotebookSortMode = when (this) {
    NotebookSortPreference.Recent -> NotebookSortMode.Recent
    NotebookSortPreference.Name   -> NotebookSortMode.Name
    NotebookSortPreference.Pages  -> NotebookSortMode.Pages
}

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
    /** Live shelves, ordered for list display. Consumed by the
     *  notebooks list to group books under their parent shelf. */
    val shelves: List<Shelf> = emptyList(),
    /** Only non-empty when searching; FTS hits across every live notebook. */
    val matchingPages: List<PageSearchHit> = emptyList(),
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = notebooks.isEmpty() && matchingPages.isEmpty()
    val notebookCount: Int get() = notebooks.size
    val chapterCount: Int get() = notebooks.sumOf { it.chapterCount }
    val pageCount: Int get() = notebooks.sumOf { it.pageCount }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotebookTabViewModel(
    application: Application,
    private val notebookRepository: NotebookRepository,
    private val chapterRepository: ChapterRepository,
    private val pageRepository: PageRepository,
    private val shelfRepository: ShelfRepository,
    private val uiPreferences: UiPreferences = UiPreferences.get(application),
) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _tab = MutableStateFlow(NotebookListTab.Current)

    /** Initial value pulled from the persisted [UiPreferences] so a
     *  freshly-mounted view shows the user's last sort without
     *  flashing through Recent. Updates write back through
     *  [setSortMode] so the choice survives a cold start. */
    private val _sortMode = MutableStateFlow(uiPreferences.state.value.notebookSort.toUi())
    val sortMode: StateFlow<NotebookSortMode> = _sortMode.asStateFlow()

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
    private val pageResultsFlow: Flow<List<PageSearchHit>> = _query
        .debounce { q -> if (q.isBlank()) 0L else 150L }
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else pageRepository.searchAllWithContext(q)
        }

    // Pre-combine to fit inside the 5-arg `combine` overload without
    // giving up compile-time types. The pair destructuring below keeps
    // the final combine readable.
    private val tabAndNotebooksFlow: Flow<Pair<NotebookListTab, List<NotebookEntity>>> =
        combine(_tab, notebooksFlow) { tab, notebooks -> tab to notebooks }

    private val countsAndShelvesFlow:
            Flow<Triple<Map<String, Int>, Map<String, Int>, List<ShelfEntity>>> =
        combine(chapterCountsFlow, pageCountsFlow, shelfRepository.observeActive()) {
            ch, pg, shelves -> Triple(ch, pg, shelves)
        }

    val state: StateFlow<NotebookTabUiState> = combine(
        _query,
        tabAndNotebooksFlow,
        countsAndShelvesFlow,
        pageResultsFlow,
    ) { q, (tab, notebooks), (chapterCounts, pageCounts, shelves), matchingPages ->
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
            shelves       = shelves.map { it.toDomain() },
            matchingPages = matchingPages,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotebookTabUiState(),
    )

    /** Create a fresh shelf — wired to the New-shelf overflow item
     *  *and* the inline "+ New shelf…" row inside the notebook-create
     *  dialog. The optional `colorToken` lets the caller pick one of
     *  the four leaf themes; we convert it to the hex string the
     *  shelf entity stores. */
    fun createShelf(
        name: String,
        colorToken: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        val resolved = name.trim().ifEmpty { "Untitled shelf" }
        val hex = colorToken?.let(::themeHex)
        viewModelScope.launch {
            val shelf = shelfRepository.createShelf(name = resolved, colorHex = hex)
            onCreated(shelf.id)
        }
    }

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun clearQuery() {
        _query.value = ""
    }

    fun setSortMode(mode: NotebookSortMode) {
        _sortMode.value = mode
        uiPreferences.setNotebookSort(mode.toPref())
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
        colorToken: String? = null,
        shelfId: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        val resolvedShelf = shelfId?.takeIf { it.isNotBlank() } ?: ShelfEntity.DEFAULT_GENERAL_ID
        // Caller may pass either a leaf-theme token (preferred) or a
        // raw hex string (legacy). Token wins when both are set.
        val resolvedHex = colorToken?.let(::themeHex) ?: colorHex
        viewModelScope.launch {
            val created = notebookRepository.createNotebook(
                title       = trimmed,
                colorHex    = resolvedHex,
                description = description,
                shelfId     = resolvedShelf,
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

    /**
     * Soft-delete a shelf. Books that lived on it remain in the
     * notebooks table — the screen surfaces them as orphans under the
     * synthetic "Unshelved" group until the user moves them or the
     * delete is undone.
     *
     * Refuses to delete the seeded General shelf so the fallback parent
     * for fresh notebooks always exists.
     */
    fun softDeleteShelf(id: String) {
        if (id == ShelfEntity.DEFAULT_GENERAL_ID) return
        viewModelScope.launch { shelfRepository.softDelete(id) }
    }

    /** Restore a soft-deleted shelf. */
    fun undoDeleteShelf(id: String) {
        viewModelScope.launch { shelfRepository.undoSoftDelete(id) }
    }

    fun archive(id: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { notebookRepository.archiveNotebook(id) }
                .onSuccess { onComplete(true) }
                .onFailure { onComplete(false) }
        }
    }

    fun unarchive(id: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { notebookRepository.unarchiveNotebook(id) }
                .onSuccess { onComplete(true) }
                .onFailure { onComplete(false) }
        }
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
                    shelfRepository    = app.shelfRepository,
                )
            }
        }
    }
}

// ---------- helpers ----------

private fun ShelfEntity.toDomain(): Shelf = Shelf(
    id        = id,
    name      = name,
    colorHex  = colorHex,
    position  = position.toInt(),
    updatedAt = runCatching { Instant.parse(updatedAt) }.getOrDefault(Instant.EPOCH),
)

/** Collapse a repo-count feed into the `notebookId → count` lookup each row needs. */
private fun Flow<List<NotebookCountRow>>.mapToCountMap(): Flow<Map<String, Int>> =
    map { rows -> rows.associateBy({ it.notebookId }, { it.count }) }
