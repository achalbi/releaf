/*
 * TasksPerspectivesView.kt
 *
 * Combined Perspectives + Kanban Boards surface — the primary view
 * mode on the Tasks screen. Reached from the view-mode switcher
 * next to the breadcrumb.
 *
 * Layout (top → bottom):
 *   - Eyebrow + serif title ("Your work") + one-line helper.
 *   - Horizontal perspective tile row — "All" (escape hatch),
 *     followed by the user's saved [PerspectiveEntity]s in
 *     sort-order, followed by a trailing "+ Add" tile that opens
 *     the [AddPerspectiveSheet].
 *   - Section divider naming the active perspective + total count.
 *   - Horizontal board-columns row — To do / Doing / Done. Each
 *     column is a vertically-scrollable stack of cards.
 *   - Bottom quick-add — the chip shows which @context the new
 *     task will land in so the user never "forgets" where they're
 *     filing it.
 *
 * Perspective CRUD:
 *   - Create  — trailing "+ Add" tile opens [AddPerspectiveSheet]
 *               with a name field and icon picker. On save, the VM
 *               writes a new [PerspectiveEntity] and the new tile
 *               appears in the row (combined flow emits).
 *   - Delete  — long-press a tile opens [DeletePerspectiveSheet]
 *               with a confirmation. "All" is not long-pressable.
 *               Deleting the active perspective falls back to All.
 *
 * Task → column mapping uses [TaskStatus.parse(task.status)].
 * Task → perspective mapping uses [extractContext] on the title.
 */

package app.releaf.mobile.features.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.perspective.PerspectiveEntity
import app.releaf.mobile.data.perspective.extractContext
import app.releaf.mobile.data.perspective.stripContext
import app.releaf.mobile.data.task.TaskEntity
import app.releaf.mobile.data.task.TaskStatus
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.LocalDate

/** Special context value meaning "no filter" — show all tasks. */
internal const val CONTEXT_ALL = "all"

/** Icon choices offered in the [AddPerspectiveSheet]. */
private val PERSPECTIVE_ICON_KEYS = listOf(
    "home", "work", "shopping_cart", "book",
    "phone", "flight", "favorite", "label",
)

// ================================================================= Top-level

