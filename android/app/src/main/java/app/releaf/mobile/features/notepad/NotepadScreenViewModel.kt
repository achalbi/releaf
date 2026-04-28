/*
 * NotepadScreenViewModel.kt
 *
 * Drives the redesigned Notepad tab — a Day / Recents segmented surface
 * built on the calendar-bloom-of-trees + garden-tiles-masonry design.
 *
 * Source: NotepadRepository.observeActive(userId) — every active entry
 * for the signed-in user. We derive these from that stream:
 *
 *   - Today's entry list + its capture / todo counts (used by the Day
 *     view's today card AND by the Recents view's hero tile)
 *   - A month-density map (one DayCount per day in the current month)
 *     used by the calendar-bloom tree grid
 *   - A "recent days" window used by the Recents masonry — last 10 days
 *     including any gaps so empty days render as hollow tiles
 *   - Custom category list (everything not in NotepadCategory.Predefined
 *     that's still in active use) — surfaces in the top filter row
 *     alongside the predefined chips
 *
 * **Multi-entry per day.** Days can hold more than one entry now —
 * `DayCount.entries` is the full ordered list (newest createdAt first
 * inside a day so the carousel reads chronologically), and
 * `DayCount.entry` returns the most-recently-updated one for callers
 * that want a single representative.
 *
 * **Category filter.** `selectedCategory` (null = no filter) gates the
 * entries that flow into `byDate`, `entriesByDate`, `monthDays`, and
 * `recentDays`. The calendar bloom and the page carousel both derive
 * from those, so the filter is reflected end-to-end automatically.
 * `customCategories` is computed from the *unfiltered* set so the
 * filter chip row keeps showing every available category even after
 * the user has narrowed the view.
 */

package app.releaf.mobile.features.notepad

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import app.releaf.mobile.data.notepad.NotepadCategory
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.notepad.NotepadRepository
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Discrete density bucket the calendar-bloom UI maps to a tint.
 *  - Empty   : no entry on this day → hollow / pale tree
 *  - Light   : has at least one entry but ≤ 1 captures total → light leaf-green
 *  - Mid     : 2–3 captures total → mid leaf-green
 *  - Deep    : 4+ captures total → deep canopy
 *
 * Multi-entry days sum their capture counts before bucketing so a day
 * with two pages of two photos each reads as "Mid" rather than "Light"
 * twice.
 */
enum class DayDensity { Empty, Light, Mid, Deep }

/**
 * One day in the calendar / recents views. Multi-entry-aware: holds
 * the full [entries] list (sorted newest createdAt first within the
 * day). Convenience accessors:
 *   - [entry]: most-recently-updated entry, used by code that just
 *     wants a representative for previews / hero tiles.
 *   - [hasEntries]: shorthand for `entries.isNotEmpty()`, kept around
 *     so call sites read at a glance.
 */
data class DayCount(
    val date: LocalDate,
    val entries: List<NotepadEntry>,
    val captureCount: Int,
    val openTodoCount: Int,
) {
    val entry: NotepadEntry? get() = entries.maxByOrNull { it.updatedAt }
    val hasEntries: Boolean get() = entries.isNotEmpty()

    val density: DayDensity get() = when {
        !hasEntries        -> DayDensity.Empty
        captureCount >= 4  -> DayDensity.Deep
        captureCount >= 2  -> DayDensity.Mid
        else               -> DayDensity.Light
    }
}

/** Per-mode breakdown for today's chip row. Sums across every entry
 *  filed under today, since today can now hold multiple pages. */
data class TodayBreakdown(
    val photoCount: Int = 0,
    val scanCount: Int = 0,
    val voiceCount: Int = 0,
    val contactCount: Int = 0,
    val locationCount: Int = 0,
    val openTodoCount: Int = 0,
) {
    val captureCount: Int
        get() = photoCount + scanCount + voiceCount + contactCount + locationCount
}

