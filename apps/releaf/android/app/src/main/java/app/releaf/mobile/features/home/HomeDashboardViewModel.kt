/*
 * HomeDashboardViewModel.kt
 *
 * Powers the redesigned Home screen: a compact dashboard of
 * notebook + notepad stat cards.
 *
 * Everything is Room-backed — no drive-fake data. Notebook counts
 * come off [NotebookRepository], notepad entries off
 * [NotepadRepository]. Today's notepad count is filtered locally
 * against the device's calendar day.
 */

package app.releaf.mobile.features.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.data.domain.CaptureCountsByMode
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookCountRow
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.notepad.NotepadRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One notebook row on the Home dashboard's Recent list. */
data class NotebookListItem(
    val id: String,
    val title: String,
    val chapterCount: Int,
    val updatedLabel: String,
    val isArchived: Boolean,
)

/** One notepad row on the Home dashboard's Recent list. */
data class NotepadListItem(
    val id: String,
    val title: String,
    val entryDateLabel: String,
    val photoCount: Int,
)

data class HomeDashboardUiState(
    val isLoading: Boolean = true,
    val totalNotebooks: Int = 0,
    val activeNotebooks: Int = 0,
    val archivedNotebooks: Int = 0,
    val totalNotepadEntries: Int = 0,
    val todayNotepadCount: Int = 0,
    val totalNotepadPhotos: Int = 0,
    val totalNotepadScans: Int = 0,
    val totalNotepadVoice: Int = 0,
    val totalNotepadContacts: Int = 0,
    val totalNotepadLocations: Int = 0,
    /** Sum of every attachment-style capture across notepad entries
     *  (photos + scans + voice + contacts + locations). Shown as the
     *  "CAPTURES" stat on the Home library card — entry count and
     *  open todos are rendered as their own lines. */
    val totalNotepadCaptures: Int = 0,
    val openNotepadTodos: Int = 0,
    val recentNotebooks: List<NotebookListItem> = emptyList(),
    val recentNotepadEntries: List<NotepadListItem> = emptyList(),
    val captureCounts: CaptureCountsByMode = CaptureCountsByMode.EMPTY,
)

