/*
 * NotepadScreen.kt
 *
 * Top-level Notepad tab — redesigned around a Day / Recents segmented
 * control:
 *
 *   • Day      → calendar bloom of trees over the current month, a
 *                today card with eyebrow + title + body + capture
 *                chips, and a quick-capture pill row (note / photo /
 *                scan / voice) above the bottom nav.
 *   • Recents  → today's plot rendered as a full-width hero tile in
 *                deep canopy + coral border, then a 2-column ragged
 *                masonry of older days (mint / leaf / deep canopy
 *                tinted by capture density, hollow tiles for empty
 *                days).
 *
 * Backed by [NotepadScreenViewModel] which observes
 * [NotepadRepository.observeActive] and derives today + per-day counts
 * for the calendar and masonry. Tapping today opens or creates today's
 * entry; tapping a past day opens that entry; tapping a quick-capture
 * pill jumps the user into a new entry.
 */

package app.releaf.mobile.features.notepad

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

/** UI-local toggle state for the Day / Recents segmented switch. */
private enum class NotepadView { Day, Recents }

@Composable
fun NotepadScreen(
    session: GoogleAuthSession,
    onOpenEntry: (String) -> Unit,
    onComposeNew: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotepadScreenViewModel = viewModel(
        factory = NotepadScreenViewModel.factory(session)
    ),
) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()
    var view by rememberSaveable { mutableStateOf(NotepadView.Day) }

    // The day whose card renders below the calendar — defaults to today,
    // updates when the user taps any day in the calendar grid.
    val today = LocalDate.now()
    var selectedDate by rememberSaveable { mutableStateOf(today.toString()) }
    val selectedLocalDate = remember(selectedDate) { LocalDate.parse(selectedDate) }
    val selectedDay = remember(selectedLocalDate, state.byDate) {
        daysForMonth(YearMonth.from(selectedLocalDate), state.byDate)
            .firstOrNull { it.date == selectedLocalDate }
            ?: DayCount(selectedLocalDate, null, 0, 0)
    }

    // System photo picker (multi-select). Each picked URI becomes a new
    // Notepad entry filed under the currently-selected day, with the
    // photo as a single attachment. Originals stay in the device gallery
    // — we just take a persistable grant on the content:// URI so it
    // resolves after app restart, matching EditorSections.kt's pattern.
    val context = LocalContext.current
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        for (uri in uris) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.importPhotosAsNewEntries(
            date = selectedLocalDate,
            uris = uris.map { it.toString() },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            // Header
            Text(
                text  = "NOTEPAD",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
            )
            Text(
                text  = when (view) {
                    NotepadView.Day     -> "A grove of days"
                    NotepadView.Recents -> "Recent garden"
                },
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
            )

            // Segmented switch — centered, fixed-max-width so the active
            // pill slides between Day and Recents in a stable lane
            // rather than expanding to fill every screen edge.
            DayRecentsSwitch(
                selected   = view,
                onSelected = { view = it },
                modifier   = Modifier.align(Alignment.CenterHorizontally),
            )

            // Content
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.s8),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AppAccent.primary)
                    }
                }
                view == NotepadView.Day -> {
                    DayView(
                        state          = state,
                        today          = today,
                        selectedDay    = selectedDay,
                        onDayTap       = { day -> selectedDate = day.date.toString() },
                        onResetToToday = { selectedDate = today.toString() },
                        onSelectedTap  = {
                            tapSelected(selectedDay, today, viewModel, onOpenEntry)
                        },
                        onQuickCapture = {
                            tapQuickCapture(selectedDay, viewModel, onOpenEntry)
                        },
                        onImportPhotos = {
                            pickPhotos.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    )
                }
                else -> {
                    RecentsView(
                        state        = state,
                        onTodayTap   = { tapToday(state, viewModel, onOpenEntry) },
                        onDayTap     = { day -> day.entry?.let { onOpenEntry(it.id) } },
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.s10))
        }
    }
}

// ---- helpers ----

private fun tapToday(
    state: NotepadScreenUiState,
    viewModel: NotepadScreenViewModel,
    onOpenEntry: (String) -> Unit,
) {
    val today = state.today
    if (today != null) {
        onOpenEntry(today.id)
    } else {
        viewModel.createForToday(onOpenEntry)
    }
}

