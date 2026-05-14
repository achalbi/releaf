/*
 * CalendarScreen.kt
 *
 * QuickInk's full-screen Calendar surface, reachable from the home
 * header's calendar button. Three vertical zones:
 *
 *   1. Top bar — back arrow, "Calendar" title, "Today" pill.
 *   2. Festival search — collapsible BasicTextField; results render
 *      below the grid when the query is non-empty.
 *   3. Calendar grid ([CalendarPanel]) + panchanga detail card for
 *      the selected date + the QuickInk-specific "Scans on this day"
 *      list + monthly festival list + source/about note.
 *
 * Port of Releaf Android's `CalendarScreen.kt`. Theme tokens routed
 * through `LocalQuickInkColors` / `LocalQuickInkTypography`. The
 * `LeafEyebrow` surface in the top bar is swapped for a simple
 * uppercase tracked text — QuickInk has no equivalent leaf glyph
 * component, and the eyebrow's job (back-affordance) is fulfilled by
 * the explicit back arrow on this screen.
 */

package app.quickink.mobile.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.panchanga.PanchangaEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CalendarScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenCapture: (String) -> Unit,
    viewModel: CalendarViewModel = viewModel(factory = CalendarViewModel.factory(userId)),
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val selectedDate by viewModel.selectedDate.collectAsState()
    val visibleMonth by viewModel.visibleMonth.collectAsState()
    val selectedDayPanchanga by viewModel.selectedDayPanchanga.collectAsState()
    val visibleMonthPanchanga by viewModel.visibleMonthPanchanga.collectAsState()
    val adjacentMonthsPanchanga by viewModel.adjacentMonthsPanchanga.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val capturesByDate by viewModel.capturesByDate.collectAsState()

    val eventDates = remember(visibleMonthPanchanga) {
        visibleMonthPanchanga
            .filter { it.specialDay.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            .toSet()
    }
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
    val captureDates = remember(capturesByDate) { capturesByDate.keys.toSet() }
    val selectedDayCaptures = remember(selectedDate, capturesByDate) {
        capturesByDate[selectedDate].orEmpty()
    }

    // Push content below the status bar so the back chevron + title
    // don't kiss the notification clock. Mirror of the HomeScreen
    // pattern (`statusBarTop + QuickInkSpacing.s6`).
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // ── Top bar ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = QuickInkSpacing.s2,
                    end = QuickInkSpacing.s4,
                    top = statusBarTop + QuickInkSpacing.s6,
                    bottom = QuickInkSpacing.s3,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.ink,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "QUICKINK · CALENDAR",
                    style = type.eyebrow,
                    color = colors.muted,
                )
                Text(
                    text = "Calendar",
                    style = type.pageTitle,
                    color = colors.ink,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accentSoft)
                    .border(1.dp, colors.accent, RoundedCornerShape(QuickInkRadius.pill))
                    .clickable { viewModel.goToToday() }
                    .padding(horizontal = QuickInkSpacing.s3, vertical = 6.dp),
            ) {
                Text(
                    text = "Today",
                    style = type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.accentDeep,
                )
            }
        }

        val monthFestivals = visibleMonthPanchanga.filter { it.specialDay.isNotBlank() }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s8,
            ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            item("search") {
                FestivalSearchField(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClear = viewModel::clearSearch,
                )
            }

            if (searchQuery.isNotBlank()) {
                item("search-results") {
                    SearchResultsSection(
                        query = searchQuery,
                        results = searchResults,
                        onSelectDate = { date ->
                            viewModel.selectDate(date)
                            viewModel.clearSearch()
                        },
                    )
                }
            }

            item("calendar") {
                CalendarPanel(
                    visibleMonth = visibleMonth,
                    onVisibleMonthChange = viewModel::setVisibleMonth,
                    selectedDate = selectedDate,
                    onSelectedDateChange = viewModel::selectDate,
                    eventDates = eventDates,
                    newMoonDates = newMoonDates,
                    fullMoonDates = fullMoonDates,
                    captureDates = captureDates,
                )
            }

            item("selected-day") {
                SelectedDayCard(
                    date = selectedDate,
                    rows = selectedDayPanchanga,
                )
            }

            if (selectedDayCaptures.isNotEmpty()) {
                item("selected-day-captures") {
                    SelectedDayCapturesList(
                        captures = selectedDayCaptures,
                        onOpen = onOpenCapture,
                    )
                }
            }

            if (monthFestivals.isNotEmpty()) {
                item("month-festivals") {
                    MonthFestivalList(rows = monthFestivals, onTap = viewModel::selectDate)
                }
            }

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
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Search festivals",
                    style = type.body,
                    color = colors.muted,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = type.body.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.accent),
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
                    tint = colors.muted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ── Selected-day detail card ────────────────────────────────────────

