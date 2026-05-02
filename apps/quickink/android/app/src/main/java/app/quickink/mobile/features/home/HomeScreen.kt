/*
 * HomeScreen.kt
 *
 * QuickInk's home dashboard. Per the mockup spec:
 *   - Greeting header
 *   - Search bar (pill with soft-border tone)
 *   - Sync status pill
 *   - Horizontal "Recent" rail of note thumbnails (handwritten Caveat
 *     preview on lined paper)
 *   - 2-column category grid
 *   - Bottom nav with a Zap (⚡) FAB in the middle
 *
 * Architecturally this replaces the Slice-3 camera-first single-CTA
 * Home. The scanner is now reachable explicitly via the FAB rather
 * than auto-launching on first appear.
 *
 * Mirror of iOS `HomeScreen.swift`.
 */

package app.quickink.mobile.features.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.features.scan.QuickCaptureScreen
import app.quickink.mobile.features.scan.ScanFlowController
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import app.releaf.mobile.data.notepad.NotepadEntry
import java.time.LocalTime

@Composable
fun HomeScreen(
    controller: ScanFlowController,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: (() -> Unit)? = null,
    onTapCategory: ((String) -> Unit)? = null,
    onOpenEntry: ((String) -> Unit)? = null,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp

    // Tap on the Zap FAB now shows QuickCaptureScreen (the dark
    // mode-picker surface) rather than launching the system
    // scanner directly. QuickCapture's Zap shutter then triggers
    // the system scanner internally and dismisses itself on
    // completion.
    var showQuickCapture by remember { mutableStateOf(false) }

    if (showQuickCapture) {
        QuickCaptureScreen(
            controller = controller,
            onDismiss  = { showQuickCapture = false },
        )
        return
    }

    // Recent notes — TODO wire to the shared NotepadDao Flow once
    // we settle on the right method (`observeAll(userId)` vs.
    // `observeRecent(limit)`). Empty list for now keeps the
    // empty-state path visible. The screen still renders correctly;
    // once the Flow is hooked up, the rail populates.
    val recentEntries: List<NotepadEntry> = emptyList()
    @Suppress("UNUSED_VARIABLE") val unusedApp = app // suppress lint until db is wired

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    top    = QuickInkSpacing.s4,
                    bottom = 100.dp, // Reserve space behind nav bar.
                ),
        ) {
            HomeHeader()
            Spacer(Modifier.size(QuickInkSpacing.s5))
            SearchBar(onClick = { onOpenSearch?.invoke() })
            Spacer(Modifier.size(QuickInkSpacing.s5))
            HomeSyncPill()
            Spacer(Modifier.size(QuickInkSpacing.s5))
            RecentRail(
                entries     = recentEntries.take(8),
                onAllNotes  = onOpenNotes,
                onOpenEntry = onOpenEntry,
            )
            Spacer(Modifier.size(QuickInkSpacing.s5))
            CategoryGrid(onTapCategory = onTapCategory)
        }

        BottomNavBar(
            onHome     = { /* current */ },
            onLibrary  = onOpenNotes,
            onScan     = { showQuickCapture = true },
            onSearch   = { onOpenSearch?.invoke() },
            onSettings = onOpenSettings,
            modifier   = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// MARK: - Header

@Composable
private fun HomeHeader() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..11  -> "Good morning"
            in 12..17 -> "Good afternoon"
            else      -> "Good evening"
        }
    }
    Column {
        Text(text = greeting, style = type.body, color = colors.muted)
        Spacer(Modifier.size(QuickInkSpacing.s1))
        Text(text = "Quickink", style = type.display, color = colors.ink)
    }
}

// MARK: - Search bar

@Composable
private fun SearchBar(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.borderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector       = Icons.Filled.Search,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Search notes & OCR text",
            style = type.body,
            color = colors.muted,
        )
    }
}

// MARK: - Sync status pill

// Local Home-side wrapper that derives the pill state from
// whatever the sync layer publishes today. Wires to SyncStateDao
// reads once the shared sync layer's pending/error fields are
// exposed in a Composable-friendly way (currently the DAO
// returns suspending lookups, not a Flow on the active state).
@Composable
private fun HomeSyncPill() {
    SyncStatusPill(state = SyncPillState.Synced(lastSyncAt = "moments ago"))
}

// MARK: - Recent rail

