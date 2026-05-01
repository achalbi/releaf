/*
 * QuickCaptureSheet.kt
 *
 * DEPRECATED — kept for one release cycle so any deep links still
 * resolving through it don't break. The Capture flow is now a real
 * top-level destination (`Routes.CAPTURE`) backed by `CaptureScreen`
 * under `features/capture/`. Tap the lifted Leaf FAB to navigate to
 * it instead of presenting this sheet. Remove this file in the next
 * release. See docs/CAPTURE_TAB_PLAN.md.
 *
 * Bottom sheet presenting the 7 capture modes as large tappable rows.
 * Opens from the CaptureFab or the center Leaf in the BottomNav.
 *
 * Uses Material 3 ModalBottomSheet but overrides surface color and corner
 * shape to match Releaf tokens (cream canvas, 20dp top corners).
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.launch
import app.releaf.mobile.ui.theme.LocalFontWeight

// ---------- QuickCaptureSheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheet(
    onDismiss: () -> Unit,
    onSelect: (CaptureMode) -> Unit,
    /**
     * Optional handler for the "Open full calendar" footer link
     * that appears beneath the inline `CalendarPanel` when the
     * user has expanded it. Null hides the link — keeps the
     * preview composable below working without wiring up a
     * real navigation graph.
     */
    onOpenFullCalendar: (() -> Unit)? = null,
    // skipPartiallyExpanded=true so the sheet jumps straight to its
    // expanded stop. Combined with the calendar-driven fillMaxHeight
    // below, tapping the calendar icon takes the sheet to full screen
    // without an intermediate partial-height pass.
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modes: List<CaptureMode> = CaptureMode.entries,
) {
    val scope = rememberCoroutineScope()
    var calendarOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Lifted to its full height when the calendar is open so the
        // month grid + holiday list have room without being clipped by
        // the partial-expand stop.
        containerColor = AppColors.Canvas,
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
        dragHandle = null,
    ) {
        // When the calendar is open we force the sheet to its full
        // height — the inner Column claims fillMaxHeight() so the
        // ModalBottomSheet snaps to its largest stop rather than sizing
        // to the (shorter) capture-mode list. When the calendar is
        // closed, the column wraps content as before so the sheet
        // returns to its natural height.
        //
        // Inset handling — `navigationBarsPadding()` is applied
        // unconditionally so the modifier chain shape is stable across
        // `calendarOpen` toggles. The status-bar gap (only needed when
        // the sheet expands to full height and the drag handle would
        // otherwise sit under system chrome) is added as a Spacer
        // child instead of a `Modifier.statusBarsPadding()` chained
        // into this Column. Reshaping a modifier chain that subscribes
        // to WindowInsets inside a `ModalBottomSheet`'s own
        // `MutableWindowInsets` plumbing — which is what the previous
        // `let { if (calendarOpen) it.statusBarsPadding() else it }`
        // pattern did on every tap — could grow `UnionInsets` chains
        // until `equals()` recursion blew the stack on the next tap of
        // the calendar toggle. Observed in production as
        // `StackOverflowError: stack size 8188KB at
        // UnionInsets.equals(WindowInsets.kt:435)` on compose-foundation
        // 1.7.3. Spacer-as-child keeps the inset-subscribing node a
        // composition lifecycle child (clean attach/detach) rather than
        // a modifier element that gets re-shaped under the sheet's
        // inset manager.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (calendarOpen) Modifier.fillMaxHeight() else Modifier)
                .navigationBarsPadding(),
        ) {
            // See the rationale on the Column above — this is the
            // status-bar gap that used to live on the Column's
            // modifier chain.
            if (calendarOpen) {
                Spacer(
                    modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars),
                )
            }

            // Drag handle — extra top breathing room (s6 = 24dp) so the
            // pill sits clear of the rounded modal corners.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = AppSpacing.s6, bottom = AppSpacing.s4)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.BorderStrong),
            )

            // Header — leaf eyebrow + lowercase serif title on the left,
            // calendar toggle pill on the right. Tapping the toggle
            // expands a full-month calendar (with Indian government
            // holidays highlighted) just below the header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.s4,
                        end = AppSpacing.s4,
                        bottom = AppSpacing.s3,
                    ),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    LeafEyebrow("releaf · capture")
                    Text(
                        text  = "what arrived?",
                        style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                        color = AppColors.TextPrimary,
                    )
                }

                // Calendar toggle — small icon button at the top-right.
                // On tap it expands a CalendarPanel from the right
                // (slide + fade) just below this header AND drives the
                // ModalBottomSheet to its fully-expanded stop so the
                // calendar + holiday list have room.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (calendarOpen) AppAccent.soft else AppColors.CardSolid)
                        .border(
                            width = 1.dp,
                            color = AppColors.BorderDefault,
                            shape = CircleShape,
                        )
                        .clickable {
                            calendarOpen = !calendarOpen
                            if (calendarOpen) {
                                scope.launch { sheetState.expand() }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = if (calendarOpen) "Hide calendar" else "Show calendar",
                        tint = if (calendarOpen) AppAccent.deep else AppColors.TextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Calendar panel — plain `if` instead of AnimatedVisibility
            // so layout never collapses the panel to zero size during a
            // transition (was the root cause of the panel staying
            // invisible after tap).
            if (calendarOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = AppSpacing.s4,
                            end = AppSpacing.s4,
                            top = AppSpacing.s2,     // breathing room below the header
                            bottom = AppSpacing.s3,
                        ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    CalendarPanel()
                    // Footer link to the full panchanga calendar
                    // surface. Only rendered when the host wired up
                    // a navigation handler — preview/test composables
                    // pass null and don't see this row.
                    if (onOpenFullCalendar != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.sm))
                                .clickable {
                                    // Dismiss the sheet first so the
                                    // calendar surface lands on its
                                    // own backdrop instead of behind
                                    // a half-open ModalBottomSheet.
                                    scope.launch { sheetState.hide() }
                                        .invokeOnCompletion {
                                            onDismiss()
                                            onOpenFullCalendar()
                                        }
                                }
                                .padding(
                                    horizontal = AppSpacing.s2,
                                    vertical = AppSpacing.s2,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                tint = AppAccent.deep,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Open full calendar",
                                style = AppTypography.Meta.copy(
                                    fontWeight = LocalFontWeight.current,
                                ),
                                color = AppAccent.deep,
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = AppAccent.deep,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            // Capture-mode list. `Modifier.weight(1f, fill = false)` so
            // it claims any leftover vertical space without pushing
            // either the calendar or itself off-screen when the column
            // is fillMaxHeight.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(weight = 1f, fill = false),
                contentPadding = PaddingValues(
                    start = AppSpacing.s4,
                    end = AppSpacing.s4,
                    bottom = AppSpacing.s6,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                items(modes, key = { it.name }) { mode ->
                    CaptureRow(mode = mode) {
                        onSelect(mode)
                        scope.launch { sheetState.hide() }
                            .invokeOnCompletion { onDismiss() }
                    }
                }
            }
        }
    }
}