data class NotepadScreenUiState(
    val isLoading: Boolean = true,
    /** Most-recently-updated today-entry, for the Recents hero tile. */
    val today: NotepadEntry? = null,
    /** Every entry filed under today (could be 0..N), newest createdAt first. */
    val todayEntries: List<NotepadEntry> = emptyList(),
    val todayBreakdown: TodayBreakdown = TodayBreakdown(),
    val month: YearMonth = YearMonth.now(),
    /** All days in the current month, in calendar order (1 → lengthOfMonth). */
    val monthDays: List<DayCount> = emptyList(),
    /** Same shape as [monthDays] but for the previous month — drives the
     *  faded left peek in the calendar carousel's static fallback. */
    val prevMonthDays: List<DayCount> = emptyList(),
    /** Same shape as [monthDays] but for the next month — drives the
     *  faded right peek in the calendar carousel's static fallback. */
    val nextMonthDays: List<DayCount> = emptyList(),
    /** Date-string → most-recently-updated entry for that date.
     *  Convenience for callers that just want "the" entry for a day;
     *  use [entriesByDate] when you need every entry. */
    val byDate: Map<String, NotepadEntry> = emptyMap(),
    /** Date-string → ordered list of every entry on that day (newest
     *  createdAt first). Drives the day-page carousel. */
    val entriesByDate: Map<String, List<NotepadEntry>> = emptyMap(),
    /** Last 10 days including today, newest first. Gaps included so the
     *  Recents masonry can render empty hollow tiles. */
    val recentDays: List<DayCount> = emptyList(),
    /** Active category filter; null = no filter (all entries flow). */
    val selectedCategory: String? = null,
    /** Custom (non-predefined) category strings discovered across every
     *  active entry — drives the filter chip row alongside the
     *  predefined list. Computed before the filter is applied so the
     *  user can switch back to a custom they previously narrowed
     *  away from. */
    val customCategories: List<String> = emptyList(),
)

/** Derive a DayCount list for any [month] from the VM's [byDate]
 *  index — used by the swipeable calendar pager to render months the
 *  user navigates to. Multi-entry-aware via [entriesByDate]. */
fun daysForMonth(
    month: YearMonth,
    byDate: Map<String, NotepadEntry>,
    entriesByDate: Map<String, List<NotepadEntry>> = emptyMap(),
): List<DayCount> = (1..month.lengthOfMonth()).map { d ->
    val date    = month.atDay(d)
    val key     = date.toString()
    val entries = entriesByDate[key]
        ?: byDate[key]?.let { listOf(it) }
        ?: emptyList()
    val captures = entries.sumOf { totalCapturesFor(it) }
    val openTodos = entries.sumOf { openTodoCountFor(it) }
    DayCount(date, entries, captures, openTodos)
}

