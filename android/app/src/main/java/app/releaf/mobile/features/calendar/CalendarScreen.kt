/*
 * CalendarScreen.kt
 *
 * Full-screen calendar surface, reachable from the home drawer
 * ("Calendar" leaf) and from a small footer link in QuickCaptureSheet
 * ("Open full calendar"). Three vertical zones:
 *
 *   1. Top bar — back arrow, "Calendar" title, "Today" pill.
 *   2. Festival search — collapsible BasicTextField; results render
 *      below the grid when the query is non-empty.
 *   3. Calendar grid (CalendarPanel, hosted with VM-driven state) +
 *      panchanga detail card for the selected date + monthly festival
 *      list + source/about note.
 *
 * The grid carries small dots under any date that has a non-empty
 * `special_day` in the bundled Vontikoppal dataset. The detail card
 * shows masa / paksha / thithi / special_day for the selected date,
 * or the documented placeholder when the date is outside the dataset.
 */

package app.releaf.mobile.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.panchanga.PanchangaEntity
import app.releaf.mobile.ui.components.CalendarPanel
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory),
) {
    val selectedDate by viewModel.selectedDate.collectAsState()
    val visibleMonth by viewModel.visibleMonth.collectAsState()
    val selectedDayPanchanga by viewModel.selectedDayPanchanga.collectAsState()
    val visibleMonthPanchanga by viewModel.visibleMonthPanchanga.collectAsState()
    // Wider panchanga slice (prev + visible + next month) that drives
    // the moon-phase glyphs in the grid — the leading / trailing
    // spillover cells need their full/new moon discs even though
    // those dates fall outside the focused month.
    val adjacentMonthsPanchanga by viewModel.adjacentMonthsPanchanga.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    // Set of dates in the visible month that carry a festival /
    // observance — used for the small dot indicator on each cell.
    // Computed in the host so CalendarPanel stays datatype-agnostic.
    val eventDates = remember(visibleMonthPanchanga) {
        visibleMonthPanchanga
            .filter { it.specialDay.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()
    }
    // Moon-phase date sets: Amavasya (new moon) and Purnima (full
    // moon) rows from the panchanga. Sourced from the wider
    // `adjacentMonthsPanchanga` slice so spillover cells (prev /
    // next month leading & trailing days in the grid) also surface
    // their moon glyphs — DayCell already mutes the glyph colour
    // for outside-month cells, so showing the disc there reads as
    // "next/previous moon is coming up" without competing with the
    // focused month. Some Gregorian dates carry two tithi rows
    // when the lunar day rolls over, so a date with either Purnima
    // or Amavasya among its rows is treated as a moon-phase day.
    // Strict thithi-name match (`equals`, not `contains`) so
    // dual-tithi rows like "Trayodashi/Chaturdashi" don't trigger
    // a false positive.
    val newMoonDates = remember(adjacentMonthsPanchanga) {
        adjacentMonthsPanchanga
            .filter { it.thithi.equals("Amavasya", ignoreCase = true) }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()
    }
    val fullMoonDates = remember(adjacentMonthsPanchanga) {
        adjacentMonthsPanchanga
            .filter { it.thithi.equals("Purnima", ignoreCase = true) }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        // ── Top bar ────────────────────────────────────────────────
        // Tap the LeafEyebrow to walk back to the previous surface —
        // mirrors the breadcrumb pattern Releaf uses on every other
        // detail screen (NotebookLocalDetailScreen, ChapterLocalDetailScreen)
        // instead of a standalone back-arrow icon.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppSpacing.s4,
                    end = AppSpacing.s4,
                    top = AppSpacing.s4,
                    bottom = AppSpacing.s2,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LeafEyebrow(
                    label = "releaf · calendar",
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Text(
                    text = "Calendar",
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize = 28.sp,
                    ),
                    color = AppColors.TextPrimary,
                )
            }
            // Today pill — quick-snap to today's date in IST.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppAccent.soft)
                    .border(1.dp, AppAccent.primary, RoundedCornerShape(AppRadius.pill))
                    .clickable { viewModel.goToToday() }
                    .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
            ) {
                Text(
                    text = "Today",
                    style = AppTypography.Button.copy(fontSize = 12.sp),
                    color = AppAccent.deep,
                )
            }
        }

        // Body uses a LazyColumn so the CalendarPanel's nested
        // VerticalPager owns its vertical drag gestures cleanly —
        // a parent `Modifier.verticalScroll` would compete with
        // the pager and make month-swipes feel sticky.
        // LazyColumn cooperates with nested vertical pagers via
        // NestedScrollConnection: the pager consumes vertical
        // drags within its own bounds, and the LazyColumn only
        // takes over for drags outside the pager.
        val monthFestivals = visibleMonthPanchanga.filter { it.specialDay.isNotBlank() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                bottom = AppSpacing.s10,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            // ── Festival search ───────────────────────────────────
            item("search") {
                FestivalSearchField(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClear = viewModel::clearSearch,
                )
            }

            // ── Search results — typeahead-style dropdown directly
            //    under the textbox so users see matches without
            //    scrolling past the calendar. Only renders when the
            //    query is non-empty; clearing the query collapses
            //    the dropdown and the calendar pops back up to the
            //    natural position below the search field.
            if (searchQuery.isNotBlank()) {
                item("search-results") {
                    SearchResultsSection(
                        query = searchQuery,
                        results = searchResults,
                        onSelectDate = viewModel::selectDate,
                    )
                }
            }

            // ── Calendar grid ─────────────────────────────────────
            item("calendar") {
                CalendarPanel(
                    visibleMonth = visibleMonth,
                    onVisibleMonthChange = viewModel::setVisibleMonth,
                    selectedDate = selectedDate,
                    onSelectedDateChange = viewModel::selectDate,
                    eventDates = eventDates,
                    newMoonDates = newMoonDates,
                    fullMoonDates = fullMoonDates,
                )
            }

            // ── Selected-date panchanga detail ────────────────────
            item("selected-day") {
                SelectedDayCard(
                    date = selectedDate,
                    rows = selectedDayPanchanga,
                )
            }

            // ── Festivals in the visible month ────────────────────
            if (monthFestivals.isNotEmpty()) {
                item("month-festivals") {
                    MonthFestivalList(rows = monthFestivals)
                }
            }

            // ── About / source note ───────────────────────────────
            item("about") {
                AboutPanchanga()
            }
        }
    }
}