/** Selected-day card tap: open the entry's editor if one exists; if
 *  the user is on today and there's no entry yet, create + open. For
 *  past days without entries, the tap is a no-op (we don't create
 *  back-dated entries from the calendar). */
private fun tapSelected(
    selectedDay: DayCount,
    today: java.time.LocalDate,
    viewModel: NotepadScreenViewModel,
    onOpenEntry: (String) -> Unit,
) {
    val entry = selectedDay.entry
    when {
        entry != null            -> onOpenEntry(entry.id)
        selectedDay.date == today -> viewModel.createForToday(onOpenEntry)
        else                     -> Unit
    }
}

/** Quick-capture pill tap: ensure an entry exists for the currently-
 *  selected day, then open its editor. Reuses an existing entry on
 *  that date if one's already there; otherwise creates a fresh one. */
private fun tapQuickCapture(
    selectedDay: DayCount,
    viewModel: NotepadScreenViewModel,
    onOpenEntry: (String) -> Unit,
) {
    viewModel.openOrCreateForDate(selectedDay.date, onOpenEntry)
}

// ---- Switch ----

@Composable
private fun DayRecentsSwitch(
    selected: NotepadView,
    onSelected: (NotepadView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(AppColors.Canvas)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(50),
            )
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SwitchSegment(
            label    = "Day",
            isActive = selected == NotepadView.Day,
            onClick  = { onSelected(NotepadView.Day) },
            modifier = Modifier.weight(1f),
        )
        SwitchSegment(
            label    = "Recents",
            isActive = selected == NotepadView.Recents,
            onClick  = { onSelected(NotepadView.Recents) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SwitchSegment(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isActive) AppColors.ThemeGreenDeep else Color.Transparent
    val fg = if (isActive) AppColors.OnAccent else AppColors.TextSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Button,
            color = fg,
        )
    }
}

// ---- Day view ----

@Composable
private fun DayView(
    state: NotepadScreenUiState,
    today: LocalDate,
    selectedDay: DayCount,
    onDayTap: (DayCount) -> Unit,
    onResetToToday: () -> Unit,
    onSelectedTap: () -> Unit,
    onQuickCapture: () -> Unit,
    onImportPhotos: () -> Unit,
) {
    // Pager state hoisted up here (rather than scoped inside
    // [CalendarCarousel]) so the legend's "today" badge can snap the
    // carousel back to today's month from outside the carousel.
    val pagerState = rememberPagerState(
        initialPage = PAGER_CENTER,
        pageCount   = { PAGER_TOTAL },
    )
    val coroutineScope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
        // Calendar + legend grouped tightly — the legend reads as a
        // footnote of the calendar so it sits close (s1 gap) to the
        // grid above, while the SelectedDayCard / quick-capture rows
        // keep their normal s4 breathing room.
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            // Swipeable calendar carousel — HorizontalPager with horizontal
            // contentPadding so prev/next months peek into view at the
            // edges and adjacent pages snap into place when swiped.
            CalendarCarousel(
                anchorMonth  = state.month,
                byDate       = state.byDate,
                today        = today,
                selectedDate = selectedDay.date,
                onDayTap     = onDayTap,
                pagerState   = pagerState,
            )

            // Density legend — rendered once below the carousel since
            // it describes the same color ramp for every month. The
            // "today" badge is wired as a tap target: clicking it
            // snaps the carousel back to today's month and reselects
            // today.
            NotepadCalendarLegend(
                modifier = Modifier.fillMaxWidth(),
                onTodayTap = {
                    onResetToToday()
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(PAGER_CENTER)
                    }
                },
            )
        }

        // Selected-day card — populates from whichever day the user
        // last tapped in the calendar. Defaults to today on first
        // open; tapping a past day swaps in that day's content.
        SelectedDayCard(
            day        = selectedDay,
            today      = today,
            breakdown  = if (selectedDay.date == today) state.todayBreakdown else null,
            onTap      = onSelectedTap,
        )

        // Quick capture: each pill opens or creates the capture entry
        // for the currently-selected day, so the user can land their
        // photo / scan / voice on a chosen date instead of always today.
        // The "import" pill is different — it bypasses the editor and
        // creates a fresh entry per picked photo (one note per photo).
        QuickCapturePills(
            onCapture = { _ -> onQuickCapture() },
            onImport  = onImportPhotos,
        )
    }
}

