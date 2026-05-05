/*
 * NotesListScreen.kt
 *
 * QuickInk's Library — the user's full scan gallery, redesigned to
 * match the editorial mock (lined-paper note cards, handwritten
 * preview snippets, date-bucketed sections).
 *
 * Visual structure (per mock):
 *   • Header — "Library" serif title, no back chevron (Library is
 *     a tab destination, accessed via the bottom-nav `Library` cell).
 *     Trailing controls live in a single pill: a date-range filter
 *     (calendar icon — opens a Material3 DateRangePicker dialog) on
 *     the left, a grid/list segmented toggle on the right with the
 *     active half painted coral. The calendar icon paints coral
 *     itself when a range is active, doubling as a status indicator;
 *     a sub-meta line below the title surfaces the range and a Clear
 *     button. (The previous newest/oldest sort affordance was
 *     replaced — captures already come back from the DAO in
 *     `created_at DESC` order, so user-controlled sort direction
 *     was thin value next to a real date filter.)
 *   • Sub-meta — `{count} notes` line under the title. Synced-bytes
 *     readout is a TODO (sums attachment file sizes; deferred until
 *     the pending-binary list query lands a size column).
 *   • Category chips — horizontal scroll, "All" leading, no count
 *     badges. Inactive chips are outlined white pills; active is
 *     filled coral.
 *   • Date sections — captures bucketed by relative day: TODAY /
 *     THIS WEEK / EARLIER. Each section renders an eyebrow header
 *     and a 2-column grid of cards (LazyVerticalGrid is awkward
 *     to mix with date headers in one scroll, so the grid is built
 *     by chunking into pairs inside a single LazyColumn).
 *   • Card design — image-first preview when a `previewUri` is
 *     present (matte + bordered photo + "Scan" badge), with a
 *     handwritten OCR placeholder showing only while the image is
 *     decoding. Fallbacks to lined paper + OCR text when there's
 *     no image, or an explicit "Preview unavailable" error state
 *     when neither image nor OCR exists. OCR snippets are preloaded
 *     for the whole user via
 *     [OcrResultDao.observeFirstSnippetsForUser] so cards land in
 *     their final state on first frame. Small "Np" page-count chip
 *     in the top-right; white footer with serif title + accent-soft
 *     category tag + relative date.
 *
 * Mirror of iOS `NotesListScreen.swift`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.notes

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.components.DateRangePickerSheet
import app.quickink.mobile.ui.components.isWithinPickedDateRange
import app.quickink.mobile.ui.components.formatDateRange
import app.quickink.mobile.ui.components.rememberQuickInkDateRangePickerState
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkFonts
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Window size for the lazy-loaded grid. The Library DAO returns the
 * full active list (cheap for personal-scale libraries), but we only
 * expose the first [LIBRARY_PAGE_SIZE] items to the LazyColumn on
 * mount and grow the window each time the user scrolls to the bottom.
 */
private const val LIBRARY_PAGE_SIZE = 20

private enum class ViewMode { Grid, List }

