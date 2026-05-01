/*
 * NotebookLocalDetailViewModel.kt
 *
 * Backs the Room-backed notebook detail screen (route: `notebook/local/{id}`).
 * Fans four Flows together — notebook metadata, chapters list, the flat
 * list of every page across those chapters, and the per-chapter page count
 * feed — and re-groups pages into a chapter → pages map for the screen
 * and a chapter → count map for the chapter row's meta line.
 *
 * The VM exposes create/update/delete/undo actions for the notebook itself,
 * plus chapters and pages, so the screen only ever talks to one surface.
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ChapterMoveDirection { Up, Down }

/**
 * Spacing between chapter `position` values when the VM rewrites the
 * full ordering. Mirrors the entity's 1024 default so a single, isolated
 * insert (which gets the default) still slots after re-ordered peers.
 */
private const val CHAPTER_POSITION_STRIDE = 1024L

data class NotebookLocalDetailUiState(
    val isLoading: Boolean = true,
    val notebook: NotebookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    /**
     * Pages grouped by `chapter_id`. The VM fills in an empty list for every
     * chapter key, so the screen can iterate `chapters` deterministically
     * without having to null-check per section.
     */
    val pagesByChapter: Map<String, List<PageEntity>> = emptyMap(),
    /** Per-chapter live-page count — drives the "N page" meta line on rows. */
    val pageCountsByChapter: Map<String, Int> = emptyMap(),
) {
    /** True when loading has settled and the notebook row is gone. */
    val notFound: Boolean get() = !isLoading && notebook == null

    val totalChapterCount: Int get() = chapters.size
    val totalPageCount: Int get() = pagesByChapter.values.sumOf { it.size }

    /**
     * Latest mutation timestamp anywhere under this notebook —
     * folds notebook.updatedAt with chapter.updatedAt and
     * page.updatedAt across the whole tree. ISO-8601 strings sort
     * lexicographically so a string `max` gives the chronological
     * latest. Drives the "Last edited X ago" footnote on the hero.
     */
    val lastEditedAt: String?
        get() {
            val candidates = sequence {
                notebook?.updatedAt?.let { yield(it) }
                chapters.forEach { yield(it.updatedAt) }
                pagesByChapter.values.forEach { pages ->
                    pages.forEach { yield(it.updatedAt) }
                }
            }
            return candidates.maxOrNull()
        }
}