@Composable
internal fun TasksPerspectivesView(
    tasks: List<TaskEntity>,
    perspectives: List<PerspectiveEntity>,
    activeContext: String,
    onContextChange: (String) -> Unit,
    today: LocalDate,
    onAddTask: (title: String) -> Unit,
    onSetStatus: (TaskEntity, TaskStatus) -> Unit,
    onEdit: (TaskEntity) -> Unit,
    onCreatePerspective: (name: String, iconKey: String) -> Unit,
    onDeletePerspective: (PerspectiveEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local UI state for CRUD sheets.
    var showAddSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PerspectiveEntity?>(null) }

    // Filter tasks by active perspective. CONTEXT_ALL = no filter.
    val activeTasks = remember(tasks, activeContext) {
        if (activeContext == CONTEXT_ALL) tasks
        else tasks.filter { extractContext(it.title) == activeContext }
    }
    val todo  = activeTasks.filter { TaskStatus.parse(it.status) == TaskStatus.Todo  }
    val doing = activeTasks.filter { TaskStatus.parse(it.status) == TaskStatus.Doing }
    val done  = activeTasks.filter { TaskStatus.parse(it.status) == TaskStatus.Done  }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 10.dp),
    ) {
        // ── Hero (compact) ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s4,
                    top    = AppSpacing.s1,
                    bottom = AppSpacing.s3,
                ),
        ) {
            Text(
                text  = "PERSPECTIVES",
                style = AppTypography.Eyebrow,
                color = AppAccent.primary,
            )
            Spacer(Modifier.height(AppSpacing.s1))
            Text(
                text  = "Your tasks",
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(AppSpacing.s1))
            Text(
                text  = heroSubtitle(activeContext, todo.size + doing.size),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        // ── Perspective tile row ──
        LazyRow(
            contentPadding = PaddingValues(horizontal = AppSpacing.s4),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            modifier = Modifier.padding(bottom = AppSpacing.s3),
        ) {
            // "All" — the escape hatch. Not long-pressable (can't delete).
            item(key = "tile-all") {
                ContextTile(
                    label     = "All",
                    icon      = Icons.AutoMirrored.Filled.FormatListBulleted,
                    openCount = tasks.count { TaskStatus.parse(it.status) != TaskStatus.Done },
                    isActive  = activeContext == CONTEXT_ALL,
                    onClick   = { onContextChange(CONTEXT_ALL) },
                    onLongPress = null,
                )
            }
            // User-saved perspectives.
            items(perspectives, key = { "tile-${it.id}" }) { p ->
                val openInCtx = tasks.count {
                    extractContext(it.title) == p.name &&
                        TaskStatus.parse(it.status) != TaskStatus.Done
                }
                ContextTile(
                    label       = "@${p.name}",
                    icon        = iconForKey(p.iconKey),
                    openCount   = openInCtx,
                    isActive    = activeContext == p.name,
                    onClick     = { onContextChange(p.name) },
                    onLongPress = { pendingDelete = p },
                )
            }
            // Trailing add tile.
            item(key = "tile-add") {
                AddTile(onClick = { showAddSheet = true })
            }
        }

        // ── Board header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = boardHeaderLabel(activeContext, activeTasks.size),
                style = AppTypography.Eyebrow,
                color = AppAccent.primary,
            )
            Spacer(Modifier.width(AppSpacing.s2))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(AppColors.BorderDefault),
            )
        }

        // ── Board columns ──
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = AppSpacing.s4,
                end   = AppSpacing.s4,
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            item(key = "col-todo") {
                BoardColumn(
                    title   = "To do",
                    dotFill = AppColors.TextTertiary,
                    count   = todo.size,
                    tasks   = todo,
                    today   = today,
                    status  = TaskStatus.Todo,
                    onEdit  = onEdit,
                    onSetStatus = onSetStatus,
                )
            }
            item(key = "col-doing") {
                BoardColumn(
                    title   = "Doing",
                    dotFill = AppColors.Warning,
                    count   = doing.size,
                    tasks   = doing,
                    today   = today,
                    status  = TaskStatus.Doing,
                    onEdit  = onEdit,
                    onSetStatus = onSetStatus,
                )
            }
            item(key = "col-done") {
                BoardColumn(
                    title   = "Done",
                    dotFill = AppColors.Success,
                    count   = done.size,
                    tasks   = done,
                    today   = today,
                    status  = TaskStatus.Done,
                    onEdit  = onEdit,
                    onSetStatus = onSetStatus,
                )
            }
        }

        // ── Context-aware quick-add ──
        // Sits as the last child of the Column. The activity handles
        // the soft-keyboard inset via adjustResize at the window
        // level, so no explicit imePadding is needed here — when the
        // keyboard opens the Column's available height shrinks and
        // the quick-add rides up with it.
        Spacer(Modifier.height(AppSpacing.s3))
        ContextQuickAdd(
            activeContext = activeContext,
            perspectives  = perspectives,
            onAdd         = onAddTask,
            modifier      = Modifier.padding(horizontal = AppSpacing.s4),
        )
    }

    // ── Add perspective sheet ──
    if (showAddSheet) {
        AddPerspectiveSheet(
            existingNames = perspectives.map { it.name }.toSet(),
            onSave = { name, icon ->
                onCreatePerspective(name, icon)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    // ── Delete perspective sheet ──
    pendingDelete?.let { target ->
        DeletePerspectiveSheet(
            perspective = target,
            onConfirm = {
                onDeletePerspective(target)
                // If the user was viewing the one they just deleted,
                // fall back to "All" so they don't stare at an empty
                // board for a filter that no longer exists.
                if (activeContext == target.name) {
                    onContextChange(CONTEXT_ALL)
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ================================================================= Context tile

@Composable
private fun ContextTile(
    label: String,
    icon: ImageVector,
    openCount: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val bg by animateColorAsState(
        targetValue = if (isActive) AppAccent.primary else AppColors.CardSolid,
        animationSpec = tween(180),
        label = "tileBg",
    )
    val fg by animateColorAsState(
        targetValue = if (isActive) AppColors.OnAccent else AppColors.TextPrimary,
        animationSpec = tween(180),
        label = "tileFg",
    )
    val subFg = if (isActive) AppColors.OnAccent.copy(alpha = 0.85f) else AppColors.TextSecondary
    val borderC = if (isActive) Color.Transparent else AppColors.BorderDefault

    // Pointer input chain — tap fires onClick; long-press (if
    // provided) fires the rename/delete affordance.
    val gestureModifier = if (onLongPress != null) {
        Modifier.pointerInput(onClick, onLongPress) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { onLongPress() },
            )
        }
    } else {
        Modifier.clickable(role = Role.Tab, onClickLabel = "Show $label") { onClick() }
    }

    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .border(1.dp, borderC, RoundedCornerShape(AppRadius.md))
            .then(gestureModifier)
            .padding(vertical = AppSpacing.s2, horizontal = AppSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(AppSpacing.s1))
        Text(
            text  = label,
            style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
        Text(
            text  = if (openCount == 1) "1 open" else "$openCount open",
            style = AppTypography.Tag,
            color = subFg,
        )
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppAccent.soft)
            .border(1.dp, AppAccent.border, RoundedCornerShape(AppRadius.md))
            .clickable(role = Role.Button, onClickLabel = "Add perspective") { onClick() }
            .padding(vertical = AppSpacing.s2, horizontal = AppSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(AppSpacing.s1))
        Text(
            text  = "Add",
            style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
            color = AppAccent.primary,
        )
        Text(
            text  = "new tile",
            style = AppTypography.Tag,
            color = AppAccent.primary,
        )
    }
}

/**
 * Map a perspective's stored [PerspectiveEntity.iconKey] to a
 * concrete Material icon. Unknown keys fall back to the generic
 * [Icons.AutoMirrored.Filled.Label].
 */
internal fun iconForKey(iconKey: String): ImageVector = when (iconKey) {
    "home"          -> Icons.Filled.Home
    "work"          -> Icons.Filled.Work
    "shopping_cart" -> Icons.Filled.ShoppingCart
    "book"          -> Icons.Filled.Book
    "phone"         -> Icons.Filled.Phone
    "flight"        -> Icons.Filled.Flight
    "favorite"      -> Icons.Filled.Favorite
    else            -> Icons.AutoMirrored.Filled.Label
}

// ================================================================= Column

@Composable
private fun BoardColumn(
    title: String,
    dotFill: Color,
    count: Int,
    tasks: List<TaskEntity>,
    today: LocalDate,
    status: TaskStatus,
    onEdit: (TaskEntity) -> Unit,
    onSetStatus: (TaskEntity, TaskStatus) -> Unit,
) {
    Column(
        modifier = Modifier.width(280.dp),
    ) {
        // Column header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s1, vertical = AppSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotFill),
            )
            Spacer(Modifier.width(AppSpacing.s2))
            Text(
                text  = title,
                style = AppTypography.Button.copy(fontWeight = FontWeight.Bold),
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.width(AppSpacing.s2))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(dotFill.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text  = count.toString(),
                    style = AppTypography.Tag.copy(fontWeight = FontWeight.Bold),
                    color = dotFill,
                )
            }
        }

        // Cards — verticalScroll wraps a plain Column (LazyColumn
        // inside a LazyRow is disallowed). `weight(1f)` gives the
        // scroll area the space remaining below the column header.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            if (tasks.isEmpty()) {
                EmptyColumnStub(status = status)
            } else {
                tasks.forEach { task ->
                    BoardCard(
                        task        = task,
                        today       = today,
                        onEdit      = { onEdit(task) },
                        onSetStatus = { target -> onSetStatus(task, target) },
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.s4))
        }
    }
}

