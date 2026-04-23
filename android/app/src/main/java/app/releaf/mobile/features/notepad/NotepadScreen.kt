/*
 * NotepadScreen.kt
 *
 * Top-level Notepad tab. Mirrors the notebook/chapter/page surfaces visually
 * — eyebrow + serif title + avatar header, then date-grouped
 * [CollapsibleCard]s whose bodies hold entry rows (green Releaf leaf
 * mark, title + relative timestamp top-right, and attribute pills that
 * surface which media the entry carries).
 *
 * Search mode collapses the date grouping into a single "Results" card so
 * FTS rank order wins over calendar ordering.
 */

package app.releaf.mobile.features.notepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.ui.components.CollapsibleCard
import app.releaf.mobile.ui.components.DeleteConfirmationDialog
import app.releaf.mobile.ui.components.HairlineDivider
import app.releaf.mobile.ui.components.MetaPill
import app.releaf.mobile.ui.components.ReleafLogo
import app.releaf.mobile.ui.components.relativeTimeAgo
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NotepadScreen(
    onOpenEntry: (String) -> Unit,
    onComposeNew: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotepadListViewModel = viewModel(factory = NotepadListViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Pending delete — holds the entry the user swiped until they confirm
    // via the guard dialog.
    var pendingDelete by remember { mutableStateOf<NotepadEntry?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onComposeNew,
                containerColor = AppAccent.primary,
                contentColor   = AppColors.OnAccent,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New entry")
            }
        },
        containerColor = Color.Transparent,
        // Outer SignedInShell Scaffold already consumes the system-bar
        // insets; swallow them here so the header aligns with the
        // editor surfaces (which are plain Column, no nested Scaffold).
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            NotepadHeader(
                avatarInitial = "A",
                onOpenSettings = onOpenSettings,
                onSignOut = onSignOut,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::updateQuery,
                    onClearQuery = viewModel::clearQuery,
                )
            }
            Spacer(Modifier.height(AppSpacing.s3))
            when {
                state.entries.isNotEmpty() ->
                    EntryBody(
                        entries = state.entries,
                        grouped = !state.isSearching,
                        onOpenEntry = onOpenEntry,
                        onDeleteRequest = { entry -> pendingDelete = entry },
                        modifier = Modifier.weight(1f, fill = true),
                    )

                state.isSearching ->
                    EmptySearchState(query = state.query, modifier = Modifier.weight(1f))

                else ->
                    EmptyState(modifier = Modifier.weight(1f))
            }
        }
    }

    pendingDelete?.let { entry ->
        val title = displayTitle(entry)
        DeleteConfirmationDialog(
            title = "Delete entry?",
            message = "\u201C$title\u201D will be deleted. " +
                "You can undo this immediately after.",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val id = entry.id
                pendingDelete = null
                viewModel.softDelete(id)
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message     = "Entry deleted",
                        actionLabel = "Undo",
                        duration    = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        viewModel.undoDelete(id)
                    }
                }
            },
        )
    }
}

/* ---------- header ---------- */

// Notepad screen uses its own header instead of the shared `ScreenHeader` so
// the title sits flush with the top of the content area and the avatar can
// anchor a Settings / Sign out dropdown.
@Composable
private fun NotepadHeader(
    avatarInitial: String,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.s4,
                end = AppSpacing.s4,
                top = AppSpacing.s3,
                bottom = AppSpacing.s3,
            ),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "NOTEPAD",
                style = AppTypography.Eyebrow,
                color = AppColors.TextTertiary,
            )
            Text(
                text = "Daily",
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AppAccent.soft)
                    .border(1.dp, AppAccent.border, CircleShape)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = avatarInitial.take(1).uppercase(),
                    style = AppTypography.Button,
                    color = AppAccent.deep,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onOpenSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onSignOut()
                    },
                )
            }
        }
    }
}

/* ---------- search ---------- */

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.pill),
            )
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Box(Modifier.weight(1f, fill = true)) {
            if (query.isEmpty()) {
                Text(
                    "Search notes\u2026",
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
            Spacer(Modifier.size(AppSpacing.s2))
            Icon(
                Icons.Filled.Close,
                contentDescription = "Clear search",
                tint = AppColors.TextTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onClearQuery() },
            )
        }
    }
}

/* ---------- empty states ---------- */

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing here yet",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Tap the + button to jot something down.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
    }
}

@Composable
private fun EmptySearchState(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No matches",
            style = AppTypography.SectionTitle,
            color = AppColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Nothing in your notepad matches \u201C$query\u201D.",
            style = AppTypography.Body,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppSpacing.s2),
        )
    }
}

/* ---------- list body ---------- */

