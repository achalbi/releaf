/*
 * HomeScreenVariant1.kt
 * Editorial "Your shelves" screen — hero-card per notebook, category
 * filters, and a floating action bar. Shares [HomeViewModel] with the
 * classic screen so the underlying data source is identical.
 */

package app.releaf.mobile.features.home

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.NotebookStatus
import app.releaf.mobile.ui.components.ShelfPalette
import app.releaf.mobile.ui.components.ShelfTheme
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.Duration
import java.time.Instant

@Composable
fun HomeScreenVariant1(
    session: GoogleAuthSession,
    onOpenNotebook: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelvesViewModel = viewModel(factory = ShelvesViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    var filter by remember { mutableStateOf(ShelfFilter.All) }
    var showNewBookDialog by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            ShelvesUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = app.releaf.mobile.ui.theme.AppAccent.primary)
                }
            }
            is ShelvesUiState.Loaded -> {
                ShelvesLoaded(
                    shelves        = s.shelves,
                    notebooks      = s.notebooks,
                    captureCounts  = s.captureCounts,
                    filter         = filter,
                    onFilter       = { filter = it },
                    onOpenNotebook = onOpenNotebook,
                    onNewNotebook  = { showNewBookDialog = true },
                    onSignOut      = onSignOut,
                )
                if (showNewBookDialog) {
                    NewBookDialog(
                        shelves       = s.shelves,
                        onDismiss     = { showNewBookDialog = false },
                        onCreateShelf = { name, onCreated ->
                            viewModel.createShelf(name) { id -> onCreated(id) }
                        },
                        onConfirm     = { title, shelfId ->
                            viewModel.createNotebook(
                                title   = title,
                                shelfId = shelfId,
                                onCreated = { id -> onOpenNotebook(id) },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelvesLoaded(
    shelves: List<app.releaf.mobile.data.domain.Shelf>,
    notebooks: List<Notebook>,
    captureCounts: app.releaf.mobile.data.domain.CaptureCountsByMode,
    filter: ShelfFilter,
    onFilter: (ShelfFilter) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onNewNotebook: () -> Unit,
    onSignOut: () -> Unit,
) {
    val filtered = filter.apply(notebooks)
    val totals = remember(notebooks) {
        Triple(
            notebooks.size,
            notebooks.sumOf { it.chapterCount },
            notebooks.sumOf { it.pageCount },
        )
    }
    // Photos / scans / voice / contacts stay at 0 until the
    // captures-table migration lands; when it does, the shape here
    // doesn't change — only `CaptureRepository` populates the new
    // fields.
    val impact = remember(captureCounts) {
        TreesSavedMetrics(
            notes    = captureCounts.notes,
            photos   = captureCounts.photos,
            scans    = captureCounts.scans,
            voice    = captureCounts.voice,
            contacts = captureCounts.contacts,
        )
    }
    // Group the filtered books by shelfId, keeping shelves in their
    // stored order. Shelves with zero matching books are still
    // rendered so the user can see an empty-state row and tap the
    // "+ New notebook" action knowing which shelf it'll land on.
    val booksByShelf = remember(filtered) {
        filtered.groupBy { it.shelfId }
    }
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = AppSpacing.s5)
                .padding(top = AppSpacing.s5, bottom = AppSpacing.s10 + AppSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s5),
        ) {
            Header(notebookCount = totals.first, chapterCount = totals.second,
                   pageCount = totals.third, onSignOut = onSignOut)
            TreesSavedStrip(metrics = impact)
            FilterRow(selected = filter, onSelect = onFilter)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AppColors.BorderDefault)
            )
            if (shelves.isEmpty()) {
                Text(
                    "No shelves yet. Tap \u201c+ New notebook\u201d to get started.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = AppSpacing.s6),
                )
            } else {
                shelves.forEach { shelf ->
                    ShelfSection(
                        shelf    = shelf,
                        books    = booksByShelf[shelf.id].orEmpty(),
                        onOpenBook = onOpenNotebook,
                    )
                }
                // Orphan fallback: in the unlikely event a book's
                // shelf got deleted or isn't in the live list,
                // surface those rows at the bottom under an
                // "Unshelved" heading so they're still reachable.
                val orphaned = filtered.filter { nb -> shelves.none { it.id == nb.shelfId } }
                if (orphaned.isNotEmpty()) {
                    ShelfSectionHeader(name = "Unshelved", count = orphaned.size)
                    orphaned.forEach { nb ->
                        ShelfCard(notebook = nb, onClick = { onOpenNotebook(nb.id) })
                    }
                }
            }
        }

        ActionBar(
            onNew = onNewNotebook,
            onSearch = { /* TODO */ },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s4),
        )
    }
}

@Composable
private fun ShelfSection(
    shelf: app.releaf.mobile.data.domain.Shelf,
    books: List<Notebook>,
    onOpenBook: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        ShelfSectionHeader(name = shelf.name, count = books.size)
        if (books.isEmpty()) {
            Text(
                "No books on this shelf yet.",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = AppSpacing.s2),
            )
        } else {
            books.forEach { nb ->
                ShelfCard(notebook = nb, onClick = { onOpenBook(nb.id) })
            }
        }
    }
}

