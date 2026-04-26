/*
 * TasksScreen.kt
 *
 * Workspace-level task list. Opens from the Tasks card on the home
 * screen.
 *
 * UX model:
 *   • Tap a row           → opens an Edit bottom sheet (full edit:
 *                           title, notes, priority, due, delete).
 *   • Tap the circle      → toggles completed. Haptic nudge on change.
 *   • Tap the trash icon  → soft-delete with Undo snackbar (4s).
 *   • Scroll past add     → floating "+" FAB that scrolls back and
 *                           focuses the input for rapid capture.
 *   • Completed section   → collapsible; default-collapsed when you
 *                           have more than a handful of finished tasks
 *                           so the list stays scannable.
 *
 * Layout (top → bottom):
 *   - Breadcrumb row (Home ▸ Tasks)
 *   - Hero card: editorial title + live summary + open/overdue/done
 *     stat pills + animated completion ring.
 *   - Segmented filter (All / Today / Upcoming / Done).
 *   - Quick-add card: title, color-coded priority flag, due-date chip
 *     with Today/Tomorrow shortcuts, animated Add pill. Keeps focus
 *     after commit so the user can keep typing.
 *   - Smart-grouped list: Overdue / Today / This Week / Later / No
 *     Date / Completed — each header shows a count chip.
 *
 * Everything that paints color reads from [AppAccent] / [AppColors]
 * so the screen retints with the active palette.
 */

package app.releaf.mobile.features.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.perspective.PerspectiveEntity
import app.releaf.mobile.data.perspective.extractContext
import app.releaf.mobile.data.reminder.ReminderEntity
import app.releaf.mobile.data.task.TaskEntity
import app.releaf.mobile.data.task.TaskStatus
import app.releaf.mobile.ui.components.BreadcrumbSegment
import app.releaf.mobile.ui.components.Breadcrumbs
import app.releaf.mobile.ui.components.DotGridBackground
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.TaskDefaultView
import app.releaf.mobile.ui.theme.UiPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import app.releaf.mobile.ui.theme.LocalFontWeight

// ---------------------------------------------------------------- Filters & views

private enum class TaskFilter(val label: String) {
    All("All"),
    Today("Today"),
    Upcoming("Upcoming"),
    Done("Done"),
}

// The two top-level view modes live in
// [app.releaf.mobile.ui.theme.TaskDefaultView] — keeping the enum
// in the preferences module means the Settings screen's picker,
// the in-screen switcher, and the persisted default all share one
// source of truth.

