/*
 * DaylightStatusBar.kt
 *
 * Custom status bar that sits above every authenticated screen and
 * shows where the user is in the current daylight (or nighttime)
 * phase. Layout locked through the design iteration in chat — see
 * iOS `DaylightStatusBar.swift` for the matching spec.
 *
 * Mirror of iOS implementation. When you change the colors, layout,
 * or marker geometry here, change `DaylightStatusBar.swift` too.
 */

package app.quickink.mobile.features.daylight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import app.quickink.mobile.ui.theme.QuickInkFonts
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

// region Colors — fixed across themes (see file header)

private val ColorCanvas    = Color(0xFFFBF6EE)
private val ColorTrack     = Color(0xFFE5DDD0)
private val ColorDayFill   = Color(0xFFEAB734)
private val ColorNightFill = Color(0xFF1A1A1A)
private val ColorSun       = Color(0xFFD85A30)
private val ColorMoon      = Color(0xFF4A4A4A)
private val ColorLabel     = Color(0xFF888780)
private val ColorInk       = Color(0xFF2C2C2A)

// endregion

/**
 * Status-bar-height composable, ~60dp tall, rendering the current
 * solar-phase meter. [latitude] / [longitude] come from the host
 * (usually `QuickInkRoot` after a `LocationService` fix); pass
 * null to render the empty-state shell while the location fetch
 * is in flight.
 */
@Composable
fun DaylightStatusBar(
    latitude: Double?,
    longitude: Double?,
    modifier: Modifier = Modifier,
) {
    if (latitude == null || longitude == null) {
        DaylightStatusBarShell(modifier = modifier)
        return
    }

    // Re-render every minute so the dot glides forward and the
    // center clock ticks without a pinned Date. delay() inside
    // LaunchedEffect gives us a cooperative coroutine tick that
    // pauses when the screen is off — no battery cost while idle.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(latitude, longitude) {
        while (true) {
            now = Instant.now()
            delay(60_000L)
        }
    }

    val phase = SolarPhaseCalculator.compute(
        latitude  = latitude,
        longitude = longitude,
        at        = now,
        zone      = ZoneId.systemDefault(),
    )
    DaylightStatusBarContent(phase = phase, modifier = modifier)
}

// region Content (with a real phase)

@Composable
private fun DaylightStatusBarContent(
    phase: SolarPhase,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val isDay = phase.phase == SolarPhase.Phase.DAY

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ColorCanvas)
            .padding(top = 28.dp)
            .padding(horizontal = 22.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        // Labels row
        Row(
            modifier = Modifier.fillMaxWidth().height(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsLabel(if (isDay) "SUNRISE" else "SUNSET")
            Spacer(modifier = Modifier.weight(1f))
            CapsLabel("NOW")
            Spacer(modifier = Modifier.weight(1f))
            CapsLabel(if (isDay) "SUNSET" else "SUNRISE")
        }

        // Times row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockText(formatClock(phase.anchorLeft, zone), sizeSp = 11f)
            Spacer(modifier = Modifier.weight(1f))
            ClockText(formatClock(phase.now,          zone), sizeSp = 13f)
            Spacer(modifier = Modifier.weight(1f))
            ClockText(formatClock(phase.anchorRight,  zone), sizeSp = 11f)
        }

        // Bar row
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .semantics {
                    contentDescription = accessibilityLabel(phase)
                },
        ) {
            drawMeter(
                width    = size.width,
                height   = size.height,
                fraction = phase.fraction.toFloat(),
                isDay    = isDay,
            )
        }

        // Captions row — the two duration labels are centered on
        // the elapsed / remaining halves of the bar so they
        // visually anchor to their portions of the meter.
        CaptionsRow(
            elapsed   = formatDuration(phase.elapsedMillis),
            remaining = formatDuration(phase.remainingMillis),
            fraction  = phase.fraction.toFloat(),
        )
    }
}

