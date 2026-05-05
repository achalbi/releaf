/*
 * DateRangePickerSheet.kt
 *
 * Shared date-range filter UI used by Library + Search. Lives here
 * (not under a feature folder) because two features need it now and
 * we'd rather have one source of truth than copy-paste at two call
 * sites that drift out of sync.
 *
 * Implementation: a Compose `Dialog` rather than Material's
 * `ModalBottomSheet`. Trade-offs:
 *  - Pro: appears INSTANTLY on tap. `ModalBottomSheet` runs an
 *    internal Hidden→Expanded slide animation (~250ms) that the
 *    user perceived as "slow to open". `Dialog` opens on the next
 *    frame with a brief fade.
 *  - Pro: no swipeable-machine + scrim-animator overhead, which
 *    contributed another ~50–100ms of composition lag on cold opens.
 *  - Con: no native drag-to-dismiss gesture. Tap-scrim and
 *    back-press still dismiss; that covers ~95% of dismiss intents.
 *  - Con: we hand-roll the bottom-anchored layout (Box with
 *    `BottomCenter` alignment + a Card with rounded top corners).
 *    Visually identical to a sheet, but it's literally a Dialog.
 *
 * Visual rhythm:
 *   [Clear (text) ······ Done (filled coral pill)]   ← top action row
 *   [Material DateRangePicker — title + headline + grid]
 *
 * Apply (here labelled "Done") is disabled until a start has been
 * picked. Single-day picks are allowed (start == end) — when only a
 * start is picked, end comes back equal to start so the caller's
 * filter logic doesn't have to special-case.
 */

package app.quickink.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.QuickInkFonts
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Decide whether a capture's UTC ISO-8601 timestamp falls inside the
 * range the user picked. Compares **local** calendar dates so the
 * filter matches what the user sees on their device:
 *
 *  - The picker returns midnight UTC for each endpoint, but the user
 *    picked those cells thinking "May 4 in my calendar", not "the
 *    24-hour UTC window starting at 00:00Z May 4". We convert each
 *    endpoint to its UTC `LocalDate` — which is the date the user
 *    visually picked.
 *  - The capture's instant is converted to the device's local zone
 *    before extracting its date. Without this, a capture created at
 *    02:00 IST (= 20:30 UTC of the previous day) would be bucketed
 *    against the previous calendar day and leak into "yesterday"
 *    filters.
 *
 * Returns `true` when no range is active. Returns `false` if the
 * timestamp string can't be parsed (corrupted row).
 */
fun isWithinPickedDateRange(
    createdAtIso: String,
    startMillis: Long?,
    endMillis: Long?,
): Boolean {
    if (startMillis == null && endMillis == null) return true
    val instant = runCatching { Instant.parse(createdAtIso) }.getOrNull() ?: return false
    val captureDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val startDate = startMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    }
    val endDate = endMillis?.let {
        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    }
    val afterStart = startDate?.let { !captureDate.isBefore(it) } ?: true
    val beforeEnd = endDate?.let { !captureDate.isAfter(it) } ?: true
    return afterStart && beforeEnd
}