// Context parsing (extractContext / stripContext / CONTEXT_REGEX)
// lives in `app.releaf.mobile.data.perspective.ContextParser` so the
// ViewModel can reuse it without depending on UI code.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf(TaskFilter.All) }

    // The view-mode lives on [UiPreferences] so it behaves as both
    // "the default the Settings screen sets" AND "what the user
    // last picked from the in-screen switcher" — they write to the
    // same place, so there's no divergence. SharedPreferences is
    // already scoped to the app, so re-entering the screen reads
    // back whatever was last selected without an explicit saver.
    val context     = LocalContext.current
    val uiPrefs     = remember(context) { UiPreferences.get(context) }
    val uiPrefsState by uiPrefs.state.collectAsState()
    val viewMode    = uiPrefsState.defaultTaskView

    // Active perspective — [CONTEXT_ALL] shows the entire board,
    // named contexts (@home, @work, …) filter it to that perspective.
    var activeContext by rememberSaveable { mutableStateOf(CONTEXT_ALL) }
    val today = remember { LocalDate.now() }

    // Page-level state — hoisted so actions can live at the top and
    // child rows stay stateless. Editing pops a bottom sheet; deletes
    // route through a snackbar with Undo.
    var editingTask      by remember { mutableStateOf<TaskEntity?>(null) }
    // Task pending deletion — when non-null, the confirmation
    // ModalBottomSheet is rendered. Only the List view's inline
    // trash icon populates this; the Edit sheet's own Delete button
    // already lives in a focused context and goes straight to the
    // undo-snackbar flow.
    var pendingDeleteTask by remember { mutableStateOf<TaskEntity?>(null) }
    var completedExpanded by rememberSaveable { mutableStateOf(false) }

    val snackbarHost = remember { SnackbarHostState() }
    val scope        = rememberCoroutineScope()
    val haptics      = LocalHapticFeedback.current
    val listState    = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Show a floating Add pill once the quick-add has scrolled out of
    // view. Uses derivedStateOf so recompositions only fire on the
    // cross-threshold tick, not every pixel scroll.
    //
    // The breadcrumb lives OUTSIDE the LazyColumn now, so the List
    // view's indices shift down by one: hero=0, filters=1,
    // quick-add=2. FAB starts showing as the filters row is about to
    // scroll off (quick-add following right behind).
    //
    // Also gated on List mode — the FAB scrolls the list's
    // LazyColumn back to top and focuses its quick-add. The
    // Perspectives view has its own anchored quick-add at the bottom
    // already, so the FAB would be redundant there.
    val showFab by remember {
        derivedStateOf {
            viewMode == TaskDefaultView.List && (
                listState.firstVisibleItemIndex > 1 ||
                    (listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset > 40)
            )
        }
    }

    // Centralized delete → undo flow. Called from both the inline row
    // trash and the Edit sheet's Delete button.
    val deleteWithUndo: (TaskEntity) -> Unit = remember(viewModel, snackbarHost, scope) {
        { task ->
            viewModel.deleteTask(task.id)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                val clipped = if (task.title.length > 32) task.title.take(32) + "…" else task.title
                val result = snackbarHost.showSnackbar(
                    message      = "Deleted \"$clipped\"",
                    actionLabel  = "Undo",
                    duration     = SnackbarDuration.Short,
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoDelete(task.id)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
            // Tap-outside-to-defocus — detectTapGestures only fires
            // on taps that nothing downstream consumed, so button /
            // card / row clicks still work as normal. A tap in an
            // empty region (the gutter between rows, the hero
            // background, or the stripe of canvas below the quick-
            // add) drops keyboard focus and hides the soft keyboard.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
    ) {
        DotGridBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            // ── Breadcrumb + view-mode switcher ──
            // Breadcrumb lives outside the LazyColumn now so it stays
            // put while the list scrolls, and the small icon-only
            // view-mode switcher sits on its right so toggling
            // List ↔ Perspectives is a one-tap move from anywhere on
            // the page.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AppSpacing.s4,
                        vertical   = AppSpacing.s3,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Breadcrumbs(
                    segments = listOf(
                        BreadcrumbSegment(label = "Home", onTap = onBack),
                        BreadcrumbSegment(label = "Tasks"),
                    ),
                    modifier = Modifier.weight(1f),
                )
                ViewModeSwitcher(
                    selected = viewMode,
                    onSelect = {
                        uiPrefs.setDefaultTaskView(it)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                )
            }

            // ── View dispatch ──
            when (viewMode) {
                TaskDefaultView.List -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = AppSpacing.s10,
                    ),
                ) {
                    item(key = "hero") {
                        HeroCard(
                            state = state,
                            today = today,
                            modifier = Modifier
                                .padding(horizontal = AppSpacing.s4)
                                .padding(bottom = AppSpacing.s3),
                        )
                    }

                    item(key = "filters") {
                        FilterTabs(
                            selected = filter,
                            onSelect = {
                                filter = it
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            modifier = Modifier
                                .padding(horizontal = AppSpacing.s4)
                                .padding(bottom = AppSpacing.s3),
                        )
                    }

                    item(key = "quick-add") {
                        QuickAddCard(
                            onAdd = { title, dueDate, priority ->
                                viewModel.addTask(title, dueDate, priority)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            today = today,
                            focusRequester = focusRequester,
                            modifier = Modifier.padding(horizontal = AppSpacing.s4),
                        )
                        Spacer(Modifier.height(AppSpacing.s4))
                    }

                    val filtered = filteredTasks(state.tasks, filter, today)
                    if (filtered.isEmpty() && !state.isLoading) {
                        item(key = "empty") {
                            EmptyState(
                                filter = filter,
                                onFocus = {
                                    scope.launch {
                                        listState.animateScrollToItem(0)
                                        focusRequester.requestFocus()
                                    }
                                },
                            )
                        }
                    } else {
                        val groups = groupedTasks(filtered, filter, today)

                        // Auto-collapse Completed by default when it's
                        // long enough to crowd the list — keeps open
                        // tasks as the focal point. Plain val: we're
                        // inside LazyListScope (not @Composable), so no
                        // `remember` here.
                        val autoCollapseDone =
                            (groups.firstOrNull { it.first.id == "done" }?.second?.size ?: 0) > 4
                        val doneCollapsed = when {
                            filter == TaskFilter.All -> autoCollapseDone && !completedExpanded
                            else -> false
                        }

                        for ((section, tasks) in groups) {
                            if (tasks.isEmpty()) continue
                            val collapsible = section.id == "done" && filter == TaskFilter.All
                            val collapsed   = collapsible && doneCollapsed

                            item(key = "hdr-${section.id}") {
                                SectionHeader(
                                    label        = section.label,
                                    count        = tasks.size,
                                    tint         = section.tint(),
                                    collapsible  = collapsible,
                                    collapsed    = collapsed,
                                    onToggle     = {
                                        if (collapsible) completedExpanded = !completedExpanded
                                    },
                                    modifier = Modifier.padding(
                                        start  = AppSpacing.s4,
                                        end    = AppSpacing.s4,
                                        top    = AppSpacing.s3,
                                        bottom = AppSpacing.s2,
                                    ),
                                )
                            }
                            if (!collapsed) {
                                items(tasks, key = { it.id }) { task ->
                                    TaskRow(
                                        task     = task,
                                        today    = today,
                                        onEdit   = { editingTask = task },
                                        onToggle = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleCompleted(task.id, !task.completed)
                                        },
                                        // Route through the confirm
                                        // sheet rather than straight
                                        // to deleteWithUndo — the
                                        // inline trash is easy to
                                        // mis-tap on a dense list.
                                        onDelete = { pendingDeleteTask = task },
                                        modifier = Modifier.padding(
                                            horizontal = AppSpacing.s4,
                                            vertical   = 3.dp,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                TaskDefaultView.Perspectives -> TasksPerspectivesView(
                    tasks               = state.tasks,
                    perspectives        = state.perspectives,
                    activeContext       = activeContext,
                    onContextChange     = {
                        activeContext = it
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    today               = today,
                    onAddTask           = { title ->
                        // When a perspective is active, auto-prepend
                        // its @tag so the new task files into the
                        // right column. If the user already typed an
                        // explicit @tag (or we're in "All" mode),
                        // their input wins.
                        val fullTitle = when {
                            activeContext == CONTEXT_ALL      -> title
                            extractContext(title) != null     -> title
                            else                              -> "@$activeContext $title"
                        }
                        viewModel.addTask(fullTitle, null, 0)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onSetStatus         = { task, status ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setStatus(task.id, status)
                    },
                    onEdit              = { editingTask = it },
                    onCreatePerspective = { name, iconKey ->
                        viewModel.createPerspective(name, iconKey)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDeletePerspective = { perspective ->
                        viewModel.deletePerspective(perspective.id)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    modifier            = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

        // ── Floating Add FAB ──
        // List-mode only; the Perspectives view has its own anchored
        // quick-add at the bottom, so the FAB would be redundant.
        AnimatedVisibility(
            visible = showFab,
            enter   = scaleIn(tween(180)) + fadeIn(tween(180)),
            exit    = scaleOut(tween(140)) + fadeOut(tween(140)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = AppSpacing.s4, bottom = AppSpacing.s10)
                .navigationBarsPadding(),
        ) {
            AddFab {
                scope.launch {
                    listState.animateScrollToItem(0)
                    focusRequester.requestFocus()
                }
            }
        }

        // ── Snackbar host (for delete-undo) ──
        SnackbarHost(
            hostState = snackbarHost,
            modifier  = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3)
                .navigationBarsPadding(),
        ) { data ->
            Snackbar(
                containerColor = AppColors.CardSolid,
                contentColor   = AppColors.TextPrimary,
                actionColor    = AppAccent.primary,
                shape          = RoundedCornerShape(AppRadius.md),
                snackbarData   = data,
            )
        }
    }

    // ── Edit bottom sheet ──
    // Rendered outside the Box so it anchors to window bottom, not the
    // screen's Box.
    editingTask?.let { task ->
        // `remember(task.id)` keeps the same Flow instance across
        // recompositions for the same task — without it, every tick
        // would allocate a new Flow and kill `collectAsState`'s
        // backing state, blinking the chip.
        val reminderFlow = remember(task.id) { viewModel.observeReminderForTask(task.id) }
        EditTaskSheet(
            task            = task,
            today           = today,
            reminderFlow    = reminderFlow,
            onSetReminder   = { ms -> viewModel.setTaskReminder(task, ms) },
            onClearReminder = { viewModel.clearTaskReminder(task.id) },
            onSave          = {
                viewModel.updateTask(it)
                editingTask = null
            },
            onDelete        = {
                deleteWithUndo(task)
                editingTask = null
            },
            onDismiss       = { editingTask = null },
        )
    }

    // ── Delete-confirmation sheet ──
    // Confirms the destructive action, then falls through to
    // `deleteWithUndo` — the undo snackbar is still available as a
    // second safety net for accidental confirms.
    pendingDeleteTask?.let { task ->
        DeleteTaskConfirmSheet(
            task      = task,
            onConfirm = {
                deleteWithUndo(task)
                pendingDeleteTask = null
            },
            onDismiss = { pendingDeleteTask = null },
        )
    }
}

// ================================================================= View-mode switcher

/**
 * Icon-only segmented pill sitting on the right of the breadcrumb.
 * Two segments for now — List and Perspectives — with a soft
 * card-coloured fill on the active one and the accent tint on its
 * icon. Deliberately small: it's navigation chrome, not a headline.
 */
@Composable
private fun ViewModeSwitcher(
    selected: TaskDefaultView,
    onSelect: (TaskDefaultView) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.InputBg)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TaskDefaultView.values().forEach { mode ->
            val isSel = mode == selected
            val bg by animateColorAsState(
                targetValue = if (isSel) AppColors.CardSolid else Color.Transparent,
                animationSpec = tween(180),
                label = "switchBg",
            )
            val fg by animateColorAsState(
                targetValue = if (isSel) AppAccent.primary else AppColors.TextSecondary,
                animationSpec = tween(180),
                label = "switchFg",
            )
            val border = if (isSel) AppColors.BorderDefault else Color.Transparent
            val clickLabel = when (mode) {
                TaskDefaultView.List         -> "Show list view"
                TaskDefaultView.Perspectives -> "Show perspectives view"
            }
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 30.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(AppRadius.pill))
                    .clickable(role = Role.Tab, onClickLabel = clickLabel) { onSelect(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (mode) {
                        TaskDefaultView.List         -> Icons.AutoMirrored.Filled.FormatListBulleted
                        TaskDefaultView.Perspectives -> Icons.Filled.ViewColumn
                    },
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

// ================================================================= Hero card

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(
    state: TasksUiState,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val total = state.tasks.size
    val open  = state.openCount
    val done  = state.doneCount
    val overdue = state.tasks.count { t ->
        !t.completed && t.dueDate?.let { runCatching { LocalDate.parse(it).isBefore(today) }.getOrDefault(false) } == true
    }
    val progress = if (total == 0) 0f else done.toFloat() / total.toFloat()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TASKS",
                style = AppTypography.Eyebrow,
                color = AppAccent.primary,
            )
            Spacer(Modifier.height(AppSpacing.s1))
            Text(
                text = "Your tasks",
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(AppSpacing.s1))
            Text(
                text = summaryLine(state, today, overdue),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(AppSpacing.s3))

            // Stat pills — only rendered when non-zero, so we never
            // ship the classic "0 done" clutter when the list is fresh.
            // FlowRow keeps the spacing consistent if the set ever wraps
            // (rare on phone widths but costs us nothing).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                verticalArrangement   = Arrangement.spacedBy(AppSpacing.s1),
            ) {
                if (open > 0) {
                    StatPill(
                        count = open,
                        label = "open",
                        fg    = AppAccent.primary,
                        bg    = AppAccent.soft,
                    )
                }
                if (overdue > 0) {
                    StatPill(
                        count = overdue,
                        label = "overdue",
                        fg    = AppColors.Warning,
                        bg    = AppColors.WarningSoft,
                    )
                }
                if (done > 0) {
                    StatPill(
                        count = done,
                        label = "done",
                        fg    = AppColors.Success,
                        bg    = AppColors.SuccessSoft,
                    )
                }
            }
        }

        Spacer(Modifier.size(AppSpacing.s3))

        ProgressRing(
            progress = progress,
            primary  = AppAccent.primary,
            track    = AppColors.BorderDefault,
            donePct  = "${(progress * 100).toInt()}%",
        )
    }
}

@Composable
private fun StatPill(count: Int, label: String, fg: Color, bg: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .padding(horizontal = AppSpacing.s2, vertical = 4.dp)
            .semantics {
                stateDescription = "$count $label"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = count.toString(),
            style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
            color = fg,
        )
        Spacer(Modifier.size(3.dp))
        Text(
            text = label,
            style = AppTypography.Tag,
            color = fg,
        )
    }
}

@Composable
private fun ProgressRing(
    progress: Float,
    primary: Color,
    track: Color,
    donePct: String,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 650),
        label = "taskProgress",
    )
    Box(
        modifier = Modifier
            .size(72.dp)
            .semantics { stateDescription = "$donePct complete" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset  = stroke / 2
            drawArc(
                color     = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter  = false,
                topLeft    = Offset(inset, inset),
                size       = Size(size.width - stroke, size.height - stroke),
                style      = Stroke(width = stroke),
            )
            drawArc(
                color     = primary,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter  = false,
                topLeft    = Offset(inset, inset),
                size       = Size(size.width - stroke, size.height - stroke),
                style      = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Text(
            text  = donePct,
            style = AppTypography.Button.copy(fontWeight = LocalFontWeight.current, fontSize = 13.sp),
            color = AppColors.TextPrimary,
        )
    }
}

// ================================================================= Filter tabs

@Composable
private fun FilterTabs(
    selected: TaskFilter,
    onSelect: (TaskFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = TaskFilter.values()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.InputBg)
            .padding(4.dp),
    ) {
        val totalWidth = maxWidth
        val segmentWidth = totalWidth / options.size
        val targetIndex = options.indexOf(selected).coerceAtLeast(0)
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * targetIndex,
            animationSpec = tween(durationMillis = 220),
            label = "filterIndicator",
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .size(width = segmentWidth, height = 36.dp)
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.CardSolid)
                .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill)),
        )

        Row(Modifier.fillMaxWidth()) {
            options.forEach { opt ->
                val isSel = opt == selected
                val fg by animateColorAsState(
                    targetValue = if (isSel) AppAccent.primary else AppColors.TextSecondary,
                    animationSpec = tween(durationMillis = 180),
                    label = "filterFg",
                )
                Box(
                    modifier = Modifier
                        .size(width = segmentWidth, height = 36.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .clickable(
                            role = Role.Tab,
                            onClickLabel = "Show ${opt.label.lowercase()} tasks",
                        ) { onSelect(opt) }
                        .semantics {
                            stateDescription = if (isSel) "Selected" else "Not selected"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = opt.label,
                        style = AppTypography.Button.copy(
                            fontWeight = if (isSel) LocalFontWeight.current else LocalFontWeight.current,
                        ),
                        color = fg,
                    )
                }
            }
        }
    }
}

// ================================================================= Quick-add

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickAddCard(
    onAdd: (title: String, dueDate: String?, priority: Int) -> Unit,
    today: LocalDate,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var dueDate by rememberSaveable { mutableStateOf<String?>(null) }
    var priority by rememberSaveable { mutableStateOf(0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showPriorityMenu by remember { mutableStateOf(false) }

    // Commit is idempotent — returns early if empty. After a commit we
    // clear the inputs but deliberately *don't* release focus; users
    // adding several tasks in a row stay in the flow.
    val commit = submit@{
        if (title.isBlank()) return@submit
        onAdd(title, dueDate, priority)
        title = ""
        dueDate = null
        priority = 0
    }

    val focused = title.isNotBlank()
    val borderColor by animateColorAsState(
        targetValue = if (focused) AppAccent.border else AppColors.BorderDefault,
        animationSpec = tween(180),
        label = "addBorder",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, borderColor, RoundedCornerShape(AppRadius.lg))
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppAccent.soft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = null,
                    tint               = AppAccent.primary,
                    modifier           = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(AppSpacing.s2))
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppAccent.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            "Add a task…",
                            style = AppTypography.Body,
                            color = AppColors.TextTertiary,
                        )
                    }
                    inner()
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColors.BorderDefault),
        )

        // Date row — due-date chip + Today / Tomorrow quick-picks,
        // always on one line. Wrapped in a horizontal scroll so the
        // row never word-breaks a chip on a very narrow device; on a
        // normal phone the three chips fit comfortably without
        // scrolling. Deliberately a plain Row (no FlowRow) so
        // "Tomorrow" can't jump onto its own line.
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            verticalAlignment     = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            DueDateChip(
                dueDate = dueDate,
                today   = today,
                onTap   = { showDatePicker = true },
                onClear = { dueDate = null },
            )
            if (dueDate == null) {
                QuickDatePill("Today")    { dueDate = today.toString() }
                QuickDatePill("Tomorrow") { dueDate = today.plusDays(1).toString() }
            }
        }

        // Action row — priority chip bottom-left, Add button
        // bottom-right. `weight(1f)` on the spacer pushes Add to the
        // edge so the CTA always lives in the same spot no matter
        // which priority level is selected.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PriorityFlagChip(
                priority = priority,
                onClick  = { showPriorityMenu = !showPriorityMenu },
            )
            Spacer(Modifier.weight(1f))
            val canAdd = title.isNotBlank()
            val bg by animateColorAsState(
                targetValue = if (canAdd) AppAccent.primary else AppColors.Muted,
                animationSpec = tween(180),
                label = "addBg",
            )
            val fg by animateColorAsState(
                targetValue = if (canAdd) AppColors.OnAccent else AppColors.TextTertiary,
                animationSpec = tween(180),
                label = "addFg",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(bg)
                    .clickable(
                        enabled = canAdd,
                        role = Role.Button,
                        onClickLabel = "Add task",
                    ) { commit() }
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = null,
                    tint               = fg,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("Add task", style = AppTypography.Button, color = fg)
            }
        }

        AnimatedVisibility(
            visible = showPriorityMenu,
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.md))
                    .background(AppColors.InputBg)
                    .padding(AppSpacing.s2),
            ) {
                (0..3).forEach { level ->
                    PriorityOption(
                        level  = level,
                        active = level == priority,
                        onTap  = {
                            priority = level
                            showPriorityMenu = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val initialMs = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            ?: today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = pickerState.selectedDateMillis
                    if (ms != null) {
                        dueDate = Instant.ofEpochMilli(ms)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                    }
                    showDatePicker = false
                }) { Text("Set due date", color = AppAccent.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        ) {
            DatePicker(state = pickerState, title = null, headline = null)
        }
    }
}

@Composable
private fun QuickDatePill(label: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.InputBg)
            .clickable(onClickLabel = "Set due date to $label") { onTap() }
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun PriorityFlagChip(priority: Int, onClick: () -> Unit) {
    val (fg, bg) = priorityColors(priority)
    // Short label on the chip — "Priority" reads as a CTA when nothing
    // is set, and the level names ("Low"/"Medium"/"High") are already
    // tight. Saves ~40dp vs "No priority" and prevents the chip row
    // from crowding out the due-date quick-picks on narrow phones.
    val chipLabel = if (priority == 0) "Priority" else priorityLabel(priority)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .border(
                1.dp,
                if (priority == 0) AppColors.BorderDefault else Color.Transparent,
                RoundedCornerShape(AppRadius.pill),
            )
            .clickable(onClickLabel = "Change priority") { onClick() }
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Flag,
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text  = chipLabel,
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = fg,
        )
    }
}

