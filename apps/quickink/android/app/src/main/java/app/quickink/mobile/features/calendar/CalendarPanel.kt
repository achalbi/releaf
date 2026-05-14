/*
 * CalendarPanel.kt
 *
 * Month-view calendar grid for QuickInk's Calendar screen. Renders
 * the visible month with day-of-week headers (Mon-first), today
 * emphasised, Indian government holidays + panchanga events marked
 * with rings + dots, full/new moon glyphs in the top-right of each
 * cell, plus a QuickInk-specific accent dot under any day that has
 * at least one scan / import.
 *
 * Port of Releaf Android's `CalendarPanel.kt` — same structure, same
 * vertical-pager behaviour, retuned through `LocalQuickInkColors` /
 * `LocalQuickInkTypography` so the panel inherits the user's primary-
 * color pick. `IndianHolidays` is bundled inline (same hard-coded
 * 2026 / 2027 list Releaf carries).
 */

package app.quickink.mobile.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
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
 * Pager epoch — the YearMonth at page index 0. Every other page is
 * the offset from this anchor in months. Arbitrary value; the pager
 * exposes ~178 million pages on either side which covers any
 * realistic navigation range.
 */
private val PAGER_EPOCH: YearMonth = YearMonth.of(1900, 1)

private fun monthToPage(month: YearMonth): Int =
    java.time.temporal.ChronoUnit.MONTHS.between(PAGER_EPOCH, month).toInt()

private fun pageToMonth(page: Int): YearMonth =
    PAGER_EPOCH.plusMonths(page.toLong())

private fun rowsForMonth(month: YearMonth): Int {
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
    val totalCells = leadingBlanks + month.lengthOfMonth()
    return (totalCells + 6) / 7
}

private val GridRowHeight = 36.dp

// ── CalendarPanel ──────────────────────────────────────────────────