@Composable
private fun SelectedDayCard(date: LocalDate, rows: List<PanchangaEntity>) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
            ) {
                Text(
                    text = date.format(LongDateFormatter).uppercase(),
                    style = type.eyebrow,
                    color = colors.accentDeep,
                )
                Text(
                    text = date.format(SerifDateFormatter),
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = colors.ink,
                )
            }
            Text(
                text = "Panchanga",
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 16.sp,
                ),
                color = colors.accent,
            )
        }
        Spacer(Modifier.height(2.dp))

        if (rows.isEmpty()) {
            Text(
                text = "Panchanga data not available for this date.",
                style = type.body,
                color = colors.inkSoft,
            )
            Text(
                text = "The bundled Vontikoppal dataset covers 2026-03-19 to 2027-04-06.",
                style = type.meta,
                color = colors.muted,
            )
        } else {
            rows.forEach { row -> PanchangaRow(row, date = date) }
        }
    }
}

@Composable
private fun PanchangaRow(row: PanchangaEntity, date: LocalDate) {
    val colors = LocalQuickInkColors.current
    val rahuKala = remember(date) { rahuKalaFor(date) }
    val sunTimes = remember(date) { sunriseSunsetFor(date) }
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            DetailField(label = "Masa", value = row.masa, modifier = Modifier.weight(1f))
            DetailField(label = "Paksha", value = row.paksha, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            DetailField(
                label = "Thithi",
                value = "${row.thithi} (${row.thithiNum})",
                modifier = Modifier.weight(1f),
            )
            DetailField(
                label = "Rahu Kala",
                value = rahuKala,
                valueColor = colors.accent,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
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
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
                Text(
                    text = row.specialDay,
                    style = LocalQuickInkTypography.current.body,
                    color = colors.ink,
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
    valueColor: androidx.compose.ui.graphics.Color = LocalQuickInkColors.current.ink,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = type.eyebrow.copy(fontSize = 10.sp),
            color = colors.muted,
        )
        Text(
            text = value,
            style = type.body,
            color = valueColor,
        )
    }
}

// ── Selected-day captures (QuickInk-specific) ──────────────────────

@Composable
private fun SelectedDayCapturesList(
    captures: List<CaptureEntity>,
    onOpen: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(
            text = "Scans on this day",
            style = type.heading,
            color = colors.ink,
        )
        captures.forEach { capture ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.sm))
                    .clickable { onOpen(capture.id) }
                    .padding(vertical = QuickInkSpacing.s1),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(QuickInkRadius.sm))
                        .background(colors.paper2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = capture.pageCount.toString(),
                        style = type.label,
                        color = colors.inkSoft,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = (capture.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Scan").replaceFirstChar {
                            it.titlecase(Locale.getDefault())
                        },
                        style = type.cardTitle,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(if (capture.source == "import") "Import" else "Scan")
                            append(" · ")
                            append(if (capture.pageCount == 1) "1 page" else "${capture.pageCount} pages")
                        },
                        style = type.caption,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

// ── Month festival list ─────────────────────────────────────────────

@Composable
private fun MonthFestivalList(rows: List<PanchangaEntity>, onTap: (LocalDate) -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(
            text = "Festivals & observances",
            style = type.heading,
            color = colors.ink,
        )
        Spacer(Modifier.height(2.dp))
        rows.forEach { row ->
            val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.sm))
                    .clickable(enabled = date != null) { date?.let(onTap) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = row.specialDay,
                        style = type.body,
                        color = colors.ink,
                    )
                    Text(
                        text = buildString {
                            if (date != null) {
                                append(date.format(ShortDateFormatter))
                                append(" · ")
                            }
                            append("${row.masa} ${row.paksha} ${row.thithi}")
                        },
                        style = type.meta,
                        color = colors.inkSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(
            text = "Results for “$query”",
            style = type.heading,
            color = colors.ink,
        )
        if (results.isEmpty()) {
            Text(
                text = "No festivals match.",
                style = type.meta,
                color = colors.muted,
            )
        } else {
            val capped = results.take(SEARCH_RESULT_CAP)
            capped.forEach { row ->
                val date = runCatching { LocalDate.parse(row.date) }.getOrNull()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(QuickInkRadius.sm))
                        .clickable(enabled = date != null) { date?.let(onSelectDate) }
                        .padding(vertical = 6.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = row.specialDay,
                            style = type.body,
                            color = colors.ink,
                        )
                        Text(
                            text = buildString {
                                if (date != null) {
                                    append(date.format(ShortDateFormatter))
                                    append(" · ")
                                }
                                append("${row.masa} ${row.paksha} ${row.thithi}")
                            },
                            style = type.meta,
                            color = colors.inkSoft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (results.size > SEARCH_RESULT_CAP) {
                Text(
                    text = "${results.size - SEARCH_RESULT_CAP} more matches — refine the query to narrow.",
                    style = type.meta,
                    color = colors.muted,
                )
            }
        }
    }
}

// ── About / source note ─────────────────────────────────────────────

@Composable
private fun AboutPanchanga() {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        Text(
            text = "ABOUT THIS DATA",
            style = type.eyebrow,
            color = colors.muted,
        )
        Text(
            text = "Panchanga data derived from the printed Ontikoppal Panchanga, published by Ontikoppal Panchanga Mandira, Mysore. OCR-derived and unofficial; verify important ritual dates against the original.",
            style = type.meta,
            color = colors.inkSoft,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

private const val SEARCH_RESULT_CAP = 20

private val LongDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

private val SerifDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

private val ShortDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
