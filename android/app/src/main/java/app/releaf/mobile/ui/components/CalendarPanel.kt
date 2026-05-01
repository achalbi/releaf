/*
 * CalendarPanel.kt
 *
 * Compact month-view calendar used by the capture modal. Renders the
 * current month with day-of-week headers, today emphasised, and Indian
 * government holidays marked with a small coral dot. A list under the
 * grid shows the holidays falling in the visible month.
 *
 * The component is self-contained — it owns the visible month state and
 * exposes prev / next chevrons. Holiday data is hard-coded for 2026 and
 * 2027 in `IndianHolidays.entries`; extend that list when the year
 * rolls over.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

// ── Holiday data ───────────────────────────────────────────────────

/**
 * Hard-coded list of Indian government / national holidays. Covers
 * 2026 and 2027 — the dates are the published GoI list (some lunar
 * holidays shift; refresh annually). Extend when 2028 arrives.
 */
object IndianHolidays {
    data class Holiday(val date: LocalDate, val name: String)

    val entries: List<Holiday> = listOf(
        // 2026
        Holiday(LocalDate.of(2026, 1, 1),  "New Year's Day"),
        Holiday(LocalDate.of(2026, 1, 14), "Makar Sankranti / Pongal"),
        Holiday(LocalDate.of(2026, 1, 26), "Republic Day"),
        Holiday(LocalDate.of(2026, 3, 3),  "Holi"),
        Holiday(LocalDate.of(2026, 3, 21), "Ram Navami"),
        Holiday(LocalDate.of(2026, 3, 31), "Eid-ul-Fitr"),
        Holiday(LocalDate.of(2026, 4, 3),  "Good Friday"),
        Holiday(LocalDate.of(2026, 4, 14), "Dr Ambedkar Jayanti"),
        Holiday(LocalDate.of(2026, 5, 1),  "Labour Day"),
        Holiday(LocalDate.of(2026, 6, 7),  "Eid al-Adha (Bakrid)"),
        Holiday(LocalDate.of(2026, 7, 5),  "Muharram"),
        Holiday(LocalDate.of(2026, 8, 15), "Independence Day"),
        Holiday(LocalDate.of(2026, 8, 27), "Janmashtami"),
        Holiday(LocalDate.of(2026, 9, 14), "Eid-e-Milad"),
        Holiday(LocalDate.of(2026, 10, 2), "Gandhi Jayanti"),
        Holiday(LocalDate.of(2026, 10, 20), "Dussehra"),
        Holiday(LocalDate.of(2026, 11, 8),  "Diwali"),
        Holiday(LocalDate.of(2026, 11, 24), "Guru Nanak Jayanti"),
        Holiday(LocalDate.of(2026, 12, 25), "Christmas Day"),
        // 2027 (selected highlights — extend as needed)
        Holiday(LocalDate.of(2027, 1, 1),   "New Year's Day"),
        Holiday(LocalDate.of(2027, 1, 26),  "Republic Day"),
        Holiday(LocalDate.of(2027, 3, 22),  "Holi"),
        Holiday(LocalDate.of(2027, 8, 15),  "Independence Day"),
        Holiday(LocalDate.of(2027, 10, 2),  "Gandhi Jayanti"),
        Holiday(LocalDate.of(2027, 10, 28), "Diwali"),
        Holiday(LocalDate.of(2027, 12, 25), "Christmas Day"),
    )

    fun forMonth(month: YearMonth): List<Holiday> =
        entries.filter { YearMonth.from(it.date) == month }

    fun forDate(date: LocalDate): Holiday? =
        entries.firstOrNull { it.date == date }
}

// ── VerticalPager mapping ──────────────────────────────────────────

/**
 * Pager epoch — the YearMonth at page index 0. Every other page
 * is the offset from this anchor in months. The value is otherwise
 * arbitrary; the pager exposes ~178 million pages on either side
 * (`Int.MAX_VALUE` total) which more than covers any realistic
 * navigation range.
 */
private val PAGER_EPOCH: YearMonth = YearMonth.of(1900, 1)

private fun monthToPage(month: YearMonth): Int =
    java.time.temporal.ChronoUnit.MONTHS.between(PAGER_EPOCH, month).toInt()

private fun pageToMonth(page: Int): YearMonth =
    PAGER_EPOCH.plusMonths(page.toLong())

/**
 * Number of rows the Mon-first calendar grid needs to render
 * [month] in full. Tracks the ISO weekday of the 1st + the
 * month's length (28..31 days). Returns 4–6.
 */