@Composable
private fun PriorityOption(
    level: Int,
    active: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (fg, bg) = priorityColors(level)
    val border = if (active) fg else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(AppRadius.sm))
            .clickable(
                role = Role.RadioButton,
                onClickLabel = "Set ${priorityLabel(level).lowercase()} priority",
            ) { onTap() }
            .padding(vertical = AppSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector        = Icons.Filled.Flag,
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = when (level) { 0 -> "None"; 1 -> "Low"; 2 -> "Med"; else -> "High" },
            style = AppTypography.Tag,
            color = fg,
        )
    }
}

@Composable
private fun DueDateChip(
    dueDate: String?,
    today: LocalDate,
    onTap: () -> Unit,
    onClear: () -> Unit,
) {
    val parsed = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val (fg, bg, label) = when {
        parsed == null -> Triple(AppColors.TextSecondary, AppColors.InputBg, "Due date")
        parsed.isBefore(today) -> Triple(AppColors.Warning, AppColors.WarningSoft, "Overdue · ${formatShortDate(dueDate!!)}")
        parsed == today -> Triple(AppAccent.primary, AppAccent.soft, "Today")
        parsed == today.plusDays(1) -> Triple(AppColors.Info, AppColors.InfoSoft, "Tomorrow")
        else -> Triple(AppColors.Info, AppColors.InfoSoft, formatShortDate(dueDate!!))
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .border(
                1.dp,
                if (parsed == null) AppColors.BorderDefault else Color.Transparent,
                RoundedCornerShape(AppRadius.pill),
            )
            .clickable(onClickLabel = "Pick due date") { onTap() }
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text  = label,
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = fg,
        )
        if (parsed != null) {
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Clear due date") { onClear() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = null,
                    tint               = fg,
                    modifier           = Modifier.size(11.dp),
                )
            }
        }
    }
}

