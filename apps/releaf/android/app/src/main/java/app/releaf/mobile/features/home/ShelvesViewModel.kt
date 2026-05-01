/*
 * ShelvesViewModel.kt
 *
 * Backs the variant-1 "Your shelves" screen with real Room data.
 * Joins three streams: notebooks (active), per-notebook chapter
 * counts, and per-notebook page counts, then maps each notebook
 * entity into the domain [Notebook] shape the shelves UI speaks.
 *
 * Shelf-awareness: exposes the list of live shelves so the book-
 * creation sheet can offer a picker, and threads `shelfId` through
 * the create call.
 */

package app.releaf.mobile.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.data.domain.CaptureCountsByMode
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.NotebookStatus
import app.releaf.mobile.data.domain.Shelf
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookCountRow
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notepad.NotepadRepository
import app.releaf.mobile.data.shelf.ShelfEntity
import app.releaf.mobile.data.shelf.ShelfRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One open page-todo flattened out of a single page's `todos` JSON
 * column, enriched with the context labels the library-header modal
 * needs to render a navigable row. `updatedAt` is the *page*'s last
 * edit — TodoItem itself has no per-row timestamp.
 */
data class OpenTodoRow(
    val id: String,
    val body: String,
    val priority: Int,
    val pageId: String,
    val pageTitle: String,
    val notebookTitle: String,
    val chapterTitle: String,
    val updatedAt: Instant,
)

sealed interface ShelvesUiState {
    data object Loading : ShelvesUiState
    data class Loaded(
        val notebooks: List<Notebook>,
        val shelves: List<Shelf>,
        val captureCounts: CaptureCountsByMode,
        val openTodos: List<OpenTodoRow>,
    ) : ShelvesUiState
}