@Composable
private fun EntryBody(
    entries: List<NotepadEntry>,
    grouped: Boolean,
    onOpenEntry: (String) -> Unit,
    onDeleteRequest: (NotepadEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups: List<Pair<String, List<NotepadEntry>>> = remember(entries, grouped) {
        if (grouped) {
            entries.groupBy { it.entryDate }
                .toList()
                .sortedByDescending { it.first }
        } else {
            // Search: one "Results" group keyed on the null-ish marker so
            // rank order wins over calendar ordering.
            listOf("" to entries)
        }
    }

    // Which groups are expanded. Default to just the first (most recent)
    // date so users see their latest work; older stays collapsed for
    // scan-ability on long histories.
    var expanded by rememberSaveable(stateSaver = StringSetSaver) {
        mutableStateOf(groups.firstOrNull()?.first?.let { setOf(it) } ?: emptySet())
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start  = AppSpacing.s4,
            end    = AppSpacing.s4,
            top    = AppSpacing.s0,
            bottom = AppSpacing.s10,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        items(items = groups, key = { it.first.ifEmpty { "__results__" } }) { (dateKey, group) ->
            val isExpanded = dateKey in expanded
            EntryGroupCard(
                title = if (grouped) formatDateHeader(dateKey) else "Results",
                subtitle = if (grouped) null else "Matches ranked by relevance",
                entries = group,
                expanded = isExpanded,
                onToggle = {
                    expanded = if (dateKey in expanded) expanded - dateKey else expanded + dateKey
                },
                onOpenEntry = onOpenEntry,
                onDeleteRequest = onDeleteRequest,
            )
        }
        item { Spacer(Modifier.height(AppSpacing.s6)) }
    }
}

@Composable
private fun EntryGroupCard(
    title: String,
    subtitle: String?,
    entries: List<NotepadEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onDeleteRequest: (NotepadEntry) -> Unit,
) {
    val countLabel = if (entries.size == 1) "1 entry" else "${entries.size} entries"
    CollapsibleCard(
        title = title,
        subtitle = subtitle,
        expanded = expanded,
        onToggle = onToggle,
        trailing = {
            MetaPill(text = countLabel, accent = true)
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) HairlineDivider()
                SwipeableEntryRow(
                    entry = entry,
                    onOpen = { onOpenEntry(entry.id) },
                    onDelete = { onDeleteRequest(entry) },
                )
            }
        }
    }
}

/* ---------- entry row ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEntryRow(
    entry: NotepadEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // Hand off to the screen's guard dialog. Returning false snaps
            // the row back while the dialog is open.
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeDeleteBackground() },
    ) {
        EntryRow(entry = entry, onClick = onOpen)
    }
}

@Composable
private fun EntryRow(entry: NotepadEntry, onClick: () -> Unit) {
    val attachments = remember(entry.attachments) { entry.attachments.parseAttachments() }
    val locations = remember(entry.locations) { entry.locations.parseLocations() }
    val contacts = remember(entry.contacts) { entry.contacts.parseContacts() }
    val todos = remember(entry.todos) { entry.todos.parseTodos() }
    val photoCount = attachments.count { it.type == Attachment.TYPE_PHOTO }
    val voiceCount = attachments.count { it.type == Attachment.TYPE_VOICE }
    val scanCount = attachments.count { it.type == Attachment.TYPE_SCAN }
    val titleText = displayTitle(entry)

    // Derived "what's in this entry" chips. Kept to real attributes of the
    // stored row so the list reads as a preview of the entry's shape — not
    // as user-defined tags (which the data model doesn't carry yet).
    val pills = buildList {
        if (photoCount > 0) add("$photoCount Photo${if (photoCount == 1) "" else "s"}")
        if (voiceCount > 0) add("$voiceCount Voice")
        if (scanCount > 0)  add("$scanCount Scan${if (scanCount == 1) "" else "s"}")
        if (locations.isNotEmpty()) add("Location")
        if (contacts.isNotEmpty()) {
            add("${contacts.size} Contact${if (contacts.size == 1) "" else "s"}")
        }
        if (todos.isNotEmpty()) {
            add("${todos.size} To-do${if (todos.size == 1) "" else "s"}")
        }
    }

    // Opaque fill is required: SwipeToDismissBox lays the delete background
    // *behind* the foreground row, so a transparent row would leak the red
    // strip through at rest.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.Top,
    ) {
        LeafChip()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = titleText,
                    // Softer weight than SectionTitle to match the reference
                    // screenshot — a ~17sp semibold reads as "important" next
                    // to the pill rail without feeling like a section header.
                    style = AppTypography.SectionTitle.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    ),
                    color = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = relativeTimeAgo(entry.updatedAt),
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                )
            }
            val summary = remember(entry.notes, entry.title) { summarizeNotes(entry) }
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pills.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    pills.forEach { label ->
                        MetaPill(text = label)
                    }
                }
            }
        }
    }
}

// Green-tinted Releaf leaf mark used as the row's visual anchor. Sits in a
// circular soft-green chip so it reads as a badge next to the title rather
// than floating loose on the cream card fill.
@Composable
private fun LeafChip() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(AppColors.ThemeGreenBgSoft),
        contentAlignment = Alignment.Center,
    ) {
        ReleafLogo(
            size = 26.dp,
            filled = true,
            outlineColor = AppColors.ThemeGreenDeep,
            fillGradientStart = AppColors.ThemeGreenPrimary,
            fillGradientEnd = AppColors.ThemeGreenDeep,
            strokeWidth = 0.8.dp,
        )
    }
}

@Composable
private fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Danger)
            .padding(horizontal = AppSpacing.s4),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = AppColors.OnAccent,
            modifier = Modifier.size(20.dp),
        )
    }
}

/* ---------- helpers ---------- */