@Composable
fun NotesListScreen(
    userId: String,
    onOpenScan: (captureId: String) -> Unit,
    /// Tab navigation callbacks for the floating bottom nav. The
    /// Library tab paints itself active; tapping it is a no-op
    /// (we're already here). The other callbacks switch tabs at
    /// the route level — see QuickInkRoot's
    /// `popUpTo(HOME) { saveState=true }` wiring.
    ///
    /// `onBack` was removed when the Library header swapped from a
    /// back chevron to the calendar / view-mode control pill;
    /// Library is a tab destination, so back-stack pops happen via
    /// the system back gesture and the Home tab callback, not an
    /// in-header arrow. `onHome` defaults to a no-op here for the
    /// same reason — callers are expected to wire it explicitly.
    onHome: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onScan: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val captureDao = remember(app) { app.database.captureDao() }
    val categoryDao = remember(app) { app.database.categoryDao() }
    val ocrResultDao = remember(app) { app.database.ocrResultDao() }

    val captures by remember(userId, captureDao) {
        captureDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    val categories by remember(userId, categoryDao) {
        categoryDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    // Preload first-page OCR snippets in one Flow so cards render in
    // their final state — no per-card LaunchedEffect, no mid-screen
    // swap from image to handwritten overlay.
    val ocrSnippetRows by remember(userId, ocrResultDao) {
        ocrResultDao.observeFirstSnippetsForUser(userId)
    }.collectAsState(initial = emptyList())

    val ocrSnippetByCapture: Map<String, String?> = remember(ocrSnippetRows) {
        ocrSnippetRows.associate { it.captureId to it.text }
    }

    var viewMode by remember { mutableStateOf(ViewMode.Grid) }
    var activeCategory by remember { mutableStateOf("All") }

    // Date-range filter — replaces the previous sort affordance.
    // Both endpoints are epoch millis at midnight UTC (the format
    // Material3's DateRangePicker hands back). `null` on either side
    // means "no filter on that side", so:
    //   (null, null) → show all
    //   (start, null) → from `start` onward
    //   (null, end) → up to and including `end` (end-of-day)
    //   (start, end) → both bounds, end widened to end-of-day.
    var dateRangeStart by remember { mutableStateOf<Long?>(null) }
    var dateRangeEnd by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Hoist the picker state up here so it's created once on screen
    // entry, not on every sheet open. Re-creating it inside the
    // sheet caused a perceptible delay because Material has to
    // allocate ~365 day-cell composables. With state hoisted, the
    // first open warms the cache; subsequent opens are near-instant.
    val datePickerState = rememberQuickInkDateRangePickerState(
        initialStart = dateRangeStart,
        initialEnd   = dateRangeEnd,
    )

    // Lazy window — only this many of the filtered captures are
    // exposed to the LazyColumn at a time. Grows on scroll-to-bottom.
    var visibleLimit by remember { mutableStateOf(LIBRARY_PAGE_SIZE) }

    // Reset the window when the filter changes — switching categories
    // or date range should always start at the top with a fresh page.
    LaunchedEffect(activeCategory, dateRangeStart, dateRangeEnd) {
        visibleLimit = LIBRARY_PAGE_SIZE
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val filteredSorted = remember(captures, activeCategory, dateRangeStart, dateRangeEnd) {
        val byCategory = if (activeCategory == "All") {
            captures
        } else {
            val needle = activeCategory.lowercase()
            captures.filter { (it.category ?: "").lowercase() == needle }
        }
        if (dateRangeStart == null && dateRangeEnd == null) return@remember byCategory  // DAO already returns newest-first; nothing further to do.

        byCategory.filter { isWithinPickedDateRange(it.createdAt, dateRangeStart, dateRangeEnd) }
    }

    val visibleCaptures = remember(filteredSorted, visibleLimit) {
        filteredSorted.take(visibleLimit)
    }

    val buckets = remember(visibleCaptures) { bucketByDate(visibleCaptures) }

    val listState = rememberLazyListState()

    // Trip when the LazyColumn's last laid-out item is on screen —
    // that's "user has reached the bottom of what's currently shown".
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total > 0 && lastVisible >= total - 1
        }
    }

    // Expand the window when at the bottom. Keyed on filteredSorted.size
    // too so a sync that lands new captures while the user is parked
    // at the bottom still triggers an expansion.
    LaunchedEffect(isAtBottom, filteredSorted.size) {
        if (isAtBottom && visibleLimit < filteredSorted.size) {
            visibleLimit = (visibleLimit + LIBRARY_PAGE_SIZE)
                .coerceAtMost(filteredSorted.size)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarTop + QuickInkSpacing.s4),
        ) {
        // Header — "Library" title + sort/view control pill on the
        // right. Padding-only horizontal so the header content
        // visually aligns with the chips and grid below.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "Library",
                style    = type.pageTitle,
                color    = colors.ink,
                modifier = Modifier.weight(1f),
            )
            ControlPill(
                viewMode               = viewMode,
                dateRangeActive        = dateRangeStart != null || dateRangeEnd != null,
                onOpenDatePicker       = { showDatePicker = true },
                onViewModeChange       = { viewMode = it },
            )
        }

        // Sub-meta line: when a range is active we surface a small
        // "May 1 – May 4" readout and a clear button so the user can
        // see + cancel the filter without re-opening the picker.
        if (dateRangeStart != null || dateRangeEnd != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s5),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = formatDateRange(dateRangeStart, dateRangeEnd),
                    style = type.meta,
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    dateRangeStart = null
                    dateRangeEnd = null
                }) {
                    Text("Clear", style = type.meta, color = colors.accent)
                }
            }
        }

        // The picker dialog itself — opens when `showDatePicker` is
        // flipped to true via the calendar icon. Returning a
        // (start, end) pair commits both endpoints atomically.
        if (showDatePicker) {
            DateRangePickerSheet(
                state     = datePickerState,
                onDismiss = { showDatePicker = false },
                onConfirm = { start, end ->
                    dateRangeStart = start
                    dateRangeEnd = end
                },
            )
        }

        // Subtitle — count first, then a TODO sync-byte readout. The
        // size sum needs a `SUM(file_size)` column on captures that
        // doesn't exist yet; landing the row visually now keeps the
        // future wiring drop-in.
        Text(
            text  = "${captures.size} notes",
            style = type.meta,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s1),
        )

        Spacer(Modifier.height(QuickInkSpacing.s3))

        // Filter chips — horizontal scroll, "All" first, then live
        // categories. No count badges in the new design — the grid
        // itself is the affordance for "how much is in here".
        CategoryChipRow(
            categories     = categories.map { it.name },
            activeCategory = activeCategory,
            onSelect       = { activeCategory = it },
        )

        Spacer(Modifier.height(QuickInkSpacing.s4))

        if (buckets.isEmpty()) {
            EmptyState(activeCategory)
        } else {
            LazyColumn(
                state               = listState,
                modifier            = Modifier.fillMaxSize(),
                // Reserve [QuickInkBottomNavReservedHeight] at the
                // bottom so the last grid row clears the floating
                // nav bar; without this, the bar covers the final
                // card on a scroll-to-end.
                contentPadding      = PaddingValues(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    bottom = QuickInkBottomNavReservedHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
            ) {
                buckets.forEach { bucket ->
                    item(key = "header-${bucket.title}") {
                        Text(
                            text  = bucket.title,
                            style = type.eyebrow,
                            color = colors.muted,
                        )
                    }

                    if (viewMode == ViewMode.Grid) {
                        // Two-column grid by chunking into pairs.
                        // LazyColumn + LazyVerticalGrid in one scroll
                        // is awkward, so we hand-roll the pairing.
                        bucket.items.chunked(2).forEachIndexed { idx, pair ->
                            item(key = "grid-${bucket.title}-$idx-${pair.first().id}") {
                                Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                                    pair.forEach { capture ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            LibraryNoteCard(
                                                capture    = capture,
                                                ocrSnippet = ocrSnippetByCapture[capture.id],
                                                onTap      = { onOpenScan(capture.id) },
                                            )
                                        }
                                    }
                                    if (pair.size == 1) {
                                        // Keep the lone card in the
                                        // left column; the right slot
                                        // is empty space, not a
                                        // stretched card.
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    } else {
                        items(bucket.items, key = { "list-${bucket.title}-${it.id}" }) { capture ->
                            LibraryScanListRow(
                                capture = capture,
                                onTap   = { onOpenScan(capture.id) },
                            )
                        }
                    }
                }
            }
        }
        }

        // Floating bottom nav — Library tab is active; tapping it is
        // a no-op (we're already here). The other callbacks switch
        // tabs; the FAB launches the scanner.
        QuickInkBottomNavBar(
            activeTab  = NavTab.Library,
            onHome     = onHome,
            onLibrary  = onLibrary,
            onScan     = onScan,
            onSearch   = onSearch,
            onSettings = onSettings,
            modifier   = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Header control pill — date-range filter + grid/list segmented toggle
// ────────────────────────────────────────────────────────────────────

/**
 * Two trailing controls on the header: a circular calendar button on
 * the left (opens the date-range picker dialog) and a segmented
 * grid/list pill on the right. The active half of the segment paints
 * coral; the inactive half stays cream. When a date range is active
 * the calendar button paints coral too — the user gets a clear
 * "filter is on" signal without having to open the picker.
 *
 * Replaced the previous newest/oldest sort affordance — the underlying
 * captures list always comes back from the DAO in `created_at DESC`
 * order, so user control over sort direction added little value
 * compared to a date-range filter.
 */
@Composable
private fun ControlPill(
    viewMode: ViewMode,
    dateRangeActive: Boolean,
    onOpenDatePicker: () -> Unit,
    onViewModeChange: (ViewMode) -> Unit,
) {
    val colors = LocalQuickInkColors.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Date-range filter — circular calendar button. Paints coral
        // when the filter is active so the affordance doubles as a
        // status indicator.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (dateRangeActive) colors.accent else colors.surface)
                .border(1.dp, if (dateRangeActive) colors.accent else colors.border, CircleShape)
                .clickable(onClick = onOpenDatePicker),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.CalendarMonth,
                contentDescription = "Filter by date",
                tint              = if (dateRangeActive) colors.textOnAccent else colors.ink,
                modifier          = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s2))

        // Grid/list segmented pill.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill)),
        ) {
            SegmentButton(
                icon     = Icons.Filled.GridView,
                label    = "Grid",
                active   = viewMode == ViewMode.Grid,
                onClick  = { onViewModeChange(ViewMode.Grid) },
            )
            SegmentButton(
                icon     = Icons.AutoMirrored.Filled.List,
                label    = "List",
                active   = viewMode == ViewMode.List,
                onClick  = { onViewModeChange(ViewMode.List) },
            )
        }
    }
}