/**
 * Convenience helper — `rememberDateRangePickerState` with our
 * defaults already wired. Call this at the parent screen (Library /
 * Search) so the state survives across show/hide cycles of the
 * sheet. Re-creating the state inside the sheet on every open
 * causes a perceptible delay because Material has to allocate the
 * day cells fresh; hoisting fixes that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberQuickInkDateRangePickerState(
    initialStart: Long? = null,
    initialEnd: Long? = null,
): DateRangePickerState = rememberDateRangePickerState(
    initialSelectedStartDateMillis = initialStart,
    initialSelectedEndDateMillis   = initialEnd,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerSheet(
    state: DateRangePickerState,
    onDismiss: () -> Unit,
    onConfirm: (start: Long?, end: Long?) -> Unit,
) {
    val colors = LocalQuickInkColors.current

    // Compose `Dialog` (not Material's `ModalBottomSheet`) — opens
    // on the next frame with no slide animation. We hand-roll the
    // bottom-anchored layout via Box `BottomCenter` + a Surface
    // shaped like a sheet (rounded top corners, square bottom).
    Dialog(
        onDismissRequest = onDismiss,
        // `usePlatformDefaultWidth = false` lets us fill the screen
        // width — the Dialog's outer Box covers everything so the
        // tap-scrim and bottom-anchored sheet both work.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                // Tap on the scrim → dismiss. Use a no-ripple
                // interaction source so the scrim doesn't flash
                // when tapped (it's destructive feedback, not
                // affirmative).
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = colors.surface,
                shape = RoundedCornerShape(
                    topStart = QuickInkRadius.xl,
                    topEnd   = QuickInkRadius.xl,
                    bottomStart = 0.dp,
                    bottomEnd   = 0.dp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow taps on the sheet itself so they
                    // don't bubble up to the scrim's clickable.
                    // Without this, tapping anywhere on the
                    // calendar would dismiss the dialog.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
            // Top action row — Clear (text) + Done (filled coral
            // pill), top-right anchored. Placed at the top so the
            // primary action is obvious and reachable as soon as the
            // sheet opens, not buried below the calendar grid where
            // it could be missed on smaller devices.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = QuickInkSpacing.s4,
                        end    = QuickInkSpacing.s4,
                        top    = QuickInkSpacing.s2,
                        bottom = QuickInkSpacing.s2,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        onConfirm(null, null)
                        onDismiss()
                    },
                ) { Text("Clear") }
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Button(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        onConfirm(
                            state.selectedStartDateMillis,
                            state.selectedEndDateMillis ?: state.selectedStartDateMillis,
                        )
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor   = colors.textOnAccent,
                    ),
                    shape = RoundedCornerShape(QuickInkRadius.pill),
                ) { Text("Done") }
            }

            // Override the Material typography slots that
            // DateRangePicker reads from. The picker doesn't expose
            // text-style overrides on its public API, so this is the
            // canonical workaround. Sizes pulled down from Material3
            // defaults; family pinned to Inter (`QuickInkFonts.ui`)
            // to match the rest of the app's sans typography.
            val parentTypography = MaterialTheme.typography
            val compact = parentTypography.copy(
                bodyLarge     = parentTypography.bodyLarge.copy(
                    fontSize = 13.sp, fontFamily = QuickInkFonts.ui,
                ),
                bodyMedium    = parentTypography.bodyMedium.copy(
                    fontSize = 12.sp, fontFamily = QuickInkFonts.ui,
                ),
                labelLarge    = parentTypography.labelLarge.copy(
                    fontSize = 11.sp, fontFamily = QuickInkFonts.ui,
                ),
                titleSmall    = parentTypography.titleSmall.copy(
                    fontSize = 13.sp, fontFamily = QuickInkFonts.ui,
                ),
                headlineSmall = parentTypography.headlineSmall.copy(
                    fontSize = 18.sp, fontFamily = QuickInkFonts.ui,
                ),
            )
            MaterialTheme(typography = compact) {
                DateRangePicker(
                    state          = state,
                    showModeToggle = false,
                    colors = DatePickerDefaults.colors(
                        containerColor                       = colors.surface,
                        titleContentColor                    = colors.inkSoft,
                        headlineContentColor                 = colors.ink,
                        weekdayContentColor                  = colors.muted,
                        subheadContentColor                  = colors.ink,
                        dayContentColor                      = colors.ink,
                        disabledDayContentColor              = colors.muted,
                        selectedDayContentColor              = colors.textOnAccent,
                        selectedDayContainerColor            = colors.accent,
                        todayContentColor                    = colors.accent,
                        todayDateBorderColor                 = colors.accent,
                        dayInSelectionRangeContentColor      = colors.accentDeep,
                        dayInSelectionRangeContainerColor    = colors.accentSoft,
                    ),
                )
            }
                }
            }
        }
    }
}

/**
 * Render an active date-range filter as a compact label like
 * "May 1 – May 4". Falls back to a single-side label when only one
 * endpoint is set ("From May 1" / "Until May 4").
 *
 * Both endpoints come in as epoch millis at midnight UTC. Rendered
 * in the device's local zone so a user filtering "today" sees
 * today's date even when UTC has rolled over.
 */
fun formatDateRange(start: Long?, end: Long?): String {
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    val zone = ZoneId.systemDefault()
    val s = start?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) }
    val e = end?.let { Instant.ofEpochMilli(it).atZone(zone).format(fmt) }
    return when {
        s != null && e != null && s == e -> s
        s != null && e != null           -> "$s – $e"
        s != null                        -> "From $s"
        e != null                        -> "Until $e"
        else                             -> ""
    }
}
