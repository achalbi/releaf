/*
 * DaylightHero.kt
 *
 * Home-screen card that sits directly above [SustainabilityHero],
 * answering "how much daylight do I have left today?" with a
 * glance — sunrise time, sunset time, and a meter whose position is
 * *the answer* rather than decoration.
 *
 * Layout (B2 design direction):
 *
 *   ┌────────────────────────┐  ┌────────────────────────┐
 *   │ ☀  Sunrise   5:54 AM   │  │ ☀  Sunset    7:06 PM   │
 *   └────────────────────────┘  └────────────────────────┘
 *     8h 49m in                              4h 23m left
 *     ─────────●───────────────────────────────────────
 *
 * Two warm-tinted "split tiles" carry the times; a slim meter
 * underneath shows progress through the day with a `now` dot
 * positioned at the current daylight fraction. Elapsed time
 * appears on the meter's left, remaining on the right.
 *
 * Behaviour:
 *   - Before sunrise: meter empty; left label flips to
 *     "Day starts in Xh Ym"; right shows the day's total daylight.
 *   - After sunset:  meter full;  left says "Day ended"; right
 *     points at tomorrow's sunrise.
 *   - During daylight: elapsed/remaining are minute-precise; dot
 *     is at `elapsed / total`.
 *
 * Time source: [sunTimesFor] in the calendar package — the same
 * commons-suncalc backend the panchanga's Rahu Kala window uses,
 * anchored to Mysuru. If we add per-user lat/lon later, pass it
 * through here.
 *
 * The now-indicator is animated only enough to feel alive — a
 * subtle 1.6 s sun-glyph pulse on each tile's ring, staggered by
 * ~1.7 s so the pair doesn't breathe in lockstep. The meter dot's
 * position is the *actual* now (60 s tick), not a CSS-style sweep,
 * so it stays correct without relying on the screen being open.
 *
 * Mirror of iOS `DaylightHero.swift`.
 */

package app.quickink.mobile.features.home

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.quickink.mobile.features.calendar.sunTimesFor
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkColors
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - Daylight model

/**
 * Phase the user is currently in relative to today's sunrise/sunset.
 * Drives which labels and progress value the view renders.
 */
internal enum class DaylightPhase {
    BeforeSunrise,
    Daytime,
    AfterSunset,
    /** Polar fallback — sunrise/sunset couldn't be resolved. */
    Unresolved,
}

/**
 * One day's daylight snapshot for a given `now`. Pre-computes the
 * fractional position the now-marker sits at and the two flanking
 * labels so the view layer is presentational only.
 */
internal data class DaylightSnapshot(
    val phase: DaylightPhase,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val now: ZonedDateTime,
    /** 0.0 at sunrise, 1.0 at sunset, clamped. */
    val dayProgress: Float,
    val leadingLabel: String,
    val trailingLabel: String,
)

/**
 * Build a [DaylightSnapshot] for `now`. Sunrise/sunset come from
 * the shared [sunTimesFor] helper so the displayed times match the
 * Calendar's panchanga card.
 */
