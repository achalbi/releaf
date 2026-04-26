/*
 * PageDetailViewModel.kt
 *
 * Loads a single Page (full payload with all seven capture modes) and
 * owns the state for the page-level overflow actions: archive,
 * duplicate, share, export PDF, move-to-notebook, apply-template.
 *
 * Each action has a corresponding `fun` here + a matching StateFlow
 * for any UI surface (alert, sheet, share intent, toast) it presents.
 * The screen binds to the flows rather than managing presentation
 * itself; the menu items just call `viewModel.archivePage()` etc.
 *
 * Repository wiring: archive / duplicate currently surface a toast
 * and TODO against the data layer — wiring real DriveRepository
 * mutations is a bounded follow-up. The ViewModel's contract
 * doesn't change when that lands.
 */

package app.releaf.mobile.features.page

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.PageTemplate
import app.releaf.mobile.data.drive.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PageDetailUiState {
    data object Loading : PageDetailUiState
    /** Loaded state. `parentNotebook` is the page's parent — populated
     *  on a best-effort basis after the page itself loads so the
     *  header eyebrow can pick up its color. Null when the lookup
     *  hasn't completed yet or the parent isn't reachable; in that
     *  case the eyebrow falls back to default green. */
    data class Loaded(
        val page: Page,
        val parentNotebook: Notebook? = null,
    ) : PageDetailUiState
    data class Failed(val message: String) : PageDetailUiState
}

/** Payload for the system share sheet. Carries text that the share
 *  sheet treats as the share content, and an optional `fileUri` for
 *  share targets that prefer a real file (the PDF-export path uses
 *  this; plain text-share leaves it null). */
data class ShareIntent(
    val title: String,
    val body: String,
    val fileUri: android.net.Uri? = null,
    val fileMime: String = "text/plain",
)

/** One-shot toast emitted after an action fires. `actionLabel` +
 *  `actionKind` optionally surface a single inline pill the user
 *  can tap to follow up — currently used by the archive flow to
 *  offer "Undo". Passing both fields unlocks the pill; nil values
 *  render the toast as a plain message. */
data class PageToast(
    val message: String,
    val actionLabel: String? = null,
    val actionKind: ToastActionKind? = null,
)

/** Discrete tags for toast follow-up actions. The screen
 *  dispatches back through `viewModel.performToastAction(...)`
 *  which translates the tag into the right call. */
enum class ToastActionKind { UndoArchive }

/** Aggregated overflow-action state. The screen binds to one flow
 *  and reads the booleans + intents from it. Keeping this in a
 *  single object means a single .collectAsState() in the View. */
data class PageOverflowState(
    val confirmingArchive: Boolean = false,
    val pendingShare: ShareIntent? = null,
    val presentingMoveSheet: Boolean = false,
    val presentingTemplateSheet: Boolean = false,
    val toast: PageToast? = null,
    /** Notebooks the user has, populated when the Move-to-notebook
     *  picker opens. Picker reads directly off this list. */
    val availableNotebooks: List<Notebook> = emptyList(),
    /** True while [availableNotebooks] is being fetched; the picker
     *  shows a spinner row while this is true. */
    val loadingNotebooks: Boolean = false,
    /** Chapters per notebook id, lazy-loaded when a row is
     *  expanded in the picker. Empty for notebooks the user
     *  hasn't drilled into. */
    val chaptersByNotebookId: Map<String, List<Chapter>> = emptyMap(),
    /** Notebook ids currently being loaded; the row shows a
     *  per-row spinner. */
    val chaptersLoadingFor: Set<String> = emptySet(),
    /** Templates available to apply, populated when the
     *  Apply-template picker opens. */
    val availableTemplates: List<PageTemplate> = emptyList(),
    /** True while [availableTemplates] is being fetched. */
    val loadingTemplates: Boolean = false,
    /** Whether the tag editor sheet is open. */
    val presentingTagEditor: Boolean = false,
)

