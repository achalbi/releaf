/*
 * NotepadScreenViewModel.kt
 *
 * Drives the redesigned Notepad tab — a Day / Recents segmented surface
 * built on the calendar-bloom-of-trees + garden-tiles-masonry design.
 *
 * Source: NotepadRepository.observeActive(userId) — every active entry
 * for the signed-in user. We derive three things from that stream:
 *
 *   - Today's entry + its capture / todo counts (used by the Day view's
 *     today card AND by the Recents view's hero tile)
 *   - A month-density map (one DayCount per day in the current month)
 *     used by the calendar-bloom tree grid
 *   - A "recent days" window used by the Recents masonry — last 10 days
 *     including any gaps so empty days render as hollow tiles
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
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.notepad.NotepadRepository
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Discrete density bucket the calendar-bloom UI maps to a tint.
 *  - Empty   : no entry on this day → hollow / pale tree
 *  - Light   : has an entry but ≤ 1 captures → light leaf-green
 *  - Mid     : 2–3 captures → mid leaf-green
 *  - Deep    : 4+ captures → deep canopy
 */
enum class DayDensity { Empty, Light, Mid, Deep }

/**
 * One day in the calendar / recents views. Carries the entry (if any),
 * a derived capture count, and the open-todo count so the UI can render
 * tile dots / "+ N todos" without re-parsing JSON.
 */
data class DayCount(
    val date: LocalDate,
    val entry: NotepadEntry?,
    val captureCount: Int,
    val openTodoCount: Int,
) {
    val density: DayDensity get() = when {
        entry == null            -> DayDensity.Empty
        captureCount >= 4        -> DayDensity.Deep
        captureCount >= 2        -> DayDensity.Mid
        else                     -> DayDensity.Light
    }
}

/** Per-mode breakdown for today's chip row. */
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
    val today: NotepadEntry? = null,
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
    /** Date-string → entry index. The swipeable pager uses this to derive
     *  [DayCount] lists for any month the user navigates to without
     *  needing the VM to pre-compute every month. */
    val byDate: Map<String, NotepadEntry> = emptyMap(),
    /** Last 10 days including today, newest first. Gaps included so the
     *  Recents masonry can render empty hollow tiles. */
    val recentDays: List<DayCount> = emptyList(),
)

/** Derive a DayCount list for any [month] from the VM's [byDate]
 *  index — used by the swipeable calendar pager to render months the
 *  user navigates to. */
fun daysForMonth(month: YearMonth, byDate: Map<String, NotepadEntry>): List<DayCount> =
    (1..month.lengthOfMonth()).map { d ->
        val date = month.atDay(d)
        val entry = byDate[date.toString()]
        val captures = entry?.let { totalCapturesFor(it) } ?: 0
        val openTodos = entry?.let { openTodoCountFor(it) } ?: 0
        DayCount(date, entry, captures, openTodos)
    }

class NotepadScreenViewModel(
    application: Application,
    private val session: GoogleAuthSession,
    private val notepadRepository: NotepadRepository,
) : AndroidViewModel(application) {

    val state: StateFlow<NotepadScreenUiState> = notepadRepository
        .observeActive(session.userId)
        .map { entries -> derive(entries) }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotepadScreenUiState(),
        )

    /** Create a new notepad entry filed under today and notify the caller. */
    fun createForToday(onCreated: (String) -> Unit = {}) {
        createForDate(LocalDate.now(), onCreated)
    }

    /** Open the entry filed under [date] if one exists, else create a
     *  fresh one filed under that date and call [onResult] with its id.
     *  Used by the quick-capture pills so a capture lands on the day
     *  the user has selected in the calendar, not always on today. */
    fun openOrCreateForDate(date: LocalDate, onResult: (String) -> Unit = {}) {
        val key = date.toString()
        val existing = state.value.byDate[key]
        if (existing != null) {
            onResult(existing.id)
        } else {
            createForDate(date, onResult)
        }
    }

    private fun createForDate(date: LocalDate, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val entry = notepadRepository.create(
                userId    = session.userId,
                entryDate = date.toString(),
                title     = null,
                notes     = "",
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
                        attachments = listOf(attachment).toJsonString(),
                    )
                }.onSuccess { created++ }
            }
            onComplete(created)
        }
    }

    // ---- derive ----

    private fun derive(entries: List<NotepadEntry>): NotepadScreenUiState {
        val today = LocalDate.now()
        val month = YearMonth.from(today)

        // Group all active entries by their stored entry-date string. The
        // table allows >1 entry per day; we treat the most-recently-updated
        // one as the canonical day-card.
        val byDate: Map<String, NotepadEntry> = entries
            .groupBy { it.entryDate }
            .mapValues { (_, list) -> list.maxByOrNull { it.updatedAt } ?: list.first() }

        val todayKey   = today.toString()
        val todayEntry = byDate[todayKey]
        val todayBreak = todayEntry?.let { breakdownFor(it) } ?: TodayBreakdown()

        // Calendar bloom — one DayCount per day in the current month.
        val monthDays = daysFor(month, byDate)
        val prevMonthDays = daysFor(month.minusMonths(1), byDate)
        val nextMonthDays = daysFor(month.plusMonths(1), byDate)

        // Recents masonry — last 10 days including today, newest first.
        // Gaps are kept (empty entry) so hollow tiles can render.
        val recentDays = (0L..9L).map { i ->
            val date = today.minusDays(i)
            dayCountFor(date, byDate[date.toString()])
        }

        return NotepadScreenUiState(
            isLoading       = false,
            today           = todayEntry,
            todayBreakdown  = todayBreak,
            month           = month,
            monthDays       = monthDays,
            prevMonthDays   = prevMonthDays,
            nextMonthDays   = nextMonthDays,
            byDate          = byDate,
            recentDays      = recentDays,
        )
    }

    /** Build a DayCount per day-of-month for [m], using [byDate] as the
     *  entry lookup — used three times (prev / current / next) to feed
     *  the calendar carousel. */
    private fun daysFor(
        m: YearMonth,
        byDate: Map<String, NotepadEntry>,
    ): List<DayCount> = (1..m.lengthOfMonth()).map { d ->
        val date = m.atDay(d)
        dayCountFor(date, byDate[date.toString()])
    }

    private fun dayCountFor(date: LocalDate, entry: NotepadEntry?): DayCount {
        val captures = entry?.let { totalCapturesFor(it) } ?: 0
        val openTodos = entry?.let { openTodoCountFor(it) } ?: 0
        return DayCount(date, entry, captures, openTodos)
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

private fun breakdownFor(entry: NotepadEntry): TodayBreakdown {
    val attachments = runCatching { entry.attachments.parseAttachments() }.getOrDefault(emptyList())
    val contacts    = runCatching { entry.contacts.parseContacts() }.getOrDefault(emptyList())
    val locations   = runCatching { entry.locations.parseLocations() }.getOrDefault(emptyList())
    val todos       = runCatching { entry.todos.parseTodos() }.getOrDefault(emptyList())
    return TodayBreakdown(
        photoCount     = attachments.count { it.type == "photo" },
        scanCount      = attachments.count { it.type == "scan"  },
        voiceCount     = attachments.count { it.type == "voice" },
        contactCount   = contacts.size,
        locationCount  = locations.size,
        openTodoCount  = todos.count { !it.done },
    )
}

private fun totalCapturesFor(entry: NotepadEntry): Int {
    val b = breakdownFor(entry)
    return b.captureCount
}

private fun openTodoCountFor(entry: NotepadEntry): Int =
    runCatching { entry.todos.parseTodos().count { !it.done } }.getOrDefault(0)
