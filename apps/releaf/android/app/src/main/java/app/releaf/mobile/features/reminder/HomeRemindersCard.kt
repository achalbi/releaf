/*
 * HomeRemindersCard.kt
 *
 * Home-screen entry for the Reminders surface. Mirrors the
 * HomeTasksCard shape (56dp hero tile + stat row + summary line)
 * but dressed as a small constellation — each star represents
 * one upcoming reminder; stars "light up" as reminders fire
 * (`firedAt != null`), giving the card a night-sky motif that
 * animates through the day as alerts come in.
 *
 * Derives four numbers from the active reminder list:
 *
 *   • today     — unfired, uncompleted, scheduled before tomorrow midnight
 *   • tomorrow  — scheduled within tomorrow's 24-hour window
 *   • thisWeek  — scheduled within the 7-day window from today
 *   • next      — the single soonest unfired reminder (if any)
 */

package app.releaf.mobile.features.reminder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.reminder.ReminderEntity
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.releaf.mobile.ui.theme.LocalFontWeight

@Composable
fun HomeRemindersCard(onOpenReminders: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReleafApp

    val rowsFlow = remember(app) {
        val userId = (app.authStore.state.value as? AuthState.SignedIn)?.session?.userId
        if (userId == null) flowOf(emptyList())
        else app.reminderRepository.observeActive(userId)
    }
    val rows: List<ReminderEntity> by rowsFlow.collectAsState(initial = emptyList())

    val now = System.currentTimeMillis()
    val zone = remember { ZoneId.systemDefault() }
    val todayDate = remember { LocalDate.now(zone) }

    // Upper bounds of the three buckets — pre-computed once per
    // recomposition so the per-row `.count { … }` stays trivial.
    val tomorrowStartMs = remember(todayDate) {
        todayDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val dayAfterTomorrowStartMs = remember(todayDate) {
        todayDate.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val weekEndMs = remember(todayDate) {
        todayDate.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    val upcoming = rows.filter { it.completedAt == null && it.remindAt > now }
    val todayCount    = upcoming.count { it.remindAt < tomorrowStartMs }
    val tomorrowCount = upcoming.count { it.remindAt in tomorrowStartMs until dayAfterTomorrowStartMs }
    val weekCount     = upcoming.count { it.remindAt in dayAfterTomorrowStartMs until weekEndMs }
    val next          = upcoming.minByOrNull { it.remindAt }

    // firedToday / todayTotal drives how many constellation stars
    // light up — a visual representation of "how much of the day
    // has already pinged you".
    val firedToday = rows.count { row ->
        val t = row.firedAt ?: return@count false
        t in (now - 24 * 3_600_000L)..now
    }
    val rawLit = if (todayCount + firedToday > 0)
        firedToday.toFloat() / (todayCount + firedToday)
    else 0f
    val litRatio by animateFloatAsState(
        targetValue   = rawLit.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label         = "constellationLit",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable { onOpenReminders() }
            .padding(AppSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Dark-indigo tile frames the constellation so the gold
        // "lit" stars pop without fighting the rest of the cream
        // home surface.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(Color(0xFF1E1B4B)),
            contentAlignment = Alignment.Center,
        ) {
            ConstellationIcon(
                litRatio = litRatio,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = "REMINDERS",
                    style = AppTypography.Eyebrow,
                    color = AppAccent.primary,
                )
                Spacer(Modifier.weight(1f))
                if (next != null) {
                    Text(
                        text  = countdownLabel(next.remindAt - now),
                        style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
                        color = AppAccent.primary,
                    )
                }
            }
            Text(
                text  = headline(firedToday = firedToday, total = upcoming.size),
                style = AppTypography.SectionTitle.copy(fontWeight = LocalFontWeight.current),
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            StatRow(
                today    = todayCount,
                tomorrow = tomorrowCount,
                week     = weekCount,
            )
            Spacer(Modifier.height(4.dp))
            NextLine(next = next)
        }
    }
}

// ================================================================= Stats

@Composable
private fun StatRow(today: Int, tomorrow: Int, week: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatInline(today,    "today",     AppAccent.primary)
        Dot()
        StatInline(tomorrow, "tomorrow",  AppColors.Info)
        Dot()
        StatInline(week,     "this week", AppColors.TextSecondary)
    }
}

@Composable
private fun StatInline(value: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text  = value.toString(),
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = color,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = label,
            style = AppTypography.Tag.copy(fontWeight = LocalFontWeight.current),
            color = color,
        )
    }
}