internal fun computeDaylight(now: ZonedDateTime): DaylightSnapshot {
    val today = now.toLocalDate()
    val (sunrise, sunset) = sunTimesFor(today)
    if (sunrise == null || sunset == null) {
        return DaylightSnapshot(
            phase         = DaylightPhase.Unresolved,
            sunrise       = sunrise,
            sunset        = sunset,
            now           = now,
            dayProgress   = 0f,
            leadingLabel  = "—",
            trailingLabel = "—",
        )
    }
    val totalSec = (sunset.toEpochSecond() - sunrise.toEpochSecond()).coerceAtLeast(1L)

    return when {
        now.isBefore(sunrise) -> {
            val untilRise = Duration.between(now, sunrise)
            DaylightSnapshot(
                phase         = DaylightPhase.BeforeSunrise,
                sunrise       = sunrise,
                sunset        = sunset,
                now           = now,
                dayProgress   = 0f,
                leadingLabel  = "Day starts in ${formatDuration(untilRise)}",
                trailingLabel = "${formatDuration(Duration.ofSeconds(totalSec))} today",
            )
        }
        now.isAfter(sunset) -> {
            // After sunset, point at *tomorrow's* sunrise — that's
            // the information the user actually wants in the
            // "remaining" slot. Fallback silently to today's total
            // if the next-day solar calc fails (polar only).
            val tomorrowRise = sunTimesFor(today.plusDays(1)).first
            val trailing = if (tomorrowRise != null) {
                "Sunrise in ${formatDuration(Duration.between(now, tomorrowRise))}"
            } else {
                "${formatDuration(Duration.ofSeconds(totalSec))} today"
            }
            DaylightSnapshot(
                phase         = DaylightPhase.AfterSunset,
                sunrise       = sunrise,
                sunset        = sunset,
                now           = now,
                dayProgress   = 1f,
                leadingLabel  = "Day ended",
                trailingLabel = trailing,
            )
        }
        else -> {
            val elapsed   = Duration.between(sunrise, now)
            val remaining = Duration.between(now, sunset)
            val progress = (elapsed.seconds.toFloat() / totalSec.toFloat())
                .coerceIn(0f, 1f)
            DaylightSnapshot(
                phase         = DaylightPhase.Daytime,
                sunrise       = sunrise,
                sunset        = sunset,
                now           = now,
                dayProgress   = progress,
                leadingLabel  = "${formatDuration(elapsed)} in",
                trailingLabel = "${formatDuration(remaining)} left",
            )
        }
    }
}

// MARK: - Composable

/**
 * Renders the daylight hero card. Drives its own 60 s recompose
 * tick — the screen recomposes once a minute, which is sufficient
 * given the meter dot moves ~1 dp/min on a typical phone width.
 *
 * `fixedNow` is for previews / tests only; passing `null` (the
 * default) wires up the live ticker.
 */