class PageDetailViewModel(
    application: Application,
    private val pageId: String,
    private val repository: DriveRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<PageDetailUiState>(PageDetailUiState.Loading)
    val state: StateFlow<PageDetailUiState> = _state.asStateFlow()

    private val _overflow = MutableStateFlow(PageOverflowState())
    val overflow: StateFlow<PageOverflowState> = _overflow.asStateFlow()

    init { load() }

    fun load() {
        _state.value = PageDetailUiState.Loading
        viewModelScope.launch {
            try {
                val page = repository.loadPage(pageId)
                _state.value = PageDetailUiState.Loaded(page)
                // Best-effort parent-notebook lookup so the header
                // eyebrow can tint to the notebook's color. Failure
                // is silent — the header simply renders default
                // green.
                runCatching { repository.loadNotebook(page.notebookId) }
                    .onSuccess { detail ->
                        val current = _state.value
                        if (current is PageDetailUiState.Loaded) {
                            _state.value = current.copy(parentNotebook = detail.notebook)
                        }
                    }
            } catch (e: Exception) {
                _state.value = PageDetailUiState.Failed(
                    e.localizedMessage ?: "Couldn't load page"
                )
            }
        }
    }

    // ---------- Overflow actions ----------

    /** Step 1 of archive — show a confirmation. The screen binds an
     *  AlertDialog to `confirmingArchive`; tapping confirm calls
     *  [confirmArchive]. */
    fun archivePage() {
        if (_state.value !is PageDetailUiState.Loaded) return
        _overflow.update { it.copy(confirmingArchive = true) }
    }

    /** Step 2 of archive — performs the archival, refreshes the
     *  loaded page so the ArchivedBanner shows immediately, and
     *  surfaces a toast. */
    fun confirmArchive() {
        viewModelScope.launch {
            try {
                val updated = repository.archivePage(pageId)
                _state.value = updateLoadedPage(updated)
                // Surface an inline "Undo" pill on the toast — tap
                // dispatches back through `performToastAction` which
                // calls `restorePage()`. The toast still
                // auto-dismisses after the screen's display window;
                // the pill just gives a shortcut while it's up.
                _overflow.update {
                    it.copy(
                        confirmingArchive = false,
                        toast = PageToast(
                            message     = "Page archived",
                            actionLabel = "Undo",
                            actionKind  = ToastActionKind.UndoArchive,
                        ),
                    )
                }
            } catch (e: Exception) {
                _overflow.update {
                    it.copy(confirmingArchive = false, toast = PageToast("Couldn't archive — try again"))
                }
            }
        }
    }

    /** Open the tag editor sheet. The screen binds presentation
     *  state to [PageOverflowState.presentingTagEditor]. */
    fun presentTagEditor() {
        if (_state.value !is PageDetailUiState.Loaded) return
        _overflow.update { it.copy(presentingTagEditor = true) }
    }

    fun dismissTagEditor() {
        _overflow.update { it.copy(presentingTagEditor = false) }
    }

    /** Persist the user's edited tag list. De-dupes
     *  case-insensitively while preserving order (first occurrence
     *  wins). Updates state with the returned page so the new tags
     *  appear immediately. */
    fun saveTags(tags: List<String>) {
        viewModelScope.launch {
            val cleaned = dedupeTags(tags)
            try {
                val updated = repository.setPageTags(pageId, cleaned)
                _state.value = updateLoadedPage(updated)
                _overflow.update {
                    it.copy(
                        presentingTagEditor = false,
                        toast = PageToast(
                            if (cleaned.isEmpty()) "Tags cleared"
                            else "Tags updated · ${cleaned.size}"
                        ),
                    )
                }
            } catch (e: Exception) {
                _overflow.update { it.copy(toast = PageToast("Couldn't save tags — try again")) }
            }
        }
    }

    /** Run the follow-up action attached to the current toast.
     *  Tagged dispatch (rather than storing a closure on PageToast)
     *  keeps the data class trivially equatable and the action
     *  set auditable in one place. */
    fun performToastAction(kind: ToastActionKind) {
        // Clear the toast right away so the action button doesn't
        // double-fire if the user taps mid-animation. The result
        // toast (e.g. "Page restored") is set by the action itself.
        _overflow.update { it.copy(toast = null) }
        when (kind) {
            ToastActionKind.UndoArchive -> restorePage()
        }
    }

    /** Copy a single tag to the system clipboard and emit a toast
     *  confirming the action. Bound to the long-press gesture on
     *  the read-only tag pills surfaced under the page title — the
     *  short-tap on the same pill goes through `presentTagEditor`. */
    fun copyTagToClipboard(tag: String) {
        val context = getApplication<Application>().applicationContext
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Tag", tag))
        _overflow.update { it.copy(toast = PageToast("Copied · $tag")) }
    }

    /** Copy a deep-link to the current page onto the clipboard.
     *  The URL scheme (`releaf://page/{id}`) is a placeholder —
     *  the app doesn't yet handle inbound links, but the format
     *  is stable so users can save / share these strings now. The
     *  toast surfaces the URL itself so users can sanity-check
     *  what landed on the clipboard before pasting. */
    fun copyPageLinkToClipboard() {
        val loaded = _state.value as? PageDetailUiState.Loaded ?: return
        val url = "releaf://page/${loaded.page.id}"
        val context = getApplication<Application>().applicationContext
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Page link", url))
        _overflow.update { it.copy(toast = PageToast("Copied · $url")) }
    }

    /** Copy a list of tags to the clipboard as a comma-separated
     *  string. Surfaces from the "Copy all" pill on the
     *  `EditTagsSheet`. Same toast pipeline as `copyTagToClipboard`. */
    fun copyTagsToClipboard(tags: List<String>) {
        val joined = tags.joinToString(", ")
        if (joined.isEmpty()) return
        val context = getApplication<Application>().applicationContext
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Tags", joined))
        val label = if (tags.size == 1) "1 tag" else "${tags.size} tags"
        _overflow.update { it.copy(toast = PageToast("Copied · $label")) }
    }

    /** Copy the daily plant's headline (Sanskrit name + epithet) to
     *  the clipboard. Surfaces from the Copy pill on the
     *  `DailyPlantInfoSheet`. Same toast pipeline as `copyTag`. */
    fun copyPlantHeadlineToClipboard(plant: app.releaf.mobile.ui.theme.DailyPlant) {
        val headline = if (plant.commonName.isEmpty()) {
            "${plant.name} — ${plant.epithet}"
        } else {
            "${plant.name} (${plant.commonName}) — ${plant.epithet}"
        }
        val context = getApplication<Application>().applicationContext
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Plant", headline))
        _overflow.update { it.copy(toast = PageToast("Copied · ${plant.name}")) }
    }

    /** Build a new [PageDetailUiState.Loaded] using `updated` as the
     *  page, preserving whatever `parentNotebook` was already
     *  resolved on the prior Loaded state. Mutations like archive /
     *  duplicate / saveTags shouldn't drop the resolved parent —
     *  the eyebrow color would flicker back to default green. */
    private fun updateLoadedPage(updated: Page): PageDetailUiState.Loaded {
        val current = _state.value as? PageDetailUiState.Loaded
        return PageDetailUiState.Loaded(
            page = updated,
            parentNotebook = current?.parentNotebook,
        )
    }

    private fun dedupeTags(tags: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val out  = mutableListOf<String>()
        for (raw in tags) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (seen.add(key)) out += trimmed
        }
        return out
    }

    /** Inverse of archive. Called from the ArchivedBanner's Restore
     *  button. Refreshes the page so the banner disappears. */
    fun restorePage() {
        viewModelScope.launch {
            try {
                val updated = repository.restorePage(pageId)
                _state.value = updateLoadedPage(updated)
                _overflow.update { it.copy(toast = PageToast("Page restored")) }
            } catch (e: Exception) {
                _overflow.update { it.copy(toast = PageToast("Couldn't restore — try again")) }
            }
        }
    }

    fun cancelArchive() {
        _overflow.update { it.copy(confirmingArchive = false) }
    }

    /** Duplicate is a one-tap action; no confirmation. Calls the
     *  repo, navigates the detail to the new copy (so the user can
     *  immediately edit it), and surfaces a toast. */
    fun duplicatePage() {
        viewModelScope.launch {
            try {
                val copy = repository.duplicatePage(pageId)
                _state.value = updateLoadedPage(copy)
                _overflow.update { it.copy(toast = PageToast("Duplicated · ${copy.title}")) }
            } catch (e: Exception) {
                _overflow.update { it.copy(toast = PageToast("Couldn't duplicate — try again")) }
            }
        }
    }

    /** Builds a share intent off the loaded page. The screen reads
     *  `pendingShare` and launches a system share sheet. */
    fun presentShareSheet() {
        val loaded = _state.value as? PageDetailUiState.Loaded ?: return
        _overflow.update {
            it.copy(
                pendingShare = ShareIntent(
                    title = loaded.page.title,
                    body  = loaded.page.notes.firstOrNull()?.body ?: loaded.page.title,
                )
            )
        }
    }

    fun consumeShareIntent() {
        _overflow.update { it.copy(pendingShare = null) }
    }

    /** Render the loaded page to a PDF, write it to the app's
     *  cache directory under exports/, and route the file Uri
     *  through the share-sheet machinery. */
    fun exportPDF() {
        val loaded = _state.value as? PageDetailUiState.Loaded ?: return
        viewModelScope.launch {
            try {
                val uri = app.releaf.mobile.data.drive.PdfExporter.export(
                    context = getApplication(),
                    page    = loaded.page,
                )
                _overflow.update {
                    it.copy(
                        pendingShare = ShareIntent(
                            title    = loaded.page.title,
                            body     = loaded.page.title,
                            fileUri  = uri,
                            fileMime = "application/pdf",
                        ),
                        toast = PageToast("PDF ready"),
                    )
                }
            } catch (e: Exception) {
                _overflow.update { it.copy(toast = PageToast("Couldn't export — try again")) }
            }
        }
    }

    fun presentMoveToNotebook() {
        _overflow.update { it.copy(presentingMoveSheet = true) }
        loadAvailableNotebooks()
    }

    fun dismissMoveSheet() {
        _overflow.update { it.copy(presentingMoveSheet = false) }
    }

    /** Re-parent the current page under [notebookId]. When
     *  [chapterId] is null the destination's first chapter is used;
     *  otherwise that exact chapter receives the page. Closes the
     *  sheet on success and surfaces a toast. */
    fun selectNotebook(notebookId: String, chapterId: String? = null) {
        viewModelScope.launch {
            val nbTitle = _overflow.value.availableNotebooks
                .firstOrNull { it.id == notebookId }?.title
                ?: "notebook"
            val chapterTitle = _overflow.value.chaptersByNotebookId[notebookId]
                ?.firstOrNull { it.id == chapterId }?.title
            val label = if (chapterTitle != null) "$nbTitle / $chapterTitle" else nbTitle
            try {
                repository.movePage(pageId, notebookId, chapterId)
                _overflow.update {
                    it.copy(
                        presentingMoveSheet = false,
                        toast = PageToast("Moved to $label"),
                    )
                }
            } catch (e: Exception) {
                _overflow.update { it.copy(toast = PageToast("Couldn't move — try again")) }
            }
        }
    }

    /** Lazy-load chapters for a notebook the user is expanding in
     *  the picker. No-op once chapters are already cached. */
    fun loadChaptersFor(notebookId: String) {
        val current = _overflow.value
        if (current.chaptersByNotebookId.containsKey(notebookId)) return
        if (current.chaptersLoadingFor.contains(notebookId)) return
        _overflow.update { it.copy(chaptersLoadingFor = it.chaptersLoadingFor + notebookId) }
        viewModelScope.launch {
            try {
                val chapters = repository.loadChapters(notebookId)
                _overflow.update {
                    it.copy(
                        chaptersByNotebookId = it.chaptersByNotebookId + (notebookId to chapters),
                        chaptersLoadingFor   = it.chaptersLoadingFor - notebookId,
                    )
                }
            } catch (e: Exception) {
                _overflow.update {
                    it.copy(
                        chaptersByNotebookId = it.chaptersByNotebookId + (notebookId to emptyList()),
                        chaptersLoadingFor   = it.chaptersLoadingFor - notebookId,
                    )
                }
            }
        }
    }

    private fun loadAvailableNotebooks() {
        if (_overflow.value.loadingNotebooks) return
        _overflow.update { it.copy(loadingNotebooks = true) }
        viewModelScope.launch {
            try {
                val nbs = repository.listNotebooks()
                _overflow.update {
                    it.copy(loadingNotebooks = false, availableNotebooks = nbs)
                }
            } catch (e: Exception) {
                _overflow.update {
                    it.copy(
                        loadingNotebooks = false,
                        availableNotebooks = emptyList(),
                        toast = PageToast("Couldn't load notebooks"),
                    )
                }
            }
        }
    }

    fun presentTemplatePicker() {
        _overflow.update { it.copy(presentingTemplateSheet = true) }
        loadAvailableTemplates()
    }

    fun dismissTemplateSheet() {
        _overflow.update { it.copy(presentingTemplateSheet = false) }
    }

    /** Apply the chosen template's pre-filled content onto the
     *  current page. On success, refreshes the loaded page so the
     *  new notes / todos appear immediately. */
    fun selectTemplate(templateId: String) {
        viewModelScope.launch {
            val title = _overflow.value.availableTemplates
                .firstOrNull { it.id == templateId }?.title
                ?: "template"
            try {
                val updated = repository.applyTemplate(pageId, templateId)
                _state.value = updateLoadedPage(updated)
                _overflow.update {
                    it.copy(
                        presentingTemplateSheet = false,
                        toast = PageToast("Applied $title"),
                    )
                }
            } catch (e: Exception) {
                _overflow.update { it.copy(toast = PageToast("Couldn't apply — try again")) }
            }
        }
    }

    private fun loadAvailableTemplates() {
        if (_overflow.value.loadingTemplates) return
        _overflow.update { it.copy(loadingTemplates = true) }
        viewModelScope.launch {
            try {
                val templates = repository.listPageTemplates()
                _overflow.update {
                    it.copy(loadingTemplates = false, availableTemplates = templates)
                }
            } catch (e: Exception) {
                _overflow.update {
                    it.copy(
                        loadingTemplates = false,
                        availableTemplates = emptyList(),
                        toast = PageToast("Couldn't load templates"),
                    )
                }
            }
        }
    }

    fun consumeToast() {
        _overflow.update { it.copy(toast = null) }
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