// ================================================================= Task row

@Composable
private fun TaskRow(
    task: TaskEntity,
    today: LocalDate,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dueParsed = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val isOverdue = !task.completed && dueParsed != null && dueParsed.isBefore(today)
    val isToday   = dueParsed == today

    val rowBg by animateColorAsState(
        targetValue = when {
            task.completed -> AppColors.Muted
            isOverdue      -> AppColors.WarningSoft
            isToday        -> AppAccent.soft
            else           -> AppColors.CardSolid
        },
        animationSpec = tween(220),
        label = "rowBg",
    )
    val borderC = when {
        task.completed -> AppColors.BorderDefault
        isOverdue      -> AppColors.Warning.copy(alpha = 0.35f)
        isToday        -> AppAccent.border
        else           -> AppColors.BorderDefault
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(rowBg)
            .border(1.dp, borderC, RoundedCornerShape(AppRadius.md))
            .clickable(onClickLabel = "Edit task") { onEdit() }
            .padding(
                start  = AppSpacing.s3,
                end    = AppSpacing.s2,
                top    = AppSpacing.s3,
                bottom = AppSpacing.s3,
            )
            .semantics {
                stateDescription = if (task.completed) "Completed" else "Open"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Priority rail — subtle colored stripe at the row's leading edge.
        val priColor = priorityColors(task.priority).first
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 26.dp)
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(if (task.priority > 0) priColor else Color.Transparent),
        )

        // Checkbox. Only this element toggles completed — the rest of
        // the row opens the Edit sheet. Matches Things / Reminders.
        val checkBg by animateColorAsState(
            targetValue = if (task.completed) AppAccent.primary else Color.Transparent,
            animationSpec = tween(180),
            label = "checkBg",
        )
        val checkBorder by animateColorAsState(
            targetValue = if (task.completed) AppAccent.primary else AppColors.BorderStrong,
            animationSpec = tween(180),
            label = "checkBorder",
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(2.dp, checkBorder, CircleShape)
                .background(checkBg)
                .clickable(
                    role = Role.Checkbox,
                    onClickLabel = if (task.completed) "Mark as open" else "Mark as done",
                ) { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (task.completed) {
                Icon(
                    imageVector        = Icons.Filled.Check,
                    contentDescription = null,
                    tint               = AppColors.OnAccent,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = AppTypography.Body.copy(
                    fontWeight = if (task.completed) LocalFontWeight.current else LocalFontWeight.current,
                ),
                color = if (task.completed) AppColors.TextSecondary else AppColors.TextPrimary,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            )
            // Description preview — surface the notes the user wrote so
            // the row stays informative without opening the sheet.
            if (!task.description.isNullOrBlank() && !task.completed) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = task.description.trim().lineSequence().first(),
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                )
            }

            val hasMeta = task.dueDate != null || task.priority > 0
            if (hasMeta && !task.completed) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dueParsed != null) {
                        val (fg, bg) = when {
                            isOverdue -> AppColors.Warning to AppColors.WarningSoft
                            isToday   -> AppAccent.primary to AppAccent.soft
                            else      -> AppColors.Info to AppColors.InfoSoft
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .background(bg)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.CalendarMonth,
                                contentDescription = null,
                                tint               = fg,
                                modifier           = Modifier.size(10.dp),
                            )
                            Spacer(Modifier.size(3.dp))
                            Text(
                                text = dueMetaLabel(dueParsed, today, isOverdue, isToday),
                                style = AppTypography.Tag,
                                color = fg,
                            )
                        }
                        if (task.priority > 0) Spacer(Modifier.size(6.dp))
                    }
                    if (task.priority > 0) {
                        val (fg, bg) = priorityColors(task.priority)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .background(bg)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.Flag,
                                contentDescription = null,
                                tint               = fg,
                                modifier           = Modifier.size(10.dp),
                            )
                            Spacer(Modifier.size(3.dp))
                            Text(
                                text = priorityLabel(task.priority),
                                style = AppTypography.Tag,
                                color = fg,
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Delete task",
                ) { onDelete() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.DeleteOutline,
                contentDescription = null,
                tint               = AppColors.TextTertiary,
                modifier           = Modifier.size(17.dp),
            )
        }
    }
}

