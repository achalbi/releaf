/*
 * NotepadGardenTiles.kt
 *
 * The Recents view — a 2-column ragged masonry of "garden plot" tiles
 * for older days, with today's plot rendered as a full-width hero tile
 * up top in deep canopy + coral border. Tile color encodes capture
 * density (mint = 1, leaf-green = 2–3, deep canopy = 4+) and tiny dots
 * inside the tile count the captures. Empty days appear as hollow
 * dashed-outline tiles with "no entry" so gaps stay legible.
 *
 * Reads like a community garden viewed from above.
 */

package app.releaf.mobile.features.notepad

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DeepFill   = Color(0xFF3E6B3B)
private val MidFill    = Color(0xFF7AA874)
private val LightFill  = Color(0xFFC0DD97)
private val MintFill   = Color(0xFFD9EDE2)
private val EmptyStrk  = Color(0xFF1E5943)
private val Cream      = Color(0xFFFFF8EE)
private val MutedDark  = Color(0xFF888780)
private val DotDeep    = Color(0xFF3E6B3B)
private val InkOnDark  = Color(0xFFFFF8EE)
private val InkOnLight = Color(0xFF1E5943)

private fun fillFor(density: DayDensity): Color = when (density) {
    DayDensity.Empty -> Color.Transparent
    DayDensity.Light -> MintFill
    DayDensity.Mid   -> MidFill
    DayDensity.Deep  -> DeepFill
}

/** Pick text color that contrasts with the tile fill. */
private fun inkFor(density: DayDensity): Color = when (density) {
    DayDensity.Deep, DayDensity.Mid -> InkOnDark
    else                            -> InkOnLight
}

private fun heightFor(density: DayDensity): Dp = when (density) {
    DayDensity.Empty -> 56.dp
    DayDensity.Light -> 76.dp
    DayDensity.Mid   -> 92.dp
    DayDensity.Deep  -> 112.dp
}

@Composable
fun NotepadGardenTiles(
    today: DayCount,
    earlier: List<DayCount>,
    onTodayTap: () -> Unit,
    onDayTap: (DayCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        TodayHeroTile(today = today, onTap = onTodayTap)

        if (earlier.isNotEmpty()) {
            Text(
                text  = "EARLIER IN ${monthLabel(today.date)}",
                style = AppTypography.Eyebrow,
                color = AppColors.ThemeGreenDeep,
            )
            EarlierMasonry(days = earlier, onDayTap = onDayTap)
        }
    }
}

@Composable
private fun TodayHeroTile(
    today: DayCount,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = today.entry
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(DeepFill)
            .border(1.4.dp, AppAccent.primary, RoundedCornerShape(AppRadius.lg))
            .clickable { onTap() }
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Text(
            text  = "TODAY · ${dayHeader(today.date)}",
            style = AppTypography.Eyebrow,
            color = Color(0xFFFCEAE0),
        )
        Text(
            text  = entry?.title?.takeIf { it.isNotBlank() } ?: "Today's entry",
            color = InkOnDark,
            fontFamily = FontFamily.Serif,
            fontSize = 18.sp,
        )
        // Short coral underline below the title — same accent as the
        // Day view's today card, ties the two surfaces visually.
        Box(
            modifier = Modifier
                .height(1.2.dp)
                .width(28.dp)
                .background(AppAccent.primary.copy(alpha = 0.85f)),
        )
        val notesPreview = entry?.notes
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(120)
        if (!notesPreview.isNullOrBlank()) {
            Text(
                text  = notesPreview,
                color = Color(0xFFD9EDE2),
                fontFamily = FontFamily.Serif,
                fontSize = 12.sp,
                maxLines = 2,
            )
        }
        if (today.captureCount > 0 || today.openTodoCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                if (today.captureCount > 0) {
                    Text(
                        text  = "${today.captureCount} captures",
                        style = AppTypography.Tag,
                        color = Color(0xFFFCEAE0),
                    )
                }
                if (today.captureCount > 0 && today.openTodoCount > 0) {
                    Text("·", style = AppTypography.Tag, color = Color(0xFFFCEAE0))
                }
                if (today.openTodoCount > 0) {
                    Text(
                        text  = "${today.openTodoCount} todos",
                        style = AppTypography.Tag,
                        color = Color(0xFFFCEAE0),
                    )
                }
            }
        }
    }
}

@Composable
private fun EarlierMasonry(
    days: List<DayCount>,
    onDayTap: (DayCount) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two-column ragged masonry: alternate days into the column with
    // less accumulated height.
    val left = mutableListOf<DayCount>()
    val right = mutableListOf<DayCount>()
    var leftH = 0
    var rightH = 0
    days.forEach { d ->
        val h = heightFor(d.density).value.toInt()
        if (leftH <= rightH) { left += d; leftH += h } else { right += d; rightH += h }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(modifier = Modifier.weight(1f),
               verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            left.forEach { day -> EarlierTile(day = day, onTap = { onDayTap(day) }) }
        }
        Column(modifier = Modifier.weight(1f),
               verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            right.forEach { day -> EarlierTile(day = day, onTap = { onDayTap(day) }) }
        }
    }
}

@Composable
private fun EarlierTile(
    day: DayCount,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = day.density
    val tileH = heightFor(density)
    val isEmpty = day.entry == null
    if (isEmpty) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = tileH)
                .clip(RoundedCornerShape(AppRadius.md))
                .border(
                    width = 0.5.dp,
                    color = EmptyStrk.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(AppRadius.md),
                )
                .padding(AppSpacing.s3),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text  = dayLabel(day.date),
                    style = AppTypography.Tag,
                    color = MutedDark,
                )
                Text(
                    text  = "no entry",
                    color = MutedDark,
                    fontFamily = FontFamily.Serif,
                    fontSize = 11.sp,
                )
            }
        }
        return
    }
    val ink = inkFor(density)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = tileH)
            .clip(RoundedCornerShape(AppRadius.md))
            .background(fillFor(density))
            .clickable { onTap() }
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text  = dayLabel(day.date),
            style = AppTypography.Tag,
            color = ink.copy(alpha = 0.85f),
        )
        Text(
            text  = day.entry?.title?.takeIf { it.isNotBlank() }
                ?: day.entry?.notes
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.take(40)
                ?: "Untitled",
            color = ink,
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            maxLines = 2,
        )
        if (day.captureCount > 0) {
            Spacer(Modifier.height(2.dp))
            CaptureDots(count = day.captureCount, color = ink)
        }
    }
}

@Composable
private fun CaptureDots(count: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        val visible = count.coerceAtMost(5)
        repeat(visible) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        if (count > 5) {
            Text(
                text = "+${count - 5}",
                style = AppTypography.Tag,
                color = color,
                fontSize = 8.sp,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

// ---- formatting ----

private val DayLabelFmt   = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val DayHeaderFmt  = DateTimeFormatter.ofPattern("EEE · MMM d", Locale.getDefault())
private val MonthLabelFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())

private fun dayLabel(date: LocalDate): String =
    DayLabelFmt.format(date).uppercase()

private fun dayHeader(date: LocalDate): String =
    DayHeaderFmt.format(date).uppercase()

private fun monthLabel(date: LocalDate): String =
    MonthLabelFmt.format(date).uppercase()