class ShelvesViewModel(
    private val notebookRepository: NotebookRepository,
    chapterRepository: ChapterRepository,
    pageRepository: PageRepository,
    private val shelfRepository: ShelfRepository,
    notepadRepository: NotepadRepository,
    session: GoogleAuthSession,
) : ViewModel() {

    /**
     * Create a standalone book (no series) on the given shelf.
     * Falls back to the General shelf when `shelfId` is null so
     * pre-shelf-aware call-sites still work.
     */
    fun createNotebook(
        title: String = "",
        shelfId: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        val resolvedTitle = title.trim().ifEmpty { "Untitled notebook" }
        val resolvedShelf = shelfId?.takeIf { it.isNotBlank() } ?: ShelfEntity.DEFAULT_GENERAL_ID
        viewModelScope.launch {
            val created = notebookRepository.createNotebook(
                title   = resolvedTitle,
                shelfId = resolvedShelf,
            )
            onCreated(created.id)
        }
    }

    /** Create a book and its series in one go when the user opts
     *  into "this book will have volumes" at create time. */
    fun createBookInNewSeries(
        shelfId: String,
        seriesName: String,
        volumeName: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        val resolvedShelf = shelfId.takeIf { it.isNotBlank() } ?: ShelfEntity.DEFAULT_GENERAL_ID
        val resolvedName = seriesName.trim().ifEmpty { "Untitled book" }
        viewModelScope.launch {
            val created = notebookRepository.createBookInNewSeries(
                shelfId    = resolvedShelf,
                seriesName = resolvedName,
                volumeName = volumeName,
            )
            onCreated(created.id)
        }
    }

    /** Add a new volume to an existing series of a book. */
    fun addVolumeToSeries(
        seriesId: String,
        volumeName: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val created = notebookRepository.addVolumeToSeries(
                seriesId   = seriesId,
                volumeName = volumeName,
            )
            onCreated(created.id)
        }
    }

    /** Create a fresh shelf. The new shelf appears in [state] via the
     *  observer stream and is immediately available in the picker. */
    fun createShelf(name: String, onCreated: (String) -> Unit = {}) {
        val resolved = name.trim().ifEmpty { "Untitled shelf" }
        viewModelScope.launch {
            val shelf = shelfRepository.createShelf(name = resolved)
            onCreated(shelf.id)
        }
    }

    // Bundle the five notebook-side flows into one composite so that the
    // outer combine stays at five-arg arity (typed lambda, no array-cast
    // ceremony). The notepad flow joins as the second leg.
    private data class NotebookSnapshot(
        val notebooks: List<NotebookEntity>,
        val chapterCounts: Map<String, Int>,
        val pageCounts: Map<String, Int>,
        val shelves: List<ShelfEntity>,
        val pagesWithTodos: List<app.releaf.mobile.data.notebook.PageTodosRow>,
    )

    private val notebookSnapshot: Flow<NotebookSnapshot> = combine(
        notebookRepository.observeActive(),
        chapterRepository.observeChapterCounts().mapToCountMap(),
        pageRepository.observePageCountsByNotebook().mapToCountMap(),
        shelfRepository.observeActive(),
        pageRepository.observePagesWithTodos(),
    ) { notebooks, chapterCounts, pageCounts, shelves, pagesWithTodos ->
        NotebookSnapshot(notebooks, chapterCounts, pageCounts, shelves, pagesWithTodos)
    }

    val state: StateFlow<ShelvesUiState> = combine(
        notebookSnapshot,
        notepadRepository.observeActive(session.userId),
    ) { snapshot, notepad ->
        val notebooks      = snapshot.notebooks
        val chapterCounts  = snapshot.chapterCounts
        val pageCounts     = snapshot.pageCounts
        val shelves        = snapshot.shelves
        val pagesWithTodos = snapshot.pagesWithTodos

        val shelfNameById = shelves.associateBy({ it.id }, { it.name })
        val mapped = notebooks.mapIndexed { index, entity ->
            entity.toNotebook(
                index         = index,
                chapterCount  = chapterCounts[entity.id] ?: 0,
                pageCount     = pageCounts[entity.id] ?: 0,
                resolvedShelf = shelfNameById[entity.shelfId],
            )
        }
        val mappedShelves = shelves.map { it.toDomain() }

        // Aggregate capture counts span notebook AND notepad surfaces
        // so the trees-saved hero reflects everything the user has
        // committed digitally — not just notebook page count. Notepad
        // contributions break down into photos/scans/voice (parsed out
        // of the entry attachments JSON) plus contacts/locations from
        // their own JSON columns; entries themselves count as "notes"
        // alongside notebook pages.
        val parsedAttachments = notepad.map { entry ->
            runCatching { entry.attachments.parseAttachments() }.getOrDefault(emptyList())
        }
        val totalNotepadPhotos    = parsedAttachments.sumOf { list -> list.count { it.type == "photo" } }
        val totalNotepadScans     = parsedAttachments.sumOf { list -> list.count { it.type == "scan"  } }
        val totalNotepadVoice     = parsedAttachments.sumOf { list -> list.count { it.type == "voice" } }
        val totalNotepadContacts  = notepad.sumOf { entry ->
            runCatching { entry.contacts.parseContacts() }.getOrDefault(emptyList()).size
        }
        val totalNotepadLocations = notepad.sumOf { entry ->
            runCatching { entry.locations.parseLocations() }.getOrDefault(emptyList()).size
        }
        val captureCounts = CaptureCountsByMode(
            notes     = pageCounts.values.sum() + notepad.size,
            photos    = totalNotepadPhotos,
            scans     = totalNotepadScans,
            voice     = totalNotepadVoice,
            contacts  = totalNotepadContacts,
            locations = totalNotepadLocations,
        )
        // pagesWithTodos is already ordered newest-edit-first by the
        // DAO, so a flatMap preserves that across pages; within a
        // page we keep the author's ordering (position).
        val openTodos = pagesWithTodos.flatMap { row ->
            val parsed = runCatching { row.todos.parseTodos() }.getOrElse { emptyList() }
            parsed.asSequence()
                .filter { !it.done }
                .map { todo ->
                    OpenTodoRow(
                        id            = todo.id,
                        body          = todo.text,
                        priority      = todo.priority,
                        pageId        = row.id,
                        pageTitle     = row.title?.trim()?.ifEmpty { null } ?: "Untitled page",
                        notebookTitle = row.notebookTitle,
                        chapterTitle  = row.chapterTitle,
                        updatedAt     = parseIsoOrEpoch(row.updatedAt),
                    )
                }
                .toList()
        }
        ShelvesUiState.Loaded(
            notebooks     = mapped,
            shelves       = mappedShelves,
            captureCounts = captureCounts,
            openTodos     = openTodos,
        ) as ShelvesUiState
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShelvesUiState.Loading,
    )

    companion object {
        /** Session-aware factory — required so the view model can pull
         *  the signed-in user's notepad entries into the trees-saved
         *  totals. */
        fun factory(session: GoogleAuthSession): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                ShelvesViewModel(
                    notebookRepository = app.notebookRepository,
                    chapterRepository  = app.chapterRepository,
                    pageRepository     = app.pageRepository,
                    shelfRepository    = app.shelfRepository,
                    notepadRepository  = app.notepadRepository,
                    session            = session,
                )
            }
        }
    }
}