@Composable
private fun MonthPagerStrip(
    month: YearMonth,
    modifier: Modifier = Modifier,
) {
    val prev = month.minusMonths(1)
    val next = month.plusMonths(1)
    val short = DateTimeFormatter.ofPattern("MMM")
    val long  = DateTimeFormatter.ofPattern("MMMM")
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "‹  ${short.format(prev).uppercase()}  ·  ",
            style = AppTypography.Tag,
            color = AppColors.TextSecondary,
        )
        Text(
            text  = long.format(month).uppercase(),
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
        )
        Text(
            text  = "  ·  ${short.format(next).uppercase()}  ›",
            style = AppTypography.Tag,
            color = AppColors.TextSecondary,
        )
    }
}

private const val PAGER_CENTER = 24
private const val PAGER_TOTAL  = PAGER_CENTER * 2 + 1

@Composable
private fun CalendarCarousel(
    anchorMonth: YearMonth,
    byDate: Map<String, app.releaf.mobile.data.notepad.NotepadEntry>,
    today: LocalDate,
    selectedDate: LocalDate,
    onDayTap: (DayCount) -> Unit,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Pager strip reflects the currently-centered page so the user
        // can see which month they've swiped into.
        val centeredMonth = remember(pagerState.currentPage) {
            anchorMonth.plusMonths((pagerState.currentPage - PAGER_CENTER).toLong())
        }
        MonthPagerStrip(month = centeredMonth)

        Spacer(Modifier.height(AppSpacing.s3))

        // Weekday strip rendered ONCE above the pager. If we left it
        // inside `NotepadCalendarBloom` instead, the prev / next pages'
        // own strips would bleed into the centered page's edges and
        // turn the 7-letter row into an 11-letter mash-up.
        NotepadCalendarWeekdayStrip(
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(AppSpacing.s2))

        Box {
            HorizontalPager(
                state            = pagerState,
                modifier         = Modifier.fillMaxWidth(),
                // Side padding leaves room for prev/next pages to peek
                // in at ~22dp on each edge once page-spacing is subtracted.
                contentPadding   = PaddingValues(horizontal = 24.dp),
                pageSpacing      = 4.dp,
            ) { page ->
                val pageMonth = anchorMonth.plusMonths((page - PAGER_CENTER).toLong())
                val pageDays  = remember(pageMonth, byDate) { daysForMonth(pageMonth, byDate) }
                val isCurrent = page == pagerState.currentPage
                // Pages flanking the centered one fade as they slide off
                // the focal area — visual reinforcement of "you swiped
                // away from the active month."
                val pageOffset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                val alpha = (1f - pageOffset.absoluteValue * 0.7f).coerceIn(0.3f, 1f)

                NotepadCalendarBloom(
                    month            = pageMonth,
                    days             = pageDays,
                    today            = today,
                    onDayTap         = if (isCurrent) onDayTap else { _ -> },
                    modifier         = Modifier.alpha(alpha),
                    showLegend       = false,
                    showWeekdayStrip = false,
                    // Only the centered page gets the green ring — side
                    // peeks shouldn't compete for the user's attention
                    // since their days aren't tappable.
                    selectedDate     = if (isCurrent) selectedDate else null,
                )
            }

            // Subtle chevron hints sitting in the contentPadding gutter
            // so they signal swipeability without overlapping any tree
            // cell. Coral so they tie into the today pin's accent.
            Text(
                text     = "‹",
                style    = AppTypography.EditorialTitle,
                color    = AppAccent.primary.copy(alpha = 0.55f),
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp),
            )
            Text(
                text     = "›",
                style    = AppTypography.EditorialTitle,
                color    = AppAccent.primary.copy(alpha = 0.55f),
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun SelectedDayCard(
    day: DayCount,
    today: LocalDate,
    breakdown: TodayBreakdown?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday    = day.date == today
    val isFuture   = day.date.isAfter(today)
    val entry      = day.entry
    val eyebrowTag = if (isToday) "TODAY" else if (isFuture) "UPCOMING" else "SELECTED"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(
                width = 1.2.dp,
                color = AppAccent.primary.copy(alpha = if (isToday) 0.55f else 0.30f),
                shape = RoundedCornerShape(AppRadius.lg),
            )
            .clickable(onClick = onTap)
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text  = "$eyebrowTag · ${dayHeader(day.date)}",
            style = AppTypography.Eyebrow,
            color = AppAccent.primary,
        )
        Text(
            text  = entry?.title?.takeIf { it.isNotBlank() }
                ?: if (isToday) "Today's entry" else "Untitled",
            color = AppColors.TextPrimary,
            fontFamily = FontFamily.Serif,
            fontSize = 18.sp,
        )

        val notesPreview = entry?.notes
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(140)
        if (!notesPreview.isNullOrBlank()) {
            Text(
                text  = notesPreview,
                color = AppColors.TextSecondary,
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp,
                maxLines = 2,
            )
        } else {
            Text(
                text = when {
                    isToday  -> "Nothing captured yet — tap to start today's note."
                    isFuture -> "No entry — yet."
                    else     -> "No entry on this day."
                },
                color = AppColors.TextTertiary,
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp,
            )
        }

        // Capture / todo chip row — only shown for today (where we have
        // the full per-mode breakdown). Past/future days collapse to
        // a compact "X captures · Y todos" line below.
        if (breakdown != null && (breakdown.captureCount > 0 || breakdown.openTodoCount > 0)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (breakdown.photoCount > 0) ChipPill("photo · ${breakdown.photoCount}", Color(0xFFFCEAE0), Color(0xFF993C1D))
                if (breakdown.scanCount  > 0) ChipPill("scan · ${breakdown.scanCount}",  Color(0xFFD9EDE2), Color(0xFF1E5943))
                if (breakdown.voiceCount > 0) ChipPill("voice · ${breakdown.voiceCount}", Color(0xFFFAEEDA), Color(0xFF854F0B))
                if (breakdown.openTodoCount > 0) {
                    Text(
                        text  = "+ ${breakdown.openTodoCount} todos",
                        style = AppTypography.Tag,
                        color = AppAccent.deep,
                    )
                }
            }
        } else if (day.captureCount > 0 || day.openTodoCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                if (day.captureCount > 0) {
                    Text(
                        text  = "${day.captureCount} captures",
                        style = AppTypography.Tag,
                        color = AppColors.ThemeGreenDeep,
                    )
                }
                if (day.captureCount > 0 && day.openTodoCount > 0) {
                    Text("·", style = AppTypography.Tag, color = AppColors.TextSecondary)
                }
                if (day.openTodoCount > 0) {
                    Text(
                        text  = "${day.openTodoCount} todos",
                        style = AppTypography.Tag,
                        color = AppAccent.deep,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipPill(label: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = AppSpacing.s3, vertical = 4.dp),
    ) {
        Text(label, style = AppTypography.Tag, color = fg)
    }
}

@Composable
private fun QuickCapturePills(
    onCapture: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text  = "QUICK CAPTURE",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        // Five pills with weight(1f) each — labels stay short ("import"
        // is 6 chars, same band as "voice"/"photo") so the row still
        // fits comfortably on a 360dp-wide screen.
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            CapturePill("note",   modifier = Modifier.weight(1f)) { onCapture("note") }
            CapturePill("photo",  modifier = Modifier.weight(1f)) { onCapture("photo") }
            CapturePill("scan",   modifier = Modifier.weight(1f)) { onCapture("scan") }
            CapturePill("voice",  modifier = Modifier.weight(1f)) { onCapture("voice") }
            CapturePill("import", modifier = Modifier.weight(1f)) { onImport() }
        }
    }
}

@Composable
private fun CapturePill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(AppColors.CardSolid)
            .border(0.6.dp, AppColors.BorderDefault, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Button,
            color = AppColors.ThemeGreenDeep,
        )
    }
}

// ---- Recents view ----

@Composable
private fun RecentsView(
    state: NotepadScreenUiState,
    onTodayTap: () -> Unit,
    onDayTap: (DayCount) -> Unit,
) {
    val today = state.recentDays.firstOrNull { it.date == LocalDate.now() }
        ?: DayCount(LocalDate.now(), state.today, state.todayBreakdown.captureCount, state.todayBreakdown.openTodoCount)
    val earlier = state.recentDays.filter { it.date != LocalDate.now() }

    NotepadGardenTiles(
        today      = today,
        earlier    = earlier,
        onTodayTap = onTodayTap,
        onDayTap   = onDayTap,
    )
}

// ---- formatting ----

private fun dayHeader(date: LocalDate): String {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEEE · MMM d", java.util.Locale.getDefault())
    return fmt.format(date).uppercase()
}