@Composable
private fun EmptyColumnStub(status: TaskStatus) {
    val message = when (status) {
        TaskStatus.Todo  -> "Nothing to start"
        TaskStatus.Doing -> "Nothing in progress"
        TaskStatus.Done  -> "Nothing completed yet"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(vertical = AppSpacing.s4, horizontal = AppSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = message,
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )
    }
}

// ================================================================= Card

@Composable
private fun BoardCard(
    task: TaskEntity,
    today: LocalDate,
    onEdit: () -> Unit,
    onSetStatus: (TaskStatus) -> Unit,
) {
    val currentStatus = TaskStatus.parse(task.status)
    val dueParsed = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val isOverdue = currentStatus != TaskStatus.Done && dueParsed != null && dueParsed.isBefore(today)
    val isToday   = dueParsed == today

    val cardBg = when (currentStatus) {
        TaskStatus.Doing -> AppColors.WarningSoft.copy(alpha = 0.55f)
        TaskStatus.Done  -> AppColors.Muted
        TaskStatus.Todo  -> AppColors.CardSolid
    }
    val leadStripe = when {
        currentStatus == TaskStatus.Done  -> AppColors.Success
        currentStatus == TaskStatus.Doing -> AppColors.Warning
        isOverdue                         -> AppColors.Warning
        task.priority >= 3                -> AppColors.Danger
        task.priority == 2                -> AppColors.Warning
        task.priority == 1                -> AppColors.Success
        else                              -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(cardBg)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(role = Role.Button, onClickLabel = "Edit task") { onEdit() }
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(leadStripe),
            )
            Spacer(Modifier.width(AppSpacing.s2))
            Text(
                text = stripContext(task.title),
                style = AppTypography.Body.copy(
                    fontWeight = if (currentStatus == TaskStatus.Done) FontWeight.Normal else FontWeight.SemiBold,
                ),
                color = if (currentStatus == TaskStatus.Done) AppColors.TextSecondary else AppColors.TextPrimary,
                textDecoration = if (currentStatus == TaskStatus.Done) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
        }

        val hasDue = dueParsed != null
        val hasPriority = task.priority > 0 && currentStatus != TaskStatus.Done
        if (hasDue || hasPriority) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasDue) {
                    val (fg, bg) = when {
                        isOverdue -> AppColors.Warning to AppColors.WarningSoft
                        isToday   -> AppAccent.primary to AppAccent.soft
                        else      -> AppColors.Info to AppColors.InfoSoft
                    }
                    SmallChip(
                        text  = dueLabel(dueParsed!!, today, isOverdue, isToday),
                        icon  = Icons.Filled.CalendarMonth,
                        fg    = fg,
                        bg    = bg,
                    )
                }
                if (hasPriority) {
                    val (fg, bg) = priorityColors(task.priority)
                    SmallChip(
                        text  = priorityLabel(task.priority),
                        icon  = Icons.Filled.Flag,
                        fg    = fg,
                        bg    = bg,
                    )
                }
            }
        }

        when (currentStatus) {
            TaskStatus.Todo -> Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                ActionChip(
                    text       = "Start →",
                    fg         = AppColors.OnAccent,
                    bg         = AppAccent.primary,
                    onClick    = { onSetStatus(TaskStatus.Doing) },
                    labelAnnouncement = "Move to Doing",
                )
                ActionChip(
                    text       = "Finish ✓",
                    fg         = AppColors.Success,
                    bg         = AppColors.SuccessSoft,
                    onClick    = { onSetStatus(TaskStatus.Done) },
                    labelAnnouncement = "Mark done",
                )
            }
            TaskStatus.Doing -> Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                ActionChip(
                    text       = "Finish ✓",
                    fg         = AppColors.OnAccent,
                    bg         = AppColors.Success,
                    onClick    = { onSetStatus(TaskStatus.Done) },
                    labelAnnouncement = "Mark done",
                )
                ActionChip(
                    text       = "← Back",
                    fg         = AppColors.TextSecondary,
                    bg         = Color.Transparent,
                    bordered   = true,
                    onClick    = { onSetStatus(TaskStatus.Todo) },
                    labelAnnouncement = "Move back to To do",
                )
            }
            TaskStatus.Done -> Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                ActionChip(
                    text       = "↻ Reopen",
                    fg         = AppColors.TextSecondary,
                    bg         = Color.Transparent,
                    bordered   = true,
                    icon       = Icons.Filled.Refresh,
                    onClick    = { onSetStatus(TaskStatus.Todo) },
                    labelAnnouncement = "Reopen task",
                )
            }
        }
    }
}

