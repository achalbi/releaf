/*
 * SearchScreen.kt
 *
 * QuickInk's Search surface — captures-first search, redesigned to
 * match the editorial mock:
 *
 *   • Top bar — circular back button + rounded search input. The
 *     input has a leading magnifier glyph and a trailing clear (✕)
 *     button while the user is typing. Submitting the query (IME
 *     "Search" / Enter) commits it to the recent-searches MRU list.
 *   • Result-count strip — "{n} results · across titles + content"
 *     on the left, a Filter affordance on the right (sliders icon).
 *     The filter is a future surface; the affordance is rendered so
 *     the layout matches the mock and lands later as a single edit.
 *   • Sectioned results:
 *       - IN PAGE CONTENT — full-width cards per OCR match, with a
 *         lined-paper thumbnail, title, date, and the OCR snippet
 *         with the matched query highlighted in coral. Comes from
 *         `CaptureRepository.search` hits whose `ocrSnippet != null`.
 *       - IN TITLES — compact icon rows for category-name matches.
 *         Comes from the same search call's hits where `ocrSnippet
 *         is null` (category-substring branch). Captures don't have
 *         a true "title" column today; the category is the closest
 *         human-readable label, so it's what we surface here.
 *   • Recent searches — pill row at the bottom (when query is empty
 *     or missing). Backed by `SettingsPreferences.recentSearches`.
 *     Tapping a pill replays the search.
 *
 * Search itself goes through `fts_ocr_text` MATCH via
 * `CaptureRepository.search`. Searching debounces 250ms while the
 * user types so we don't spam the FTS engine on every keystroke.
 *
 * Mirror of iOS `SearchScreen.swift`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.search

import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.capture.SearchHit
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.ui.components.DateRangePickerSheet
import app.quickink.mobile.ui.components.isWithinPickedDateRange
import app.quickink.mobile.ui.components.formatDateRange
import app.quickink.mobile.ui.components.rememberQuickInkDateRangePickerState
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SearchScreen(
    userId: String,
    onOpenScan: (captureId: String) -> Unit,
    /// Tab navigation callbacks for the floating bottom nav. The
    /// Search tab paints itself active; tapping it is a no-op (we're
    /// already here). `onHome` previously defaulted to `onBack`,
    /// but now that the back arrow is replaced by the date-range
    /// filter, callers must wire the Home callback explicitly.
    onHome: () -> Unit = {},
    onWorkspace: () -> Unit = {},
    onScan: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val captureDao = remember(app) { app.database.captureDao() }
    val repository = remember(app) {
        CaptureRepository(
            captureDao   = captureDao,
            ocrResultDao = app.database.ocrResultDao(),
        )
    }
    val preferences = remember { SettingsPreferences(context) }

    val captures by captureDao.observeActive(userId).collectAsState(initial = emptyList())

    var queryDraft by remember { mutableStateOf("") }
    var liveQuery by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Date-range filter — same model as Library. `null/null` means
    // no range applied (show every hit). Both endpoints are epoch
    // millis at midnight UTC (the format `DateRangePickerSheet`
    // hands back).
    var dateRangeStart by remember { mutableStateOf<Long?>(null) }
    var dateRangeEnd by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Hoist the picker state up here so it's created once on screen
    // entry rather than on every sheet open. Re-creating inside the
    // sheet caused a perceptible delay because Material has to
    // allocate ~365 day-cell composables. Hoisting moves the cost
    // to first frame (where the user can't notice it) and makes
    // subsequent opens near-instant.
    val datePickerState = rememberQuickInkDateRangePickerState(
        initialStart = dateRangeStart,
        initialEnd   = dateRangeEnd,
    )
    // Recent-searches list mirrors a SharedPreferences-backed
    // store. We hold it as @State so a successful pushRecentSearch
    // re-renders the pill row without a Flow on top of prefs.
    var recentSearches by remember { mutableStateOf(preferences.recentSearches) }

    // Debounced search runner — same pattern as the prior surface.
    LaunchedEffect(queryDraft) {
        val draft = queryDraft.trim()
        if (draft.isEmpty()) {
            liveQuery = ""
            hits = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        delay(250)
        if (queryDraft.trim() != draft) return@LaunchedEffect
        liveQuery = draft
        isSearching = true
        try {
            hits = repository.search(userId, draft)
        } catch (_: Exception) {
            hits = emptyList()
        } finally {
            isSearching = false
        }
    }

    /// Commit the current draft to the recent-searches MRU. Called
    /// when the user taps the IME "Search" action OR taps a result
    /// (the latter signals "this query was useful enough to keep").
    fun commitToRecents(query: String = queryDraft) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        preferences.pushRecentSearch(trimmed)
        recentSearches = preferences.recentSearches
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarTop + QuickInkSpacing.s4),
        ) {
        TopBar(
            queryDraft       = queryDraft,
            onQueryChange    = { queryDraft = it },
            onClear          = { queryDraft = "" },
            onSubmit         = { commitToRecents() },
            dateRangeActive  = dateRangeStart != null || dateRangeEnd != null,
            onOpenDatePicker = { showDatePicker = true },
        )

        // Active-range chip — surfaces the picked range and a Clear
        // affordance below the input. Mirrors the Library pattern.
        if (dateRangeStart != null || dateRangeEnd != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = QuickInkSpacing.s5,
                        end    = QuickInkSpacing.s5,
                        bottom = QuickInkSpacing.s2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = formatDateRange(dateRangeStart, dateRangeEnd),
                    style    = type.meta,
                    color    = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text  = "Clear",
                    style = type.meta,
                    color = colors.accent,
                    modifier = Modifier.clickable {
                        dateRangeStart = null
                        dateRangeEnd = null
                    },
                )
            }
        }

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

        // Date filter — applied to both the live search hits and the
        // empty-query "RECENT NOTES" list so the calendar control
        // affects every visible row. `isWithinPickedDateRange` does
        // the local-date conversion so a capture's calendar day
        // matches what the user sees in the picker, regardless of UTC
        // offset.
        val filteredHits = remember(hits, dateRangeStart, dateRangeEnd) {
            if (dateRangeStart == null && dateRangeEnd == null) return@remember hits
            hits.filter { isWithinPickedDateRange(it.capture.createdAt, dateRangeStart, dateRangeEnd) }
        }

        val filteredCaptures = remember(captures, dateRangeStart, dateRangeEnd) {
            if (dateRangeStart == null && dateRangeEnd == null) return@remember captures
            captures.filter { isWithinPickedDateRange(it.createdAt, dateRangeStart, dateRangeEnd) }
        }

        if (liveQuery.isNotEmpty()) {
            ResultCountStrip(
                resultCount = filteredHits.size,
                isSearching = isSearching,
            )
        }

        when {
            // Empty query — show recent searches + a captures
            // timeline preview so the screen never goes blank.
            liveQuery.isEmpty() -> EmptyQueryView(
                recentSearches = recentSearches,
                onPickRecent   = { queryDraft = it },
                onClearRecents = {
                    preferences.clearRecentSearches()
                    recentSearches = emptyList()
                },
                captures = filteredCaptures,
                onOpen   = { id ->
                    commitToRecents()
                    onOpenScan(id)
                },
            )
            isSearching && filteredHits.isEmpty() -> LoadingState()
            filteredHits.isEmpty()                -> NoMatchesState()
            else                                  -> ResultsView(
                hits  = filteredHits,
                query = liveQuery,
                onOpen = { id ->
                    commitToRecents()
                    onOpenScan(id)
                },
            )
        }
        }

        // Floating bottom nav — Search tab is active.
        QuickInkBottomNavBar(
            activeTab  = NavTab.Search,
            onHome     = onHome,
            onWorkspace  = onWorkspace,
            onScan     = onScan,
            onSearch   = onSearch,
            onSettings = onSettings,
            modifier   = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Top bar — back button + rounded search input
// ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    queryDraft: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    dateRangeActive: Boolean,
    onOpenDatePicker: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        // Date-range filter — replaces the previous back arrow.
        // Search is a tab destination (reached via the bottom-nav
        // Search cell), so a back affordance here was redundant —
        // the tab swap is the back path. Same circular pill shape
        // as Library's calendar button; paints coral when a range
        // is active, doubling as a status indicator.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (dateRangeActive) colors.accent else colors.surface)
                .border(
                    1.dp,
                    if (dateRangeActive) colors.accent else colors.border,
                    CircleShape,
                )
                .clickable(onClick = onOpenDatePicker),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.CalendarMonth,
                contentDescription = "Filter by date",
                tint              = if (dateRangeActive) colors.textOnAccent else colors.ink,
                modifier          = Modifier.size(18.dp),
            )
        }

        // Search input — rounded white pill, leading magnifier and
        // trailing X. Hand-rolled with `BasicTextField` rather than
        // Material's `OutlinedTextField` because the latter forces
        // a label/floating-text frame that fights the pill shape.
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.surface)
                .border(1.5.dp, colors.accent, RoundedCornerShape(QuickInkRadius.pill))
                .padding(horizontal = QuickInkSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector       = Icons.Filled.Search,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(18.dp),
            )

            BasicTextField(
                value           = queryDraft,
                onValueChange   = onQueryChange,
                textStyle       = type.body.copy(color = colors.ink),
                cursorBrush     = SolidColor(colors.accent),
                singleLine      = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                // `fillMaxHeight` so the text field stretches to the
                // pill's 40dp height (set on the parent Row), giving
                // the decorationBox below a real height to centre
                // against. Without this the BasicTextField's
                // intrinsic height is just the text-line height
                // (~16dp for `meta`, ~24dp for `body`) and any
                // child trying to centre vertically only centres
                // against that small box, leaving the placeholder
                // hugging the top of the visible pill.
                modifier        = Modifier.weight(1f).fillMaxHeight(),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        // `fillMaxSize` (height + width) — width so
                        // the placeholder hits the start of the
                        // BasicTextField's slot, height so its
                        // CenterStart anchor lines up with the
                        // vertical centre of the 40dp pill.
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (queryDraft.isEmpty()) {
                            // Smaller `meta` rather than `body` so
                            // the placeholder reads as helper text,
                            // not a typed value. Same scale as the
                            // active-range chip directly below.
                            Text(
                                text  = "Search scans & OCR text",
                                style = type.meta,
                                color = colors.muted,
                            )
                        }
                        inner()
                    }
                },
            )

            if (queryDraft.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.borderSoft)
                        .clickable(onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector       = Icons.Filled.Close,
                        contentDescription = "Clear",
                        tint              = colors.inkSoft,
                        modifier          = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Result-count strip with Filter affordance
// ────────────────────────────────────────────────────────────────────

@Composable
private fun ResultCountStrip(resultCount: Int, isSearching: Boolean) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                isSearching && resultCount == 0 -> "Searching…"
                resultCount == 0                 -> "No results"
                else                             -> "$resultCount results · across titles + content"
            },
            style = type.meta,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clickable {
                    // TODO(filter): open filter sheet — categories,
                    // date range, page-count threshold.
                }
                .padding(QuickInkSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            Icon(
                imageVector       = Icons.Filled.Tune,
                contentDescription = "Filter",
                tint              = colors.ink,
                modifier          = Modifier.size(16.dp),
            )
            Text(text = "Filter", style = type.label, color = colors.ink)
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Empty-query view — recent searches + recent captures preview
// ────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyQueryView(
    recentSearches: List<String>,
    onPickRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    captures: List<CaptureEntity>,
    onOpen: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(
            start  = QuickInkSpacing.s5,
            end    = QuickInkSpacing.s5,
            top    = QuickInkSpacing.s3,
            bottom = QuickInkBottomNavReservedHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
    ) {
        if (recentSearches.isNotEmpty()) {
            item("recent-header") {
                SectionEyebrow(icon = Icons.Filled.AccessTime, label = "RECENT SEARCHES", trailing = {
                    Text(
                        text = "Clear",
                        style = type.caption,
                        color = colors.muted,
                        modifier = Modifier.clickable(onClick = onClearRecents),
                    )
                })
            }
            item("recent-pills") {
                RecentSearchPills(queries = recentSearches, onPick = onPickRecent)
            }
        }

        if (captures.isEmpty()) {
            item("empty") {
                EmptyTimelineState()
            }
        } else {
            item("timeline-header") {
                SectionEyebrow(icon = Icons.Filled.Description, label = "RECENT NOTES")
            }
            items(captures, key = { "tl-${it.id}" }) { capture ->
                CompactRow(
                    title    = capture.displayTitle("Untitled scan"),
                    subtitle = relativeDate(capture.createdAt) +
                        if (capture.pageCount > 1) " · ${capture.pageCount} pages" else "",
                    onClick  = { onOpen(capture.id) },
                )
            }
        }
    }
}

@Composable
private fun RecentSearchPills(queries: List<String>, onPick: (String) -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    // FlowRow would let pills wrap, but it's still experimental at
    // some Compose versions — emulate with a Column of Rows of up
    // to 3 pills each. Conservative and visually identical for the
    // capped recent list (max 10 items).
    val rows = queries.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        rows.forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                rowItems.forEach { q ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
                            .clickable { onPick(q) }
                            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
                    ) {
                        Text(text = q, style = type.label, color = colors.ink)
                    }
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────
// Results view — IN PAGE CONTENT (OCR) + IN TITLES (categories)
// ────────────────────────────────────────────────────────────────────

@Composable
private fun ResultsView(hits: List<SearchHit>, query: String, onOpen: (String) -> Unit) {
    val pageHits  = hits.filter { !it.ocrSnippet.isNullOrBlank() }
    val titleHits = hits.filter { it.ocrSnippet.isNullOrBlank() }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(
            start  = QuickInkSpacing.s5,
            end    = QuickInkSpacing.s5,
            top    = QuickInkSpacing.s3,
            bottom = QuickInkBottomNavReservedHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
    ) {
        if (pageHits.isNotEmpty()) {
            item("page-header") {
                SectionEyebrow(icon = Icons.AutoMirrored.Filled.Article, label = "IN PAGE CONTENT")
            }
            items(pageHits, key = { "p-${it.capture.id}" }) { hit ->
                PageContentResultCard(hit = hit, query = query, onClick = { onOpen(hit.capture.id) })
            }
        }

        if (titleHits.isNotEmpty()) {
            item("title-header") {
                SectionEyebrow(icon = Icons.AutoMirrored.Filled.MenuBook, label = "IN TITLES")
            }
            items(titleHits, key = { "t-${it.capture.id}" }) { hit ->
                TitleResultRow(hit = hit, onClick = { onOpen(hit.capture.id) })
            }
        }
    }
}

/**
 * Full-width card for an OCR-snippet match. Mirrors the mock's "IN
 * PAGE CONTENT" card: lined-paper thumbnail on the left, title +
 * date row on the top right, OCR snippet with the query highlighted
 * in coral underneath, and a category tag pinned to the bottom-left.
 */