@Composable
private fun ShelfSectionHeader(name: String, count: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            name.uppercase(),
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
        )
        Text(
            "· $count book${if (count == 1) "" else "s"}",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun Header(
    notebookCount: Int,
    chapterCount: Int,
    pageCount: Int,
    onSignOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "RELEAF · VOL %02d".format(notebookCount),
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppColors.ActionPrimary)
                    .clickable { onSignOut() },
                contentAlignment = Alignment.Center,
            ) {
                Text("AI", style = AppTypography.Tag, color = AppColors.OnPrimary)
            }
        }
        Text(
            text = "Your shelves",
            color = AppColors.TextPrimary,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
        )
        Text(
            text = "$notebookCount notebooks · $chapterCount chapters · $pageCount pages",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun FilterRow(selected: ShelfFilter, onSelect: (ShelfFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        ShelfFilter.entries.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(if (active) AppColors.ActionPrimary else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (active) Color.Transparent else AppColors.BorderStrong,
                        shape = RoundedCornerShape(AppRadius.pill),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
            ) {
                Text(
                    text = option.label,
                    style = AppTypography.Button,
                    color = if (active) AppColors.OnPrimary else AppColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun ShelfCard(notebook: Notebook, onClick: () -> Unit) {
    val palette = remember(notebook.colorToken) { ShelfTheme.palette(notebook.colorToken) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        // Hero block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.md))
                .background(palette.background)
                .padding(AppSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = eyebrow(notebook),
                    style = AppTypography.Eyebrow,
                    color = palette.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = ShelfTheme.icon(notebook.iconKey),
                    contentDescription = null,
                    tint = palette.onBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = notebook.title,
                color = palette.onBackground,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            )
            ProgressDashes(total = 4, filled = progressFilled(notebook.resolvedStatus),
                           palette = palette)
        }
        // Footer (cream) — pulled up so the hero slightly overlaps it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-4).dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(AppColors.CardSolid)
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = footerMeta(notebook),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            StatusPill(status = notebook.resolvedStatus)
        }
    }
}

@Composable
private fun ProgressDashes(total: Int, filled: Int, palette: ShelfPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(32.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(
                        if (i < filled) palette.onBackground
                        else            palette.onBackgroundMuted.copy(alpha = 0.45f)
                    ),
            )
        }
    }
}

@Composable
private fun StatusPill(status: NotebookStatus) {
    val (bg, fg, label) = when (status) {
        NotebookStatus.Active   -> Triple(AppColors.SuccessSoft, AppColors.GreenText,     "active")
        NotebookStatus.Paused   -> Triple(Color.Transparent,     AppColors.TextSecondary, "paused")
        NotebookStatus.Archived -> Triple(AppColors.NeutralSoft, AppColors.Neutral,       "archived")
        NotebookStatus.Shared   -> Triple(AppColors.InfoSoft,    AppColors.Info,          "shared")
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .then(
                if (status == NotebookStatus.Paused)
                    Modifier.border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
                else Modifier
            )
            .padding(horizontal = AppSpacing.s3, vertical = 5.dp),
    ) {
        Text(label, style = AppTypography.Tag, color = fg)
    }
}

@Composable
private fun ActionBar(
    onNew: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.ActionPrimary)
                .clickable { onNew() }
                .padding(vertical = AppSpacing.s3),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = AppColors.OnPrimary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AppSpacing.s2))
            Text("New notebook", style = AppTypography.Button, color = AppColors.OnPrimary)
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AppColors.CardSolid)
                .border(1.dp, AppColors.BorderStrong, CircleShape)
                .clickable { onSearch() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ---------- helpers ----------

private fun eyebrow(nb: Notebook): String {
    val shelf = nb.shelfName ?: nb.title.uppercase()
    // Single-volume books (seriesId == null) hide the "Vol N" suffix.
    // Only books that belong to a series show the volume number.
    return if (nb.seriesId != null) {
        "$shelf · VOL %02d".format(nb.seriesVolumeNumber)
    } else {
        shelf
    }
}

private fun progressFilled(status: NotebookStatus): Int = when (status) {
    NotebookStatus.Archived -> 4
    NotebookStatus.Paused   -> 1
    else                    -> 2
}

private fun footerMeta(nb: Notebook): String {
    val chapters = "${nb.chapterCount} chapter${if (nb.chapterCount == 1) "" else "s"}"
    val edit     = "last edit ${relativeShort(nb.updatedAt)}"
    return "$chapters · $edit"
}

internal fun relativeShort(instant: Instant, now: Instant = Instant.now()): String {
    val delta = Duration.between(instant, now)
    val seconds = delta.seconds.coerceAtLeast(0)
    return when {
        seconds < 60          -> "just now"
        seconds < 3600        -> "${seconds / 60}m ago"
        seconds < 86_400      -> "${seconds / 3600}h ago"
        seconds < 2 * 86_400  -> "yesterday"
        seconds < 7 * 86_400  -> "${seconds / 86_400}d ago"
        else                  -> java.time.format.DateTimeFormatter
            .ofPattern("MMM d")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }
}

enum class ShelfFilter(val label: String) {
    All("All"),
    Active("Active"),
    Archived("Archived"),
    Shared("Shared");

    fun apply(notebooks: List<Notebook>): List<Notebook> = when (this) {
        All      -> notebooks
        Active   -> notebooks.filter { it.resolvedStatus == NotebookStatus.Active }
        Archived -> notebooks.filter { it.resolvedStatus == NotebookStatus.Archived }
        Shared   -> notebooks.filter { it.resolvedStatus == NotebookStatus.Shared }
    }
}