/**
 * Month-view calendar. Hosts that drive the calendar from outside
 * (the full [CalendarScreen]) pass `visibleMonth` /
 * `onVisibleMonthChange` / `selectedDate` / `onSelectedDateChange`
 * so the panchanga detail card below the grid tracks the user's
 * selection. Standalone drop-ins can omit them — the panel owns
 * fallback state for each field.
 *
 * `eventDates` paints a small dot under each matching cell — used
 * to surface dates with festivals / observances in the panchanga
 * dataset without crowding the cell with text.
 *
 * `captureDates` (QuickInk-specific) paints a separate accent-deep
 * dot under any day that has at least one capture, so the user can
 * spot scan-bearing days at a glance.
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
     *  corner — Amavasya from the panchanga. */
    newMoonDates: Set<LocalDate> = emptySet(),
    /** Dates rendered with a light cream moon glyph in the top-right
     *  corner — Purnima from the panchanga. */
    fullMoonDates: Set<LocalDate> = emptySet(),
    /** Dates carrying at least one QuickInk capture (scan / import).
     *  Paints a coral dot under the day number so the user can spot
     *  scan-bearing days at a glance — merges visually with the
     *  panchanga event dot when both fire on the same day. */
    captureDates: Set<LocalDate> = emptySet(),
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

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
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(
                start = QuickInkSpacing.s3,
                end = QuickInkSpacing.s3,
                top = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s3,
            ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        // Month header — title on the left, ▲/▼ chevrons stacked on
        // the right. Up = previous month; Down = next. Matches the
        // vertical swipe convention.
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
                color = colors.ink,
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
                        tint = colors.ink,
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
                        tint = colors.ink,
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
                    style = type.meta,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Date grid (Monday-first) inside a VerticalPager — each page
        // is one month, mapped 1:1 by month-offset from a fixed
        // epoch. The pager handles realtime drag-tracking with snap-
        // and-fling on release.
        val today = LocalDate.now()
        val pagerState = rememberPagerState(
            initialPage = monthToPage(effectiveVisibleMonth),
            pageCount = { Int.MAX_VALUE },
        )
        LaunchedEffect(effectiveVisibleMonth) {
            val target = monthToPage(effectiveVisibleMonth)
            if (pagerState.currentPage != target && !pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(target)
            }
        }
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
        // window. Avoids clipping during the swipe transition.
        val pagerHeight = remember(effectiveVisibleMonth) {
            maxOf(
                rowsForMonth(effectiveVisibleMonth.minusMonths(1)),
                rowsForMonth(effectiveVisibleMonth),
                rowsForMonth(effectiveVisibleMonth.plusMonths(1)),
            )
        }.let { rows -> GridRowHeight * rows }
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
            val month = pageToMonth(page)
            val firstOfMonth = month.atDay(1)
            val daysInMonth  = month.lengthOfMonth()
            val leadingBlanks = firstOfMonth.dayOfWeek.value - 1
            val totalCells = leadingBlanks + daysInMonth
            val rows = (totalCells + 6) / 7
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
                                dayNum < 1 -> firstOfMonth.minusDays((1 - dayNum).toLong())
                                dayNum > daysInMonth -> month.plusMonths(1).atDay(dayNum - daysInMonth)
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
                            val hasCapture = date in captureDates
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
                                hasCapture = hasCapture,
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
            Spacer(Modifier.height(QuickInkSpacing.s1))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Holidays this month",
                        style = type.meta,
                        color = colors.muted,
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
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Text(
                    text = "No public holidays this month",
                    style = type.meta,
                    color = colors.muted,
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
    hasCapture: Boolean,
    isNewMoon: Boolean,
    isFullMoon: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current

    // Two festival signals at play:
    //   - `isHoliday`  → Indian government holiday set. Carries a
    //                    coral RING so the date reads as a public
    //                    calendar event at a glance.
    //   - `hasEvent`   → panchanga `specialDay` row. Carries a coral
    //                    DOT under the number; no ring.
    val isFestiveDay = isHoliday || hasEvent

    val ringColor = when {
        isOutsideMonth -> androidx.compose.ui.graphics.Color.Transparent
        isSelected     -> colors.accentDeep
        isToday        -> colors.success
        isHoliday      -> colors.accent
        else           -> androidx.compose.ui.graphics.Color.Transparent
    }
    val ringWidth = if (isSelected && !isOutsideMonth) 2.5.dp else 2.dp
    val fg = when {
        isOutsideMonth && isFestiveDay -> colors.warning
        isOutsideMonth                 -> colors.muted
        isToday                        -> colors.success
        isFestiveDay                   -> colors.accent
        isSelected                     -> colors.accentDeep
        isWeekend                      -> colors.danger
        else                           -> colors.ink
    }
    val emphasised = !isOutsideMonth && (isToday || isSelected || isFestiveDay || hasCapture)

    // Dot under the day number — fires for either a panchanga event
    // or a QuickInk capture. When both fire the same day, the dot
    // renders once (visual de-dupe) and picks up the festive color
    // since the cell already carries the festive emphasis above.
    val showDot = hasEvent || hasCapture
    val dotColor = when {
        !showDot       -> androidx.compose.ui.graphics.Color.Transparent
        isOutsideMonth -> colors.muted
        isToday        -> colors.success
        else           -> colors.accent
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
        if (showDot) {
            Box(
                modifier = Modifier
                    .padding(top = 26.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        if (isNewMoon || isFullMoon) {
            val moonFill =
                if (isFullMoon) androidx.compose.ui.graphics.Color.White
                else colors.ink
            val moonBorder =
                if (isFullMoon) colors.ink
                else colors.inkSoft
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

@Composable
private fun MoonPhaseLegend() {
    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val colors = LocalQuickInkColors.current
        MoonPhaseLegendRow(
            swatch = {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .border(0.7.dp, colors.ink, CircleShape),
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
                        .background(colors.ink)
                        .border(0.5.dp, colors.inkSoft, CircleShape),
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
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = type.meta,
            color = colors.muted,
        )
    }
}

// ── Holiday list row ────────────────────────────────────────────────

@Composable
private fun HolidayRow(date: LocalDate, name: String, isFocused: Boolean) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent),
        )
        Text(
            text = "${date.dayOfMonth}",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = colors.ink,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = name,
            style = if (isFocused) type.body else type.meta,
            color = if (isFocused) colors.ink else colors.inkSoft,
        )
    }
}