@Composable
private fun SegmentButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(if (active) colors.accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = label,
            tint              = if (active) colors.textOnAccent else colors.ink,
            modifier          = Modifier.size(16.dp),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Category chip row
// ────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryChipRow(
    categories: List<String>,
    activeCategory: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = QuickInkSpacing.s5),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Chip(label = "All", active = activeCategory == "All", onClick = { onSelect("All") })
        categories.forEach { cat ->
            Chip(label = cat, active = cat == activeCategory, onClick = { onSelect(cat) })
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(if (active) colors.accent else colors.surface)
            .border(1.dp, if (active) Color.Transparent else colors.border, RoundedCornerShape(QuickInkRadius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
    ) {
        Text(
            text  = label,
            style = type.label,
            color = if (active) colors.textOnAccent else colors.ink,
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Date bucketing — TODAY / THIS WEEK / EARLIER
// ────────────────────────────────────────────────────────────────────

private data class Bucket(val title: String, val items: List<CaptureEntity>)

/**
 * Bucket [captures] into TODAY / THIS WEEK / EARLIER. Empty buckets
 * are dropped so we never render a header with nothing under it.
 * "This week" follows the user's locale (Sunday- vs Monday-start).
 */
private fun bucketByDate(captures: List<CaptureEntity>): List<Bucket> {
    val today = LocalDate.now(ZoneId.systemDefault())
    val weekFields = WeekFields.of(Locale.getDefault())
    val weekOfYear = weekFields.weekOfWeekBasedYear()

    val tToday = mutableListOf<CaptureEntity>()
    val tWeek  = mutableListOf<CaptureEntity>()
    val tElse  = mutableListOf<CaptureEntity>()

    for (capture in captures) {
        val date = parseDateOrNull(capture.createdAt)
        when {
            date == null              -> tElse.add(capture)
            date == today             -> tToday.add(capture)
            sameWeek(date, today, weekOfYear) -> tWeek.add(capture)
            else                      -> tElse.add(capture)
        }
    }

    val out = mutableListOf<Bucket>()
    if (tToday.isNotEmpty()) out += Bucket("TODAY",     tToday)
    if (tWeek.isNotEmpty())  out += Bucket("THIS WEEK", tWeek)
    if (tElse.isNotEmpty())  out += Bucket("EARLIER",   tElse)
    return out
}

private fun sameWeek(date: LocalDate, ref: LocalDate, weekField: java.time.temporal.TemporalField): Boolean {
    if (date == ref) return false // today gets its own bucket
    return date.year == ref.year && date.get(weekField) == ref.get(weekField)
}

private fun parseDateOrNull(iso: String): LocalDate? = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
} catch (_: Exception) {
    null
}

// ────────────────────────────────────────────────────────────────────
// Library note card (grid)
// ────────────────────────────────────────────────────────────────────

/**
 * Editorial note card matching the Library mock:
 *   • Image-first preview — when a `previewUri` exists, the actual
 *     scan photo wins (inset on a paper-tone matte, thin border, and
 *     a "Scan" badge so it reads as a photographed page). The
 *     handwritten OCR snippet only appears as a placeholder while
 *     the image decode is in flight.
 *   • Image absent, OCR present — full-bleed lined paper with the
 *     OCR snippet (we have nothing better to show).
 *   • Image absent, OCR absent — centered "Preview unavailable"
 *     error state on a soft-border background.
 *   • Small "Np" page-count chip in the top-right, only when
 *     `pageCount > 1`.
 *   • White footer with serif title (first OCR line truncated, or
 *     the category, or "Untitled scan") + accent-soft category tag
 *     + relative date.
 *
 * [ocrSnippet] is preloaded at the screen level so the card lands
 * in its final state on first frame instead of swapping when a
 * per-card LaunchedEffect resolves.
 */
@Composable
private fun LibraryNoteCard(
    capture: CaptureEntity,
    ocrSnippet: String?,
    onTap: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    val hasOcr = !ocrSnippet.isNullOrBlank()
    val hasImage = !capture.previewUri.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp),
        ) {
            when {
                hasImage -> {
                    // Image is the primary state. SubcomposeAsyncImage
                    // surfaces explicit loading/error slots; the lined
                    // paper + OCR/placeholder is only visible until the
                    // photo decodes (or permanently if it errors).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.paper(capture.id.hashCode()))
                            .padding(QuickInkSpacing.s2),
                    ) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(Uri.parse(capture.previewUri))
                                .crossfade(true)
                                .build(),
                            contentDescription = capture.displayTitle(),
                            contentScale       = ContentScale.Crop,
                            loading = {
                                LinedPaperPreview(
                                    seed       = capture.id.hashCode(),
                                    text       = ocrSnippet?.takeIf { it.isNotBlank() }
                                        ?: "Loading scan…",
                                    handwritten = hasOcr,
                                )
                            },
                            error = {
                                // Image URI exists but decode failed —
                                // fall back to OCR text if we have it,
                                // otherwise an explicit error message.
                                LinedPaperPreview(
                                    seed       = capture.id.hashCode(),
                                    text       = ocrSnippet?.takeIf { it.isNotBlank() }
                                        ?: "Preview unavailable",
                                    handwritten = hasOcr,
                                )
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(QuickInkRadius.sm))
                                .border(
                                    width = 1.dp,
                                    color = colors.border,
                                    shape = RoundedCornerShape(QuickInkRadius.sm),
                                ),
                        )
                    }
                }
                hasOcr -> {
                    // No image to show, but we have OCR — handwritten
                    // overlay is the best content we've got.
                    LinedPaperPreview(
                        seed       = capture.id.hashCode(),
                        text       = ocrSnippet!!,
                        handwritten = true,
                    )
                }
                else -> {
                    // Neither image nor OCR — explicit error state so
                    // the card doesn't masquerade as a usable preview.
                    Box(
                        modifier         = Modifier
                            .fillMaxSize()
                            .background(colors.borderSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.Description,
                                contentDescription = null,
                                tint               = colors.muted,
                                modifier           = Modifier.size(28.dp),
                            )
                            Text(
                                text  = "Preview unavailable",
                                style = type.caption,
                                color = colors.muted,
                            )
                        }
                    }
                }
            }

            if (hasImage) {
                val isImport = capture.source == "import"
                val badgeIcon = if (isImport) Icons.Filled.Image else Icons.Filled.PhotoCamera
                val badgeLabel = if (isImport) "Import" else "Scan"
                val badgeBg = if (isImport) colors.accent else colors.surface.copy(alpha = 0.9f)
                val badgeFg = if (isImport) colors.textOnAccent else colors.ink.copy(alpha = 0.7f)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(QuickInkSpacing.s2)
                        .background(
                            color = badgeBg,
                            shape = RoundedCornerShape(QuickInkRadius.sm),
                        )
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector        = badgeIcon,
                        contentDescription = null,
                        tint               = badgeFg,
                        modifier           = Modifier.size(12.dp),
                    )
                    Text(
                        text  = badgeLabel,
                        style = type.caption,
                        color = badgeFg,
                    )
                }
            }

            if (capture.pageCount > 1) {
                Text(
                    text  = "${capture.pageCount}p",
                    style = type.caption,
                    color = colors.ink.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(QuickInkSpacing.s2)
                        .background(
                            color = colors.surface.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(QuickInkRadius.sm),
                        )
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 2.dp),
                )
            }
        }

        // White footer — title + category tag + relative date.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(QuickInkSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Text(
                // Title Case: per-word first letter upper, rest
                // lower. Normalises whatever case the OCR / user
                // supplied — "personal information",
                // "PERSONAL INFORMATION", "Personal information"
                // all render as "Personal Information". Compose
                // has no text-transform on TextStyle, so the
                // transform happens on the string here. Word
                // boundary is whitespace, which is fine for note
                // titles; if a title contains "iPhone" or similar,
                // it'll come out "Iphone" — accept that for now,
                // OCR rarely surfaces brand-cased terms in the
                // first line.
                text     = displayedTitle(capture, ocrSnippet)
                    .split(' ')
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar(Char::titlecaseChar)
                    },
                // Library card titles render in Inter (UI sans), not
                // the editorial Fraunces. Card titles are functional
                // (scannable, dense grid) — the editorial serif felt
                // precious for the file-list context.
                style    = type.heading.copy(
                    fontFamily = QuickInkFonts.ui,
                    fontSize   = 14.sp,
                ),
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cat = capture.category
                if (!cat.isNullOrEmpty()) {
                    Text(
                        text  = cat,
                        style = type.caption,
                        color = colors.accent,
                        modifier = Modifier
                            .background(colors.accentSoft, RoundedCornerShape(QuickInkRadius.sm))
                            .padding(horizontal = QuickInkSpacing.s2, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text  = relativeDate(capture.createdAt),
                    style = type.caption,
                    color = colors.muted,
                )
            }
        }
    }
}