// ── Festival search ─────────────────────────────────────────────────

@Composable
private fun FestivalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search festivals",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppAccent.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ── Selected-day detail card ────────────────────────────────────────

@Composable
private fun SelectedDayCard(date: LocalDate, rows: List<PanchangaEntity>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // Top row: date eyebrow + serif heading on the left, the
        // italic "Panchanga" wordmark anchored to the top-right
        // corner. The wordmark identifies the data source for this
        // card (and the calendar above) without competing with the
        // primary date readout below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
            ) {
                Text(
                    text = date.format(LongDateFormatter).uppercase(),
                    style = AppTypography.Eyebrow,
                    color = AppAccent.deep,
                )
                Text(
                    text = date.format(SerifDateFormatter),
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = AppColors.TextPrimary,
                )
            }
            Text(
                text = "Panchanga",
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 16.sp,
                ),
                color = AppAccent.primary,
            )
        }
        Spacer(Modifier.height(2.dp))

        if (rows.isEmpty()) {
            Text(
                text = "Panchanga data not available for this date.",
                style = AppTypography.Body,
                color = AppColors.TextSecondary,
            )
            Text(
                text = "The bundled Vontikoppal dataset covers " +
                    "2026-03-19 to 2027-04-06.",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
        } else {
            rows.forEach { row -> PanchangaRow(row, date = date) }
        }
    }
}