@Composable
private fun CaptionsRow(
    elapsed: String,
    remaining: String,
    fraction: Float,
) {
    // Wrap in a Box so the two labels can position themselves at
    // the elapsed-half and remaining-half centers. Using Layout
    // would be cleaner but adds boilerplate for a two-element row.
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(9.dp)
    ) {
        // Elapsed label — center on the midpoint of [0, fraction].
        val elapsedFrac   = (fraction / 2f).coerceIn(0f, 1f)
        val remainingFrac = ((fraction + 1f) / 2f).coerceIn(0f, 1f)
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(elapsedFrac.coerceAtLeast(0.0001f)))
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "$elapsed in",
                    fontFamily = QuickInkFonts.ui,
                    fontSize   = 8.sp,
                    fontWeight = FontWeight.Normal,
                    color      = ColorLabel,
                    textAlign  = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.weight((1f - elapsedFrac).coerceAtLeast(0.0001f)))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(remainingFrac.coerceAtLeast(0.0001f)))
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "$remaining left",
                    fontFamily = QuickInkFonts.ui,
                    fontSize   = 8.sp,
                    fontWeight = FontWeight.Normal,
                    color      = ColorLabel,
                    textAlign  = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.weight((1f - remainingFrac).coerceAtLeast(0.0001f)))
        }
    }
}

@Composable
private fun CapsLabel(text: String) {
    Text(
        text       = text,
        fontFamily = QuickInkFonts.ui,
        fontSize   = 8.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.8.sp,
        color      = ColorLabel,
    )
}

@Composable
private fun ClockText(text: String, sizeSp: Float) {
    Text(
        text       = text,
        fontFamily = QuickInkFonts.serif,
        fontSize   = sizeSp.sp,
        fontWeight = FontWeight.Normal,
        color      = ColorInk,
        style      = TightTextStyle,
    )
}

// Disables Android's legacy `includeFontPadding` and forces the
// line height to exactly the glyph height. Together they strip the
// 4-6sp of invisible padding the platform normally tacks onto every
// Text — so the times row hugs its glyphs with no extra space.
private val TightTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim      = LineHeightStyle.Trim.Both,
    ),
)

// endregion

// region Empty shell

@Composable
private fun DaylightStatusBarShell(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ColorCanvas)
            .padding(top = 28.dp)
            .padding(horizontal = 22.dp, vertical = 0.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsLabel("SUNRISE")
            Spacer(modifier = Modifier.weight(1f))
            CapsLabel("NOW")
            Spacer(modifier = Modifier.weight(1f))
            CapsLabel("SUNSET")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockText("—:—", 11f)
            Spacer(modifier = Modifier.weight(1f))
            ClockText("—:—", 13f)
            Spacer(modifier = Modifier.weight(1f))
            ClockText("—:—", 11f)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
        ) {
            drawTrack(size.width, size.height)
        }
        // Reserve caption-row height so the layout doesn't jump
        // when the live phase arrives.
        Spacer(modifier = Modifier.height(9.dp))
    }
}

// endregion