/**
 * Library card title cascade: (1) the user-set [CaptureEntity.title]
 * (trimmed, non-empty) — explicit user intent always wins;
 * (2) the OCR snippet's first line (≤40 chars) — gives the wall a
 * handwritten preview when the user hasn't titled the scan;
 * (3) the category; (4) "Untitled scan".
 */
private fun displayedTitle(capture: CaptureEntity, ocrSnippet: String?): String {
    val titled = capture.title?.trim().orEmpty()
    if (titled.isNotEmpty()) return titled
    val firstLine = ocrSnippet
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }
    if (!firstLine.isNullOrEmpty()) return firstLine.take(40)
    if (!capture.category.isNullOrEmpty()) return capture.category!!
    return "Untitled scan"
}

/**
 * Lined-paper preview slot used in two places:
 *   • SubcomposeAsyncImage's loading/error slots — a transient
 *     placeholder behind the photo while it decodes (or a fallback
 *     when the URI exists but decode fails).
 *   • Image-absent path — the final rendered preview when there's
 *     no `previewUri` but we have OCR text to show.
 *
 * [handwritten] toggles the italic Caveat treatment when [text] is
 * actual OCR content, vs. a flatter caption-style treatment for
 * placeholder/error strings (e.g., "Loading scan…",
 * "Preview unavailable").
 */