@Composable
internal fun DaylightHero(
    fixedNow: ZonedDateTime? = null,
) {
    val type = LocalQuickInkTypography.current
    val colors = LocalQuickInkColors.current

    // Soft warm tints carry the sunrise-vs-sunset identity via
    // *background colour*, not text colour. Earlier pass used
    // `LeafYellowDeep` for the icon tint and meter labels — but
    // saturated yellow on yellow has zero contrast (the sunrise
    // glyph effectively disappeared into the amber ring), and on
    // the cream canvas underneath the meter labels read as a faint
    // amber smudge. Text now routes through `colors.ink` (inside
    // tiles) / `colors.inkSoft` (meter labels) — the warm-brown
    // primary tokens, so the daylight card has the same legibility
    // ceiling the rest of the app does.
    val sunBg     = QuickInkColors.LeafYellowBase.copy(alpha = 0.20f)
    val sunBorder = QuickInkColors.LeafYellowBase.copy(alpha = 0.55f)
    val setBg     = QuickInkColors.CoralBase.copy(alpha = 0.18f)
    val setBorder = QuickInkColors.CoralBase.copy(alpha = 0.45f)

    // 60 s tick. Compose's `LaunchedEffect(Unit)` survives recomposes
    // and is cancelled when the composable leaves the tree, so the
    // ticker is bounded by screen lifecycle without extra wiring.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    if (fixedNow == null) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    val now: ZonedDateTime = fixedNow
        ?: ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), IST)
    val snapshot = remember(now.toEpochSecond()) { computeDaylight(now) }

    // Pulse animation for the two tile rings — shared infinite
    // transition so both rings re-use the same animation engine,
    // but each ring reads a different `animateFloat` so we can
    // phase-shift them. 1.6 s easeInOut, autoReverse, 8 % scale
    // swing — matches the iOS [BreathingPulse] modifier 1:1.
    val pulse = rememberInfiniteTransition(label = "daylight-pulse")
    val risePulse by pulse.animateFloat(
        initialValue = 1f,
        targetValue  = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rise-pulse",
    )
    val setPulse by pulse.animateFloat(
        initialValue = 1.08f,   // start at the apex so the two
        targetValue  = 1f,      // tiles aren't synchronous; the
        animationSpec = infiniteRepeatable(  // inversion gives us
            animation = tween(durationMillis = 1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "set-pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabel(snapshot) },
    ) {
        // Split tiles — sunrise on the left, sunset on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            SplitTile(
                modifier   = Modifier.weight(1f),
                label      = "Sunrise",
                time       = formatTime(snapshot.sunrise),
                icon       = SunIcon.Rise,
                ringFill   = QuickInkColors.LeafYellowBase,
                bg         = sunBg,
                border     = sunBorder,
                pulseScale = risePulse,
                titleStyle = type.editorial,
                inkColor   = colors.ink,
                captionColor = colors.inkSoft,
                labelStyle = type.caption,
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            SplitTile(
                modifier   = Modifier.weight(1f),
                label      = "Sunset",
                time       = formatTime(snapshot.sunset),
                icon       = SunIcon.Set,
                ringFill   = QuickInkColors.CoralBase,
                bg         = setBg,
                border     = setBorder,
                pulseScale = setPulse,
                titleStyle = type.editorial,
                inkColor   = colors.ink,
                captionColor = colors.inkSoft,
                labelStyle = type.caption,
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s2))

        if (snapshot.phase == DaylightPhase.Unresolved) {
            Text(
                text     = "Sunrise unavailable",
                style    = type.caption,
                color    = colors.muted,
                modifier = Modifier.padding(horizontal = QuickInkSpacing.s1),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s1),
            ) {
                Text(
                    text  = snapshot.leadingLabel.uppercase(Locale.getDefault()),
                    style = type.caption,
                    color = colors.inkSoft,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text  = snapshot.trailingLabel.uppercase(Locale.getDefault()),
                    style = type.caption,
                    color = colors.inkSoft,
                )
            }
            Spacer(Modifier.size(4.dp))
            DaylightMeter(
                progress = snapshot.dayProgress,
                trackBg  = QuickInkColors.LeafYellowBase.copy(alpha = 0.30f),
                fill     = QuickInkColors.LeafYellowDeep,
                disc     = QuickInkColors.LeafYellowDeep,
                rayColor = QuickInkColors.CoralDeep,
            )
        }
    }
}

// MARK: - Subviews

/** Discriminator for which sunset/sunrise glyph to use on a tile. */
private enum class SunIcon { Rise, Set }

@Composable
private fun SplitTile(
    modifier: Modifier,
    label: String,
    time: String,
    icon: SunIcon,
    ringFill: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color,
    pulseScale: Float,
    titleStyle: androidx.compose.ui.text.TextStyle,
    inkColor: androidx.compose.ui.graphics.Color,
    captionColor: androidx.compose.ui.graphics.Color,
    labelStyle: androidx.compose.ui.text.TextStyle,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(QuickInkRadius.md))
            .padding(
                horizontal = QuickInkSpacing.s3,
                vertical   = QuickInkSpacing.s2,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(ringFill),
            contentAlignment = Alignment.Center,
        ) {
            // Material Icons only ships a single sun glyph
            // (`WbSunny`); `WbTwilight` (sun below horizon line)
            // is the closest match for a distinct "sunset" mark.
            // The tile's coral border + uppercase "SUNSET" label
            // carries the rest of the distinction.
            //
            // Icon tint is `inkColor` (the warm-brown primary text
            // colour). Earlier pass tinted with the matching deep
            // hue — but yellow-on-yellow disappeared into the ring.
            Icon(
                imageVector = when (icon) {
                    SunIcon.Rise -> Icons.Filled.WbSunny
                    SunIcon.Set  -> Icons.Filled.WbTwilight
                },
                contentDescription = null,
                tint     = inkColor,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Column {
            Text(
                text  = label.uppercase(Locale.getDefault()),
                style = labelStyle,
                color = captionColor,
            )
            Text(
                text  = time,
                style = titleStyle,
                color = inkColor,
            )
        }
    }
}

