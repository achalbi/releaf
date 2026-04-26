/*
 * ChapterLocalDetailViewModel.kt
 *
 * Backs the chapter detail screen (route: `chapter/local/{chapterId}`).
 * Joins the chapter row, its parent notebook (for breadcrumbs), the list
 * of pages under the chapter, and the chapter's own position within its
 * notebook (for the "Ch. N" / "Order: N" pills).
 *
 * The screen uses this single state bundle — no direct DAO access.
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
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.PageRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChapterLocalDetailUiState(
    val isLoading: Boolean = true,
    val chapter: ChapterEntity? = null,
    val notebook: NotebookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    /** 1-based position of the chapter within its notebook's ordered list. */
    val orderInNotebook: Int = 0,
) {
    val notFound: Boolean get() = !isLoading && chapter == null
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterLocalDetailViewModel(
    application: Application,
    private val chapterId: String,
    private val notebookRepository: NotebookRepository,
    private val chapterRepository: ChapterRepository,
    private val pageRepository: PageRepository,
) : AndroidViewModel(application) {

    private val chapterFlow: Flow<ChapterEntity?> = chapterRepository.observeById(chapterId)

    // Chain through chapter → notebook / sibling-chapters / pages. When the
    // chapter row disappears (deleted) the downstream flows collapse to
    // empty / null so the screen renders the not-found state.
    private val notebookFlow: Flow<NotebookEntity?> = chapterFlow.flatMapLatest { chapter ->
        chapter?.let { notebookRepository.observeById(it.notebookId) } ?: flowOf(null)
    }
    private val siblingsFlow: Flow<List<ChapterEntity>> = chapterFlow.flatMapLatest { chapter ->
        chapter?.let { chapterRepository.observeForNotebook(it.notebookId) } ?: flowOf(emptyList())
    }
    private val pagesFlow: Flow<List<PageEntity>> = pageRepository.observeForChapter(chapterId)

    val state: StateFlow<ChapterLocalDetailUiState> = combine(
        chapterFlow,
        notebookFlow,
        siblingsFlow,
        pagesFlow,
    ) { chapter, notebook, siblings, pages ->
        val order = if (chapter != null) {
            siblings.indexOfFirst { it.id == chapter.id }.let { if (it < 0) 0 else it + 1 }
        } else 0
        ChapterLocalDetailUiState(
            isLoading = false,
            chapter = chapter,
            notebook = notebook,
            pages = pages,
            orderInNotebook = order,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChapterLocalDetailUiState(),
    )

    /* ---------- chapter actions ---------- */

    fun saveChapter(title: String, description: String?) {
        val current = state.value.chapter ?: return
        val cleanedTitle = title.trim().ifEmpty { current.title }
        val cleanedDescription = description?.trim()?.ifEmpty { null }
        if (cleanedTitle == current.title && cleanedDescription == current.description) return
        viewModelScope.launch {
            chapterRepository.saveChapter(
                current.copy(title = cleanedTitle, description = cleanedDescription),
            )
        }
    }

    /* ---------- page actions ---------- */

    fun createPage(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val created = pageRepository.createPage(
                chapterId = chapterId,
                title     = null,
                notes     = "",
            )
            onCreated(created.id)
        }
    }

    fun softDeletePage(id: String) {
        viewModelScope.launch { pageRepository.softDeletePage(id) }
    }

    fun undoDeletePage(id: String) {
        viewModelScope.launch { pageRepository.undoSoftDeletePage(id) }
    }

    /* ---------- chapter archive flow ---------- */

    private val _confirmingArchive = MutableStateFlow(false)
    val confirmingArchive: StateFlow<Boolean> = _confirmingArchive.asStateFlow()

    private val _archiveToast = MutableStateFlow<String?>(null)
    val archiveToast: StateFlow<String?> = _archiveToast.asStateFlow()

    /** Step 1 — surface a confirm dialog. The screen binds an
     *  AlertDialog to [confirmingArchive]. */
    fun archiveChapter() {
        if (state.value.chapter == null) return
        _confirmingArchive.value = true
    }

    fun cancelArchive() {
        _confirmingArchive.value = false
    }

    /** Step 2 — actually archive. Reuses the existing local
     *  `softDeleteChapter` since archive is a soft delete in this
     *  schema; downstream flows pick up the change via Room
     *  observations. */
    fun confirmArchiveChapter(onArchived: () -> Unit) {
        val id = state.value.chapter?.id ?: return
        _confirmingArchive.value = false
        viewModelScope.launch {
            chapterRepository.softDeleteChapter(id)
            _archiveToast.value = "Chapter archived"
            onArchived()
        }
    }

    fun consumeArchiveToast() {
        _archiveToast.value = null
    }

    companion object {
        const val ARG_CHAPTER_ID = "chapterId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val chapterId = checkNotNull(savedState.get<String>(ARG_CHAPTER_ID)) {
                    "ChapterLocalDetailViewModel missing $ARG_CHAPTER_ID"
                }
                ChapterLocalDetailViewModel(
                    application        = app,
                    chapterId          = chapterId,
                    notebookRepository = app.notebookRepository,
                    chapterRepository  = app.chapterRepository,
                    pageRepository     = app.pageRepository,
                )
            }
        }
    }
}
