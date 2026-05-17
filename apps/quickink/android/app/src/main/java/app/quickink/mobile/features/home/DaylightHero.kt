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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.features.calendar.sunTimesFor
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkColors
import app.quickink.mobile.ui.theme.QuickInkFonts
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
 * stat blocks (big value + muted caption) so the view layer is
 * presentational only.
 *
 * During the night the snapshot switches into a "night view":
 * [isNight] goes true; [nightSunset] is the sunset that *started*
 * the current night (yesterday's before sunrise, today's after
 * sunset) and [nightSunrise] is the sunrise that *closes* it
 * (today's before sunrise, tomorrow's after sunset). [dayProgress]
 * tracks the fraction of the night elapsed between the pair. The
 * composable swaps the tile order (Sunset on the left, the
 * upcoming Sunrise on the right) and the meter switches the sun
 * marker for a crescent moon.
 */
internal data class DaylightSnapshot(
    val phase: DaylightPhase,
    val sunrise: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    /** Sunset that opened the current night (yesterday's before
     *  sunrise, today's after sunset). Null in day/unresolved phases. */
    val nightSunset: ZonedDateTime?,
    /** Sunrise that closes the current night (today's before sunrise,
     *  tomorrow's after sunset). Null in day/unresolved phases. */
    val nightSunrise: ZonedDateTime?,
    val now: ZonedDateTime,
    /**
     * Day phases: 0.0 at sunrise, 1.0 at sunset, clamped.
     * Night phase: 0.0 at [nightSunset], 1.0 at [nightSunrise].
     */
    val dayProgress: Float,
    /** True during the night — drives the tile-swap + moon meter. */
    val isNight: Boolean,
    /** Big number on the left of the meter card. */
    val leadingValue: String,
    /** Muted caption under [leadingValue]. */
    val leadingCaption: String,
    /** Big number on the right of the meter card. */
    val trailingValue: String,
    /** Muted caption under [trailingValue]. */
    val trailingCaption: String,
)

/**
 * Build a [DaylightSnapshot] for `now`. Sunrise/sunset come from
 * the shared [sunTimesFor] helper. [latitude] / [longitude] come
 * from `DaylightLocationStore` (threaded through HomeScreen) so the
 * hero's times match the user-location-anchored status bar above
 * it; null falls back to Mysuru, matching the panchanga card's
 * default anchor.
 */