private fun rowsForMonth(month: YearMonth): Int {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1   // MON=0..SUN=6
    val totalCells = leadingBlanks + month.lengthOfMonth()
    return (totalCells + 6) / 7
}

/** Each grid row is one DayCell tall. Kept as a separate
 *  constant so the pager height calc and the cells themselves
 *  stay in lockstep. */
private val GridRowHeight = 36.dp

// ── CalendarPanel ──────────────────────────────────────────────────

/**
 * Month-view calendar. Defaults to self-contained — owns its visible
 * month and selected-date state internally — so simple drop-ins
 * (e.g. QuickCaptureSheet) keep working with no params.
 *
 * Hosts that need to drive the calendar from outside (the full
 * [CalendarScreen], for instance, where the panchanga details below
 * the grid track the user's selection) can pass any of the four
 * `visibleMonth` / `onVisibleMonthChange` / `selectedDate` /
 * `onSelectedDateChange` overrides; non-null values take precedence
 * over the internal state for that field.
 *
 * `eventDates` paints a small dot under each matching cell — used to
 * surface dates with festivals / observances in the dataset without
 * crowding the cell with text.
 */
@Composable
fun CalendarPanel(
    modifier: Modifier = Modifier,
    initialMonth: YearMonth = YearMonth.now(),
    visibleMonth: YearMonth? = null,
    onVisibleMonthChange: ((YearMonth) -> Unit)? = null,
    selectedDate: LocalDate? = null,
    onSelectedDateChange: ((LocalDate) -> Unit)? = null,
    eventDates: Set<LocalDate> = emptySet(),
    /** Dates rendered with a small dark moon glyph in the top-right
     *  corner of the cell — Amavasya from the panchanga. */
    newMoonDates: Set<LocalDate> = emptySet(),
    /** Dates rendered with a light cream moon glyph in the top-right
     *  corner of the cell — Purnima from the panchanga. */
    fullMoonDates: Set<LocalDate> = emptySet(),
) {
    // Internal fall-back state. Used for whichever override the host
    // didn't supply.
    var internalVisibleMonth by remember { mutableStateOf(initialMonth) }
    var internalSelectedDate by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }

    val effectiveVisibleMonth = visibleMonth ?: internalVisibleMonth
    val effectiveSelectedDate = selectedDate ?: internalSelectedDate
    val updateVisibleMonth: (YearMonth) -> Unit = { next ->
        if (onVisibleMonthChange != null) onVisibleMonthChange(next)
        else internalVisibleMonth = next
    }
    val updateSelectedDate: (LocalDate) -> Unit = { next ->
        if (onSelectedDateChange != null) onSelectedDateChange(next)
        else internalSelectedDate = next
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            // Slightly more breathing room at the top so the month
            // header doesn't kiss the card edge / the slide-in animation.
            .padding(
                start = AppSpacing.s3,
                end = AppSpacing.s3,
                top = AppSpacing.s4,
                bottom = AppSpacing.s3,
            ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // Month header — title on the left, ▲/▼ chevrons stacked
        // on the right. Up = previous month (page up the timeline);
        // Down = next month. Direction matches the swipe gesture in
        // the grid below: drag DOWN to bring previous from above,
        // drag UP to bring next from below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${effectiveVisibleMonth.month.getDisplayName(JTextStyle.FULL, Locale.getDefault())} ${effectiveVisibleMonth.year}",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { updateVisibleMonth(effectiveVisibleMonth.minusMonths(1)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Previous month",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { updateVisibleMonth(effectiveVisibleMonth.plusMonths(1)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Next month",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Day-of-week header — Monday first, Sunday last.
        Row(modifier = Modifier.fillMaxWidth()) {
            DayOfWeek.values().forEach { dow ->
                Text(
                    text = dow.getDisplayName(JTextStyle.NARROW, Locale.getDefault()),
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Date grid (Monday-first). Row count tracks the visible
        // month's length and starting weekday — short months render in
        // 4–5 rows, long ones in 6. Leading days from the previous
        // month and trailing days from the next month fill the
        // edge cells (instead of blanks); tapping a spillover cell
        // jumps the calendar to that month and selects the date.
        //
        // The grid is hosted by a `VerticalPager`: each page is one
        // month, mapped 1:1 by month-offset from a fixed epoch. The
        // pager handles realtime drag-tracking — as the user drags
        // up or down, the next/previous month's grid actually
        // appears, following the finger, with snap-and-fling on
        // release. Two `LaunchedEffect`s keep the pager and the
        // hoisted `effectiveVisibleMonth` state in sync: chevron
        // taps animate the pager to the target page, and pager
        // settles flow back into `updateVisibleMonth`.
        //
        // Pager direction matches the swipe convention: increasing
        // page index = future month, so dragging UP (which advances
        // the pager) brings tomorrow's month from below, and
        // dragging DOWN steps back. The chevrons mirror this:
        // ▲ = previous (page back), ▼ = next (page forward).
        val today = LocalDate.now()
        val pagerState = rememberPagerState(
            initialPage = monthToPage(effectiveVisibleMonth),
            pageCount = { Int.MAX_VALUE },
        )
        // External state → pager. Fires on chevron taps / Today
        // CTAs / outside-month tap-jumps. The current-page guard
        // avoids a redundant scroll when the pager is already on
        // the target (e.g. when the pager itself just settled and
        // pushed the new month back into the host). The
        // `isScrollInProgress` guard makes the effect a no-op
        // while a swipe is in flight — without it, a host-side
        // visibleMonth update mid-drag would call
        // `animateScrollToPage`, which would interrupt the user's
        // drag and visibly stall the pager.
        LaunchedEffect(effectiveVisibleMonth) {
            val target = monthToPage(effectiveVisibleMonth)
            if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(target)
            }
        }
        // Pager → external state. `settledPage` only updates after
        // a drag/fling finishes, so the host VM doesn't ping-pong
        // every frame during a drag. `distinctUntilChanged` filters
        // identical re-emits if the same page settles twice; the
        // equality guard against `effectiveVisibleMonth` then
        // closes the loop with the host.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { settled ->
                    val newMonth = pageToMonth(settled)
                    if (newMonth != effectiveVisibleMonth) {
                        updateVisibleMonth(newMonth)
                    }
                }
        }

        // Pager height = max rows across the prev/visible/next
        // window. Two reasons:
        //   1. Static views avoid showing a trailing empty 6th row
        //      that month-of-current isn't using.
        //   2. During a swipe, the page being dragged into view
        //      always fits in the pager's bounds — without this,
        //      a 4-row February → 5-row March transition would
        //      clip March's last row mid-drag and the pager
        //      could miss the snap threshold (the "stuck"
        //      symptom). Sizing to the local maximum guarantees
        //      both the source and target render at full height.
        val pagerHeight = remember(effectiveVisibleMonth) {
            maxOf(
                rowsForMonth(effectiveVisibleMonth.minusMonths(1)),
                rowsForMonth(effectiveVisibleMonth),
                rowsForMonth(effectiveVisibleMonth.plusMonths(1)),
            )
        }.let { rows -> GridRowHeight * rows }
        // Lower the partial-swipe commit threshold so a deliberate
        // ~30% drag advances the pager — the default 50% felt
        // sluggish on shorter (4-row) months where each page is
        // only ~144 dp tall and a 50% drag means moving more than
        // 70 dp of finger travel.
        val flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapPositionalThreshold = 0.3f,
        )
        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(pagerHeight),
            pageSize = PageSize.Fill,
            flingBehavior = flingBehavior,
        ) { page ->
            // All grid metrics derive from the page's month so the
            // page being dragged into view renders with its own
            // month's data, not the hoisted `effectiveVisibleMonth`
            // (which lags until settle).
            val month = pageToMonth(page)
            val firstOfMonth = month.atDay(1)
            val daysInMonth  = month.lengthOfMonth()
            val leadingBlanks = firstOfMonth.dayOfWeek.value - 1
            val totalCells = leadingBlanks + daysInMonth
            val rows = (totalCells + 6) / 7
            // Holidays for the previous + visible + next months
            // around `month` so spillover cells can still surface a
            // holiday signal in leaf-yellow.
            val holidayDates = (
                IndianHolidays.forMonth(month.minusMonths(1)) +
                    IndianHolidays.forMonth(month) +
                    IndianHolidays.forMonth(month.plusMonths(1))
                ).map { it.date }.toSet()

            Column(modifier = Modifier.fillMaxWidth()) {
                repeat(rows) { rowIndex ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        repeat(7) { colIndex ->
                            val cellIndex = rowIndex * 7 + colIndex
                            val dayNum = cellIndex - leadingBlanks + 1
                            val date: LocalDate = when {
                                dayNum < 1 -> {
                                    // Leading fill: tail end of the previous month.
                                    // `firstOfMonth.minusDays(...)` does the right
                                    // thing across year boundaries.
                                    firstOfMonth.minusDays((1 - dayNum).toLong())
                                }
                                dayNum > daysInMonth -> {
                                    // Trailing fill: start of the next month.
                                    month.plusMonths(1).atDay(dayNum - daysInMonth)
                                }
                                else -> month.atDay(dayNum)
                            }
                            val isOutsideMonth = YearMonth.from(date) != month
                            val isToday = date == today
                            val isSelected = date == effectiveSelectedDate
                            val isHoliday = date in holidayDates
                            val isWeekend =
                                date.dayOfWeek == DayOfWeek.SATURDAY ||
                                    date.dayOfWeek == DayOfWeek.SUNDAY
                            val hasEvent = date in eventDates
                            val isNewMoon = date in newMoonDates
                            val isFullMoon = date in fullMoonDates
                            DayCell(
                                day = date.dayOfMonth,
                                isToday = isToday,
                                isSelected = isSelected,
                                isHoliday = isHoliday,
                                isWeekend = isWeekend,
                                isOutsideMonth = isOutsideMonth,
                                hasEvent = hasEvent,
                                isNewMoon = isNewMoon,
                                isFullMoon = isFullMoon,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    updateSelectedDate(date)
                                    if (isOutsideMonth) {
                                        updateVisibleMonth(YearMonth.from(date))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Selected-date holiday or list of holidays in the month.
        // The "Holidays this month" / "No public holidays this month"
        // header line shares its row with the moon-phase legend (when
        // the host feeds moon-phase dates) so the key for the
        // top-right discs lives at the same vertical level as the
        // section header instead of stealing its own band.
        val selectedHoliday = effectiveSelectedDate?.let { IndianHolidays.forDate(it) }
        val monthHolidays = IndianHolidays.forMonth(effectiveVisibleMonth)
        val showMoonLegend = newMoonDates.isNotEmpty() || fullMoonDates.isNotEmpty()
        if (selectedHoliday != null) {
            HolidayRow(date = selectedHoliday.date, name = selectedHoliday.name, isFocused = true)
            if (showMoonLegend) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MoonPhaseLegend()
                }
            }
        } else if (monthHolidays.isNotEmpty()) {
            Spacer(Modifier.height(AppSpacing.s1))
            // Two-column layout: holiday list on the left (header
            // + rows), moon-phase legend on the right. The list
            // and the legend share their top edge so the legend's
            // two swatches sit next to the first two list rows
            // (header + first holiday), instead of overflowing
            // into a gap below the header.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    // Tight 2dp gap between header and first holiday
                    // so the parent Column's `spacedBy(s2)` doesn't
                    // open up a stripe between them — the legend's
                    // internal spacing is also 2dp, so the two columns
                    // visually march in step.
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Holidays this month",
                        style = AppTypography.Meta,
                        color = AppColors.TextTertiary,
                    )
                    monthHolidays.forEach { h ->
                        HolidayRow(date = h.date, name = h.name, isFocused = false)
                    }
                }
                if (showMoonLegend) {
                    MoonPhaseLegend()
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                Text(
                    text = "No public holidays this month",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (showMoonLegend) {
                    MoonPhaseLegend()
                }
            }
        }
    }
}

// ── Day cell ────────────────────────────────────────────────────────

@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    isHoliday: Boolean,
    isWeekend: Boolean,
    isOutsideMonth: Boolean,
    hasEvent: Boolean,
    isNewMoon: Boolean,
    isFullMoon: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Two festival signals at play:
    //   - `isHoliday`  → curated Indian government / national holiday
    //                    set (Republic Day, Independence Day, Diwali …).
    //                    Carries a coral RING so the date reads as a
    //                    public-calendar event at a glance.
    //   - `hasEvent`   → panchanga `special_day` row from the
    //                    Vontikoppal dataset. Carries a coral DOT
    //                    under the number; no ring, so months with
    //                    many panchanga observances don't drown in
    //                    circles.
    // Both also push the foreground text to coral via `isFestiveDay`
    // so the cell colour stays in sync with the bulleted list below
    // the grid regardless of which signal triggered.
    val isFestiveDay = isHoliday || hasEvent

    // Outlined ring around the date. Selected wins (thicker deep-
    // coral); then today (green); then `isHoliday` for the curated
    // Indian-holiday set. Panchanga events fall through to no ring —
    // they signal via the dot + text colour instead. Outside-month
    // cells never carry a ring; they're decorative context.
    val ringColor = when {
        isOutsideMonth -> androidx.compose.ui.graphics.Color.Transparent
        isSelected     -> AppAccent.deep
        isToday        -> AppColors.ThemeGreenPrimary
        isHoliday      -> AppColors.ThemeCoralPrimary
        else           -> androidx.compose.ui.graphics.Color.Transparent
    }
    val ringWidth = if (isSelected && !isOutsideMonth) 2.5.dp else 2.dp
    // Foreground priority for in-month cells: today (green) > festive
    // (coral) > selected (deep coral) > weekend (danger red) > weekday
    // (textPrimary). Outside-month cells render muted by default;
    // outside-month holidays / panchanga festivals specifically render
    // in leaf-yellow so a user paging through months can still spot
    // upcoming/past festive days in the spillover rows without those
    // days competing with the focused month's coral signal.
    val fg = when {
        isOutsideMonth && isFestiveDay -> AppColors.ThemeYellowPrimary
        isOutsideMonth                 -> AppColors.TextTertiary
        isToday                        -> AppColors.ThemeGreenPrimary
        isFestiveDay                   -> AppColors.ThemeCoralPrimary
        isSelected                     -> AppAccent.deep
        isWeekend                      -> AppColors.Danger
        else                           -> AppColors.TextPrimary
    }
    val emphasised = !isOutsideMonth && (isToday || isSelected || isFestiveDay)
    // Event indicator dot (panchanga special_day) — sits a hair below
    // the number, picks up the cell's foreground colour so the dot
    // reads as part of that day's emphasis (in-month coral on a
    // festive day, muted grey on a spillover day, etc.).
    val dotColor = when {
        !hasEvent      -> androidx.compose.ui.graphics.Color.Transparent
        isOutsideMonth -> AppColors.TextTertiary
        isToday        -> AppColors.ThemeGreenPrimary
        else           -> AppColors.ThemeCoralPrimary
    }
    Box(
        modifier = modifier
            .height(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(ringWidth, ringColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.toString(),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
                ),
                color = fg,
            )
        }
        if (hasEvent) {
            Box(
                modifier = Modifier
                    .padding(top = 26.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        // Moon-phase glyph — small filled disc in the top-right
        // corner. Full moon = white disc with a dark hairline
        // border (so the bright fill stands out against the cream
        // canvas without floating away); new moon = dark
        // silhouette with a hairline border so it stays visible
        // against the cell background. Rendered identically on
        // in-month and outside-month cells so the lunar rhythm
        // reads continuously across month boundaries — the muted
        // text colour on the date number itself already signals
        // "this is spillover" without needing to dim the moon.
        if (isNewMoon || isFullMoon) {
            val moonFill =
                if (isFullMoon) androidx.compose.ui.graphics.Color.White
                else AppColors.TextPrimary
            val moonBorder =
                if (isFullMoon) AppColors.TextPrimary
                else AppColors.TextSecondary
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(moonFill)
                    .border(0.7.dp, moonBorder, CircleShape),
            )
        }
    }
}

// ── Moon-phase legend ───────────────────────────────────────────────

/**
 * Two-item legend explaining the small discs that render in the
 * top-right corner of date cells when the date is a Purnima (full
 * moon) or Amavasya (new moon). Stacked vertically with the
 * swatches in a single left-aligned column so the two discs sit
 * directly above one another (●/◐), with their labels reading off
 * to the right. The wrapping holiday-section Row handles the
 * right-anchoring within the calendar card.
 */
@Composable
private fun MoonPhaseLegend() {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MoonPhaseLegendRow(
            swatch = {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .border(0.7.dp, AppColors.TextPrimary, CircleShape),
                )
            },
            label = "Full moon",
        )
        MoonPhaseLegendRow(
            swatch = {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AppColors.TextPrimary)
                        .border(0.5.dp, AppColors.TextSecondary, CircleShape),
                )
            },
            label = "New moon",
        )
    }
}

@Composable
private fun MoonPhaseLegendRow(
    swatch: @Composable () -> Unit,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )
    }
}

// ── Holiday list row ────────────────────────────────────────────────

@Composable
private fun HolidayRow(date: LocalDate, name: String, isFocused: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(AppColors.ThemeCoralPrimary),
        )
        Text(
            text = "${date.dayOfMonth}",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = AppColors.TextPrimary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = name,
            style = if (isFocused) AppTypography.Body else AppTypography.Meta,
            color = if (isFocused) AppColors.TextPrimary else AppColors.TextSecondary,
        )
    }
}