@Composable
private fun SmallChip(
    text: String,
    icon: ImageVector,
    fg: Color,
    bg: Color,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = text,
            style = AppTypography.Tag,
            color = fg,
        )
    }
}

@Composable
private fun ActionChip(
    text: String,
    fg: Color,
    bg: Color,
    onClick: () -> Unit,
    labelAnnouncement: String,
    bordered: Boolean = false,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(bg)
            .then(
                if (bordered) Modifier.border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.sm))
                else Modifier,
            )
            .clickable(role = Role.Button, onClickLabel = labelAnnouncement) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = fg,
                modifier           = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text  = text,
            style = AppTypography.Tag.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

// ================================================================= Quick-add

@Composable
private fun ContextQuickAdd(
    activeContext: String,
    perspectives: List<PerspectiveEntity>,
    onAdd: (title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable { mutableStateOf("") }
    val commit = commit@{
        if (title.isBlank()) return@commit
        onAdd(title.trim())
        title = ""
    }

    // Partial-@-tag detector — finds a trailing `@xxx` the user is
    // typing right now so we can surface the matching perspectives
    // as an autocomplete row above the input.
    val partial     = partialTagAtEnd(title)
    val suggestions = if (partial == null) emptyList()
                      else matchingPerspectives(perspectives, partial.partialText)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // Suggestion row — fades / slides in when the user types `@`
        // and disappears when the partial ends (whitespace or end of
        // string past the word).
        AnimatedVisibility(
            visible = suggestions.isNotEmpty(),
            enter   = fadeIn() + expandVertically(),
            exit    = fadeOut() + shrinkVertically(),
        ) {
            SuggestionRow(
                suggestions = suggestions,
                onSelect    = { name ->
                    val p = partial ?: return@SuggestionRow
                    val before = title.substring(0, p.atIndex)
                    val after  = title.substring(p.atIndex + 1 + p.partialText.length)
                    // Append a trailing space so the user can type
                    // the task title straight after the tag without
                    // first tapping space themselves.
                    title = before + "@" + name + " " + after
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.CardSolid)
                .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
                .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(AppAccent.soft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = null,
                    tint               = AppAccent.primary,
                    modifier           = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.s2))
            if (activeContext != CONTEXT_ALL) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppAccent.soft)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "@$activeContext",
                        style = AppTypography.Tag.copy(fontWeight = FontWeight.SemiBold),
                        color = AppAccent.primary,
                    )
                }
                Spacer(Modifier.width(AppSpacing.s2))
            }
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppAccent.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            text  = if (activeContext == CONTEXT_ALL) "Add a task… type @ to file it"
                                    else "Add to @$activeContext…",
                            style = AppTypography.Body,
                            color = AppColors.TextTertiary,
                        )
                    }
                    inner()
                },
            )
            val canAdd = title.isNotBlank()
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (canAdd) AppAccent.primary else AppColors.Muted)
                    .clickable(
                        enabled = canAdd,
                        role = Role.Button,
                        onClickLabel = "Add task",
                    ) { commit() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Check,
                    contentDescription = null,
                    tint               = if (canAdd) AppColors.OnAccent else AppColors.TextTertiary,
                    modifier           = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Horizontally-scrollable list of tile chips — tap one to insert
 * `@name ` at the `@` position the user is currently typing.
 */
@Composable
private fun SuggestionRow(
    suggestions: List<PerspectiveEntity>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "Insert",
            style = AppTypography.Tag,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(start = AppSpacing.s1, end = 2.dp),
        )
        suggestions.forEach { p ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppAccent.soft)
                    .border(1.dp, AppAccent.border, RoundedCornerShape(AppRadius.pill))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Insert @${p.name}",
                    ) { onSelect(p.name) }
                    .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = iconForKey(p.iconKey),
                    contentDescription = null,
                    tint               = AppAccent.primary,
                    modifier           = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "@${p.name}",
                    style = AppTypography.Tag.copy(fontWeight = FontWeight.SemiBold),
                    color = AppAccent.primary,
                )
            }
        }
    }
}

