/*
 * SearchScreen.kt
 *
 * Full-screen search surface — focused query input, grouped
 * results (Titles vs OCR Content), and a recent-searches chip
 * rail when the query is empty.
 *
 * Reads from `NotepadDao.searchActive(userId, query)` (FTS5)
 * directly via Compose state. Title-vs-content partitioning is
 * client-side over the union of FTS5 hits — the round-trip
 * already happened, so two passes here is free.
 *
 * Recent searches persist in SharedPreferences under
 * `quickink.search.recent` (JSON-encoded list, max 8 entries).
 *
 * Mirror of iOS `SearchScreen.swift`.
 */

package app.quickink.mobile.features.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.data.notepad.NotepadEntry
import androidx.compose.runtime.LaunchedEffect

private const val RECENTS_KEY    = "quickink.search.recent"
private const val RECENTS_LIMIT  = 8
private const val RECENTS_DELIM  = "" // Unit Separator — safe in user-typed strings

@Composable
fun SearchScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenEntry: (entryId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val scope = rememberCoroutineScope()
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val prefs = remember {
        context.getSharedPreferences("quickink.search", Context.MODE_PRIVATE)
    }

    var query by remember { mutableStateOf("") }
    var recents by remember { mutableStateOf(loadRecents(prefs)) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Search — observe all active entries via the DAO Flow and
    // filter client-side. The DAO does expose `searchActive` for
    // FTS5, but it takes a `RoomRawQuery` (not a (userId, query)
    // pair) and the FTS5 query construction is non-obvious without
    // direct access to the table+column names. For a personal-
    // notes app this client-side path is sub-millisecond on any
    // realistic dataset (a few thousand rows). Switch to the FTS5
    // path in a follow-up once the RoomRawQuery shape is settled.
    val dao = remember(app) { app.database.notepadDao() }
    val allEntries by dao.observeActive(userId).collectAsState(initial = emptyList())
    val hits by remember(query, allEntries) {
        mutableStateOf(
            run {
                val q = query.trim().lowercase()
                if (q.isEmpty()) emptyList()
                else allEntries.filter { entry ->
                    (entry.title?.lowercase()?.contains(q) == true) ||
                            entry.notes.lowercase().contains(q)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Search bar
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

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.borderSoft)
                    .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Search,
                    contentDescription = null,
                    tint              = colors.muted,
                    modifier          = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                BasicTextField(
                    value         = query,
                    onValueChange = { query = it },
                    textStyle     = type.body.copy(color = colors.ink),
                    cursorBrush   = SolidColor(colors.accent),
                    singleLine    = true,
                    modifier      = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        recents = commitRecent(prefs, query, recents)
                    }),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text  = "Search notes & OCR text",
                                style = type.body,
                                color = colors.muted,
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector       = Icons.Filled.Cancel,
                        contentDescription = "Clear",
                        tint              = colors.muted,
                        modifier          = Modifier
                            .size(18.dp)
                            .clickable { query = "" },
                    )
                }
            }
            Spacer(Modifier.size(QuickInkSpacing.s3))
        }

        Divider(color = colors.border, thickness = 1.dp)

        when {
            query.trim().isEmpty() -> RecentChipsView(
                recents  = recents,
                onTap    = { term -> query = term },
                onClear  = {
                    prefs.edit().remove(RECENTS_KEY).apply()
                    recents = emptyList()
                },
            )
            hits.isEmpty() -> EmptyResults()
            else -> ResultsList(
                query    = query,
                entries  = hits,
                onOpen   = { entryId ->
                    recents = commitRecent(prefs, query, recents)
                    onOpenEntry(entryId)
                },
            )
        }
    }
}

// MARK: - Recent chips (empty query state)

@Composable
private fun RecentChipsView(
    recents: List<String>,
    onTap: (String) -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    if (recents.isEmpty()) {
        Column(
            modifier             = Modifier.fillMaxSize(),
            horizontalAlignment  = Alignment.CenterHorizontally,
            verticalArrangement  = Arrangement.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Search,
                contentDescription = null,
                tint              = colors.muted,
                modifier          = Modifier.size(36.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s3))
            Text(text = "Search your notes", style = type.heading, color = colors.ink)
            Spacer(Modifier.size(QuickInkSpacing.s1))
            Text(
                text     = "Find pages by title or anything inside the OCR text.",
                style    = type.body,
                color    = colors.inkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = QuickInkSpacing.s7),
            )
        }
    } else {
        Column(
            modifier            = Modifier
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "RECENT", style = type.eyebrow, color = colors.muted)
                Spacer(Modifier.weight(1f))
                Text(
                    text     = "Clear",
                    style    = type.caption,
                    color    = colors.accent,
                    modifier = Modifier.clickable(onClick = onClear),
                )
            }
            // FlowRow available in foundation; for portability,
            // wrap with a custom row-flow using Compose layout.
            ChipFlow(
                items = recents,
                onTap = onTap,
            )
        }
    }
}