internal fun computeDaylight(
    now: ZonedDateTime,
    latitude: Double? = null,
    longitude: Double? = null,
): DaylightSnapshot {
    val lat = latitude  ?: 12.2958
    val lng = longitude ?: 76.6394
    val today = now.toLocalDate()
    val (sunrise, sunset) = sunTimesFor(today, latitude = lat, longitude = lng)
    if (sunrise == null || sunset == null) {
        return DaylightSnapshot(
            phase           = DaylightPhase.Unresolved,
            sunrise         = sunrise,
            sunset          = sunset,
            nightSunset     = null,
            nightSunrise    = null,
            now             = now,
            dayProgress     = 0f,
            isNight         = false,
            leadingValue    = "—",
            leadingCaption  = "Day passed",
            trailingValue   = "—",
            trailingCaption = "Daylight left",
        )
    }
    val totalSec = (sunset.toEpochSecond() - sunrise.toEpochSecond()).coerceAtLeast(1L)
    val totalDuration = Duration.ofSeconds(totalSec)

    return when {
        now.isBefore(sunrise) -> {
            // Pre-dawn — still inside last night. Anchor between
            // *yesterday's* sunset and today's sunrise so the
            // "since sunset" duration keeps counting up through the
            // small hours instead of resetting at midnight.
            val yesterdaySet = sunTimesFor(today.minusDays(1), latitude = lat, longitude = lng).second
            val untilRise    = Duration.between(now, sunrise)
            if (yesterdaySet != null) {
                val nightSec = (sunrise.toEpochSecond() - yesterdaySet.toEpochSecond())
                    .coerceAtLeast(1L)
                val sinceSunset   = Duration.between(yesterdaySet, now)
                val nightProgress = (sinceSunset.seconds.toFloat() / nightSec.toFloat())
                    .coerceIn(0f, 1f)
                DaylightSnapshot(
                    phase           = DaylightPhase.BeforeSunrise,
                    sunrise         = sunrise,
                    sunset          = sunset,
                    nightSunset     = yesterdaySet,
                    nightSunrise    = sunrise,
                    now             = now,
                    dayProgress     = nightProgress,
                    isNight         = true,
                    leadingValue    = formatDuration(sinceSunset),
                    leadingCaption  = "Since sunset",
                    trailingValue   = formatDuration(untilRise),
                    trailingCaption = "Until sunrise",
                )
            } else {
                // Polar fallback — yesterday's sunset unresolved.
                // Fall back to the simpler "Until sunrise" framing
                // so the card still carries useful information.
                DaylightSnapshot(
                    phase           = DaylightPhase.BeforeSunrise,
                    sunrise         = sunrise,
                    sunset          = sunset,
                    nightSunset     = null,
                    nightSunrise    = sunrise,
                    now             = now,
                    dayProgress     = 0f,
                    isNight         = false,
                    leadingValue    = formatDuration(untilRise),
                    leadingCaption  = "Until sunrise",
                    trailingValue   = formatDuration(totalDuration),
                    trailingCaption = "Today's daylight",
                )
            }
        }
        now.isAfter(sunset) -> {
            // Night view: anchor between today's sunset and the
            // *next* sunrise, with the meter tracking how far we are
            // through the night.
            val tomorrowRise = sunTimesFor(today.plusDays(1), latitude = lat, longitude = lng).first
            if (tomorrowRise != null) {
                val nightSec = (tomorrowRise.toEpochSecond() - sunset.toEpochSecond())
                    .coerceAtLeast(1L)
                val sinceSunset    = Duration.between(sunset, now)
                val untilNextRise  = Duration.between(now, tomorrowRise)
                val nightProgress  = (sinceSunset.seconds.toFloat() / nightSec.toFloat())
                    .coerceIn(0f, 1f)
                DaylightSnapshot(
                    phase           = DaylightPhase.AfterSunset,
                    sunrise         = sunrise,
                    sunset          = sunset,
                    nightSunset     = sunset,
                    nightSunrise    = tomorrowRise,
                    now             = now,
                    dayProgress     = nightProgress,
                    isNight         = true,
                    leadingValue    = formatDuration(sinceSunset),
                    leadingCaption  = "Since sunset",
                    trailingValue   = formatDuration(untilNextRise),
                    trailingCaption = "Until sunrise",
                )
            } else {
                // Polar fallback — no resolvable next sunrise. Keep
                // the day-mode framing so the card still says
                // something useful instead of an empty night view.
                DaylightSnapshot(
                    phase           = DaylightPhase.AfterSunset,
                    sunrise         = sunrise,
                    sunset          = sunset,
                    nightSunset     = sunset,
                    nightSunrise    = null,
                    now             = now,
                    dayProgress     = 1f,
                    isNight         = false,
                    leadingValue    = formatDuration(totalDuration),
                    leadingCaption  = "Day passed",
                    trailingValue   = formatDuration(totalDuration),
                    trailingCaption = "Today's daylight",
                )
            }
        }
        else -> {
            val elapsed   = Duration.between(sunrise, now)
            val remaining = Duration.between(now, sunset)
            val progress = (elapsed.seconds.toFloat() / totalSec.toFloat())
                .coerceIn(0f, 1f)
            DaylightSnapshot(
                phase           = DaylightPhase.Daytime,
                sunrise         = sunrise,
                sunset          = sunset,
                nightSunset     = null,
                nightSunrise    = null,
                now             = now,
                dayProgress     = progress,
                isNight         = false,
                leadingValue    = formatDuration(elapsed),
                leadingCaption  = "Day passed",
                trailingValue   = formatDuration(remaining),
                trailingCaption = "Daylight left",
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
    latitude: Double? = null,
    longitude: Double? = null,
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
    val snapshot = remember(now.toEpochSecond(), latitude, longitude) {
        computeDaylight(now, latitude = latitude, longitude = longitude)
    }

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
        // Split tiles — daytime shows Sunrise (left) + Sunset (right);
        // after sunset the pair swaps to Sunset (today, past) + Sunrise
        // (tomorrow, upcoming) so the trailing tile is always the next
        // solar event. Each tile carries a trailing direction arrow
        // (↑ for rising, ↓ for setting).
        val tileTitleStyle = type.editorial.copy(
            fontFamily = QuickInkFonts.ui,
            fontWeight = FontWeight.Normal,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (snapshot.isNight) {
                // Sunset tile carries the sunset that opened this
                // night (yesterday's before sunrise, today's after
                // sunset). The upcoming sunrise sits on the right.
                SplitTile(
                    modifier     = Modifier.weight(1f),
                    label        = "Sunset",
                    time         = formatTime(snapshot.nightSunset),
                    icon         = SunIcon.Set,
                    arrow        = SunIcon.Set,
                    ringFill     = QuickInkColors.CoralBase,
                    bg           = setBg,
                    border       = setBorder,
                    pulseScale   = setPulse,
                    titleStyle   = tileTitleStyle,
                    inkColor     = colors.ink,
                    captionColor = colors.inkSoft,
                    labelStyle   = type.caption,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                SplitTile(
                    modifier     = Modifier.weight(1f),
                    label        = "Sunrise",
                    time         = formatTime(snapshot.nightSunrise),
                    icon         = SunIcon.Rise,
                    arrow        = SunIcon.Rise,
                    ringFill     = QuickInkColors.LeafYellowBase,
                    bg           = sunBg,
                    border       = sunBorder,
                    pulseScale   = risePulse,
                    titleStyle   = tileTitleStyle,
                    inkColor     = colors.ink,
                    captionColor = colors.inkSoft,
                    labelStyle   = type.caption,
                )
            } else {
                SplitTile(
                    modifier     = Modifier.weight(1f),
                    label        = "Sunrise",
                    time         = formatTime(snapshot.sunrise),
                    icon         = SunIcon.Rise,
                    arrow        = SunIcon.Rise,
                    ringFill     = QuickInkColors.LeafYellowBase,
                    bg           = sunBg,
                    border       = sunBorder,
                    pulseScale   = risePulse,
                    titleStyle   = tileTitleStyle,
                    inkColor     = colors.ink,
                    captionColor = colors.inkSoft,
                    labelStyle   = type.caption,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                SplitTile(
                    modifier     = Modifier.weight(1f),
                    label        = "Sunset",
                    time         = formatTime(snapshot.sunset),
                    icon         = SunIcon.Set,
                    arrow        = SunIcon.Set,
                    ringFill     = QuickInkColors.CoralBase,
                    bg           = setBg,
                    border       = setBorder,
                    pulseScale   = setPulse,
                    titleStyle   = tileTitleStyle,
                    inkColor     = colors.ink,
                    captionColor = colors.inkSoft,
                    labelStyle   = type.caption,
                )
            }
        }

        Spacer(Modifier.size(QuickInkSpacing.s3))

        if (snapshot.phase == DaylightPhase.Unresolved) {
            Text(
                text     = "Sunrise unavailable",
                style    = type.caption,
                color    = colors.muted,
                modifier = Modifier.padding(horizontal = QuickInkSpacing.s1),
            )
        } else {
            DaylightStatsCard(
                snapshot     = snapshot,
                valueStyle   = type.heading.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize   = 14.sp,
                ),
                captionStyle = type.caption,
                inkColor     = colors.ink,
                captionColor = colors.muted,
                surface      = colors.surface,
                borderColor  = colors.border,
            )
        }
    }
}

/**
 * White surface card under the sunrise/sunset tiles. Lays out as
 * `[leading stat] [meter] [trailing stat]` in one row — the meter
 * takes the middle weighted space so the two stat columns hug the
 * card edges. Big number on top, muted caption underneath, mirroring
 * the home mockup's data-block treatment.
 */
@Composable
private fun DaylightStatsCard(
    snapshot: DaylightSnapshot,
    valueStyle: androidx.compose.ui.text.TextStyle,
    captionStyle: androidx.compose.ui.text.TextStyle,
    inkColor: androidx.compose.ui.graphics.Color,
    captionColor: androidx.compose.ui.graphics.Color,
    surface: androidx.compose.ui.graphics.Color,
    borderColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(surface)
            .border(1.dp, borderColor, RoundedCornerShape(QuickInkRadius.lg))
            .padding(
                horizontal = QuickInkSpacing.s4,
                vertical   = QuickInkSpacing.s3,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text  = snapshot.leadingValue,
                style = valueStyle,
                color = inkColor,
            )
            Text(
                text  = snapshot.leadingCaption,
                style = captionStyle,
                color = captionColor,
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Box(modifier = Modifier.weight(1f)) {
            if (snapshot.isNight) {
                // Cool indigo palette for the night meter — distinct
                // from the warm yellow daylight track. Moon marker
                // replaces the sun.
                val nightFill = androidx.compose.ui.graphics.Color(0xFF4C5A8C)
                // Dark slate grey for the moon body — strong contrast
                // against the cream marker fill and reads as a moon
                // silhouette rather than a coloured disc.
                val moonColor = androidx.compose.ui.graphics.Color(0xFF3F4451)
                DaylightMeter(
                    progress    = snapshot.dayProgress,
                    trackBg     = nightFill.copy(alpha = 0.25f),
                    fill        = nightFill,
                    ringColor   = nightFill,
                    markerFill  = surface,
                    disc        = moonColor,
                    rayColor    = moonColor,
                    moonMode    = true,
                )
            } else {
                DaylightMeter(
                    progress    = snapshot.dayProgress,
                    trackBg     = QuickInkColors.LeafYellowBase.copy(alpha = 0.30f),
                    fill        = QuickInkColors.LeafYellowDeep,
                    ringColor   = QuickInkColors.LeafYellowDeep,
                    markerFill  = surface,
                    disc        = QuickInkColors.CoralDeep,
                    rayColor    = QuickInkColors.CoralDeep,
                    moonMode    = false,
                )
            }
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text  = snapshot.trailingValue,
                style = valueStyle,
                color = inkColor,
            )
            Text(
                text  = snapshot.trailingCaption,
                style = captionStyle,
                color = captionColor,
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
    arrow: SunIcon,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = label,
                style = labelStyle,
                color = captionColor,
            )
            Text(
                text  = time,
                style = titleStyle,
                color = inkColor,
            )
        }
        // Trailing direction arrow — ↑ for sunrise (the sun is on
        // its way up), ↓ for sunset (on its way down). Muted tint
        // so the arrow reads as a cue, not a competing element to
        // the time itself.
        Icon(
            imageVector = when (arrow) {
                SunIcon.Rise -> Icons.Filled.ArrowUpward
                SunIcon.Set  -> Icons.Filled.ArrowDownward
            },
            contentDescription = null,
            tint     = captionColor,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Slim meter with a filled portion up to `progress` and a haloed-sun
 * "now" indicator overlaid at the same x. Drawn in a single Canvas
 * pass to avoid measure overhead for a slim ribbon.
 *
 * Marker construction (matches the home mockup's daylight pill):
 *   1. A card-surface-coloured disc the marker's diameter, drawn over
 *      the track so the track visually "breaks" at the marker.
 *   2. A yellow halo ring around that disc, matching the filled
 *      track's hue.
 *   3. A small coral disc + 8 coral rays inside the halo — the sun
 *      itself, same hue as the avatar's accent so the marker reads
 *      as the app's identity colour.
 */
@Composable
private fun DaylightMeter(
    progress: Float,
    trackBg: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color,
    ringColor: androidx.compose.ui.graphics.Color,
    markerFill: androidx.compose.ui.graphics.Color,
    disc: androidx.compose.ui.graphics.Color,
    rayColor: androidx.compose.ui.graphics.Color,
    moonMode: Boolean,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
    ) {
        val centerY = size.height / 2f
        val trackH  = 4.dp.toPx()
        // Sundial dimensions:
        //   outer ring Ø 25dp  (radius 12.5dp), stroke 1dp
        //   inner sun  Ø 8dp   (radius 4dp)
        //   rays — 8 ticks 2dp long, running 5.5dp → 7.5dp from centre.
        //   Gaps: 1.5dp from disc edge to ray inner tip,
        //         4dp from ray outer tip to ring's inner edge (11.5dp).
        val markerOuterR  = 12.5.dp.toPx()
        val markerStroke  = 1.dp.toPx()
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
        // Now indicator.
        val dotCx = fillW.coerceIn(markerOuterR, size.width - markerOuterR)
        // 1. Card-coloured backdrop — the track passes underneath, so
        //    this disc visually breaks the bar where the marker sits.
        drawCircle(
            color  = markerFill,
            radius = markerOuterR,
            center = Offset(dotCx, centerY),
        )
        // 2. Yellow halo ring.
        drawCircle(
            color  = ringColor,
            radius = markerOuterR - markerStroke / 2f,
            center = Offset(dotCx, centerY),
            style  = Stroke(width = markerStroke),
        )
        if (moonMode) {
            // Crescent moon — draw a full disc in the moon hue, then
            // overlay a slightly smaller, offset disc in the marker's
            // background colour to carve the crescent. Larger moon
            // body + smaller carve disc give the crescent more visual
            // weight than a thin sliver. The carve is shifted upper-
            // right so the lit edge faces lower-left.
            val moonR        = 8.dp.toPx()
            val carveR       = 6.dp.toPx()
            val carveOffsetX = 3.dp.toPx()
            val carveOffsetY = (-1.5).dp.toPx()
            drawCircle(
                color  = disc,
                radius = moonR,
                center = Offset(dotCx, centerY),
            )
            drawCircle(
                color  = markerFill,
                radius = carveR,
                center = Offset(dotCx + carveOffsetX, centerY + carveOffsetY),
            )
        } else {
            // Coral sun — central disc + 8 short rays inside the halo.
            val discR     = 4.dp.toPx()
            val rayInner  = 5.5.dp.toPx()
            val rayOuter  = 7.5.dp.toPx()
            val rayStroke = 1.25.dp.toPx()
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
 * and the trailing stat block so VoiceOver/TalkBack reads the card
 * as one coherent unit rather than five individual fragments.
 */
private fun a11yLabel(snapshot: DaylightSnapshot): String {
    val rise = formatTime(snapshot.sunrise)
    val set  = formatTime(snapshot.sunset)
    val trailingLine = "${snapshot.trailingValue} ${snapshot.trailingCaption.lowercase(Locale.getDefault())}"
    val leadingLine  = "${snapshot.leadingValue} ${snapshot.leadingCaption.lowercase(Locale.getDefault())}"
    return when (snapshot.phase) {
        DaylightPhase.BeforeSunrise -> if (snapshot.isNight) {
            val prevSet = formatTime(snapshot.nightSunset)
            "Pre-dawn. Sunset was at $prevSet, sunrise at $rise. $leadingLine. $trailingLine."
        } else {
            "Sunrise at $rise, sunset at $set. $leadingLine."
        }
        DaylightPhase.Daytime ->
            "Sunrise at $rise, sunset at $set. $trailingLine."
        DaylightPhase.AfterSunset -> if (snapshot.isNight) {
            val nextRise = formatTime(snapshot.nightSunrise)
            "Night. Sunset was at $set, next sunrise at $nextRise. $leadingLine. $trailingLine."
        } else {
            "Sunrise was at $rise, sunset was at $set. $trailingLine."
        }
        DaylightPhase.Unresolved ->
            "Sunrise and sunset unavailable for this location."
    }
}