class NotebookLocalDetailViewModel(
    application: Application,
    private val notebookId: String,
    private val notebookRepository: NotebookRepository,
    private val chapterRepository: ChapterRepository,
    private val pageRepository: PageRepository,
) : AndroidViewModel(application) {

    private val pageCountsFlow = pageRepository.observePageCountsByChapter()
        .map { rows -> rows.associateBy({ it.chapterId }, { it.count }) }

    val state: StateFlow<NotebookLocalDetailUiState> = combine(
        notebookRepository.observeById(notebookId),
        chapterRepository.observeForNotebook(notebookId),
        pageRepository.observeForNotebook(notebookId),
        pageCountsFlow,
    ) { notebook, chapters, pages, pageCounts ->
        // Pre-seed every chapter with an empty list so the UI can render a
        // section for it even when it has no pages yet.
        val grouped = chapters.associate { it.id to emptyList<PageEntity>() }.toMutableMap()
        pages.forEach { page ->
            // Defensive: a page's chapter might have been soft-deleted between
            // the two Flow emissions. Skip rather than stash under an unknown
            // key.
            val existing = grouped[page.chapterId] ?: return@forEach
            grouped[page.chapterId] = existing + page
        }
        // Within each chapter, re-sort by the page's own position (the flat
        // notebook query sorts by updated_at; we want stable per-chapter
        // ordering for the sectioned view).
        val sorted = grouped.mapValues { (_, list) ->
            list.sortedWith(compareBy({ it.position }, { it.createdAt }))
        }
        NotebookLocalDetailUiState(
            isLoading            = false,
            notebook             = notebook,
            chapters             = chapters,
            pagesByChapter       = sorted,
            pageCountsByChapter  = pageCounts,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NotebookLocalDetailUiState(),
    )

    /**
     * Archived (soft-deleted) chapters under this notebook, newest-
     * archived first. Drives the "View archived chapters" sheet —
     * surfaced as a small footer link on the chapters card so the
     * affordance only appears when there's something to recover.
     */
    val archivedChapters: StateFlow<List<ChapterEntity>> =
        chapterRepository.observeArchivedForNotebook(notebookId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    /* ---------- notebook actions ---------- */

    /** Apply an edit from the hero's Edit dialog. No-ops if unchanged. */
    fun saveNotebook(title: String, description: String?) {
        val current = state.value.notebook ?: return
        val cleanedTitle = title.trim().ifEmpty { current.title }
        val cleanedDescription = description?.trim()?.ifEmpty { null }
        if (cleanedTitle == current.title && cleanedDescription == current.description) return
        viewModelScope.launch {
            notebookRepository.saveNotebook(
                current.copy(title = cleanedTitle, description = cleanedDescription),
            )
        }
    }

    /** Restore a previously archived notebook. Surfaces from the
     *  ArchivedBanner shown at the top of an already-archived
     *  notebook. */
    fun restoreNotebook() {
        val id = state.value.notebook?.id ?: return
        viewModelScope.launch { notebookRepository.undoSoftDeleteNotebook(id) }
    }

    /**
     * Delete the notebook this screen is viewing. The screen confirms via
     * its guard dialog before calling, then routes back on completion so
     * the user lands on the Notebooks tab where the row tombstone applies.
     */
    fun softDeleteNotebook(onDeleted: () -> Unit = {}) {
        val id = state.value.notebook?.id ?: return
        viewModelScope.launch {
            notebookRepository.softDeleteNotebook(id)
            onDeleted()
        }
    }

    /* ---------- chapter actions ---------- */

    fun createChapter(title: String, description: String? = null, onCreated: (String) -> Unit = {}) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val created = chapterRepository.createChapter(notebookId, trimmed, description)
            onCreated(created.id)
        }
    }

    fun softDeleteChapter(id: String) {
        viewModelScope.launch { chapterRepository.softDeleteChapter(id) }
    }

    /**
     * Reorder a chapter up or down. Builds the desired post-move list
     * locally, then assigns distinct, evenly-spaced positions
     * (`(index + 1) * STRIDE`) to every chapter so the new ordering
     * is unambiguous even when existing chapters share the default
     * 1024 position (the entity's column default), which would
     * otherwise leave a naive position-swap inert.
     */
    fun moveChapter(id: String, direction: ChapterMoveDirection) {
        val chapters = state.value.chapters
        val index    = chapters.indexOfFirst { it.id == id }
        if (index < 0) return
        val targetIndex = when (direction) {
            ChapterMoveDirection.Up   -> index - 1
            ChapterMoveDirection.Down -> index + 1
        }
        if (targetIndex !in chapters.indices) return

        val reordered = chapters.toMutableList().apply {
            add(targetIndex, removeAt(index))
        }
        viewModelScope.launch {
            reordered.forEachIndexed { i, chapter ->
                val newPosition = (i + 1) * CHAPTER_POSITION_STRIDE
                if (chapter.position != newPosition) {
                    chapterRepository.saveChapter(chapter.copy(position = newPosition))
                }
            }
        }
    }

    /**
     * Restore a chapter. Only the chapter row itself is restored; pages that
     * were cascaded into tombstones by the delete stay deleted. Same
     * phase-3 follow-up noted in NotebookRepository.undoSoftDeleteNotebook.
     */
    fun undoDeleteChapter(id: String) {
        viewModelScope.launch { chapterRepository.undoSoftDeleteChapter(id) }
    }

    /* ---------- page actions ---------- */

    /**
     * Create an empty page under a chapter. Returns the id via callback so
     * the screen can route straight into the editor for it — that's the
     * "tap FAB → start writing" flow.
     */
    fun createPage(chapterId: String, onCreated: (String) -> Unit = {}) {
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

    /**
     * Create a page directly under the notebook's default chapter — the
     * "Default" sentinel that auto-created with a flat notebook.
     * No-ops on a chaptered notebook (the user shouldn't see the
     * affordance there). Returns the new id via [onCreated] so the
     * screen can route into the editor.
     */
    fun createPageOnDefault(onCreated: (String) -> Unit = {}) {
        val sentinel = state.value.chapters.firstOrNull() ?: return
        viewModelScope.launch {
            val created = pageRepository.createPage(
                chapterId = sentinel.id,
                title     = null,
                notes     = "",
            )
            onCreated(created.id)
        }
    }

    /**
     * Reorder a page within a flat notebook's default chapter. Same
     * shape as moveChapter — assigns evenly-spaced positions to every
     * page in the new order so the swap is unambiguous even if pages
     * share the default 1024 position.
     */
    fun movePage(id: String, direction: ChapterMoveDirection) {
        val sentinel = state.value.chapters.firstOrNull() ?: return
        val pages = state.value.pagesByChapter[sentinel.id].orEmpty()
        val index = pages.indexOfFirst { it.id == id }
        if (index < 0) return
        val targetIndex = when (direction) {
            ChapterMoveDirection.Up   -> index - 1
            ChapterMoveDirection.Down -> index + 1
        }
        if (targetIndex !in pages.indices) return

        val reordered = pages.toMutableList().apply {
            add(targetIndex, removeAt(index))
        }
        viewModelScope.launch {
            reordered.forEachIndexed { i, page ->
                val newPosition = (i + 1) * CHAPTER_POSITION_STRIDE
                if (page.position != newPosition) {
                    pageRepository.savePage(page.copy(position = newPosition))
                }
            }
        }
    }

    companion object {
        const val ARG_NOTEBOOK_ID = "notebookId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val savedState: SavedStateHandle = createSavedStateHandle()
                val notebookId = checkNotNull(savedState.get<String>(ARG_NOTEBOOK_ID)) {
                    "NotebookLocalDetailViewModel missing $ARG_NOTEBOOK_ID"
                }
                NotebookLocalDetailViewModel(
                    application        = app,
                    notebookId         = notebookId,
                    notebookRepository = app.notebookRepository,
                    chapterRepository  = app.chapterRepository,
                    pageRepository     = app.pageRepository,
                )
            }
        }
    }
}