@Composable
private fun PageContentResultCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val capture = hit.capture

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        // Lined-paper thumbnail — same posture as the Library
        // card's preview, just smaller. Distinguishes the result
        // visually from a generic image thumbnail.
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 80.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.sm))
                .quickInkLinedPaper(seed = capture.id.hashCode()),
        )

        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text     = capture.displayTitle("Untitled scan"),
                    style    = type.label,
                    color    = colors.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = relativeDate(capture.createdAt), style = type.caption, color = colors.muted)
            }

            Text(
                text     = highlightedSnippet(hit.ocrSnippet ?: "", query, colors.accent),
                style    = type.body,
                color    = colors.inkSoft,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            val cat = capture.category
            if (!cat.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .background(colors.accentSoft, RoundedCornerShape(QuickInkRadius.sm))
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 2.dp),
                ) {
                    Text(text = cat, style = type.caption, color = colors.accent)
                }
            }
        }
    }
}

/**
 * Compact row for a category-name match. Smaller leading icon, title
 * + secondary line, trailing chevron. Matches the mock's "IN TITLES"
 * row.
 */
@Composable
private fun TitleResultRow(hit: SearchHit, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val capture = hit.capture

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Description,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = capture.displayTitle("Untitled scan"),
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(capture.category, relativeDate(capture.createdAt))
                    .joinToString(" · "),
                style = type.caption,
                color = colors.muted,
            )
        }
        Icon(
            imageVector       = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(16.dp),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Compact row reused by the "RECENT NOTES" preview list
// ────────────────────────────────────────────────────────────────────

@Composable
private fun CompactRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Icon(
            imageVector       = Icons.Filled.AutoAwesomeMosaic,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            // Title Case — keeps search results visually consistent
            // with library grid + list rows. Per-word normalisation
            // (split on whitespace, lower then titlecase first char)
            // matches the helper used in NotesListScreen.kt.
            Text(
                text = title
                    .split(' ')
                    .joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar(Char::titlecaseChar)
                    },
                style = type.label,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = subtitle, style = type.caption, color = colors.muted)
        }
        Icon(
            imageVector       = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(14.dp),
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Section eyebrow — small icon + uppercase label, optional trailing
// ────────────────────────────────────────────────────────────────────

@Composable
private fun SectionEyebrow(
    icon: ImageVector,
    label: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(14.dp),
        )
        Text(
            text     = label,
            style    = type.eyebrow,
            color    = colors.muted,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// ────────────────────────────────────────────────────────────────────
// Empty / loading states
// ────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyTimelineState() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier             = Modifier.fillMaxWidth().padding(top = QuickInkSpacing.s6),
        verticalArrangement  = Arrangement.Center,
        horizontalAlignment  = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector       = Icons.Filled.Search,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(28.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text("Nothing to search yet", style = type.heading, color = colors.ink)
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Capture a scan from Home and search by category or OCR text.",
            style = type.body,
            color = colors.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingState() {
    val colors = LocalQuickInkColors.current
    Column(
        modifier             = Modifier.fillMaxSize(),
        verticalArrangement  = Arrangement.Center,
        horizontalAlignment  = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = colors.accent)
    }
}

@Composable
private fun NoMatchesState() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier             = Modifier.fillMaxSize().padding(QuickInkSpacing.s7),
        verticalArrangement  = Arrangement.Center,
        horizontalAlignment  = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector       = Icons.Filled.Search,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(32.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text("No matches", style = type.heading, color = colors.ink)
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Try a different word or check the spelling.",
            style = type.body,
            color = colors.inkSoft,
            textAlign = TextAlign.Center,
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

/// Render `snippet` with each whitespace-delimited query token
/// highlighted in accent + bold. Case-insensitive matching.
private fun highlightedSnippet(snippet: String, query: String, accent: Color): AnnotatedString {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return AnnotatedString(snippet)

    return buildAnnotatedString {
        var index = 0
        while (index < snippet.length) {
            val match = terms
                .mapNotNull { term ->
                    val pos = snippet.indexOf(term, startIndex = index, ignoreCase = true)
                    if (pos >= 0) pos to term else null
                }
                .minByOrNull { it.first }
            if (match == null) {
                append(snippet.substring(index))
                break
            }
            val (start, term) = match
            append(snippet.substring(index, start))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                append(snippet.substring(start, start + term.length))
            }
            index = start + term.length
        }
    }
}

private fun relativeDate(iso: String): String {
    val date = try {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (_: Exception) {
        return iso.take(10)
    }
    val today = LocalDate.now(ZoneId.systemDefault())
    return when {
        date == today                     -> "Today"
        date == today.minusDays(1)        -> "Yesterday"
        date.isAfter(today.minusDays(7))  -> date.format(DateTimeFormatter.ofPattern("EEE"))
        else                              -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