/** Small value type for the parsed partial-@-tag at the end of the input. */
private data class PartialTag(val atIndex: Int, val partialText: String)

/**
 * If the text ends with a partial `@tag` the user is typing, return
 * the `@` index and the characters they've typed after it. Returns
 * null if there's no `@` in the text, if what follows the last `@`
 * contains whitespace (so the user has moved past the tag already),
 * or if the `@` is preceded by a non-whitespace character (so it's
 * the middle of an email address or similar, not a mention).
 */
private fun partialTagAtEnd(text: String): PartialTag? {
    val idx = text.lastIndexOf('@')
    if (idx < 0) return null
    val after = text.substring(idx + 1)
    if (after.any { it.isWhitespace() }) return null
    if (idx > 0 && !text[idx - 1].isWhitespace()) return null
    return PartialTag(atIndex = idx, partialText = after)
}

/**
 * Filter perspectives whose names match the partial the user is
 * typing. Empty partial (the user just typed `@`) shows every
 * perspective — same set as the tile row. Capped at six so the
 * suggestion row doesn't dominate the screen.
 */
private fun matchingPerspectives(
    perspectives: List<PerspectiveEntity>,
    partial: String,
): List<PerspectiveEntity> {
    val needle = partial.lowercase()
    return if (needle.isEmpty()) perspectives.take(6)
           else perspectives.filter { it.name.startsWith(needle) }.take(6)
}