class HomeDashboardViewModel(
    application: Application,
    private val session: GoogleAuthSession,
    private val notebookRepository: NotebookRepository,
    chapterRepository: ChapterRepository,
    pageRepository: PageRepository,
    private val notepadRepository: NotepadRepository,
) : AndroidViewModel(application) {

    private val activeFlow  = notebookRepository.observeActive()
    private val archivedFlow = notebookRepository.observeArchived()
    private val chapterCountsFlow: Flow<Map<String, Int>> =
        chapterRepository.observeChapterCounts().mapToCountMap()
    private val pageCountsFlow: Flow<Map<String, Int>> =
        pageRepository.observePageCountsByNotebook().mapToCountMap()
    private val notepadFlow = notepadRepository.observeActive(session.userId)

    val state: StateFlow<HomeDashboardUiState> = combine(
        activeFlow, archivedFlow, chapterCountsFlow, pageCountsFlow, notepadFlow,
    ) { active, archived, chapterCounts, pageCounts, notepad ->
        val today = LocalDate.now().toString()
        val todayCount = notepad.count { it.entryDate == today }

        val mostRecentlyUpdated = (active + archived)
            .sortedByDescending { it.updatedAt }
            .take(RECENT_NOTEBOOK_LIMIT)
            .map { entity ->
                NotebookListItem(
                    id           = entity.id,
                    title        = entity.title.ifBlank { "Untitled notebook" },
                    chapterCount = chapterCounts[entity.id] ?: 0,
                    updatedLabel = relativeShort(parseIso(entity.updatedAt)),
                    isArchived   = entity.archivedAt != null,
                )
            }

        val recentEntries = notepad
            .sortedByDescending { it.updatedAt }
            .take(RECENT_NOTEPAD_LIMIT)
            .map { entry ->
                val photoCount = runCatching { entry.attachments.parseAttachments() }
                    .getOrDefault(emptyList())
                    .count { it.type == "photo" }
                NotepadListItem(
                    id             = entry.id,
                    title          = displayTitle(entry),
                    entryDateLabel = humanEntryDate(entry.entryDate),
                    photoCount     = photoCount,
                )
            }

        // Summations across every notepad entry's page features:
        // photos / scans / voice from `attachments` JSON split by
        // `type`, open todos from `todos`, and contacts from
        // `contacts`. In-memory reductions — no extra DB hit.
        val parsedAttachments = notepad.map { entry ->
            runCatching { entry.attachments.parseAttachments() }.getOrDefault(emptyList())
        }
        val totalNotepadPhotos   = parsedAttachments.sumOf { list -> list.count { it.type == "photo" } }
        val totalNotepadScans    = parsedAttachments.sumOf { list -> list.count { it.type == "scan"  } }
        val totalNotepadVoice    = parsedAttachments.sumOf { list -> list.count { it.type == "voice" } }
        val totalNotepadContacts = notepad.sumOf { entry ->
            runCatching { entry.contacts.parseContacts() }.getOrDefault(emptyList()).size
        }
        val totalNotepadLocations = notepad.sumOf { entry ->
            runCatching { entry.locations.parseLocations() }.getOrDefault(emptyList()).size
        }
        val openNotepadTodos = notepad.sumOf { entry ->
            runCatching { entry.todos.parseTodos() }
                .getOrDefault(emptyList())
                .count { !it.done }
        }

        // Aggregate capture counts for the Home-tab trees-saved hero
        // by summing notebook-side + notepad-side contributions for
        // each mode. Notebook page count stands in as "notes" until
        // the captures-table migration ships with per-page breakdowns
        // of photos / scans / voice / contacts / locations.
        val captureCountsCombined = CaptureCountsByMode(
            notes     = pageCounts.values.sum() + notepad.size,
            photos    = totalNotepadPhotos,
            scans     = totalNotepadScans,
            voice     = totalNotepadVoice,
            contacts  = totalNotepadContacts,
            locations = totalNotepadLocations,
        )

        HomeDashboardUiState(
            isLoading            = false,
            totalNotebooks       = active.size + archived.size,
            activeNotebooks      = active.size,
            archivedNotebooks    = archived.size,
            totalNotepadEntries  = notepad.size,
            todayNotepadCount    = todayCount,
            totalNotepadPhotos    = totalNotepadPhotos,
            totalNotepadScans     = totalNotepadScans,
            totalNotepadVoice     = totalNotepadVoice,
            totalNotepadContacts  = totalNotepadContacts,
            totalNotepadLocations = totalNotepadLocations,
            totalNotepadCaptures  = totalNotepadPhotos + totalNotepadScans +
                                    totalNotepadVoice + totalNotepadContacts +
                                    totalNotepadLocations,
            openNotepadTodos      = openNotepadTodos,
            recentNotebooks      = mostRecentlyUpdated,
            recentNotepadEntries = recentEntries,
            captureCounts        = captureCountsCombined,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeDashboardUiState(),
    )

    /** Create a new notebook. The row appears via the observer stream. */
    fun createNotebook(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val created = notebookRepository.createNotebook(title = "Untitled notebook")
            onCreated(created.id)
        }
    }

    /** Create a new notepad entry for today. */
    fun createNotepadEntry(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val entry = notepadRepository.create(
                userId = session.userId,
                entryDate = LocalDate.now().toString(),
                title = null,
                notes = "",
            )
            onCreated(entry.id)
        }
    }

    companion object {
        private const val RECENT_NOTEBOOK_LIMIT = 2
        private const val RECENT_NOTEPAD_LIMIT = 2

        fun factory(session: GoogleAuthSession): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                HomeDashboardViewModel(
                    application        = app,
                    session            = session,
                    notebookRepository = app.notebookRepository,
                    chapterRepository  = app.chapterRepository,
                    pageRepository     = app.pageRepository,
                    notepadRepository  = app.notepadRepository,
                )
            }
        }
    }
}

// ---------- helpers ----------

private fun Flow<List<NotebookCountRow>>.mapToCountMap(): Flow<Map<String, Int>> =
    map { rows -> rows.associateBy({ it.notebookId }, { it.count }) }

private fun parseIso(iso: String): Instant =
    runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)

private fun displayTitle(entry: NotepadEntry): String {
    val t = entry.title?.trim().orEmpty()
    if (t.isNotEmpty()) return t
    val firstLine = entry.notes.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
    return firstLine?.take(80) ?: "Untitled entry"
}

private fun humanEntryDate(isoDate: String): String {
    return runCatching {
        val date = LocalDate.parse(isoDate)
        DateTimeFormatter.ofPattern("MMM d, yyyy").format(date)
    }.getOrDefault(isoDate)
}