@Composable
private fun ChipFlow(items: List<String>, onTap: (String) -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    // Simplified vertical-of-rows fallback (true flow layout
    // requires foundation FlowRow which is API-stable from
    // Compose 1.5+; using a vertical column of horizontal rows
    // here keeps minimum churn).
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                row.forEach { term ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(colors.borderSoft)
                            .clickable { onTap(term) }
                            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector       = Icons.Filled.History,
                            contentDescription = null,
                            tint              = colors.ink,
                            modifier          = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(text = term, style = type.label, color = colors.ink)
                    }
                }
            }
        }
    }
}

// MARK: - Results

@Composable
private fun ResultsList(
    query: String,
    entries: List<NotepadEntry>,
    onOpen: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val q = query.lowercase()

    val titleHits   = entries.filter { it.title?.lowercase()?.contains(q) == true }
    val contentHits = entries.filter { it.notes.lowercase().contains(q) }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = androidx.compose.foundation.layout.PaddingValues(
            start  = QuickInkSpacing.s5,
            end    = QuickInkSpacing.s5,
            top    = QuickInkSpacing.s4,
            bottom = QuickInkSpacing.s7,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
    ) {
        if (titleHits.isNotEmpty()) {
            item {
                Text(text = "TITLES", style = type.eyebrow, color = colors.muted)
            }
            items(titleHits, key = { "t-${it.id}" }) { entry ->
                TitleHitRow(entry = entry, query = query, onClick = { onOpen(entry.id) })
            }
        }
        if (contentHits.isNotEmpty()) {
            item {
                Text(text = "OCR CONTENT", style = type.eyebrow, color = colors.muted)
            }
            items(contentHits, key = { "c-${it.id}" }) { entry ->
                OcrHitRow(entry = entry, query = query, onClick = { onOpen(entry.id) })
            }
        }
    }
}

@Composable
private fun TitleHitRow(entry: NotepadEntry, query: String, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Description,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = highlight(entry.title ?: "Untitled", query, colors.accent),
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = entry.entryDate, style = type.caption, color = colors.muted)
        }
    }
}

@Composable
private fun OcrHitRow(entry: NotepadEntry, query: String, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val title = entry.title?.takeIf { it.isNotEmpty() } ?: "Untitled"
    val snippet = makeSnippet(entry.notes, query, contextChars = 60)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = title,
                style    = type.label,
                color    = colors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = entry.entryDate, style = type.caption, color = colors.muted)
        }
        Text(
            text     = highlight(snippet, query, colors.accent),
            style    = type.body,
            color    = colors.inkSoft,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyResults() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier             = Modifier.fillMaxSize(),
        horizontalAlignment  = Alignment.CenterHorizontally,
        verticalArrangement  = Arrangement.Center,
    ) {
        Icon(
            imageVector       = Icons.Filled.Search,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(36.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text(text = "No matches", style = type.heading, color = colors.ink)
        Spacer(Modifier.size(QuickInkSpacing.s1))
        Text(
            text     = "Try a different word, or check your spelling.",
            style    = type.body,
            color    = colors.inkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s7),
        )
    }
}

// MARK: - Highlight + snippet

private fun highlight(text: String, query: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val q = query.lowercase()
    val idx = lower.indexOf(q)
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, idx))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
            append(text.substring(idx, idx + q.length))
        }
        append(text.substring(idx + q.length))
    }
}

private fun makeSnippet(text: String, query: String, contextChars: Int): String {
    if (query.isEmpty()) return text.take(contextChars * 2)
    val lower = text.lowercase()
    val idx = lower.indexOf(query.lowercase())
    if (idx < 0) return text.take(contextChars * 2)
    val start = (idx - contextChars).coerceAtLeast(0)
    val end   = (idx + query.length + contextChars).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return "$prefix${text.substring(start, end)}$suffix"
}

// MARK: - Recent searches persistence

private fun loadRecents(prefs: android.content.SharedPreferences): List<String> =
    prefs.getString(RECENTS_KEY, null)
        ?.split(RECENTS_DELIM)
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

private fun commitRecent(
    prefs: android.content.SharedPreferences,
    query: String,
    current: List<String>,
): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return current
    val deduped = listOf(trimmed) + current.filter { it.lowercase() != trimmed.lowercase() }
    val capped  = deduped.take(RECENTS_LIMIT)
    prefs.edit().putString(RECENTS_KEY, capped.joinToString(RECENTS_DELIM)).apply()
    return capped
}