// ================================================================= Add sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPerspectiveSheet(
    existingNames: Set<String>,
    onSave: (name: String, iconKey: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var rawName by rememberSaveable { mutableStateOf("") }
    var iconKey by rememberSaveable { mutableStateOf("label") }
    val focusRequester = remember { FocusRequester() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Pull the soft keyboard straight onto the name field when the
    // sheet opens — saves a tap and signals where the user should
    // type next.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val normalized = normalizeForPreview(rawName)
    val isDuplicate = normalized.isNotEmpty() && normalized in existingNames
    val canSave = normalized.isNotEmpty() && !isDuplicate

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.BorderStrong),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = "New perspective",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AppColors.InputBg)
                        .clickable(onClickLabel = "Close") { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = null,
                        tint               = AppColors.TextSecondary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }

            // Name input
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
                Text(
                    text  = "NAME",
                    style = AppTypography.Eyebrow,
                    color = AppColors.TextSecondary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.InputBg)
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "@",
                        style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                        color = AppAccent.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    BasicTextField(
                        value = rawName,
                        onValueChange = { rawName = it },
                        singleLine = true,
                        textStyle = AppTypography.Body.copy(
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        cursorBrush = SolidColor(AppAccent.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (canSave) onSave(rawName, iconKey)
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            if (rawName.isEmpty()) {
                                Text(
                                    text  = "personal",
                                    style = AppTypography.Body,
                                    color = AppColors.TextTertiary,
                                )
                            }
                            inner()
                        },
                    )
                }
                // Preview line: shows the normalised tag, or a
                // collision warning if that name already exists.
                val helper = when {
                    rawName.isBlank() -> "Lower-case letters, numbers, - and _. Used as the @tag on tasks."
                    isDuplicate       -> "@$normalized already exists — pick a different name."
                    else              -> "Tasks tagged @$normalized will land here."
                }
                val helperColor = if (isDuplicate) AppColors.Danger else AppColors.TextSecondary
                Text(
                    text  = helper,
                    style = AppTypography.Meta,
                    color = helperColor,
                )
            }

            // Icon picker
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
                Text(
                    text  = "ICON",
                    style = AppTypography.Eyebrow,
                    color = AppColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PERSPECTIVE_ICON_KEYS.take(4).forEach { key ->
                        IconPickOption(
                            iconKey = key,
                            isActive = key == iconKey,
                            onClick = { iconKey = key },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PERSPECTIVE_ICON_KEYS.drop(4).forEach { key ->
                        IconPickOption(
                            iconKey = key,
                            isActive = key == iconKey,
                            onClick = { iconKey = key },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.s1))

            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
                        .clickable(role = Role.Button, onClickLabel = "Cancel") { onDismiss() }
                        .padding(vertical = AppSpacing.s3),
                ) {
                    Text("Cancel", style = AppTypography.Button, color = AppColors.TextSecondary)
                }
                val bg = if (canSave) AppAccent.primary else AppColors.Muted
                val fg = if (canSave) AppColors.OnAccent else AppColors.TextTertiary
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1.4f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(bg)
                        .clickable(
                            enabled = canSave,
                            role = Role.Button,
                            onClickLabel = "Save new perspective",
                        ) { onSave(rawName, iconKey) }
                        .padding(vertical = AppSpacing.s3),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Add,
                        contentDescription = null,
                        tint               = fg,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Create perspective", style = AppTypography.Button, color = fg)
                }
            }
        }
    }
}