@Composable
private fun PanchangaRow(row: PanchangaEntity, date: LocalDate) {
    // Rahu Kala — sunrise-anchored at Mysuru via commons-suncalc.
    // The window depends on the actual length of daylight (sunrise
    // → sunset divided into eighths, then the slot keyed by
    // weekday), so two tithi rows on the same Gregorian date land
    // on the same Rahu Kala — same date, same daylight, same slot.
    val rahuKala = remember(date) { rahuKalaFor(date) }
    // Sunrise + sunset for the same date / location, sourced from
    // the same suncalc Mysuru anchor as Rahu Kala. Cached per date
    // so the suncalc call only runs once even on dates with two
    // tithi rows.
    val sunTimes = remember(date) { sunriseSunsetFor(date) }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            DetailField(label = "Masa", value = row.masa, modifier = Modifier.weight(1f))
            DetailField(label = "Paksha", value = row.paksha, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            DetailField(
                label = "Thithi",
                value = "${row.thithi} (${row.thithiNum})",
                modifier = Modifier.weight(1f),
            )
            // Rahu Kala — daily inauspicious window, computed from
            // weekday. Coloured coral so it stands out as a
            // "watchout" cue alongside the neutral panchanga
            // readouts.
            DetailField(
                label = "Rahu Kala",
                value = rahuKala,
                valueColor = AppColors.ThemeCoralPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            DetailField(
                label = "Sunrise",
                value = sunTimes.first,
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = "Sunset",
                value = sunTimes.second,
                modifier = Modifier.weight(1f),
            )
        }
        if (row.specialDay.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AppColors.ThemeCoralPrimary),
                )
                Text(
                    text = row.specialDay,
                    style = AppTypography.Body,
                    color = AppColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = AppColors.TextPrimary,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = AppTypography.Eyebrow.copy(fontSize = 10.sp),
            color = AppColors.TextTertiary,
        )
        Text(
            text = value,
            style = AppTypography.Body,
            color = valueColor,
        )
    }
}


// ── Month festival list ─────────────────────────────────────────────

@Composable
private fun MonthFestivalList(rows: List<PanchangaEntity>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = "Festivals & observances",
            style = AppTypography.SectionTitle,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(2.dp))
        rows.forEach { row -> FestivalRow(row) }
    }
}

@Composable
private fun FestivalRow(row: PanchangaEntity) {
    val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(AppColors.ThemeCoralPrimary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.specialDay,
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
            Text(
                text = buildString {
                    if (date != null) {
                        append(date.format(ShortDateFormatter))
                        append(" · ")
                    }
                    append("${row.masa} ${row.paksha} ${row.thithi}")
                },
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Search results ──────────────────────────────────────────────────

@Composable
private fun SearchResultsSection(
    query: String,
    results: List<PanchangaEntity>,
    onSelectDate: (LocalDate) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = "Results for “$query”",
            style = AppTypography.SectionTitle,
            color = AppColors.TextPrimary,
        )
        if (results.isEmpty()) {
            Text(
                text = "No festivals match.",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
        } else {
            // The list lives inside the screen-level scroll, so it
            // can't be a LazyColumn (nesting a vertically-scrollable
            // inside another would crash on layout). Cap the visible
            // count so a wildly-broad query doesn't render hundreds
            // of rows; the user can refine the query if they need
            // more.
            val capped = results.take(SEARCH_RESULT_CAP)
            capped.forEach { row ->
                SearchResultRow(row = row, onClick = {
                    runCatching { LocalDate.parse(row.date) }
                        .getOrNull()
                        ?.let(onSelectDate)
                })
            }
            if (results.size > SEARCH_RESULT_CAP) {
                Text(
                    text = "${results.size - SEARCH_RESULT_CAP} more matches — refine the query to narrow.",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: PanchangaEntity, onClick: () -> Unit) {
    val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(AppAccent.primary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.specialDay,
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
            Text(
                text = buildString {
                    if (date != null) {
                        append(date.format(ShortDateFormatter))
                        append(" · ")
                    }
                    append("${row.masa} ${row.paksha} ${row.thithi}")
                },
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── About / source note ─────────────────────────────────────────────

@Composable
private fun AboutPanchanga() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.s2, bottom = AppSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(
            text = "ABOUT THIS DATA",
            style = AppTypography.Eyebrow,
            color = AppColors.TextTertiary,
        )
        Text(
            text = "This calendar data is derived from the printed " +
                "Ontikoppal Panchanga published by Ontikoppal " +
                "Panchanga Mandira, Mysore. The dataset is " +
                "OCR-derived and unofficial; verify important " +
                "ritual dates with the original Panchanga.",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

private const val SEARCH_RESULT_CAP = 20

// "MAR 19, 2026"-style eyebrow above the long format below.
private val LongDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

// "Thursday, 19 March" — main date readout in the detail card.
private val SerifDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

// "19 Mar" — compact date used inside list rows.
private val ShortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
