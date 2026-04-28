/*
 * HomeScreenVariant1.kt
 * Editorial "Your shelves" library screen — trees-saved card, four-up
 * stats grid, filter chips, and shelves rendered as rows of book
 * spines sitting on a physical shelf line with a "THIS WK" meter on
 * the right. Shares [ShelvesViewModel] with the classic screen so the
 * underlying data source is identical.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.NotebookStatus
import app.releaf.mobile.data.domain.Shelf
import app.releaf.mobile.ui.components.LeafEyebrow
import app.releaf.mobile.ui.components.ShelfTheme
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

@Composable
fun HomeScreenVariant1(
    session: GoogleAuthSession,
    onOpenNotebook: (String) -> Unit,
    onOpenPageTodo: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelvesViewModel = viewModel(factory = ShelvesViewModel.factory(session)),
) {
    val state by viewModel.state.collectAsState()
    var filter by remember { mutableStateOf(ShelfFilter.Active) }
    var showNewBookDialog by remember { mutableStateOf(false) }
    var showNewShelfDialog by remember { mutableStateOf(false) }
    var showOpenTodosSheet by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            ShelvesUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppAccent.primary)
                }
            }
            is ShelvesUiState.Loaded -> {
                ShelvesLoaded(
                    shelves         = s.shelves,
                    notebooks       = s.notebooks,
                    captureCounts   = s.captureCounts,
                    openTodoCount   = s.openTodos.size,
                    filter          = filter,
                    onFilter        = { filter = it },
                    onOpenNotebook  = onOpenNotebook,
                    onOpenTodosTap  = { showOpenTodosSheet = true },
                    onNewNotebook   = { showNewBookDialog = true },
                    onNewShelf      = { showNewShelfDialog = true },
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
                if (showNewShelfDialog) {
                    NewShelfDialog(
                        onDismiss = { showNewShelfDialog = false },
                        onConfirm = { name ->
                            viewModel.createShelf(name)
                            showNewShelfDialog = false
                        },
                    )
                }
                if (showOpenTodosSheet) {
                    OpenTodosSheet(
                        todos       = s.openTodos,
                        onDismiss   = { showOpenTodosSheet = false },
                        onOpenTodo  = { todo ->
                            showOpenTodosSheet = false
                            onOpenPageTodo(todo.pageId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelvesLoaded(
    shelves: List<Shelf>,
    notebooks: List<Notebook>,
    captureCounts: app.releaf.mobile.data.domain.CaptureCountsByMode,
    openTodoCount: Int,
    filter: ShelfFilter,
    onFilter: (ShelfFilter) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onOpenTodosTap: () -> Unit,
    onNewNotebook: () -> Unit,
    onNewShelf: () -> Unit,
) {
    val filtered = filter.apply(notebooks)
    val totalPages = remember(notebooks) { notebooks.sumOf { it.pageCount } }
    val impact = remember(captureCounts) {
        TreesSavedMetrics(
            notes     = captureCounts.notes,
            photos    = captureCounts.photos,
            scans     = captureCounts.scans,
            voice     = captureCounts.voice,
            contacts  = captureCounts.contacts,
            locations = captureCounts.locations,
        )
    }
    val streak = remember(notebooks) { computeStreak(notebooks) }
    val booksByShelf = remember(filtered) { filtered.groupBy { it.shelfId } }
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
            Header(
                bookCount  = notebooks.size,
                shelfCount = shelves.size,
                pageCount  = totalPages,
            )
            TreesSavedStrip(metrics = impact)
            // StatsGrid hidden per design feedback — trees-saved card
            // already anchors the top; filter row moves straight under
            // it so the shelves start higher.
            FilterRow(
                selected    = filter,
                onSelect    = onFilter,
                onNewShelf  = onNewShelf,
            )

            if (shelves.isEmpty()) {
                Text(
                    "No shelves yet. Tap the \u2795 button to create your first book.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = AppSpacing.s6),
                )
            } else {
                shelves.forEach { shelf ->
                    val books = booksByShelf[shelf.id].orEmpty()
                    ShelfBlock(
                        shelf       = shelf,
                        books       = books,
                        onOpenBook  = onOpenNotebook,
                    )
                }
                val orphaned = filtered.filter { nb -> shelves.none { it.id == nb.shelfId } }
                if (orphaned.isNotEmpty()) {
                    ShelfBlock(
                        shelf = Shelf(id = "__orphan", name = "Unshelved"),
                        books = orphaned,
                        onOpenBook = onOpenNotebook,
                    )
                }
            }
        }

        FloatingAddButton(
            onClick = onNewNotebook,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = AppSpacing.s5, bottom = AppSpacing.s6),
        )
    }
}

// ================================================================== Header

@Composable
private fun Header(
    bookCount: Int,
    shelfCount: Int,
    pageCount: Int,
) {
    // Match the typography rhythm used by NotebookTabScreen / NotepadScreen —
    // small uppercase eyebrow + EditorialTitleLight serif title — so the
    // Library tab reads at the same scale as the other top-level surfaces.
    // Sign-out lives in Settings, not the library header.
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        LeafEyebrow("releaf · library")
        Text(
            text = "your shelves",
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize   = 32.sp,
            ),
            color = AppColors.TextPrimary,
        )
        Text(
            text = "$bookCount book${plural(bookCount)} \u00B7 " +
                   "$shelfCount shel${if (shelfCount == 1) "f" else "ves"} \u00B7 " +
                   "$pageCount page${plural(pageCount)}",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
    }
}

// ================================================================== Stats grid

@Composable
private fun StatsGrid(
    streak: Int,
    books: Int,
    pages: Int,
    openTodos: Int,
    onOpenTodosTap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        StatCard(
            label      = "STREAK",
            value      = "$streak",
            suffix     = "d",
            background = AppColors.CoralSoft,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "BOOKS",
            value      = "$books",
            background = AppColors.GreenSoft,
            border     = AppColors.ThemeGreenBorderSoft,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "PAGES",
            value      = "$pages",
            background = AppColors.CardSolid,
            border     = AppColors.BorderDefault,
            modifier   = Modifier.weight(1f),
        )
        StatCard(
            label      = "OPEN TODOS",
            value      = "$openTodos",
            valueColor = AppColors.Coral,
            background = AppColors.CardSolid,
            border     = AppColors.BorderDefault,
            onClick    = onOpenTodosTap,
            modifier   = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    background: Color,
    border: Color? = null,
    valueColor: Color = AppColors.TextPrimary,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(background)
            .then(
                if (border != null)
                    Modifier.border(1.dp, border, RoundedCornerShape(AppRadius.md))
                else Modifier
            )
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = label,
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 26.sp,
                fontWeight = LocalFontWeight.current,
                fontFamily = FontFamily.Serif,
            )
            if (suffix != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = suffix,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

// ================================================================== Filter row

@Composable
private fun FilterRow(
    selected: ShelfFilter,
    onSelect: (ShelfFilter) -> Unit,
    onNewShelf: () -> Unit,
) {
    // Capsule switch ported from the Classic library tab
    // (NotebookTabScreen.TabSwitcher) so both library views share
    // one segmented-control vocabulary. Two segments — Active /
    // Archived — match the Classic affordance (the "All" filter is
    // dropped here; the user can tap into either segment to scope
    // the list). The "+ Shelf" pill stays right-aligned beside the
    // switch, separated by a flexible Spacer.
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFEFE7CD))
                .border(
                    width = 1.dp,
                    color = AppColors.BorderDefault,
                    shape = RoundedCornerShape(50),
                )
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterSegment(
                label    = "Active",
                isActive = selected == ShelfFilter.Active,
                onClick  = { onSelect(ShelfFilter.Active) },
                modifier = Modifier.weight(1f),
            )
            FilterSegment(
                label    = "Archived",
                isActive = selected == ShelfFilter.Archived,
                onClick  = { onSelect(ShelfFilter.Archived) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.weight(1f))
        // "+ Shelf" pill — accent-soft fill marks it as a creation
        // affordance, distinct from the segmented control on the
        // left.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppAccent.soft)
                .clickable(onClick = onNewShelf)
                .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Icon(
                imageVector        = Icons.Filled.Add,
                contentDescription = null,
                tint               = AppAccent.deep,
                modifier           = Modifier.size(16.dp),
            )
            Text(
                text  = "Shelf",
                style = AppTypography.Button,
                color = AppAccent.deep,
            )
        }
    }
}

@Composable
private fun FilterSegment(
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

// ================================================================== Shelf block

@Composable
private fun ShelfBlock(
    shelf: Shelf,
    books: List<Notebook>,
    onOpenBook: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
        Text(
            text = "${shelf.name.uppercase()} \u00B7 ${books.size} BOOK${plural(books.size).uppercase()}",
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
        )
        if (books.isEmpty()) {
            Text(
                text = "No books on this shelf yet.",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(vertical = AppSpacing.s2),
            )
            ShelfLine()
        } else {
            val thisWkPages = remember(books) { pagesUpdatedThisWeek(books) }
            // `shelfAccent` reads @Composable theme colors, so it
            // can't live inside a `remember` lambda. Called direct
            // — the work is a single `when` branch, cheap enough
            // to re-run per composition.
            val accent = shelfAccent(books)
            val booksScroll = rememberScrollState()
            // Crowded shelves get a 25%-shrunk THIS WK card so the
            // spines have more room to breathe. Threshold mirrors the
            // point where the row starts horizontally scrolling on a
            // typical phone (≈4 spines + the card before clipping).
            val compactCard = books.size >= COMPACT_SHELF_THRESHOLD
            // Card height stays at 75% of the spine height so the
            // panel reads as a footnote to the row of spines rather
            // than a peer of them.
            val cardHeight = (if (compactCard) COMPACT_THIS_WEEK_CARD_HEIGHT else SPINE_HEIGHT_MAX) * 0.75f
            // Books row sits on its own line — fills the parent width
            // and scrolls horizontally if the spines overflow. The
            // THIS WK card moves to a separate full-width row below
            // so it spans the same width as Header / TreesSavedStrip
            // / FilterRow above (no longer sharing the row with the
            // books).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(booksScroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                books.forEach { nb ->
                    val palette = ShelfTheme.palette(nb.colorToken)
                    BookSpine(
                        title    = nb.title.ifBlank { "Untitled" },
                        color    = palette.background,
                        onColor  = palette.onBackground,
                        height   = spineHeightFor(nb, books),
                        onClick  = { onOpenBook(nb.id) },
                    )
                }
            }
            ShelfLine()
            ThisWeekCard(
                pages    = thisWkPages,
                progress = progressToGoal(thisWkPages, goal = WEEKLY_GOAL),
                accent   = accent,
                compact  = compactCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight),
            )
        }
    }
}

private const val WEEKLY_GOAL = 30

private val SPINE_HEIGHT_MIN    = 116.dp
private val SPINE_HEIGHT_MAX    = 152.dp
private val SPINE_WIDTH         = 32.dp

// Crowded-shelf affordance: when a shelf grows past this many books,
// the THIS WK stat card shrinks to ~75% of its base height so it
// reads as a quieter footnote beneath a busy spine row. The card
// always spans the full content width.
private const val COMPACT_SHELF_THRESHOLD = 4
private val COMPACT_THIS_WEEK_CARD_HEIGHT = 114.dp   // 152dp × 0.75

// ------ Book spine ------

@Composable
private fun BookSpine(
    title: String,
    color: Color,
    onColor: Color,
    height: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(SPINE_WIDTH)
            .height(height)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // Head + tail bands — paired warm-brown hairlines inset from the
        // spine ends, evoking the binding rules on a real book's spine.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BookBand()
            BookBand()
        }
        Text(
            text = title,
            color = onColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = LocalFontWeight.current,
                fontSize = 11.sp,
                letterSpacing = 0.02.sp,
            ),
            // requiredWidth so the text frame can exceed the spine's
            // 32dp parent constraint — without it the unrotated frame
            // is clamped to 32dp and the title wraps mid-word at 6
            // chars per line. The frame leaves a 2dp gap above and
            // below the head/tail bands.
            modifier = Modifier
                .requiredWidth(height - 29.dp)
                .rotate(-90f),
        )
    }
}

@Composable
private fun BookBand() {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(AppColors.ThemeDryDeep))
        Box(Modifier.fillMaxWidth().height(0.75.dp).background(AppColors.ThemeDryDeep))
    }
}

// ------ This-week card ------

@Composable
private fun ThisWeekCard(
    pages: Int,
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    // Crowded shelves use a tighter padding ladder + smaller numerals so
    // the 25%-smaller card still reads cleanly at the reduced footprint.
    val hPad        = if (compact) AppSpacing.s3 else AppSpacing.s4
    val vPad        = if (compact) AppSpacing.s2 else AppSpacing.s3
    val rowGap      = if (compact) AppSpacing.s1 else AppSpacing.s2
    val numberSize  = if (compact) 22.sp         else 30.sp
    val barHeight   = if (compact) 4.dp          else 6.dp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(horizontal = hPad, vertical = vPad),
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        Text(
            text = "THIS WK",
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
        )
        Text(
            text = "$pages",
            color = AppColors.TextPrimary,
            fontSize = numberSize,
            fontWeight = LocalFontWeight.current,
            fontFamily = FontFamily.Serif,
        )
        Text(
            text = "pages",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.Subtle),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(barHeight)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(accent),
            )
        }
    }
}

// ------ Shelf line ------

@Composable
private fun ShelfLine() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                .background(AppColors.ThemeDryPrimary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AppColors.ThemeDryDeep),
        )
    }
}

// ================================================================== Open-todos sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenTodosSheet(
    todos: List<OpenTodoRow>,
    onDismiss: () -> Unit,
    onOpenTodo: (OpenTodoRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s5)
                .padding(top = AppSpacing.s2, bottom = AppSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
                ) {
                    Text(
                        text = "OPEN TODOS",
                        style = AppTypography.Eyebrow,
                        color = AppColors.ThemeGreenDeep,
                    )
                    Text(
                        text = when (todos.size) {
                            0    -> "Nothing open right now"
                            1    -> "1 todo across your library"
                            else -> "${todos.size} todos across your library"
                        },
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = AppColors.TextSecondary,
                    )
                }
            }

            if (todos.isEmpty()) {
                Text(
                    text = "Nothing open right now. Add a todo from any page editor \u2014 it\u2019ll show up here.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(
                        items = todos,
                        key   = { it.id },
                    ) { todo ->
                        OpenTodoItemRow(todo = todo, onOpen = { onOpenTodo(todo) })
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenTodoItemRow(
    todo: OpenTodoRow,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .background(AppColors.Subtle)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityDot(priority = todo.priority)
        Spacer(Modifier.width(AppSpacing.s3))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = todo.body.ifBlank { "Untitled todo" },
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = todoContext(todo),
                style = AppTypography.Tag,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onOpen,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open page",
                tint = AppColors.Coral,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PriorityDot(priority: Int) {
    val color = when (priority) {
        3    -> AppColors.Coral          // high
        2    -> AppColors.Warning        // medium
        1    -> AppColors.ThemeGreenPrimary // low
        else -> AppColors.BorderStrong   // none
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun todoContext(todo: OpenTodoRow): String {
    val parts = listOfNotNull(
        todo.notebookTitle.ifBlank { null },
        todo.chapterTitle.ifBlank { null },
        todo.pageTitle.ifBlank { null },
    )
    val edited = "edited ${relativeShort(todo.updatedAt)}"
    return if (parts.isEmpty()) edited else "${parts.joinToString(" \u00B7 ")} \u00B7 $edited"
}

// ================================================================== FAB

@Composable
private fun FloatingAddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(AppAccent.primary)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "New book",
            tint = AppColors.OnAccent,
            modifier = Modifier.size(26.dp),
        )
    }
}

// ================================================================== Helpers

private fun plural(n: Int): String = if (n == 1) "" else "s"

private fun computeStreak(notebooks: List<Notebook>): Int {
    if (notebooks.isEmpty()) return 0
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val floor = today.minusDays(30)
    return notebooks.asSequence()
        .map { it.updatedAt.atZone(zone).toLocalDate() }
        .filter { !it.isBefore(floor) }
        .distinct()
        .count()
}

private fun pagesUpdatedThisWeek(books: List<Notebook>): Int {
    val cutoff = Instant.now().minus(Duration.ofDays(7))
    return books.filter { it.updatedAt.isAfter(cutoff) }.sumOf { it.pageCount }
}

private fun progressToGoal(value: Int, goal: Int): Float =
    if (goal <= 0) 0f else (value.toFloat() / goal.toFloat()).coerceIn(0f, 1f)

/** Pick an accent colour for the shelf's "THIS WK" bar from the
 *  first book's palette. Falls back to coral when the shelf is
 *  empty or the token is unknown. */
@Composable
private fun shelfAccent(books: List<Notebook>): Color {
    val first = books.firstOrNull() ?: return AppColors.Coral
    return when (first.colorToken?.lowercase()) {
        "green"  -> AppColors.ThemeGreenPrimary
        "info"   -> Color(0xFF8E86DB)
        "dry"    -> AppColors.ThemeDryPrimary
        "yellow" -> AppColors.ThemeYellowPrimary
        "coral"  -> AppColors.Coral
        else     -> AppColors.Coral
    }
}

/** Give taller spines to books with more pages so the row reads
 *  like a real bookshelf. Linear between min and max, capped. */
private fun spineHeightFor(nb: Notebook, books: List<Notebook>): Dp {
    val max = (books.maxOfOrNull { it.pageCount } ?: 0).coerceAtLeast(1)
    val min = books.minOfOrNull { it.pageCount } ?: 0
    if (max == min) return (SPINE_HEIGHT_MIN + SPINE_HEIGHT_MAX) / 2
    val fraction = (nb.pageCount - min).toFloat() / (max - min).toFloat()
    val range = SPINE_HEIGHT_MAX.value - SPINE_HEIGHT_MIN.value
    return (SPINE_HEIGHT_MIN.value + range * min(1f, fraction)).dp
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
