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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseSubPages
import app.releaf.mobile.data.notepad.NotepadCategory
import app.releaf.mobile.data.notepad.NotepadEntry
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
    val selectedDay = remember(selectedLocalDate, state.byDate, state.entriesByDate) {
        daysForMonth(YearMonth.from(selectedLocalDate), state.byDate, state.entriesByDate)
            .firstOrNull { it.date == selectedLocalDate }
            ?: DayCount(selectedLocalDate, emptyList(), 0, 0)
    }

    // Currently-selected page within the selected day's carousel. Drives
    // where quick-capture taps land — a scan / photo / voice goes onto
    // the page the user is *looking at* rather than always at the day's
    // first entry. Null when the user is on the trailing "+ new page"
    // card (no live entry to target — quick-capture creates one). Reset
    // when the day changes; clamped when the active entry vanishes
    // (e.g. it was filtered out by a category change).
    var selectedPageEntryId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedDate) { selectedPageEntryId = null }
    LaunchedEffect(selectedDay.entries) {
        val current = selectedPageEntryId
        if (current != null && selectedDay.entries.none { it.id == current }) {
            selectedPageEntryId = null
        }
    }
    // What quick-capture should target. Default to the day's first
    // entry when nothing's been picked yet so a brand-new day card
    // still has a sensible target the moment it appears.
    val effectiveSelectedEntryId =
        selectedPageEntryId ?: selectedDay.entries.firstOrNull()?.id

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
            LeafEyebrow(label = "releaf · notepad")
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

            // Category filter row — predefined categories plus any
            // customs the user has typed. Tapping a chip narrows
            // every downstream surface (calendar density, day card,
            // recents masonry) to that category; tapping the active
            // chip again clears the filter back to "All".
            CategoryFilterRow(
                selected = state.selectedCategory,
                customs  = state.customCategories,
                onPick   = { picked -> viewModel.setCategoryFilter(picked) },
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
                        state                  = state,
                        today                  = today,
                        selectedDay            = selectedDay,
                        selectedPageEntryId    = selectedPageEntryId,
                        onDayTap               = { day -> selectedDate = day.date.toString() },
                        onResetToToday         = { selectedDate = today.toString() },
                        onSelectedPageChange   = { id -> selectedPageEntryId = id },
                        onTapEntry             = { id -> onOpenEntry(id) },
                        onAddPage              = {
                            viewModel.createNewPageOn(selectedLocalDate) { newId ->
                                selectedPageEntryId = newId
                                onOpenEntry(newId)
                            }
                        },
                        onQuickCapture         = {
                            tapQuickCapture(
                                entryId    = effectiveSelectedEntryId,
                                date       = selectedLocalDate,
                                viewModel  = viewModel,
                                onOpenEntry = onOpenEntry,
                            )
                        },
                        onImportPhotos         = {
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

/** Quick-capture pill tap: route to whichever entry the user is
 *  currently looking at on the day's carousel. When no entry exists
 *  for the selected day yet ([entryId] is null), fall back to
 *  open-or-create on the date so a brand-new day still has somewhere
 *  for the capture to land. */
private fun tapQuickCapture(
    entryId: String?,
    date: java.time.LocalDate,
    viewModel: NotepadScreenViewModel,
    onOpenEntry: (String) -> Unit,
) {
    if (entryId != null) {
        viewModel.openEntry(entryId, onOpenEntry)
    } else {
        viewModel.openOrCreateForDate(date, onOpenEntry)
    }
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
    selectedPageEntryId: String?,
    onDayTap: (DayCount) -> Unit,
    onResetToToday: () -> Unit,
    onSelectedPageChange: (String?) -> Unit,
    onTapEntry: (String) -> Unit,
    onAddPage: () -> Unit,
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

        // Selected-day pager — date header centered above, then a row
        // of uniform-size page cards. Each card represents a separate
        // notepad ENTRY filed under this day (so days that hold
        // multiple pages render as a true carousel of pages, not as
        // sub-pages of one entry). The trailing "+ new page" card
        // creates a fresh entry on this day. The carousel reports
        // selection back via [onSelectedPageChange] so the quick-
        // capture pills can target whichever page the user is
        // currently viewing.
        SelectedDayPageCarousel(
            day                  = selectedDay,
            today                = today,
            selectedPageEntryId  = selectedPageEntryId,
            onTapEntry           = onTapEntry,
            onAddPage            = onAddPage,
            onSelectedPageChange = onSelectedPageChange,
        )

        // Quick capture: each pill opens the entry the carousel above
        // is currently pointing at (or creates one if the day is
        // empty). The "import" pill bypasses the editor and creates a
        // fresh entry per picked photo (one note per photo).
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

        Spacer(Modifier.height(AppSpacing.s2))

        // Weekday strip rendered ONCE above the pager. If we left it
        // inside `NotepadCalendarBloom` instead, the prev / next pages'
        // own strips would bleed into the centered page's edges and
        // turn the 7-letter row into an 11-letter mash-up.
        NotepadCalendarWeekdayStrip(
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(Modifier.height(AppSpacing.s1))

        // Pager height tracks the centered month's row count so 5-row
        // months don't carry the 32dp empty band a 6-row month (May,
        // August in 2026) would otherwise force the pager to reserve.
        // animateDpAsState smooths the snap when the user lands on a
        // month with a different row count.
        val centeredRows = remember(centeredMonth) {
            val leading = centeredMonth.atDay(1).dayOfWeek.value % 7
            val total   = leading + centeredMonth.lengthOfMonth()
            (total + 6) / 7
        }
        val targetPagerHeight = (centeredRows * 32).dp
        val pagerHeight by animateDpAsState(
            targetValue = targetPagerHeight,
            label       = "calendarPagerHeight",
        )

        Box {
            HorizontalPager(
                state            = pagerState,
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(pagerHeight),
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

/** Horizontal pager of uniform-size page cards for the selected day.
 *  A centered date header sits above the carousel; each card carries
 *  its own "X / N" indicator in the top-right corner. **Each card
 *  represents a separate notepad entry filed under this day** — i.e.
 *  the user's "pages of the day" — not sub-pages of a single entry.
 *  The trailing "+ new page" card creates a fresh entry on this day
 *  (omitted for future days, where back-filling forward entries
 *  isn't allowed).
 *
 *  Selection: as the user swipes, [onSelectedPageChange] fires with
 *  the entry id under the centered card (or null when the user is on
 *  the trailing "+ new page" card). The screen's quick-capture pills
 *  use this id to route their captures onto the page the user is
 *  actively viewing. */
@Composable
private fun SelectedDayPageCarousel(
    day: DayCount,
    today: LocalDate,
    selectedPageEntryId: String?,
    onTapEntry: (String) -> Unit,
    onAddPage: () -> Unit,
    onSelectedPageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday  = day.date == today
    val isFuture = day.date.isAfter(today)
    val tag      = if (isToday) "TODAY" else if (isFuture) "UPCOMING" else "SELECTED"

    val entries = day.entries
    val showNewPageCard = !isFuture
    // For days with zero entries we still need a card to show — render
    // a placeholder that doubles as the "+ new page" affordance on
    // today (and a "no entry" message otherwise).
    val showPlaceholder = entries.isEmpty()
    val totalCards =
        if (showPlaceholder) 1 + (if (showNewPageCard && !isFuture) 0 else 0)
        else                  entries.size + (if (showNewPageCard) 1 else 0)
    // When there are entries, the new-page card sits at index = entries.size.
    // When there are no entries, the placeholder card occupies index 0
    // and *is* the new-page card on today / past days.

    // Initial page: whichever entry was most-recently picked in the
    // screen-level state, falling back to the first entry. Resets to
    // 0 on day change (the LaunchedEffect on `day` below wires it).
    val initialPage = remember(day.date) {
        val idx = entries.indexOfFirst { it.id == selectedPageEntryId }
        if (idx >= 0) idx else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount   = { totalCards },
    )

    // Emit selection upward as the centered card changes. Index <
    // entries.size points to a real entry; anything beyond is the
    // "+ new page" card and reports null so the screen's
    // tapQuickCapture knows to fall back to create-or-open.
    LaunchedEffect(pagerState.currentPage, entries) {
        val idx = pagerState.currentPage
        val pickedId = entries.getOrNull(idx)?.id
        onSelectedPageChange(pickedId)
    }

    Column(
        modifier            = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // Date strip + page indicator on the same row. The date sits
        // centered as the day's header; the "X / N" indicator floats at
        // the right edge in line with it so the user can scan position
        // without it crowding the card itself.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s2),
        ) {
            Text(
                text     = "$tag · ${dayHeader(day.date)}",
                style    = AppTypography.Eyebrow,
                color    = AppAccent.primary,
                modifier = Modifier.align(Alignment.Center),
            )
            val current = pagerState.currentPage
            val indicator = when {
                showPlaceholder        -> null
                current < entries.size -> "${current + 1} / ${entries.size}"
                else                   -> null
            }
            if (indicator != null) {
                Text(
                    text     = indicator,
                    style    = AppTypography.Tag,
                    color    = AppColors.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth * 0.85f
            HorizontalPager(
                state       = pagerState,
                pageSize    = PageSize.Fixed(pageWidth),
                pageSpacing = AppSpacing.s2,
                modifier    = Modifier.fillMaxWidth(),
            ) { page ->
                when {
                    showPlaceholder -> {
                        // No entries on this day. Single placeholder card —
                        // tap to either start today's first entry, create on
                        // a past day, or just inform the user for upcoming.
                        val (title, body) = when {
                            isToday   -> "+ new page" to "Tap to start today's note."
                            isFuture  -> "Untitled"   to "No entry — yet."
                            else      -> "+ new page" to "Tap to add a page on this day."
                        }
                        PageCard(
                            title       = title,
                            body        = body,
                            emptyHint   = "",
                            accentAlpha = if (isToday) 0.55f else 0.30f,
                            background  = if (isFuture) AppColors.CardSolid else AppColors.Canvas,
                            titleColor  = if (isFuture) AppColors.TextPrimary else AppAccent.deep,
                            onTap       = if (isFuture) { -> } else onAddPage,
                        )
                    }
                    page < entries.size -> {
                        val entry  = entries[page]
                        val title  = entry.title?.takeIf { it.isNotBlank() } ?: "Untitled"
                        // Body preview prefers the entry's description (the
                        // editor's serif subtitle); otherwise pulls the first
                        // non-blank line of any sub-page's notes so the user
                        // can scan multi-page days at a glance.
                        val parsedSubPages = entry.subPages.parseSubPages()
                        val bodyText = entry.description
                            ?.takeIf { it.isNotBlank() }
                            ?: parsedSubPages
                                .asSequence()
                                .flatMap { it.notes.lineSequence() }
                                .firstOrNull { it.isNotBlank() }
                                ?.take(140)
                            ?: entry.notes.lineSequence()
                                .firstOrNull { it.isNotBlank() }
                                ?.take(140)
                        PageCard(
                            title       = title,
                            body        = bodyText,
                            emptyHint   = "Empty page — tap to write.",
                            accentAlpha = if (isToday && page == 0) 0.55f else 0.30f,
                            onTap       = { onTapEntry(entry.id) },
                            chips       = { EntryCountsRow(entry = entry) },
                        )
                    }
                    else -> {
                        // Trailing "+ new page" card.
                        PageCard(
                            title       = "+ new page",
                            body        = "Tap to add a fresh page to this day.",
                            emptyHint   = "",
                            accentAlpha = 0.45f,
                            background  = AppColors.Canvas,
                            titleColor  = AppAccent.deep,
                            onTap       = onAddPage,
                        )
                    }
                }
            }
        }
    }
}

/** Per-entry capture breakdown — one chip per content type so the
 *  user can scan "what's in this page" at a glance ("4 notes · 2
 *  photos · 1 voice · 3 scans" etc.). Notes count = number of sub-pages
 *  that carry text (or 1 for legacy entries with text only on the
 *  parent row). Attachments are split by type from the JSON column;
 *  todos count only the ones still open. The row is horizontally
 *  scrollable so a busy entry doesn't clip on narrow cards. */
@Composable
private fun EntryCountsRow(entry: NotepadEntry) {
    val parsedSubPages = entry.subPages.parseSubPages()
    val noteCount = when {
        parsedSubPages.isNotEmpty() -> parsedSubPages.count { it.notes.isNotBlank() }
        entry.notes.isNotBlank()    -> 1
        else                        -> 0
    }
    val attachments = runCatching { entry.attachments.parseAttachments() }
        .getOrDefault(emptyList())
    val photoCount = attachments.count { it.type == "photo" }
    val scanCount  = attachments.count { it.type == "scan"  }
    val voiceCount = attachments.count { it.type == "voice" }
    val todoCount  = openTodoCountFor(entry)

    if (noteCount + photoCount + scanCount + voiceCount + todoCount == 0) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        modifier              = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        if (noteCount  > 0) ChipPill(label("note",  noteCount),  Color(0xFFE8F0E5), Color(0xFF2F5C2C))
        if (photoCount > 0) ChipPill(label("photo", photoCount), Color(0xFFFCEAE0), Color(0xFF993C1D))
        if (scanCount  > 0) ChipPill(label("scan",  scanCount),  Color(0xFFD9EDE2), Color(0xFF1E5943))
        if (voiceCount > 0) ChipPill(label("voice", voiceCount), Color(0xFFFAEEDA), Color(0xFF854F0B))
        if (todoCount  > 0) ChipPill(label("todo",  todoCount),  Color(0xFFEDE2F0), Color(0xFF4B2D6B))
    }
}

/** "1 note", "3 notes" — pluralizes the common chip labels with a
 *  trailing 's' which is correct for note/photo/scan/todo. Voice stays
 *  as "voice" regardless of count since the singular and plural read
 *  the same in the UX. */
private fun label(word: String, count: Int): String = when (word) {
    "voice" -> "$count voice"
    else    -> if (count == 1) "$count $word" else "$count ${word}s"
}

/** Single uniform card used for both real pages and the trailing
 *  "+ new page" affordance. Fixed height so every card in the
 *  carousel stays the same size regardless of body length or the
 *  presence of the chip row. The page indicator and date strip are
 *  rendered by the carousel above the cards, not on the card itself. */
@Composable
private fun PageCard(
    title: String,
    body: String?,
    emptyHint: String,
    accentAlpha: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = AppColors.CardSolid,
    titleColor: Color = AppColors.TextPrimary,
    chips: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(background)
            .border(
                width = 1.2.dp,
                color = AppAccent.primary.copy(alpha = accentAlpha),
                shape = RoundedCornerShape(AppRadius.lg),
            )
            .clickable(onClick = onTap)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(
            text       = title,
            color      = titleColor,
            fontFamily = FontFamily.Serif,
            fontSize   = 18.sp,
        )
        val displayBody = body?.takeIf { it.isNotBlank() } ?: emptyHint
        Text(
            text       = displayBody,
            color      = if (body.isNullOrBlank()) AppColors.TextTertiary else AppColors.TextSecondary,
            fontFamily = FontFamily.Serif,
            fontSize   = 13.sp,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
        )
        chips?.invoke()
    }
}

/** Capture / todo chip row sourced from the entry's per-mode breakdown
 *  (today only — past days collapse to a compact "X captures · Y todos"
 *  line since we don't compute the per-mode counts for them). */
@Composable
private fun EntryChipsRow(
    day: DayCount,
    breakdown: TodayBreakdown?,
) {
    if (breakdown != null && (breakdown.captureCount > 0 || breakdown.openTodoCount > 0)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
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
        // Seven pills sized to their own labels — once we added
        // "contacts" and "location" the row no longer fits a
        // weight-distributed layout on 360dp screens, so the row is
        // horizontally scrollable instead. The first/last pills get
        // a touch of edge padding so they don't kiss the screen edge
        // when scrolled to either end.
        Row(
            modifier              = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            CapturePill("note")     { onCapture("note") }
            CapturePill("photo")    { onCapture("photo") }
            CapturePill("scan")     { onCapture("scan") }
            CapturePill("voice")    { onCapture("voice") }
            CapturePill("todos")    { onCapture("todos") }
            CapturePill("contacts") { onCapture("contacts") }
            CapturePill("location") { onCapture("location") }
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
            .padding(horizontal = AppSpacing.s4, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Button,
            color = AppColors.ThemeGreenDeep,
        )
    }
}

/**
 * Horizontally-scrollable row of category filter chips. The first
 * chip is "All" (clears the filter); after that come the predefined
 * + custom categories merged into a single ordered list, with the
 * user's preferred display order applied (Settings → Categories).
 * Tapping a chip narrows every downstream surface to that category;
 * tapping "All" — or the active chip — clears back to the
 * unfiltered view.
 *
 * Active chip: ThemeGreenDeep background + white text. Idle: GreenSoft
 * background + ThemeGreenDeep text for predefined; Neutral100 + dark
 * text for customs so the predefined set still reads as the
 * "official" options at a glance.
 */
@Composable
private fun CategoryFilterRow(
    selected: String?,
    customs: List<String>,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs   = remember(context) { app.releaf.mobile.ui.theme.UiPreferences.get(context) }
    val prefsState by prefs.state.collectAsState()
    val ordered = remember(prefsState.notepadCategoryOrder, customs) {
        NotepadCategory.applyOrder(prefsState.notepadCategoryOrder, customs)
    }
    Row(
        modifier              = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        FilterChip(
            label    = "All",
            isActive = selected == null,
            onClick  = { onPick(null) },
        )
        ordered.forEach { name ->
            val active = selected.equals(name, ignoreCase = true)
            FilterChip(
                label    = name,
                isActive = active,
                // Tap on already-active = clear (toggle-off pattern,
                // matches the Pen / Eraser toggle in the drawing
                // toolbar so all chip-style affordances behave the
                // same way).
                onClick  = { onPick(if (active) null else name) },
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    // Custom and predefined chips share the same green-soft idle
    // styling — the user wanted the chip row to read as one
    // homogeneous list rather than two visually-distinct groups.
    val (bg, fg) = if (isActive) {
        AppColors.ThemeGreenDeep to AppColors.OnAccent
    } else {
        AppColors.GreenSoft to AppColors.ThemeGreenDeep
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.s3))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
    ) {
        Text(text = label, style = AppTypography.Meta, color = fg)
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
        ?: DayCount(
            date          = LocalDate.now(),
            entries       = state.todayEntries,
            captureCount  = state.todayBreakdown.captureCount,
            openTodoCount = state.todayBreakdown.openTodoCount,
        )
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