@Composable
private fun Dot() {
    Text(
        text  = "  ·  ",
        style = AppTypography.Meta,
        color = AppColors.TextTertiary,
    )
}

@Composable
private fun NextLine(next: ReminderEntity?) {
    if (next == null) {
        Text(
            text  = "Nothing queued · tap to add",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
            maxLines = 1,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text  = "Next: ",
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )
        Text(
            text  = next.title,
            style = AppTypography.Meta.copy(fontWeight = LocalFontWeight.current),
            color = AppColors.TextPrimary,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = "· ${formatWhen(next.remindAt)}",
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
            maxLines = 1,
        )
    }
}

// ================================================================= Constellation

/**
 * Small constellation — five stars at hand-picked positions,
 * connected by faint gold lines. [litRatio] determines how many of
 * them shine: 0 = all dim, 1 = all lit. The canvas background is
 * deliberately transparent; the parent Box paints the dark indigo
 * sky.
 */
@Composable
private fun ConstellationIcon(
    litRatio: Float,
    modifier: Modifier = Modifier,
) {
    // Normalised (0..1) positions for the five stars. Tweaked by
    // eye to feel like a little scoop — not a real constellation
    // but evocative.
    val points = listOf(
        Offset(0.22f, 0.28f),
        Offset(0.48f, 0.16f),
        Offset(0.76f, 0.32f),
        Offset(0.66f, 0.68f),
        Offset(0.32f, 0.74f),
    )
    val totalStars = points.size
    val litCount   = (litRatio * totalStars).toInt().coerceIn(0, totalStars)

    val dimStar    = Color(0x73FFFFFF)      // ~45% white
    val lineColor  = Color(0x66F4C430)      // gold @ 40%
    val litColor   = Color(0xFFF4C430)      // gold
    val litHalo    = Color(0x33F4C430)      // gold @ 20%
    val litCore    = Color(0xFFFFF7D6)      // warm cream

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Connecting lines — draw all of them the same regardless
        // of lit state so the shape stays readable even on a fresh
        // install where zero stars have fired.
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            drawLine(
                color       = lineColor,
                start       = Offset(a.x * w, a.y * h),
                end         = Offset(b.x * w, b.y * h),
                strokeWidth = 0.8.dp.toPx(),
            )
        }

        // Stars. Lit ones get a halo + bright core; dim ones are a
        // small white dot at reduced opacity.
        points.forEachIndexed { i, pos ->
            val cx = pos.x * w
            val cy = pos.y * h
            if (i < litCount) {
                drawCircle(color = litHalo, radius = w * 0.10f, center = Offset(cx, cy))
                drawCircle(color = litColor, radius = w * 0.05f, center = Offset(cx, cy))
                drawCircle(color = litCore,  radius = w * 0.02f, center = Offset(cx, cy))
            } else {
                drawCircle(color = dimStar, radius = w * 0.03f, center = Offset(cx, cy))
            }
        }
    }
}

// ================================================================= Copy helpers

private fun headline(firedToday: Int, total: Int): String = when {
    total == 0       -> "Quiet sky"
    firedToday == 0  -> "Stars to light"
    firedToday < total -> "Lighting up"
    else             -> "All lit"
}

private fun countdownLabel(deltaMs: Long): String {
    if (deltaMs <= 60_000L) return "Now"
    val mins = deltaMs / 60_000L
    return when {
        mins < 60          -> "In ${mins}m"
        mins < 24 * 60     -> "In ${mins / 60}h"
        else               -> "In ${mins / (60 * 24)}d"
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private fun formatWhen(remindAtMs: Long): String {
    val zone = ZoneId.systemDefault()
    val dt    = LocalDateTime.ofInstant(Instant.ofEpochMilli(remindAtMs), zone)
    val today = LocalDate.now(zone)
    return when (dt.toLocalDate()) {
        today             -> "today ${dt.format(timeFmt)}"
        today.plusDays(1) -> "tomorrow ${dt.format(timeFmt)}"
        else              -> dt.format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault()))
    }
}
