/*
 * SearchScreen.kt
 *
 * QuickInk's Search surface — captures-first search:
 *   - Empty query → timeline of all the user's captures, newest
 *     first, grouped under "28TH APR, 2026" day headers.
 *   - Non-empty query → a list of capture cards. Each card surfaces
 *     either a category-substring match or the OCR snippet (with
 *     the matched span emphasised) that pulled it in.
 *
 * Tap → `ScanDetailScreen`. The notepad-entries-driven timeline
 * was retired alongside Library — captures are the canonical
 * artifact users browse and search.
 *
 * OCR text matches go through `fts_ocr_text` MATCH via
 * `CaptureRepository.search`. Searching debounces ~250ms while
 * typing so we don't spam the FTS engine on every keystroke.
 *
 * Mirror of iOS `SearchScreen.swift`.
 */

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SearchScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenScan: (captureId: String) -> Unit,
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

    val captures by captureDao.observeActive(userId).collectAsState(initial = emptyList())

    var queryDraft by remember { mutableStateOf("") }
    var liveQuery by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    // Debounced search runner.
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

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint              = colors.ink,
                )
            }
            OutlinedTextField(
                value         = queryDraft,
                onValueChange = { queryDraft = it },
                modifier      = Modifier.weight(1f),
                placeholder   = { Text("Search scans & OCR text", style = type.body, color = colors.muted) },
                leadingIcon   = {
                    Icon(
                        imageVector       = Icons.Filled.Search,
                        contentDescription = null,
                        tint              = colors.muted,
                        modifier          = Modifier.size(18.dp),
                    )
                },
                trailingIcon  = {
                    if (queryDraft.isNotEmpty()) {
                        IconButton(onClick = { queryDraft = "" }) {
                            Icon(
                                imageVector       = Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint              = colors.muted,
                                modifier          = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape         = RoundedCornerShape(QuickInkRadius.pill),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = colors.border,
                    unfocusedBorderColor = colors.border,
                ),
            )
        }

        Divider(color = colors.border, thickness = 1.dp)

        when {
            liveQuery.isEmpty() -> TimelineView(captures = captures, onOpen = onOpenScan)
            isSearching && hits.isEmpty() -> LoadingState()
            hits.isEmpty()      -> NoMatchesState()
            else                -> ResultsView(hits = hits, query = liveQuery, onOpen = onOpenScan)
        }
    }
}

@Composable
private fun TimelineView(captures: List<CaptureEntity>, onOpen: (String) -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    if (captures.isEmpty()) {
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
            Text("Nothing to search yet", style = type.heading, color = colors.ink)
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text  = "Capture a scan from Home and search by category or OCR text.",
                style = type.body,
                color = colors.inkSoft,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val grouped = remember(captures) {
        captures.sortedByDescending { it.createdAt }.groupBy { dayKey(it.createdAt) }
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(
            start  = QuickInkSpacing.s5,
            end    = QuickInkSpacing.s5,
            top    = QuickInkSpacing.s4,
            bottom = QuickInkSpacing.s7,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
    ) {
        grouped.forEach { (day, dayCaptures) ->
            item(key = "day-$day") {
                Text(
                    text  = formatDayHeader(day),
                    style = type.eyebrow,
                    color = colors.muted,
                )
            }
            items(dayCaptures, key = { "tl-${it.id}" }) { capture ->
                TimelineRow(capture = capture, onClick = { onOpen(capture.id) })
            }
        }
    }
}

@Composable
private fun TimelineRow(capture: CaptureEntity, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbnailBox(previewUri = capture.previewUri)
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = capture.category ?: "Scan",
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                Text(text = formatTime(capture.createdAt), style = type.caption, color = colors.muted)
                if (capture.pageCount > 1) {
                    Text("• ${capture.pageCount} pages", style = type.caption, color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun ResultsView(hits: List<SearchHit>, query: String, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(
            start  = QuickInkSpacing.s5,
            end    = QuickInkSpacing.s5,
            top    = QuickInkSpacing.s4,
            bottom = QuickInkSpacing.s7,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        items(hits, key = { "hit-${it.capture.id}" }) { hit ->
            SearchResultCard(hit = hit, query = query, onClick = { onOpen(hit.capture.id) })
        }
    }
}

@Composable
private fun SearchResultCard(hit: SearchHit, query: String, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThumbnailBox(previewUri = hit.capture.previewUri)
            Spacer(Modifier.size(QuickInkSpacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = hit.capture.category ?: "Scan",
                    style    = type.label,
                    color    = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text  = if (hit.ocrSnippet.isNullOrBlank()) "Category match" else "OCR match",
                    style = type.caption,
                    color = colors.muted,
                )
            }
        }
        if (!hit.ocrSnippet.isNullOrBlank()) {
            Text(
                text     = highlightedSnippet(hit.ocrSnippet, query, colors.accent),
                style    = type.body,
                color    = colors.inkSoft,
                modifier = Modifier.padding(horizontal = QuickInkSpacing.s2),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThumbnailBox(previewUri: String?) {
    val colors = LocalQuickInkColors.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 72.dp)
            .clip(RoundedCornerShape(QuickInkRadius.sm))
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.sm)),
    ) {
        if (previewUri.isNullOrBlank()) {
            Box(
                modifier         = Modifier.fillMaxSize().background(colors.borderSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Description,
                    contentDescription = null,
                    tint              = colors.muted,
                    modifier          = Modifier.size(14.dp),
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

// MARK: - Helpers

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

private fun dayKey(iso: String): String = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
} catch (_: Exception) {
    iso.take(10)
}

private fun formatDayHeader(yyyymmdd: String): String = try {
    val date = LocalDate.parse(yyyymmdd)
    val day = date.dayOfMonth
    val suffix = ordinalSuffix(day)
    val monthYear = date.format(DateTimeFormatter.ofPattern("MMM, yyyy")).uppercase(Locale.getDefault())
    "$day$suffix $monthYear"
} catch (_: Exception) {
    yyyymmdd
}

private fun ordinalSuffix(day: Int): String {
    val mod100 = day % 100
    if (mod100 in 11..13) return "TH"
    return when (day % 10) {
        1    -> "ST"
        2    -> "ND"
        3    -> "RD"
        else -> "TH"
    }
}

private fun formatTime(iso: String): String = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))
} catch (_: Exception) {
    ""
}
