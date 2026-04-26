/*
 * NotepadCalendarBloom.kt
 *
 * The Day view's centerpiece — a 5×7 grid of small pines, one per day
 * of the current month. Empty days render as hollow tree silhouettes;
 * days with entries fill the canopy in three tinted greens (light →
 * mid → deep) keyed off the day's [DayDensity]. Today is a coral pine
 * with a coral ring so it lifts off the page from across the screen.
 *
 * The grid is purely visual: tap callbacks are wired so a future
 * iteration can let the user open any day's entry; for now only today
 * routes through.
 *
 * The tree silhouette + color ramp mirror TreesSavedHero.TreeGlyph and
 * the side-drawer canopy header so the three surfaces feel like the
 * same forest at different framings.
 */

package app.releaf.mobile.features.notepad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// ---- Tree palette ----

private val EmptyFill   = Color(0xFFFAF1E6)
private val LightFill   = Color(0xFFC0DD97)
private val MidFill     = Color(0xFF7AA874)
private val DeepFill    = Color(0xFF3E6B3B)
private val TodayCoral  = Color(0xFFE77850)
private val TodayCoralDark = Color(0xFF993C1D)
private val TrunkColor  = Color(0xFF3E2A18)
private val TrunkSoft   = Color(0xFF5F4A2D)
private val GroundLine  = Color(0xFFB8956A)
private val GreenLine   = Color(0xFF1E5943)
/** Ring drawn around the user-tapped day in the calendar — same hue
 *  family as the active tree canopies so it reads as "garden focus,"
 *  while staying clearly distinct from today's coral pin. */
private val SelectionRing = Color(0xFF5B8C52)

private fun fillFor(density: DayDensity): Color = when (density) {
    DayDensity.Empty -> EmptyFill
    DayDensity.Light -> LightFill
    DayDensity.Mid   -> MidFill
    DayDensity.Deep  -> DeepFill
}

