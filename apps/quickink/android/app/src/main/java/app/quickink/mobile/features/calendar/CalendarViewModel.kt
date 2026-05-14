/*
 * CalendarViewModel.kt
 *
 * State holder for QuickInk's full-screen Calendar surface. Owns:
 *   - the visible month (driven by chevrons + pager swipes)
 *   - the selected date (driven by cell taps + the "Today" CTA)
 *   - the festival search query
 *   - per-date / per-month / adjacent-months panchanga rows (drives
 *     the per-cell dots + moon glyphs + selected-day detail card)
 *   - search results across the whole panchanga dataset
 *   - QuickInk-specific: per-day capture buckets (drives the
 *     capture-dot on each cell + the "Scans on this day" list)
 *
 * Port of Releaf Android's `CalendarViewModel.kt`. Adds capture
 * observation via `captureDao.observeActive(userId)`; everything
 * else is package-rename + factory-wiring.
 */

package app.quickink.mobile.features.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.panchanga.PanchangaEntity
import app.quickink.mobile.data.panchanga.PanchangaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CalendarViewModel(
    private val repository: PanchangaRepository,
    private val userId: String,
    private val captureDaoFlow: kotlinx.coroutines.flow.Flow<List<CaptureEntity>>,
) : ViewModel() {

    /**
     * The day shown in the detail card below the grid. Defaults to
     * the Asia/Kolkata "today" because the panchanga is keyed to
     * IST — users in other timezones would otherwise see yesterday's
     * tithi for their first interaction.
     */
    private val _selectedDate: MutableStateFlow<LocalDate> =
        MutableStateFlow(LocalDate.now(IST))
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * The month rendered by the grid. Tracks selectedDate by default
     * but moves independently when the user taps the prev/next
     * chevrons (which don't change the selection).
     */
    private val _visibleMonth: MutableStateFlow<YearMonth> =
        MutableStateFlow(YearMonth.from(_selectedDate.value))
    val visibleMonth: StateFlow<YearMonth> = _visibleMonth.asStateFlow()

    private val _searchQuery: MutableStateFlow<String> = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedDayPanchanga: StateFlow<List<PanchangaEntity>> =
        _selectedDate
            .flatMapLatest { date -> repository.observeForDate(date.toString()) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val visibleMonthPanchanga: StateFlow<List<PanchangaEntity>> =
        _visibleMonth
            .flatMapLatest { month -> repository.observeForMonth(monthPrefix(month)) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val adjacentMonthsPanchanga: StateFlow<List<PanchangaEntity>> =
        _visibleMonth
            .flatMapLatest { month ->
                val prev = repository.observeForMonth(monthPrefix(month.minusMonths(1)))
                val curr = repository.observeForMonth(monthPrefix(month))
                val next = repository.observeForMonth(monthPrefix(month.plusMonths(1)))
                combine(prev, curr, next) { p, c, n -> p + c + n }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<PanchangaEntity>> =
        _searchQuery
            .flatMapLatest { q -> repository.searchSpecialDay(q) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * QuickInk-specific. All of the signed-in user's captures bucketed
     * by local-zone ISO date. Drives:
     *   - the per-cell capture dot on the grid (any day with > 0
     *     entries → coral dot)
     *   - the SelectedDayCapturesList rendered below the panchanga
     *     detail card on [CalendarScreen]
     */
    val capturesByDate: StateFlow<Map<LocalDate, List<CaptureEntity>>> =
        captureDaoFlow
            .map { rows -> bucketByLocalDate(rows) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        if (YearMonth.from(date) != _visibleMonth.value) {
            _visibleMonth.value = YearMonth.from(date)
        }
    }

    fun setVisibleMonth(month: YearMonth) {
        _visibleMonth.value = month
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    /** Snap selection + visible month to today (Asia/Kolkata). */
    fun goToToday() {
        val today = LocalDate.now(IST)
        _selectedDate.value = today
        _visibleMonth.value = YearMonth.from(today)
    }

    /** Convenience for the screen — selected day's captures (or empty). */
    val selectedDayCaptures: List<CaptureEntity>
        get() = capturesByDate.value[_selectedDate.value] ?: emptyList()

    private fun monthPrefix(month: YearMonth): String =
        "%04d-%02d-".format(month.year, month.monthValue)

    private fun bucketByLocalDate(rows: List<CaptureEntity>): Map<LocalDate, List<CaptureEntity>> {
        val out = HashMap<LocalDate, MutableList<CaptureEntity>>()
        for (row in rows) {
            val date = parseIsoToLocalDate(row.createdAt) ?: continue
            out.getOrPut(date) { mutableListOf() }.add(row)
        }
        return out
    }

    private fun parseIsoToLocalDate(iso: String): LocalDate? = runCatching {
        ZonedDateTime
            .parse(iso, isoParser)
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
    }.getOrNull() ?: runCatching {
        // Tolerate timestamps without fractional seconds (older Drive payloads).
        ZonedDateTime
            .parse(iso, isoParserNoFraction)
            .withZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
    }.getOrNull() ?: runCatching {
        // Final fallback — the column has been seen to occasionally
        // carry only the date prefix on hand-written rows; trust the
        // first 10 chars when present.
        LocalDate.parse(iso.substring(0, 10))
    }.getOrNull()

    companion object {
        /** Asia/Kolkata — the panchanga is published in IST. */
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        private val isoParser: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        private val isoParserNoFraction: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

        /**
         * Provide a `userId` via [CreationExtras] when constructing
         * the ViewModel. Hosts: `viewModel(factory = CalendarViewModel.factory(userId))`.
         */
        fun factory(userId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as QuickInkApp
                CalendarViewModel(
                    repository      = app.panchangaRepository,
                    userId          = userId,
                    captureDaoFlow  = app.database.captureDao().observeActive(userId),
                )
            }
        }
    }
}