@Composable
private fun IconPickOption(
    iconKey: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isActive) AppAccent.primary else AppColors.InputBg
    val fg = if (isActive) AppColors.OnAccent else AppColors.TextPrimary
    val border = if (isActive) AppAccent.primary else AppColors.BorderDefault
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(AppRadius.md))
            .clickable(role = Role.RadioButton, onClickLabel = "Use $iconKey icon") { onClick() }
            .padding(vertical = AppSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = iconForKey(iconKey),
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(20.dp),
        )
    }
}

// ================================================================= Delete sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeletePerspectiveSheet(
    perspective: PerspectiveEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
        dragHandle       = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppColors.BorderStrong),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.Danger.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint               = AppColors.Danger,
                        modifier           = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "Delete @${perspective.name}?",
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text  = "The tile goes away. Tasks tagged @${perspective.name} keep their tag and stay visible in All.",
                        style = AppTypography.Meta,
                        color = AppColors.TextSecondary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
                        .clickable(role = Role.Button, onClickLabel = "Cancel") { onDismiss() }
                        .padding(vertical = AppSpacing.s3),
                ) {
                    Text("Cancel", style = AppTypography.Button, color = AppColors.TextSecondary)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.Danger)
                        .clickable(role = Role.Button, onClickLabel = "Delete perspective") { onConfirm() }
                        .padding(vertical = AppSpacing.s3),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint               = AppColors.OnAccent,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", style = AppTypography.Button, color = AppColors.OnAccent)
                }
            }
        }
    }
}

// ================================================================= Text helpers

/**
 * Subtitle below the "Your tasks" heading. Counts only OPEN work
 * (To do + Doing) — Done tasks are excluded so the headline
 * number matches the tile badges and reads as "how much is on my
 * plate" rather than the total across all columns.
 */
private fun heroSubtitle(activeContext: String, openCount: Int): String {
    val total = if (openCount == 1) "1 task" else "$openCount tasks"
    return when (activeContext) {
        CONTEXT_ALL -> "$total across every perspective"
        else        -> "$total in @$activeContext — long-press a tile to remove"
    }
}

private fun boardHeaderLabel(activeContext: String, visibleCount: Int): String {
    val count = if (visibleCount == 1) "1 TASK" else "$visibleCount TASKS"
    return when (activeContext) {
        CONTEXT_ALL -> "ALL · BOARD · $count"
        else        -> "@${activeContext.uppercase()} · BOARD · $count"
    }
}

private fun dueLabel(date: LocalDate, today: LocalDate, isOverdue: Boolean, isToday: Boolean): String {
    if (isToday) return "Today"
    if (isOverdue) {
        val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
        return if (days == 1L) "1d overdue" else "${days}d overdue"
    }
    if (date == today.plusDays(1)) return "Tomorrow"
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    if (days in 2..6) return "in ${days}d"
    return formatShortDate(date.toString())
}

/**
 * Mirror of [app.releaf.mobile.data.perspective.PerspectiveRepository.normalizeName]
 * but pure — we call it on every keystroke to drive the preview
 * string ("Tasks tagged @foo will land here"), and the repository
 * method is suspend-fn-owned. Keep the rules identical to avoid
 * surprises between the sheet preview and the saved row.
 */
private fun normalizeForPreview(raw: String): String {
    val trimmed = raw.trim().removePrefix("@").lowercase()
    val dashed  = trimmed.replace(Regex("[\\s./\\\\]+"), "-")
    val cleaned = dashed.replace(Regex("[^a-z0-9_-]"), "")
    return cleaned.replace(Regex("[-_]{2,}"), "-").trim('-', '_')
}