@Composable
private fun LinedPaperPreview(
    seed: Int,
    text: String,
    handwritten: Boolean,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .quickInkLinedPaper(seed = seed),
    ) {
        Text(
            text  = text,
            style = if (handwritten) {
                type.handwritten.copy(fontStyle = FontStyle.Italic)
            } else {
                type.caption
            },
            color = colors.ink.copy(alpha = if (handwritten) 0.78f else 0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = QuickInkSpacing.s3,
                    end   = QuickInkSpacing.s3,
                    top   = QuickInkSpacing.s3,
                ),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Library list row (used when viewMode == List)
// ────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryScanListRow(capture: CaptureEntity, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 72.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.sm)),
        ) {
            val previewUri = capture.previewUri
            if (previewUri.isNullOrBlank()) {
                Box(
                    modifier         = Modifier.fillMaxSize().background(colors.borderSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector       = Icons.Filled.Description,
                        contentDescription = null,
                        tint              = colors.muted,
                        modifier          = Modifier.size(16.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(previewUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.size(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                // Title Case — same normalisation as the grid view
                // card title (split on whitespace, lowercase the
                // word, then titlecase the first char). Keeps the
                // grid and list views visually consistent.
                text     = capture.displayTitle()
                    .split(' ')
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar(Char::titlecaseChar)
                    },
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (capture.source == "import") "Import" else "Scan",
                    style = type.caption,
                    color = if (capture.source == "import") colors.textOnAccent else colors.muted,
                    modifier = Modifier
                        .background(
                            color = if (capture.source == "import") colors.accent else colors.borderSoft,
                            shape = RoundedCornerShape(QuickInkRadius.sm),
                        )
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 1.dp),
                )
                Text(text = relativeDate(capture.createdAt), style = type.caption, color = colors.muted)
                if (capture.pageCount > 1) {
                    Text("• ${capture.pageCount} pages", style = type.caption, color = colors.muted)
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Empty state
// ────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(activeCategory: String) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier             = Modifier.fillMaxSize().padding(QuickInkSpacing.s7),
        verticalArrangement  = Arrangement.Center,
        horizontalAlignment  = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Description,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text(
            text  = if (activeCategory == "All") "Your library is empty" else "No $activeCategory scans yet",
            style = type.heading,
            color = colors.ink,
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = if (activeCategory == "All")
                "Tap the ⚡ on Home to capture your first page."
            else
                "Scans you tag $activeCategory on the review screen will collect here.",
            style = type.body,
            color = colors.inkSoft,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Date formatting
// ────────────────────────────────────────────────────────────────────

/**
 * "Today" / "Yesterday" / weekday for the current week / `MMM d`
 * otherwise. Used in the card footer for compact relative dates,
 * matching the mock's "Today / May 1 / Apr 28" treatment.
 */
private fun relativeDate(iso: String): String {
    val date = parseDateOrNull(iso) ?: return iso.take(10)
    val today = LocalDate.now(ZoneId.systemDefault())
    return when {
        date == today                         -> "Today"
        date == today.minusDays(1)            -> "Yesterday"
        date.isAfter(today.minusDays(7))      -> date.format(DateTimeFormatter.ofPattern("EEE"))
        else                                  -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