@Composable
fun NotepadCalendarBloom(
    month: YearMonth,
    days: List<DayCount>,
    today: LocalDate,
    onDayTap: (DayCount) -> Unit,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true,
    showWeekdayStrip: Boolean = true,
    selectedDate: LocalDate? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showWeekdayStrip) {
            NotepadCalendarWeekdayStrip()
            Spacer(Modifier.height(8.dp))
        }

        // 5×7 tree grid, drawn into one Canvas for tight layout
        val cols = 7
        val rowHeight = 36.dp
        val rows = totalRows(month)

        // Pad days with leading + trailing nulls so the grid lines up
        // against the weekday columns of the month.
        val leading = month.atDay(1).dayOfWeek.value % 7  // Sunday-based
        val totalCells = rows * cols
        val cells: List<DayCount?> = buildList {
            repeat(leading) { add(null) }
            addAll(days)
            while (size < totalCells) add(null)
        }

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            for (row in 0 until rows) {
                Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                    for (col in 0 until cols) {
                        val cell = cells[row * cols + col]
                        val isToday = cell?.date == today
                        TreeCell(
                            day = cell,
                            isToday = isToday,
                            // Selection wins over "today" for the ring —
                            // when today is the selected day (default on
                            // open), the green ring shows. Today's coral
                            // canopy fill still marks the date.
                            isSelected = cell?.date == selectedDate,
                            onTap = { c -> onDayTap(c) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (showLegend) {
            Spacer(Modifier.height(AppSpacing.s3))
            NotepadCalendarLegend()
        }
    }
}

/** Density legend strip — extracted so callers can render it once
 *  even when the calendar is shown in a 3-month carousel.
 *
 *  When [onTodayTap] is provided, the right-aligned "today" badge
 *  becomes a tap target that lets the user jump the calendar back to
 *  today's month + reselect today after they've swiped or tapped a
 *  different day. */
@Composable
fun NotepadCalendarLegend(
    modifier: Modifier = Modifier,
    onTodayTap: (() -> Unit)? = null,
) {
    LegendRow(modifier = modifier, onTodayTap = onTodayTap)
}

/** Weekday header strip (S M T W T F S). Extracted so the swipeable
 *  carousel can render it once above the pager — otherwise the prev /
 *  next month's own weekday strips bleed into the centered page's
 *  edges and look like a 9–11-letter mash-up. */
@Composable
fun NotepadCalendarWeekdayStrip(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Sun → Sat
        for (i in 0..6) {
            val dow = java.time.DayOfWeek.SUNDAY.plus(i.toLong())
            Text(
                text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = AppTypography.Tag,
                color = AppColors.TextSecondary,
                modifier = Modifier.width(36.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun totalRows(month: YearMonth): Int {
    val leading = month.atDay(1).dayOfWeek.value % 7
    val total = leading + month.lengthOfMonth()
    return (total + 6) / 7
}

@Composable
private fun TreeCell(
    day: DayCount?,
    isToday: Boolean,
    isSelected: Boolean,
    onTap: (DayCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .then(
                if (day != null) Modifier.clickable { onTap(day) } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (day != null) {
            Canvas(modifier = Modifier.size(28.dp, 32.dp)) {
                val cx = size.width / 2f
                val cyTreeMid = size.height / 2f - 2.dp.toPx()
                if (isSelected) {
                    // Selection takes ring priority — works for today
                    // (which is selected by default on open) and any
                    // past/future day the user taps. Today's coral
                    // tree fill still distinguishes the date even
                    // when its ring goes green.
                    drawCircle(
                        color = Color(0xFFD9EDE2).copy(alpha = 0.55f),
                        radius = size.minDimension / 2f,
                        center = Offset(cx, cyTreeMid),
                    )
                    drawCircle(
                        color = SelectionRing.copy(alpha = 0.85f),
                        radius = size.minDimension / 2f,
                        center = Offset(cx, cyTreeMid),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                } else if (isToday) {
                    // Today, but not currently the selected day —
                    // user has tapped a past/future day. Coral ring
                    // stays as the "today marker" until they tap
                    // back on today.
                    drawCircle(
                        color = Color(0xFFFCEAE0).copy(alpha = 0.6f),
                        radius = size.minDimension / 2f,
                        center = Offset(cx, cyTreeMid),
                    )
                    drawCircle(
                        color = TodayCoral.copy(alpha = 0.85f),
                        radius = size.minDimension / 2f,
                        center = Offset(cx, cyTreeMid),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                drawTree(
                    center  = Offset(cx, cyTreeMid),
                    density = day.density,
                    isToday = isToday,
                )
            }
        }
    }
}

private fun DrawScope.drawTree(center: Offset, density: DayDensity, isToday: Boolean) {
    val cx = center.x
    val cy = center.y
    val canopyTop    = Offset(cx,         cy - 9.dp.toPx())
    val canopyLeft   = Offset(cx - 7.dp.toPx(), cy + 5.dp.toPx())
    val canopyRight  = Offset(cx + 7.dp.toPx(), cy + 5.dp.toPx())
    val canopy = Path().apply {
        moveTo(canopyTop.x, canopyTop.y)
        lineTo(canopyLeft.x, canopyLeft.y)
        lineTo(canopyRight.x, canopyRight.y)
        close()
    }
    val canopyFill = when {
        isToday -> TodayCoral
        else    -> fillFor(density)
    }
    val isHollow = !isToday && density == DayDensity.Empty
    if (isHollow) {
        drawPath(canopy, canopyFill)
        drawPath(
            canopy,
            color = GreenLine.copy(alpha = 0.22f),
            style = Stroke(width = 0.6.dp.toPx()),
        )
    } else {
        drawPath(canopy, canopyFill)
    }
    // Trunk
    val trunkColor = when {
        isToday  -> TodayCoralDark
        isHollow -> TrunkSoft.copy(alpha = 0.4f)
        else     -> TrunkColor
    }
    drawRect(
        color   = trunkColor,
        topLeft = Offset(cx - 1.5.dp.toPx(), cy + 5.dp.toPx()),
        size    = Size(3.dp.toPx(), 4.dp.toPx()),
    )
}

@Composable
private fun LegendRow(
    modifier: Modifier = Modifier,
    onTodayTap: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("none", style = AppTypography.Tag, color = AppColors.TextSecondary)
        LegendTree(DayDensity.Empty)
        LegendTree(DayDensity.Light)
        LegendTree(DayDensity.Mid)
        LegendTree(DayDensity.Deep)
        Text("3+ captures", style = AppTypography.Tag, color = AppColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        // Today badge — tappable when [onTodayTap] is wired up; the
        // host pairs the click with snapping the carousel back to
        // today's month and reselecting today's tree.
        val tapMod = if (onTodayTap != null) {
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onTodayTap)
        } else {
            Modifier
        }
        Row(
            modifier = tapMod.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, TodayCoral, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(14.dp, 18.dp)) {
                    drawTree(
                        center  = Offset(size.width / 2f, size.height / 2f - 2.dp.toPx()),
                        density = DayDensity.Deep,
                        isToday = true,
                    )
                }
            }
            Text(
                text = "today",
                style = AppTypography.Tag,
                color = if (onTodayTap != null) TodayCoralDark else AppColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun LegendTree(density: DayDensity) {
    Canvas(modifier = Modifier.size(14.dp, 18.dp)) {
        drawTree(
            center  = Offset(size.width / 2f, size.height / 2f - 2.dp.toPx()),
            density = density,
            isToday = false,
        )
    }
}
