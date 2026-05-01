/*
 * ShelfDetailViewModel.kt
 *
 * Room-backed VM for the variant-1 chapters screen. Observes the
 * notebook metadata, its chapters, and the flat list of every page
 * beneath, then regroups into `(Notebook, List<Chapter with pages>)`
 * in the domain shape the variant UI already speaks.
 *
 * Display-only fields (`shelfName`, `volumeNumber`, `iconKey`,
 * `colorToken`) are derived here from what the schema actually
 * persists (title, position, color_hex) so the UI doesn't carry
 * duplicate mapping logic.
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
import app.releaf.mobile.data.domain.Chapter
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.NotebookStatus
import app.releaf.mobile.data.domain.PageCounts
import app.releaf.mobile.data.domain.PageSummary
import app.releaf.mobile.data.drive.NotebookDetail
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notebook.parseAttachments
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ShelfDetailUiState {
    data object Loading : ShelfDetailUiState
    data class Loaded(val detail: NotebookDetail) : ShelfDetailUiState
    data class Failed(val message: String) : ShelfDetailUiState
}

class ShelfDetailViewModel(
    application: Application,
    private val notebookId: String,
    private val notebookRepository: NotebookRepository,
    private val chapterRepository: ChapterRepository,
    private val pageRepository: PageRepository,
) : AndroidViewModel(application) {

    val state: StateFlow<ShelfDetailUiState> = combine(
        notebookRepository.observeById(notebookId),
        chapterRepository.observeForNotebook(notebookId),
        pageRepository.observeForNotebook(notebookId),
    ) { notebook, chapters, pages ->
        if (notebook == null) {
            ShelfDetailUiState.Failed("Notebook not found") as ShelfDetailUiState
        } else {
            val pagesByChapter = pages.groupBy { it.chapterId }
            val domainChapters = chapters.map { entity ->
                val chapterPages = (pagesByChapter[entity.id].orEmpty())
                    .sortedWith(compareBy({ it.position }, { it.createdAt }))
                Chapter(
                    id         = entity.id,
                    notebookId = entity.notebookId,
                    title      = entity.title,
                    position   = (entity.position / 1024L).toInt().coerceAtLeast(0),
                    updatedAt  = parseIso(entity.updatedAt),
                    pages      = chapterPages.map(::toPageSummary),
                )
            }
            val nb = notebook.toDomain(
                chapterCount = domainChapters.size,
                pageCount    = pages.size,
            )
            ShelfDetailUiState.Loaded(NotebookDetail(nb, domainChapters)) as ShelfDetailUiState
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShelfDetailUiState.Loading,
    )

    /** Create an empty chapter. Shelves UI will re-render from the flow. */
    fun createChapter(
        title: String = "",
        onCreated: (String) -> Unit = {},
    ) {
        val resolved = title.trim().ifEmpty { "Untitled chapter" }
        viewModelScope.launch {
            val created = chapterRepository.createChapter(notebookId, resolved)
            onCreated(created.id)
        }
    }

    /** Create an empty page under [chapterId]; used when a chapter row has
     *  no pages yet and the user taps it from the shelves chapters view. */
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

    /**
     * Add a new volume of this book. If the book isn't part of a
     * series yet, promote it into one (using the book's current
     * title as the series name) and then append Volume 2. Returns
     * the new volume's id via [onCreated] so the caller can
     * navigate straight into it.
     *
     * `volumeName` is optional — leave blank to let the repository
     * render the default "<series> vol <n>" label.
     */
    fun addVolume(
        volumeName: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val seriesId = notebookRepository.ensureSeriesFor(notebookId = notebookId)
            val created = notebookRepository.addVolumeToSeries(
                seriesId   = seriesId,
                volumeName = volumeName,
            )
            onCreated(created.id)
        }
    }

    companion object {
        const val ARG_NOTEBOOK_ID = "notebookId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val saved: SavedStateHandle = createSavedStateHandle()
                val notebookId = checkNotNull(saved.get<String>(ARG_NOTEBOOK_ID)) {
                    "ShelfDetailViewModel missing $ARG_NOTEBOOK_ID"
                }
                ShelfDetailViewModel(
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

// ---------- entity → domain mapping ----------

private fun app.releaf.mobile.data.notebook.NotebookEntity.toDomain(
    chapterCount: Int,
    pageCount: Int,
): Notebook {
    val token = shelfColorToken(colorHex)
    return Notebook(
        id           = id,
        title        = title,
        description  = null,
        colorToken   = token,
        position     = (position / 1024L).toInt().coerceAtLeast(0),
        archivedAt   = null,
        updatedAt    = parseIso(updatedAt),
        chapterCount = chapterCount,
        pageCount    = pageCount,
        shelfName    = shelfNameFor(title),
        volumeNumber = (position / 1024L).toInt().coerceAtLeast(1),
        status       = NotebookStatus.Active,
        iconKey      = iconKeyFor(token),
    )
}

private fun toPageSummary(entity: PageEntity): PageSummary {
    val attachments = runCatching { entity.attachments.parseAttachments() }
        .getOrDefault(emptyList())
    val photos = attachments.count { it.type == "photo" }
    val scans  = attachments.count { it.type == "scan" }
    val voice  = attachments.count { it.type == "voice" }
    return PageSummary(
        id         = entity.id,
        title      = entity.title?.takeIf { it.isNotBlank() } ?: "Untitled page",
        capturedOn = humanDate(entity.createdAt),
        updatedAt  = parseIso(entity.updatedAt),
        counts     = PageCounts(
            photos           = photos,
            voiceNotes       = voice,
            scannedDocuments = scans,
        ),
        // No tag column yet; the variant page view derives its own
        // display tags. Left empty so callers don't treat fake tags
        // as real.
        tags       = emptyList(),
    )
}

private fun shelfNameFor(title: String): String {
    val t = title.trim()
    if (t.isEmpty()) return "NOTEBOOK"
    val head = t.substringBefore(' ').substringBefore('—').trim()
    return head.uppercase().ifEmpty { "NOTEBOOK" }
}

private fun shelfColorToken(hex: String?): String = when (hex?.uppercase()?.removePrefix("#")) {
    "7AA874" -> "green"
    "E07856" -> "coral"
    "F4C430" -> "yellow"
    "B8956A" -> "dry"
    "8E86DB", "6E66BB" -> "info"
    else -> "green"
}

private fun iconKeyFor(colorToken: String): String = when (colorToken) {
    "green"  -> "plant"
    "info"   -> "chart"
    "dry"    -> "sun"
    "coral"  -> "book"
    "yellow" -> "sun"
    else     -> "plant"
}

private fun parseIso(iso: String): Instant =
    runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)

private fun humanDate(iso: String): String {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(java.time.ZoneId.systemDefault())
    return fmt.format(instant)
}