class NotepadScreenViewModel(
    application: Application,
    private val session: GoogleAuthSession,
    private val notepadRepository: NotepadRepository,
) : AndroidViewModel(application) {

    /** Active category filter. Null = show every active entry (no
     *  filtering); non-null = case-insensitive match against
     *  `entry.category`. Mutated via [setCategoryFilter]. */
    private val _selectedCategory = MutableStateFlow<String?>(null)

    val state: StateFlow<NotepadScreenUiState> = combine(
        notepadRepository.observeActive(session.userId),
        _selectedCategory,
    ) { entries, filter -> derive(entries, filter) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotepadScreenUiState(),
        )

    /** Set or clear the active category filter. Predefined names are
     *  canonicalised to their display casing via
     *  `NotepadCategory.displayName` so "home" and "Home" don't form
     *  separate filter targets. Pass null (or blank) to clear. */
    fun setCategoryFilter(category: String?) {
        _selectedCategory.value = NotepadCategory.displayName(category)
    }

    /** Create a new notepad entry filed under today and notify the caller.
     *  When a category filter is active, the new entry inherits it so a
     *  user filtering by "Work" who taps "+ new page" gets a Work-tagged
     *  page rather than an uncategorised one that immediately disappears
     *  from view. */
    fun createForToday(onCreated: (String) -> Unit = {}) {
        createForDate(LocalDate.now(), onCreated)
    }

    /** Open the most-recently-updated entry filed under [date] if one
     *  exists, else create a fresh one filed under that date and call
     *  [onResult] with its id. Used by the quick-capture pills as a
     *  fallback when no page is currently selected on the day's
     *  carousel. */
    fun openOrCreateForDate(date: LocalDate, onResult: (String) -> Unit = {}) {
        val key = date.toString()
        val existing = state.value.byDate[key]
        if (existing != null) {
            onResult(existing.id)
        } else {
            createForDate(date, onResult)
        }
    }

    /** Open an existing entry by id. Trivial — exists so the screen
     *  can route quick-capture taps through the VM (keeps onOpenEntry
     *  routing in one place) when the user has selected a specific
     *  page on the day-pager. */
    fun openEntry(id: String, onResult: (String) -> Unit = {}) {
        onResult(id)
    }

    /** Add a brand-new page to [date] alongside any existing pages.
     *  Inherits the active filter category so the new page is visible
     *  in the current view. Returns the new id via [onCreated] so the
     *  caller can route the user into it (or just select it). */
    fun createNewPageOn(date: LocalDate, onCreated: (String) -> Unit = {}) {
        createForDate(date, onCreated)
    }

    private fun createForDate(date: LocalDate, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val entry = notepadRepository.create(
                userId    = session.userId,
                entryDate = date.toString(),
                title     = null,
                notes     = "",
                category  = _selectedCategory.value,
            )
            onCreated(entry.id)
        }
    }

    /**
     * Import photos as new Notepad entries — one entry per photo,
     * each filed under [date] with the photo as a single Attachment.
     * Picker URIs are already persistent (the caller takes a
     * persistable grant), so we keep them as `content://` strings
     * matching the photo-attachment convention in EditorSections.kt.
     *
     * Inherits the active filter category so imported entries stay
     * visible under the current narrowing — same rationale as
     * [createNewPageOn].
     */
    fun importPhotosAsNewEntries(
        date: LocalDate,
        uris: List<String>,
        onComplete: (Int) -> Unit = {},
    ) {
        if (uris.isEmpty()) { onComplete(0); return }
        viewModelScope.launch {
            val capturedAt = IsoClock.nowIso()
            val entryDate = date.toString()
            val category  = _selectedCategory.value
            var created = 0
            for (uri in uris) {
                val attachment = Attachment(
                    id         = Uuidv7.generate(),
                    type       = Attachment.TYPE_PHOTO,
                    uri        = uri,
                    capturedAt = capturedAt,
                )
                runCatching {
                    notepadRepository.create(
                        userId      = session.userId,
                        title       = null,
                        notes       = "",
                        entryDate   = entryDate,
                        category    = category,
                        attachments = listOf(attachment).toJsonString(),
                    )
                }.onSuccess { created++ }
            }
            onComplete(created)
        }
    }

    // ---- derive ----

    private fun derive(
        entries: List<NotepadEntry>,
        filter: String?,
    ): NotepadScreenUiState {
        val today = LocalDate.now()
        val month = YearMonth.from(today)

        // Customs come from the UNFILTERED set so the filter chip row
        // still surfaces categories the user has narrowed away from —
        // otherwise selecting "Work" would empty the chip row of any
        // other custom name and the user couldn't pivot back.
        val customs = NotepadCategory.deriveCustomCategories(entries)

        // Apply the filter. Null = pass everything; non-null compares
        // case-insensitive against `entry.category`. Trim before
        // compare so trailing-space typos don't fork.
        val filtered = if (filter.isNullOrBlank()) {
            entries
        } else {
            entries.filter { entry ->
                entry.category?.trim().equals(filter.trim(), ignoreCase = true)
            }
        }

        // Multi-entry-per-day. Sort within a day by createdAt so the
        // carousel reads chronologically (oldest leftmost / first
        // page); the convenience `entry` accessor on DayCount picks
        // the most-recently-updated one for representative use.
        val entriesByDate: Map<String, List<NotepadEntry>> = filtered
            .groupBy { it.entryDate }
            .mapValues { (_, list) -> list.sortedBy { it.createdAt } }

        val byDate: Map<String, NotepadEntry> = entriesByDate
            .mapValues { (_, list) -> list.maxByOrNull { it.updatedAt } ?: list.first() }

        val todayKey      = today.toString()
        val todayEntries  = entriesByDate[todayKey].orEmpty()
        val todayLatest   = byDate[todayKey]
        val todayBreak    = breakdownForList(todayEntries)

        // Calendar bloom — one DayCount per day in the current month.
        val monthDays     = daysFor(month, entriesByDate)
        val prevMonthDays = daysFor(month.minusMonths(1), entriesByDate)
        val nextMonthDays = daysFor(month.plusMonths(1), entriesByDate)

        // Recents masonry — last 10 days including today, newest first.
        // Gaps are kept (empty entries list) so hollow tiles can render.
        val recentDays = (0L..9L).map { i ->
            val date = today.minusDays(i)
            dayCountFor(date, entriesByDate[date.toString()].orEmpty())
        }

        return NotepadScreenUiState(
            isLoading        = false,
            today            = todayLatest,
            todayEntries     = todayEntries,
            todayBreakdown   = todayBreak,
            month            = month,
            monthDays        = monthDays,
            prevMonthDays    = prevMonthDays,
            nextMonthDays    = nextMonthDays,
            byDate           = byDate,
            entriesByDate    = entriesByDate,
            recentDays       = recentDays,
            selectedCategory = filter,
            customCategories = customs,
        )
    }

    /** Build a DayCount per day-of-month for [m] from the multi-entry
     *  index — used three times (prev / current / next) to feed the
     *  calendar carousel. */
    private fun daysFor(
        m: YearMonth,
        entriesByDate: Map<String, List<NotepadEntry>>,
    ): List<DayCount> = (1..m.lengthOfMonth()).map { d ->
        val date = m.atDay(d)
        dayCountFor(date, entriesByDate[date.toString()].orEmpty())
    }

    private fun dayCountFor(date: LocalDate, entries: List<NotepadEntry>): DayCount {
        val captures  = entries.sumOf { totalCapturesFor(it) }
        val openTodos = entries.sumOf { openTodoCountFor(it) }
        return DayCount(date, entries, captures, openTodos)
    }

    companion object {
        fun factory(session: GoogleAuthSession): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as ReleafApp
                    NotepadScreenViewModel(
                        application       = app,
                        session           = session,
                        notepadRepository = app.notepadRepository,
                    )
                }
            }
    }
}