// ---------- Row ----------

@Composable
private fun CaptureRow(mode: CaptureMode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = AppSpacing.s4,
                vertical = AppSpacing.s3,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        IconChip(icon = mode.icon)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = mode.title,
                style = AppTypography.Button,
                color = AppColors.TextPrimary,
            )
            Text(
                text = mode.subtitle,
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun IconChip(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppAccent.soft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent.deep,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------- Host helper ----------

/**
 * Convenience composable: wire the sheet to a simple Boolean flag.
 *
 *     var open by remember { mutableStateOf(false) }
 *     if (open) QuickCaptureSheetHost(
 *         onDismiss = { open = false },
 *         onSelect  = { mode -> ... }
 *     )
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheetHost(
    onDismiss: () -> Unit,
    onSelect: (CaptureMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    QuickCaptureSheet(
        onDismiss = onDismiss,
        onSelect = onSelect,
        sheetState = sheetState,
    )
}

// ---------- Preview ----------

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390, heightDp = 720)
@Composable
private fun QuickCaptureSheetPreview() {
    // Previews can't reliably host a real ModalBottomSheet — render the
    // contents directly against the cream canvas to validate layout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Canvas),
    ) {
        Spacer(Modifier.size(AppSpacing.s6))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            LeafEyebrow("releaf · capture")
            Text(
                text  = "what arrived?",
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp),
                color = AppColors.TextPrimary,
            )
        }
        Spacer(Modifier.size(AppSpacing.s3))
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            CaptureMode.entries.forEach { mode ->
                CaptureRow(mode = mode, onClick = {})
            }
        }
    }
}
