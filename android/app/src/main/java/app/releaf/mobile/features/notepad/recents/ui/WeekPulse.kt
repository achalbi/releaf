package app.releaf.mobile.features.notepad.recents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.features.notepad.recents.model.RecentsWeekDay
import app.releaf.mobile.features.notepad.recents.theme.BgChip
import app.releaf.mobile.features.notepad.recents.theme.Green200
import app.releaf.mobile.features.notepad.recents.theme.Green400
import app.releaf.mobile.features.notepad.recents.theme.Green800
import app.releaf.mobile.features.notepad.recents.theme.TextMuted
import app.releaf.mobile.features.notepad.recents.theme.Type
import java.time.format.TextStyle
import java.util.Locale

/**
 * Compact "this week" strip — 7 stout cells (one per day) showing how
 * many pages a day got. Each cell renders as a wide rounded rect (the
 * track) with a green fill rising from the bottom; the fill height
 * encodes pageCount/6.
 *
 * Color tiers:
 *   1-2 pages -> Green200
 *   3-5 pages -> Green400
 *   6+ pages  -> Green800
 *   0         -> empty track only
 */
@Composable
fun WeekPulse(
    days: List<RecentsWeekDay>,
    modifier: Modifier = Modifier,
) {
    val cellHeight = 44.dp
    val cellRadius = 12.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            DayCell(
                day = day,
                cellHeight = cellHeight,
                cellRadius = cellRadius,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayCell(
    day: RecentsWeekDay,
    cellHeight: androidx.compose.ui.unit.Dp,
    cellRadius: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val tier  = colorForCount(day.pageCount)
    // Cap at 6 pages = 100%. Tiny minimum height so a 1-page day still
    // shows a clearly-visible bar at the bottom of the track.
    val ratio = (day.pageCount.coerceAtMost(6).toFloat() / 6f)
    val labelColor = if (day.isToday) Green800 else TextMuted
    val numColor   = if (day.isToday) Green800 else TextMuted

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Track + fill — both span the full cell width so the strip
        // reads as a row of stout pills, matching the reference design.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cellHeight),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cellRadius))
                    .background(BgChip),
            )
            if (day.pageCount > 0) {
                val filled = (cellHeight * ratio).coerceAtLeast(10.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(filled)
                        .clip(RoundedCornerShape(cellRadius))
                        .background(tier),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Brief: only Regular and Medium weights. Today gets Medium so
        // the bolder treatment still reads "selected" against the
        // muted Normal weight on the rest of the week.
        BasicText(
            text = day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
            style = Type.MicroLabel.copy(
                color = labelColor,
                fontWeight = if (day.isToday) FontWeight.Medium else FontWeight.Normal,
            ),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = day.date.dayOfMonth.toString(),
            style = Type.BodySmall.copy(
                color = numColor,
                fontSize = 13.sp,
                fontWeight = if (day.isToday) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

private fun colorForCount(count: Int): Color = when {
    count >= 6 -> Green800
    count >= 3 -> Green400
    count >= 1 -> Green200
    else -> Color.Transparent
}