@Composable
private fun RecentRail(
    entries: List<NotepadEntry>,
    onAllNotes: () -> Unit,
    onOpenEntry: ((String) -> Unit)?,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "RECENT",
                style = type.eyebrow,
                color = colors.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text     = "All notes →",
                style    = type.meta,
                color    = colors.accent,
                modifier = Modifier.clickable(onClick = onAllNotes),
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s3))

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                    .padding(QuickInkSpacing.s4),
            ) {
                Column {
                    Text(
                        text  = "No scans yet.",
                        style = type.body,
                        color = colors.ink,
                    )
                    Spacer(Modifier.size(QuickInkSpacing.s1))
                    Text(
                        text  = "Tap the ⚡ to capture your first page.",
                        style = type.meta,
                        color = colors.muted,
                    )
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                itemsIndexed(entries) { index, entry ->
                    RecentNoteThumb(
                        entry = entry,
                        seed  = index,
                        onTap = { onOpenEntry?.invoke(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentNoteThumb(entry: NotepadEntry, seed: Int, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val title = entry.title?.takeIf { it.isNotEmpty() } ?: "Untitled"
    val preview = entry.notes.take(50).ifEmpty { title }

    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onTap),
    ) {
        Box(
            modifier = Modifier
                .size(width = 140.dp, height = 120.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .quickInkLinedPaper(seed = seed.hashCode())
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                .padding(QuickInkSpacing.s3),
        ) {
            Text(
                text     = preview,
                style    = type.handwritten,
                color    = colors.ink.copy(alpha = 0.75f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s2))

        Text(
            text     = title,
            style    = type.label,
            color    = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text  = entry.entryDate,
            style = type.caption,
            color = colors.muted,
        )
    }
}

// MARK: - Category grid

private data class Category(val name: String, val icon: ImageVector)

private val CATEGORIES = listOf(
    Category("Ideas",      Icons.Filled.Lightbulb),
    Category("Projects",   Icons.Filled.Folder),
    Category("Brainstorm", Icons.Filled.Star),
    Category("Meetings",   Icons.Filled.Group),
    Category("Journal",    Icons.Filled.Book),
    Category("Study",      Icons.Filled.School),
)

@Composable
private fun CategoryGrid(onTapCategory: ((String) -> Unit)?) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column {
        Text(text = "CATEGORIES", style = type.eyebrow, color = colors.muted)
        Spacer(Modifier.size(QuickInkSpacing.s3))
        // 2 columns × 3 rows. Compose's LazyVGrid is overkill for 6
        // fixed cells — use a Column of Rows.
        CATEGORIES.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.size(QuickInkSpacing.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                pair.forEach { cat ->
                    CategoryTile(
                        name     = cat.name,
                        icon     = cat.icon,
                        count    = 0,
                        onTap    = { onTapCategory?.invoke(cat.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    name: String,
    icon: ImageVector,
    count: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap)
            .padding(QuickInkSpacing.s4),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = icon,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text(text = name, style = type.heading, color = colors.ink)
        Text(
            text  = if (count == 0) "No notes yet" else "$count note${if (count == 1) "" else "s"}",
            style = type.caption,
            color = colors.muted,
        )
    }
}

// MARK: - Bottom nav with Zap FAB

@Composable
private fun BottomNavBar(
    onHome: () -> Unit,
    onLibrary: () -> Unit,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(topStart = QuickInkRadius.lg, topEnd = QuickInkRadius.lg))
            .clip(RoundedCornerShape(topStart = QuickInkRadius.lg, topEnd = QuickInkRadius.lg))
            .background(colors.surface)
            .padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                top    = QuickInkSpacing.s3,
                bottom = QuickInkSpacing.s5,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(icon = Icons.Filled.Home,             label = "Home",     active = true,  onClick = onHome,     modifier = Modifier.weight(1f))
        NavIcon(icon = Icons.AutoMirrored.Filled.ListAlt, label = "Library", active = false, onClick = onLibrary, modifier = Modifier.weight(1f))
        ZapFab(onClick = onScan, modifier = Modifier.weight(1f))
        NavIcon(icon = Icons.Filled.Search,           label = "Search",   active = false, onClick = onSearch,   modifier = Modifier.weight(1f))
        NavIcon(icon = Icons.Filled.Settings,         label = "Settings", active = false, onClick = onSettings, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val tint = if (active) colors.accent else colors.muted

    Column(
        modifier             = modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment  = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = label,
            tint              = tint,
            modifier          = Modifier.size(18.dp),
        )
        Text(text = label, style = type.caption, color = tint)
    }
}

@Composable
private fun ZapFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier             = modifier,
        contentAlignment     = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(onClick = onClick)
                .padding(QuickInkSpacing.s3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Bolt,
                contentDescription = "Scan",
                tint              = colors.textOnAccent,
                modifier          = Modifier.size(22.dp),
            )
        }
    }
}