// ================================================================= Edit sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTaskSheet(
    task: TaskEntity,
    today: LocalDate,
    reminderFlow: Flow<ReminderEntity?>,
    onSetReminder: (Long) -> Unit,
    onClearReminder: () -> Unit,
    onSave: (TaskEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title       by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var description by rememberSaveable(task.id) { mutableStateOf(task.description.orEmpty()) }
    var dueDate     by rememberSaveable(task.id) { mutableStateOf(task.dueDate) }
    var priority    by rememberSaveable(task.id) { mutableStateOf(task.priority) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Reminder pickers — two-step flow (date → time). `pickedDateMs`
    // carries the UTC-midnight value between the two dialogs.
    var showReminderDatePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var pickedReminderDateMs   by remember { mutableStateOf<Long?>(null) }

    val reminder by reminderFlow.collectAsState(initial = null)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Persists the field values back onto the entity and closes the
    // sheet. Called by the Save button and when the user confirms with
    // a non-empty title.
    val commit = {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) {
            onSave(
                task.copy(
                    title       = trimmed,
                    description = description.trim().ifEmpty { null },
                    dueDate     = dueDate,
                    priority    = priority,
                )
            )
        } else {
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { commit() },
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
                .padding(horizontal = AppSpacing.s4)
                .padding(bottom = AppSpacing.s4)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Edit task",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AppColors.InputBg)
                        .clickable(onClickLabel = "Close") { commit() },
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

            // Title field
            LabeledField(label = "Title") {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary, fontWeight = LocalFontWeight.current),
                    cursorBrush = SolidColor(AppAccent.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.InputBg)
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                    decorationBox = { inner ->
                        if (title.isEmpty()) Text("Task title", style = AppTypography.Body, color = AppColors.TextTertiary)
                        inner()
                    },
                )
            }

            // Notes field — uses the TaskEntity.description column
            // (unused until now). Multi-line for any extra context.
            LabeledField(label = "Notes") {
                BasicTextField(
                    value = description,
                    onValueChange = { description = it },
                    textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                    cursorBrush = SolidColor(AppAccent.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.InputBg)
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                    decorationBox = { inner ->
                        if (description.isEmpty()) {
                            Text(
                                "Add notes, context, links…",
                                style = AppTypography.Body,
                                color = AppColors.TextTertiary,
                            )
                        }
                        inner()
                    },
                )
            }

            // Priority row
            LabeledField(label = "Priority") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    (0..3).forEach { level ->
                        PriorityOption(
                            level  = level,
                            active = level == priority,
                            onTap  = { priority = level },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Due date row
            LabeledField(label = "Due date") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DueDateChip(
                        dueDate = dueDate,
                        today   = today,
                        onTap   = { showDatePicker = true },
                        onClear = { dueDate = null },
                    )
                    if (dueDate == null) {
                        QuickDatePill("Today")    { dueDate = today.toString() }
                        QuickDatePill("Tomorrow") { dueDate = today.plusDays(1).toString() }
                    }
                }
            }

            // Reminder row — drives a local notification at the
            // picked time via AlarmManager (ReminderRepository.setForTask).
            LabeledField(label = "Remind me") {
                ReminderChip(
                    reminder = reminder,
                    onTap    = { showReminderDatePicker = true },
                    onClear  = { onClearReminder() },
                )
            }

            Spacer(Modifier.height(AppSpacing.s2))

            // Action row — Delete + Save side by side
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .border(1.dp, AppColors.Danger.copy(alpha = 0.35f), RoundedCornerShape(AppRadius.pill))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Delete task",
                        ) { onDelete() }
                        .padding(vertical = AppSpacing.s3),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint               = AppColors.Danger,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("Delete", style = AppTypography.Button, color = AppColors.Danger)
                }

                val canSave = title.trim().isNotEmpty()
                val bg by animateColorAsState(
                    targetValue = if (canSave) AppAccent.primary else AppColors.Muted,
                    animationSpec = tween(150),
                    label = "saveBg",
                )
                val fg by animateColorAsState(
                    targetValue = if (canSave) AppColors.OnAccent else AppColors.TextTertiary,
                    animationSpec = tween(150),
                    label = "saveFg",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1.5f)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(bg)
                        .clickable(
                            enabled = canSave,
                            role = Role.Button,
                            onClickLabel = "Save changes",
                        ) { commit() }
                        .padding(vertical = AppSpacing.s3),
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Check,
                        contentDescription = null,
                        tint               = fg,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("Save", style = AppTypography.Button, color = fg)
                }
            }
        }
    }

    if (showDatePicker) {
        val initialMs = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            ?: today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = pickerState.selectedDateMillis
                    if (ms != null) {
                        dueDate = Instant.ofEpochMilli(ms)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString()
                    }
                    showDatePicker = false
                }) { Text("Set", color = AppAccent.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        ) {
            DatePicker(state = pickerState, title = null, headline = null)
        }
    }

    // ── Reminder pickers ──
    if (showReminderDatePicker) {
        // Default to the task's due date if one is set; otherwise
        // today at midnight UTC. DatePicker measures time in UTC ms.
        val initialMs = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
            ?: today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showReminderDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedReminderDateMs = pickerState.selectedDateMillis
                    showReminderDatePicker = false
                    showReminderTimePicker = true
                }) { Text("Next", color = AppAccent.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDatePicker = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        ) {
            DatePicker(state = pickerState, title = null, headline = null)
        }
    }

    if (showReminderTimePicker) {
        val nowLocal = LocalTime.now()
        val timeState = rememberTimePickerState(
            initialHour   = (nowLocal.hour + 1).coerceAtMost(23),
            initialMinute = 0,
            is24Hour      = false,
        )
        AlertDialog(
            onDismissRequest = { showReminderTimePicker = false },
            containerColor   = AppColors.CardSolid,
            confirmButton    = {
                TextButton(onClick = {
                    val dateMs = pickedReminderDateMs
                    if (dateMs != null) {
                        val ms = combineDateAndTimeToEpochMs(dateMs, timeState.hour, timeState.minute)
                        onSetReminder(ms)
                    }
                    showReminderTimePicker = false
                    pickedReminderDateMs = null
                }) { Text("Remind me", color = AppAccent.primary) }
            },
            dismissButton    = {
                TextButton(onClick = {
                    showReminderTimePicker = false
                    pickedReminderDateMs = null
                }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            title = {
                Text(
                    text  = "Pick a time",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                )
            },
            text = {
                TimePicker(state = timeState)
            },
        )
    }
}

/**
 * Combine a UTC-midnight date (what DatePicker returns) and a local
 * hour+minute into an epoch-millis in the user's wall-clock zone —
 * the shape AlarmManager expects. Round-trips through LocalDate so a
 * date picked at 11:00 UTC doesn't become "yesterday" in a western
 * timezone.
 */
private fun combineDateAndTimeToEpochMs(
    dateUtcMs: Long,
    hour: Int,
    minute: Int,
): Long {
    val zone = ZoneId.systemDefault()
    val localDate = Instant.ofEpochMilli(dateUtcMs)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return localDate.atTime(hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

private val reminderDisplayFmt: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")

/**
 * Pill-shaped reminder chip — mirrors the DueDateChip visually but
 * with a bell icon. Shows "Set reminder" when no reminder is
 * attached, a formatted timestamp when one is. Clearing is a small
 * × inside the chip (same pattern as DueDateChip).
 */
@Composable
private fun ReminderChip(
    reminder: ReminderEntity?,
    onTap: () -> Unit,
    onClear: () -> Unit,
) {
    val (fg, bg, label) = if (reminder == null) {
        Triple(AppColors.TextSecondary, AppColors.InputBg, "Set reminder")
    } else {
        val when_ = Instant.ofEpochMilli(reminder.remindAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(reminderDisplayFmt)
        Triple(AppAccent.primary, AppAccent.soft, when_)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .border(
                1.dp,
                if (reminder == null) AppColors.BorderDefault else Color.Transparent,
                RoundedCornerShape(AppRadius.pill),
            )
            .clickable(onClickLabel = "Pick reminder time") { onTap() }
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Notifications,
            contentDescription = null,
            tint               = fg,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text  = label,
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = fg,
        )
        if (reminder != null) {
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Clear reminder") { onClear() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = null,
                    tint               = fg,
                    modifier           = Modifier.size(11.dp),
                )
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(
            text  = label.uppercase(),
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        content()
    }
}

// ================================================================= Delete-confirm sheet

/**
 * Two-step destructive flow for the List view's inline trash icon.
 * Confirmation first, then the existing undo-snackbar kicks in once
 * the user taps Delete — so an accidental tap on the trash still
 * has two lines of defence before the row is gone for good.
 *
 * Title is truncated to a reasonable length so a very long task
 * title doesn't push the action row off-screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteTaskConfirmSheet(
    task: TaskEntity,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val titlePreview = if (task.title.length > 60) task.title.take(60) + "…" else task.title

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
                .padding(horizontal = AppSpacing.s5)
                .padding(top = AppSpacing.s3, bottom = AppSpacing.s6)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s5),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s4),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.Danger.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint               = AppColors.Danger,
                        modifier           = Modifier.size(24.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    Text(
                        text  = "Delete this task?",
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text  = "\u201C$titlePreview\u201D",
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
                        .clickable(role = Role.Button, onClickLabel = "Delete task") { onConfirm() }
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

// ================================================================= Section header

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    tint: Color,
    collapsible: Boolean = false,
    collapsed: Boolean = false,
    onToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = tween(180),
        label = "chevronRot",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (collapsible) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = if (collapsed) "Expand $label" else "Collapse $label",
                ) { onToggle() } else Modifier,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            style = AppTypography.Eyebrow,
            color = tint,
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(tint.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text(
                text  = count.toString(),
                style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
                color = tint,
            )
        }
        if (collapsible) {
            Spacer(Modifier.size(AppSpacing.s2))
            Icon(
                imageVector        = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint               = tint,
                modifier           = Modifier
                    .size(14.dp)
                    .rotate(chevronRotation),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .weight(4f)
                .height(1.dp)
                .background(AppColors.BorderDefault),
        )
    }
}

// ================================================================= Empty state

@Composable
private fun EmptyState(filter: TaskFilter, onFocus: () -> Unit) {
    val (title, sub, cta) = when (filter) {
        TaskFilter.All      -> Triple("No tasks yet",
            "Add a task above to start tracking work that doesn't fit in a notebook page.",
            "Create your first task")
        TaskFilter.Today    -> Triple("Nothing due today",
            "You're clear. Enjoy the day, or pull a task forward.",
            "Add a task for today")
        TaskFilter.Upcoming -> Triple("No upcoming tasks",
            "Future-you thanks you. Schedule one when you're ready.",
            "Schedule a task")
        TaskFilter.Done     -> Triple("Nothing completed yet",
            "Tick a task off and it'll land here.",
            null)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppAccent.soft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint               = AppAccent.primary,
                modifier           = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(AppSpacing.s3))
        Text(
            text  = title,
            style = AppTypography.SectionTitle,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(AppSpacing.s2))
        Text(
            text  = sub,
            style = AppTypography.Body,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = AppSpacing.s4),
        )
        if (cta != null) {
            Spacer(Modifier.height(AppSpacing.s4))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppAccent.primary)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = cta,
                    ) { onFocus() }
                    .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = null,
                    tint               = AppColors.OnAccent,
                    modifier           = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(cta, style = AppTypography.Button, color = AppColors.OnAccent)
            }
        }
    }
}

// ================================================================= FAB

@Composable
private fun AddFab(onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .shadow(8.dp, RoundedCornerShape(AppRadius.pill), clip = false)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppAccent.primary)
            .clickable(
                role = Role.Button,
                onClickLabel = "Add task",
            ) { onTap() }
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = null,
            tint               = AppColors.OnAccent,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text("Add task", style = AppTypography.Button, color = AppColors.OnAccent)
    }
}

// ================================================================= Helpers

private data class TaskSection(
    val id: String,
    val label: String,
    val tintKind: SectionTint,
) {
    @Composable
    fun tint(): Color = when (tintKind) {
        SectionTint.Danger  -> AppColors.Danger
        SectionTint.Warning -> AppColors.Warning
        SectionTint.Accent  -> AppAccent.primary
        SectionTint.Info    -> AppColors.Info
        SectionTint.Muted   -> AppColors.TextTertiary
        SectionTint.Success -> AppColors.Success
    }
}

private enum class SectionTint { Danger, Warning, Accent, Info, Muted, Success }

private fun filteredTasks(
    tasks: List<TaskEntity>,
    filter: TaskFilter,
    today: LocalDate,
): List<TaskEntity> = when (filter) {
    TaskFilter.All -> tasks
    TaskFilter.Today -> tasks.filter {
        !it.completed && it.dueDate?.let { d ->
            runCatching { LocalDate.parse(d) == today }.getOrDefault(false)
        } == true
    }
    TaskFilter.Upcoming -> tasks.filter {
        !it.completed && it.dueDate?.let { d ->
            runCatching { LocalDate.parse(d).isAfter(today) }.getOrDefault(false)
        } == true
    }
    TaskFilter.Done -> tasks.filter { it.completed }
}

private fun groupedTasks(
    tasks: List<TaskEntity>,
    filter: TaskFilter,
    today: LocalDate,
): List<Pair<TaskSection, List<TaskEntity>>> {
    return when (filter) {
        TaskFilter.All -> {
            val open = tasks.filterNot { it.completed }
            val done = tasks.filter { it.completed }

            val overdue = open.filter { t ->
                t.dueDate?.let { runCatching { LocalDate.parse(it).isBefore(today) }.getOrDefault(false) } == true
            }
            val onToday = open.filter { t ->
                t.dueDate?.let { runCatching { LocalDate.parse(it) == today }.getOrDefault(false) } == true
            }
            val soon = open.filter { t ->
                t.dueDate?.let { runCatching {
                    val d = LocalDate.parse(it)
                    d.isAfter(today) && d.isBefore(today.plusDays(8))
                }.getOrDefault(false) } == true
            }
            val later = open.filter { t ->
                t.dueDate?.let { runCatching {
                    LocalDate.parse(it).isAfter(today.plusDays(7))
                }.getOrDefault(false) } == true
            }
            val undated = open.filter { it.dueDate == null }

            listOf(
                TaskSection("overdue",  "OVERDUE",   SectionTint.Warning) to overdue.sortedBy { it.dueDate },
                TaskSection("today",    "TODAY",     SectionTint.Accent)  to onToday,
                TaskSection("upcoming", "THIS WEEK", SectionTint.Info)    to soon.sortedBy { it.dueDate },
                TaskSection("later",    "LATER",     SectionTint.Info)    to later.sortedBy { it.dueDate },
                TaskSection("undated",  "NO DATE",   SectionTint.Muted)   to undated,
                TaskSection("done",     "COMPLETED", SectionTint.Success) to done,
            )
        }
        TaskFilter.Today -> listOf(
            TaskSection("today", "TODAY", SectionTint.Accent) to tasks,
        )
        TaskFilter.Upcoming -> {
            val soon = tasks.filter { t ->
                t.dueDate?.let { runCatching {
                    val d = LocalDate.parse(it)
                    d.isBefore(today.plusDays(8))
                }.getOrDefault(false) } == true
            }
            val later = tasks.filter { t ->
                t.dueDate?.let { runCatching {
                    LocalDate.parse(it).isAfter(today.plusDays(7))
                }.getOrDefault(false) } == true
            }
            listOf(
                TaskSection("upcoming", "THIS WEEK", SectionTint.Accent) to soon.sortedBy { it.dueDate },
                TaskSection("later",    "LATER",     SectionTint.Info)   to later.sortedBy { it.dueDate },
            )
        }
        TaskFilter.Done -> listOf(
            TaskSection("done", "COMPLETED", SectionTint.Success) to tasks,
        )
    }
}

@Composable
internal fun priorityColors(priority: Int): Pair<Color, Color> = when (priority) {
    1    -> AppColors.Success to AppColors.SuccessSoft
    2    -> AppColors.Warning to AppColors.WarningSoft
    3    -> AppColors.Danger  to AppColors.Danger.copy(alpha = 0.12f)
    else -> AppColors.TextTertiary to AppColors.InputBg
}

private fun summaryLine(state: TasksUiState, today: LocalDate, overdue: Int): String {
    // This line carries the emotional/contextual message — the
    // numerical counts are on the stat pills below, so we deliberately
    // don't duplicate them here.
    if (state.isLoading) return "Loading…"
    if (state.tasks.isEmpty()) return "A fresh start — add your first task"
    val open = state.openCount
    return when {
        overdue == 1     -> "1 task is overdue"
        overdue > 1      -> "$overdue tasks are overdue"
        open == 0        -> "All caught up — nice work"
        open == 1        -> "1 task on your plate"
        else             -> "$open tasks on your plate"
    }
}

private fun dueMetaLabel(date: LocalDate, today: LocalDate, isOverdue: Boolean, isToday: Boolean): String {
    if (isToday) return "Today"
    if (isOverdue) {
        val days = ChronoUnit.DAYS.between(date, today)
        return if (days == 1L) "1 day overdue" else "$days days overdue"
    }
    if (date == today.plusDays(1)) return "Tomorrow"
    val days = ChronoUnit.DAYS.between(today, date)
    if (days in 2..6) return "in $days days"
    return formatShortDate(date.toString())
}

internal fun priorityLabel(priority: Int): String = when (priority) {
    1    -> "Low"
    2    -> "Medium"
    3    -> "High"
    else -> "No priority"
}

private val shortDateFmt: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun formatShortDate(iso: String): String = try {
    LocalDate.parse(iso).format(shortDateFmt)
} catch (_: Throwable) {
    iso
}