// ---- entry → counts helpers ----

/** Sum a TodayBreakdown across [entries]. Used to derive the today
 *  card's chip row when today holds multiple pages. */
private fun breakdownForList(entries: List<NotepadEntry>): TodayBreakdown {
    if (entries.isEmpty()) return TodayBreakdown()
    var photo = 0; var scan = 0; var voice = 0
    var contact = 0; var location = 0; var openTodo = 0
    for (entry in entries) {
        val attachments = runCatching { entry.attachments.parseAttachments() }.getOrDefault(emptyList())
        val contacts    = runCatching { entry.contacts.parseContacts() }.getOrDefault(emptyList())
        val locations   = runCatching { entry.locations.parseLocations() }.getOrDefault(emptyList())
        val todos       = runCatching { entry.todos.parseTodos() }.getOrDefault(emptyList())
        photo    += attachments.count { it.type == "photo" }
        scan     += attachments.count { it.type == "scan"  }
        voice    += attachments.count { it.type == "voice" }
        contact  += contacts.size
        location += locations.size
        openTodo += todos.count { !it.done }
    }
    return TodayBreakdown(
        photoCount    = photo,
        scanCount     = scan,
        voiceCount    = voice,
        contactCount  = contact,
        locationCount = location,
        openTodoCount = openTodo,
    )
}

internal fun totalCapturesFor(entry: NotepadEntry): Int {
    val attachments = runCatching { entry.attachments.parseAttachments() }.getOrDefault(emptyList())
    val contacts    = runCatching { entry.contacts.parseContacts() }.getOrDefault(emptyList())
    val locations   = runCatching { entry.locations.parseLocations() }.getOrDefault(emptyList())
    return attachments.size + contacts.size + locations.size
}

internal fun openTodoCountFor(entry: NotepadEntry): Int =
    runCatching { entry.todos.parseTodos().count { !it.done } }.getOrDefault(0)