// region Canvas drawing

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMeter(
    width: Float,
    height: Float,
    fraction: Float,
    isDay: Boolean,
) {
    val trackHeight = 5f
    val trackY = (height - trackHeight) / 2f

    // 1. Track
    drawRoundRect(
        color = ColorTrack,
        topLeft = Offset(x = 0f, y = trackY),
        size    = Size(width = width, height = trackHeight),
        cornerRadius = CornerRadius(trackHeight / 2f),
    )

    // 2. Fill
    val fillWidth = (width * fraction).coerceAtLeast(0f)
    if (fillWidth > 0f) {
        drawRoundRect(
            color   = if (isDay) ColorDayFill else ColorNightFill,
            topLeft = Offset(x = 0f, y = trackY),
            size    = Size(width = fillWidth, height = trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
    }

    // 3. Marker
    val centerY = trackY + trackHeight / 2f
    val center  = Offset(x = fillWidth, y = centerY)
    if (isDay) drawSun(center) else drawMoon(center)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrack(
    width: Float, height: Float,
) {
    val trackHeight = 5f
    val trackY = (height - trackHeight) / 2f
    drawRoundRect(
        color   = ColorTrack,
        topLeft = Offset(x = 0f, y = trackY),
        size    = Size(width = width, height = trackHeight),
        cornerRadius = CornerRadius(trackHeight / 2f),
    )
}

/**
 * 8-ray coral sun: small body + 1px cream halo ring + 8 short rays
 * at the cardinal + ordinal compass points. Geometry copied from
 * the locked SVG / iOS implementation so all three render
 * identically.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSun(center: Offset) {
    val bodyR    = 4f
    val haloR    = 6f
    val rayInner = 7f
    val rayOuter = 10f
    val rayWidth = 1.4f
    val ord  = rayInner / sqrt(2f)
    val ord2 = rayOuter / sqrt(2f)

    // Halo first so the body's coral pops on the gold fill.
    drawCircle(color = ColorCanvas, radius = haloR, center = center)

    // Rays — 4 cardinal + 4 diagonal.
    val rays = listOf(
        Offset( 0f, -rayInner) to Offset( 0f, -rayOuter),
        Offset( 0f,  rayInner) to Offset( 0f,  rayOuter),
        Offset( rayInner,  0f) to Offset( rayOuter,  0f),
        Offset(-rayInner,  0f) to Offset(-rayOuter,  0f),
        Offset( ord,  -ord) to Offset( ord2,  -ord2),
        Offset(-ord,  -ord) to Offset(-ord2,  -ord2),
        Offset( ord,   ord) to Offset( ord2,   ord2),
        Offset(-ord,   ord) to Offset(-ord2,   ord2),
    )
    for ((start, end) in rays) {
        drawLine(
            color = ColorSun,
            start = Offset(center.x + start.x, center.y + start.y),
            end   = Offset(center.x + end.x,   center.y + end.y),
            strokeWidth = rayWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }

    // Body on top of halo.
    drawCircle(color = ColorSun, radius = bodyR, center = center)
}

/**
 * Crescent moon: dark-grey disc with a cream punch-out offset to
 * the upper-right, producing a waxing shape.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMoon(center: Offset) {
    val bodyR = 6.5f
    val cutR  = 5.1f
    val cutDx =  2.3f
    val cutDy = -1.2f

    // Halo (separates the moon from the inky bar fill on its left
    // side, matching the sun's halo for consistency).
    drawCircle(color = ColorCanvas, radius = bodyR + 1.5f, center = center)

    // Body.
    drawCircle(color = ColorMoon, radius = bodyR, center = center)

    // Cream cut-out for the crescent shape.
    drawCircle(
        color  = ColorCanvas,
        radius = cutR,
        center = Offset(center.x + cutDx, center.y + cutDy),
    )
}

// endregion

// region Formatters

private val ClockFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun formatClock(instant: Instant, zone: ZoneId): String {
    val zoned = ZonedDateTime.ofInstant(instant, zone)
    // Pattern emits "AM" / "PM" — lowercase to match the spec.
    return ClockFormatter.format(zoned)
        .replace("AM", "am")
        .replace("PM", "pm")
}

/** "8h 49m" — drops hour when zero so the first hour reads "12m". */
private fun formatDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val hours   = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours == 0) "${minutes}m" else "${hours}h ${minutes}m"
}

private fun accessibilityLabel(phase: SolarPhase): String {
    val elapsed   = formatDuration(phase.elapsedMillis)
    val remaining = formatDuration(phase.remainingMillis)
    val kind      = if (phase.phase == SolarPhase.Phase.DAY) "daylight" else "nighttime"
    return "$elapsed into $kind, $remaining remaining."
}

// endregion

// region Previews

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewLive() {
    DaylightStatusBar(latitude = 12.97, longitude = 77.59)
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewShell() {
    DaylightStatusBar(latitude = null, longitude = null)
}

// endregion
