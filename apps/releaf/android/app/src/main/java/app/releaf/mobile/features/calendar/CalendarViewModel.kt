/*
 * CalendarViewModel.kt
 *
 * State holder for the full-screen calendar surface. Owns:
 *   - the visible month (driven by chevrons + outside-month taps)
 *   - the selected date (driven by cell taps + the "Today" CTA)
 *   - the festival search query (drawer'd into the screen)
 *
 * All three drive observation flows over the panchanga DAO so the
 * screen renders a per-date detail card, dot indicators across the
 * grid, and an optional search-results list. The repo's
 * `ensureLoaded()` is called on init so a freshly-installed device
 * doesn't hit an empty DB on first render.
 */

package app.releaf.mobile.features.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.data.panchanga.PanchangaEntity
import app.releaf.mobile.data.panchanga.PanchangaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CalendarViewModel(
    private val repository: PanchangaRepository,
) : ViewModel() {

    /**
     * The day shown in the detail card below the grid. Defaults to the
     * Asia/Kolkata "today" because the panchanga is keyed to IST —
     * users in other timezones would otherwise see yesterday's tithi
     * for their first interaction.
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

    /**
     * Panchanga rows for [selectedDate]. Empty list = "data not
     * available for this date" (out of dataset range or DB not
     * bootstrapped yet). Some dates carry 2+ rows — see PanchangaDao.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedDayPanchanga: StateFlow<List<PanchangaEntity>> =
        _selectedDate
            .flatMapLatest { date -> repository.observeForDate(date.toString()) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Panchanga rows for the visible month. The screen turns this
     * into a `Set<LocalDate>` for the calendar's dot indicators
     * (rows whose `special_day` is non-empty).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val visibleMonthPanchanga: StateFlow<List<PanchangaEntity>> =
        _visibleMonth
            .flatMapLatest { month ->
                // `yyyy-MM-` for the LIKE pattern in the DAO.
                repository.observeForMonth(monthPrefix(month))
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Panchanga rows spanning the visible month and its two
     * neighbours. The grid spills 1–6 days from each adjacent
     * month into the leading / trailing cells, and the moon-phase
     * glyphs (full + new moon) need to render on those spillover
     * cells too — which means the date set powering the glyphs
     * has to know about Amavasya / Purnima rows in the prev and
     * next months as well.
     *
     * The festival LIST under the grid still renders only the
     * visible month's events (`visibleMonthPanchanga`); this wider
     * flow is just for the per-cell decorations that need to
     * survive the leading/trailing fill.
     */
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

    /**
     * Search results for the festival search field. Empty when the
     * query is blank — the repo short-circuits there and we don't
     * issue a DB query.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<PanchangaEntity>> =
        _searchQuery
            .flatMapLatest { q -> repository.searchSpecialDay(q) }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Asset-backed bootstrap may not have finished by the time the
        // user lands here on a very fresh install — kick it again so
        // the empty-state placeholder is the exception, not the norm.
        viewModelScope.launch { repository.ensureLoaded() }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        // Keep the grid in sync: tapping a spillover date in the
        // current month bumps the visible month over to match. The
        // CalendarPanel itself does this on cell taps too, but the
        // VM-level dispatch covers the "open by deep link to a
        // date in another month" case.
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

    private fun monthPrefix(month: YearMonth): String =
        "%04d-%02d-".format(month.year, month.monthValue)

    companion object {
        /** Asia/Kolkata — the panchanga is published in IST and tithi
         *  rollover times are computed against IST midnight. Using the
         *  device's local timezone here would mis-attribute today on
         *  travelers' phones. */
        private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                CalendarViewModel(repository = app.panchangaRepository)
            }
        }
    }
}