/** Saves the Set<String> of expanded date keys through config changes. */
private val StringSetSaver: Saver<Set<String>, List<String>> = Saver(
    save    = { it.toList() },
    restore = { it.toSet() },
)

/** Title if set, otherwise the first non-empty line of the body. */
private fun displayTitle(entry: NotepadEntry): String {
    entry.title?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val firstLine = entry.notes
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
    return firstLine?.takeIf { it.isNotEmpty() } ?: "Untitled"
}

/**
 * Plain-text summary shown as the 2-line description under the title.
 * Strips CommonMark syntax so headings, list bullets, emphasis, links,
 * and code fences don't leak into the preview as raw `#` / `*` / `[...]`
 * noise, and collapses whitespace so multi-paragraph notes flatten to a
 * scannable run. Strips the first line when it's already the title so
 * we don't echo the same string twice in the row.
 *
 * Intentionally deterministic and synchronous — this is the baseline
 * preview that renders immediately when the list loads. An ML Kit GenAI
 * Summarization pass can be layered on top later to compress longer
 * notes, writing its output to a cache column and falling back to this
 * helper whenever the cache is empty or stale.
 */
private fun summarizeNotes(entry: NotepadEntry): String {
    val raw = entry.notes.trim()
    if (raw.isEmpty()) return ""
    val titleIsFromNotes = entry.title.isNullOrBlank()
    val body = if (titleIsFromNotes) {
        raw.lineSequence().drop(1).joinToString("\n")
    } else {
        raw
    }
    return stripMarkdown(body)
}

/**
 * Lightweight CommonMark + HTML → plain-text stripper tuned for preview
 * copy. Not a full parser — we only need to kill the syntax characters
 * that would read as noise in a 2-line description. Covers both markdown
 * (because that's our canonical storage format) and raw HTML (because
 * `richeditor-compose` emits tags like `<br>` for hard line breaks and
 * raw-HTML blocks for constructs CommonMark can't express). The order
 * of substitutions matters: HTML first so its tags don't get caught by
 * the link-bracket regex, then fenced code + links, then inline
 * emphasis + line markers.
 */
private fun stripMarkdown(src: String): String {
    if (src.isBlank()) return ""
    var s = src
    // HTML tags — collapse to a single space so `<br>` between two
    // words doesn't smash them into "wordone wordtwo" without a break.
    // Covers `<br>`, `<br/>`, `<br />`, `<p>...</p>`, `<strong>…`, etc.
    s = Regex("<[^>]+>").replace(s, " ")
    // Most-common HTML entities richeditor-compose emits. Full entity
    // decoding would pull in another regex pass for `&#nnn;` / `&#xhh;`
    // — not worth it for preview copy.
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
    // Fenced code blocks — drop the fence and keep the inner text.
    s = Regex("```[a-zA-Z0-9]*\\n?").replace(s, "")
    // Inline code — keep the code text, drop the backticks.
    s = Regex("`([^`]*)`").replace(s) { it.groupValues[1] }
    // Images: ![alt](url) — keep only the alt text.
    s = Regex("!\\[([^\\]]*)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
    // Links: [text](url) — keep only the link text.
    s = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
    // Bold / italic markers — strip the surrounding *, _, ~ while
    // keeping the wrapped text.
    s = Regex("(\\*\\*|__)(.*?)\\1").replace(s) { it.groupValues[2] }
    s = Regex("(\\*|_)(.*?)\\1").replace(s) { it.groupValues[2] }
    s = Regex("~~(.*?)~~").replace(s) { it.groupValues[1] }
    // Leading line markers: headings (#…), blockquotes (>), unordered
    // list bullets (- / * / +) and ordered list markers (1. / 2.).
    s = s.lineSequence()
        .map { line ->
            line
                .replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")
                .replace(Regex("^\\s{0,3}>\\s?"), "")
                .replace(Regex("^\\s{0,3}[-*+]\\s+"), "")
                .replace(Regex("^\\s{0,3}\\d+\\.\\s+"), "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    // Collapse stray runs of whitespace into single spaces so the
    // preview reads as flowing prose instead of awkwardly-spaced
    // fragments.
    return s.replace(Regex("\\s+"), " ").trim()
}

/**
 * Format an ISO date (YYYY-MM-DD) for a section header:
 *   - today / yesterday get friendly names
 *   - other dates fall back to "April 21, 2026"
 */
private fun formatDateHeader(iso: String): String {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val today = LocalDate.now()
    return when (date) {
        today               -> "Today"
        today.minusDays(1)  -> "Yesterday"
        else                -> date.format(LONG_DATE_FMT)
    }
}

private val LONG_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