private fun Flow<List<NotebookCountRow>>.mapToCountMap(): Flow<Map<String, Int>> =
    map { rows -> rows.associateBy({ it.notebookId }, { it.count }) }

// ---------- entity → domain mapping ----------

private fun ShelfEntity.toDomain(): Shelf = Shelf(
    id        = id,
    name      = name,
    colorHex  = colorHex,
    position  = position.toInt(),
    updatedAt = parseIsoOrEpoch(updatedAt),
)

private fun NotebookEntity.toNotebook(
    index: Int,
    chapterCount: Int,
    pageCount: Int,
    resolvedShelf: String?,
): Notebook {
    val token = colorTokenFor(colorHex, fallbackIndex = index)
    // Prefer the real shelf name; fall back to first-word-of-title
    // for rows whose shelf got renamed or deleted between the two
    // observed snapshots (rare but possible inside the combine).
    val shelfDisplayName = resolvedShelf?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        ?: deriveShelfName(title)
    return Notebook(
        id                 = id,
        title              = title,
        description        = description,
        colorToken         = token,
        position           = index,
        archivedAt         = archivedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        updatedAt          = parseIsoOrEpoch(updatedAt),
        chapterCount       = chapterCount,
        pageCount          = pageCount,
        shelfName          = shelfDisplayName,
        volumeNumber       = volumeNumber,
        status             = if (archivedAt != null) NotebookStatus.Archived else NotebookStatus.Active,
        iconKey            = iconKeyFor(token),
        shelfId            = shelfId,
        seriesId           = seriesId,
        seriesVolumeNumber = volumeNumber,
        volumeLabel        = this.volumeName,
    )
}

/** Map a stored hex to one of the four theme palettes, rotating by
 *  index when there's no explicit color. */
private fun colorTokenFor(hex: String?, fallbackIndex: Int): String {
    when (hex?.uppercase()?.removePrefix("#")) {
        "7AA874" -> return "green"
        "E07856" -> return "coral"
        "F4C430" -> return "yellow"
        "B8956A" -> return "dry"
        "8E86DB", "6E66BB" -> return "info"
    }
    return ROTATION[fallbackIndex % ROTATION.size]
}

private val ROTATION = listOf("green", "info", "dry", "coral", "yellow")

private fun iconKeyFor(colorToken: String): String = when (colorToken) {
    "green"  -> "plant"
    "info"   -> "chart"
    "dry"    -> "sun"
    "coral"  -> "book"
    "yellow" -> "sun"
    else     -> "plant"
}

/** First word of the title, uppercased — the same feel as the Figma
 *  eyebrow ("GARDEN · VOL 02") without requiring a schema column. */
private fun deriveShelfName(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "NOTEBOOK"
    val first = trimmed.substringBefore(' ').substringBefore('—').trim()
    return first.uppercase().ifEmpty { "NOTEBOOK" }
}

/** `position` is stored on a 1024-step grid. Roughly convert to a
 *  user-visible volume number; fall back to index+1 when `position`
 *  hasn't been customised. */
private fun deriveVolumeNumber(position: Long, fallbackIndex: Int): Int {
    if (position <= 0) return fallbackIndex + 1
    val derived = (position / 1024L).toInt().coerceAtLeast(1)
    return if (derived > 99) fallbackIndex + 1 else derived
}

private fun parseIsoOrEpoch(iso: String): Instant =
    runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)