/**
 * 8 dp-tall meter with a filled portion up to `progress` and a
 * rayed-sun "now" indicator overlaid at the same x. Drawn in a
 * single Canvas pass to avoid measure overhead for a slim ribbon.
 */
@Composable
private fun DaylightMeter(
    progress: Float,
    trackBg: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color,
    disc: androidx.compose.ui.graphics.Color,
    rayColor: androidx.compose.ui.graphics.Color,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        val centerY  = size.height / 2f
        val trackH   = 8.dp.toPx()
        val dotSize  = 18.dp.toPx()
        // Track (full width).
        drawRoundRect(
            color    = trackBg,
            topLeft  = Offset(0f, centerY - trackH / 2f),
            size     = Size(size.width, trackH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
        )
        // Filled (elapsed).
        val fillW = (progress.coerceIn(0f, 1f) * size.width)
        if (fillW > 0f) {
            drawRoundRect(
                color    = fill,
                topLeft  = Offset(0f, centerY - trackH / 2f),
                size     = Size(fillW, trackH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2f),
            )
        }
        // Now indicator — rayed sun. Disc matches the fill; rays
        // carry the contrast so the marker reads against both
        // halves of the track.
        val dotCx     = fillW.coerceIn(dotSize / 2f, size.width - dotSize / 2f)
        val discR     = 4.dp.toPx()
        val rayInner  = discR + 1.5.dp.toPx()
        val rayOuter  = dotSize / 2f
        val rayStroke = 2.dp.toPx()
        drawCircle(
            color  = disc,
            radius = discR,
            center = Offset(dotCx, centerY),
        )
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            drawLine(
                color = rayColor,
                start = Offset(dotCx + cos * rayInner, centerY + sin * rayInner),
                end   = Offset(dotCx + cos * rayOuter, centerY + sin * rayOuter),
                strokeWidth = rayStroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

// MARK: - Helpers

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

/**
 * "5:54 AM" — IST 12-hour formatter, matches Mysuru anchor used
 * by [sunTimesFor]. Hoisted to file scope so each row render
 * doesn't allocate a new formatter.
 */
private val TimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun formatTime(time: ZonedDateTime?): String =
    time?.withZoneSameInstant(IST)?.format(TimeFormatter) ?: "—"

/**
 * "8h 49m" / "13m" / "0m" — minute-precise, drops the leading
 * "Xh" when under an hour. Used by the daylight labels so the
 * elapsed and remaining strings share a consistent shape.
 */
internal fun formatDuration(duration: Duration): String {
    val totalSec = duration.seconds.coerceAtLeast(0L)
    val hours   = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    return if (hours == 0L) "${minutes}m" else "${hours}h ${minutes}m"
}

/**
 * Single-string a11y label combining sunrise time, sunset time,
 * and the trailing label so VoiceOver/TalkBack reads the card as
 * one coherent unit rather than five individual fragments.
 */
private fun a11yLabel(snapshot: DaylightSnapshot): String {
    val rise = formatTime(snapshot.sunrise)
    val set  = formatTime(snapshot.sunset)
    return when (snapshot.phase) {
        DaylightPhase.BeforeSunrise ->
            "Sunrise at $rise, sunset at $set. ${snapshot.leadingLabel}."
        DaylightPhase.Daytime ->
            "Sunrise at $rise, sunset at $set. ${snapshot.trailingLabel}."
        DaylightPhase.AfterSunset ->
            "Sunrise was at $rise, sunset was at $set. ${snapshot.trailingLabel}."
        DaylightPhase.Unresolved ->
            "Sunrise and sunset unavailable for this location."
    }
}
