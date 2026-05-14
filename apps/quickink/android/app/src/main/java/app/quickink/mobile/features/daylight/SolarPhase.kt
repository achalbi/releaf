/*
 * SolarPhase.kt
 *
 * Pure sunrise / sunset calculator. Mirror of iOS's `SolarPhase.swift`
 * — same algorithm (Almanac for Computers, accurate to ~1 minute),
 * same return shape, same polar fallback. Both apps consume this
 * via `DaylightStatusBar` to position the sun/moon marker.
 *
 * When you change the math here, change `SolarPhase.swift` too.
 *
 *   1. Day-of-year for the date (1–366)
 *   2. Convert longitude to an hour offset; approximate sunrise/set
 *      time of day in those terms
 *   3. Sun's mean anomaly → true longitude
 *   4. Sun's right ascension (quadrant-corrected, deg → hours)
 *   5. Sun's declination
 *   6. Local hour angle from official zenith (90.833° — refraction +
 *      apparent semidiameter)
 *   7. Local mean time → UT → Instant in UTC
 *
 * Polar fallback: when cos(H) falls outside [-1, 1] the sun never
 * rises (polar night) or never sets (midnight sun); the sunrise /
 * sunset accessors return null, and `compute` collapses to a flat
 * 24h day rooted at local midnight.
 */

package app.quickink.mobile.features.daylight

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Snapshot of the current solar phase plus the two flanking
 * sunrise / sunset events. Returned from [SolarPhaseCalculator.compute];
 * consumed by `DaylightStatusBar` to position its sun/moon marker.
 */
data class SolarPhase(
    val phase: Phase,
    /** Left anchor of the bar — sunrise (day) or sunset (night). */
    val anchorLeft: Instant,
    /** Right anchor of the bar — sunset (day) or next sunrise (night). */
    val anchorRight: Instant,
    /** Current instant the calculation was made for. */
    val now: Instant,
) {
    enum class Phase {
        /** Between sunrise and sunset. */
        DAY,

        /** Between sunset and the next sunrise. */
        NIGHT,
    }

    /**
     * 0..1 position from [anchorLeft] to [anchorRight]. Clamped so
     * off-by-a-few-seconds inputs (we ticked just past sunset, the
     * caller hasn't refreshed yet) don't push the dot off the rail.
     */
    val fraction: Double
        get() {
            val total = anchorRight.toEpochMilli() - anchorLeft.toEpochMilli()
            if (total <= 0L) return 0.0
            val elapsed = now.toEpochMilli() - anchorLeft.toEpochMilli()
            return (elapsed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        }

    /** Time elapsed since [anchorLeft] in milliseconds. Drives the "8h 49m in" caption. */
    val elapsedMillis: Long get() = now.toEpochMilli() - anchorLeft.toEpochMilli()

    /** Time remaining until [anchorRight] in milliseconds. Drives the "4h 23m left" caption. */
    val remainingMillis: Long get() = anchorRight.toEpochMilli() - now.toEpochMilli()
}

object SolarPhaseCalculator {

    /**
     * Standard "official" zenith — accounts for atmospheric refraction
     * (~34') and the sun's apparent semidiameter (~16'). The sun's
     * upper limb crosses the horizon at this angle, which is what
     * most people call "sunrise/sunset" in everyday usage.
     */
    private const val OFFICIAL_ZENITH = 90.833

    /**
     * Compute the current phase given coordinates and an instant.
     * [zone] controls which day's sunrise/sunset are computed and
     * how "midnight" is resolved — pass `ZoneId.systemDefault()`
     * in production so day boundaries follow the user's local time
     * zone.
     */
    fun compute(
        latitude: Double,
        longitude: Double,
        at: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): SolarPhase {
        val localToday = at.atZone(zone).toLocalDate()
        val todaySunrise = sunrise(localToday, latitude, longitude, zone)
        val todaySunset  = sunset (localToday, latitude, longitude, zone)

        // Day phase — current instant sits between today's sunrise
        // and today's sunset.
        if (todaySunrise != null && todaySunset != null &&
            !at.isBefore(todaySunrise) && at.isBefore(todaySunset)
        ) {
            return SolarPhase(
                phase       = SolarPhase.Phase.DAY,
                anchorLeft  = todaySunrise,
                anchorRight = todaySunset,
                now         = at,
            )
        }

        // Pre-sunrise — yesterday's sunset → today's sunrise.
        if (todaySunrise != null && at.isBefore(todaySunrise)) {
            val yesterdaySunset = sunset(localToday.minusDays(1), latitude, longitude, zone)
            if (yesterdaySunset != null) {
                return SolarPhase(
                    phase       = SolarPhase.Phase.NIGHT,
                    anchorLeft  = yesterdaySunset,
                    anchorRight = todaySunrise,
                    now         = at,
                )
            }
        }

        // Post-sunset — today's sunset → tomorrow's sunrise.
        if (todaySunset != null && !at.isBefore(todaySunset)) {
            val tomorrowSunrise = sunrise(localToday.plusDays(1), latitude, longitude, zone)
            if (tomorrowSunrise != null) {
                return SolarPhase(
                    phase       = SolarPhase.Phase.NIGHT,
                    anchorLeft  = todaySunset,
                    anchorRight = tomorrowSunrise,
                    now         = at,
                )
            }
        }

        // Polar fallback — sun didn't rise/set today AND the bracketing
        // days didn't supply one either. Render a flat 24h "day"
        // anchored at local midnight so the bar still has something
        // coherent to show.
        val midnight = localToday.atStartOfDay(zone).toInstant()
        val nextMidnight = localToday.plusDays(1).atStartOfDay(zone).toInstant()
        return SolarPhase(
            phase       = SolarPhase.Phase.DAY,
            anchorLeft  = midnight,
            anchorRight = nextMidnight,
            now         = at,
        )
    }

    /**
     * Sunrise instant for [date] at the given coordinates. Returns
     * null at latitudes where the sun doesn't rise on that date.
     */
    fun sunrise(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): Instant? =
        solarEvent(rising = true, date = date, latitude = latitude, longitude = longitude, zone = zone)

    /** Sunset instant. Symmetric with [sunrise]. */
    fun sunset(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): Instant? =
        solarEvent(rising = false, date = date, latitude = latitude, longitude = longitude, zone = zone)

    // region Algorithm

    private fun solarEvent(
        rising: Boolean,
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zone: ZoneId,
    ): Instant? {
        val dayOfYear = date.dayOfYear

        val lngHour = longitude / 15.0

        // Approximate the event's local time of day in fractional
        // hours, which seeds the iteration. (Non-iterative — one
        // pass with these seeds is accurate to ~1 minute.)
        val t = if (rising) {
            dayOfYear + ((6.0  - lngHour) / 24.0)
        } else {
            dayOfYear + ((18.0 - lngHour) / 24.0)
        }

        // Sun's mean anomaly (degrees), then true longitude.
        val mDeg = (0.9856 * t) - 3.289
        var lDeg = mDeg + (1.916 * sinDeg(mDeg)) + (0.020 * sinDeg(2 * mDeg)) + 282.634
        lDeg = normalizeDegrees(lDeg)

        // Right ascension — atan flattens L into a single quadrant;
        // nudge RA into L's quadrant so the result wraps correctly.
        var raDeg = atanDeg(0.91764 * tanDeg(lDeg))
        raDeg = normalizeDegrees(raDeg)
        val lQuadrant  = floor(lDeg  / 90.0) * 90.0
        val raQuadrant = floor(raDeg / 90.0) * 90.0
        raDeg += (lQuadrant - raQuadrant)
        val raHours = raDeg / 15.0   // degrees → hours

        // Sun's declination.
        val sinDec = 0.39782 * sinDeg(lDeg)
        val cosDec = cosDeg(asinDeg(sinDec))

        // Local hour angle from the official zenith.
        val cosH = (cosDeg(OFFICIAL_ZENITH) - (sinDec * sinDeg(latitude))) /
                   (cosDec * cosDeg(latitude))

        // Polar conditions — sun never rises (cosH > 1) or never
        // sets (cosH < -1) on this date at this latitude.
        if (cosH > 1.0 || cosH < -1.0) return null

        val hDeg = if (rising) (360.0 - acosDeg(cosH)) else acosDeg(cosH)
        val hHours = hDeg / 15.0

        // Local mean time of the event in hours.
        val tLocal = hHours + raHours - (0.06571 * t) - 6.622

        // Convert to UT, wrap into [0, 24).
        var ut = tLocal - lngHour
        ut = ((ut % 24.0) + 24.0) % 24.0

        val hour       = ut.toInt()
        val minuteRaw  = (ut - hour) * 60.0
        val minute     = minuteRaw.toInt()
        val second     = ((minuteRaw - minute) * 60.0).toInt().coerceIn(0, 59)

        return ZonedDateTime
            .of(date.year, date.monthValue, date.dayOfMonth, hour, minute, second, 0, ZoneId.of("UTC"))
            .toInstant()
    }

    // endregion

    // region Trig helpers (degrees, since the published formulas use degrees)

    private fun sinDeg (x: Double): Double = sin(x * PI / 180.0)
    private fun cosDeg (x: Double): Double = cos(x * PI / 180.0)
    private fun tanDeg (x: Double): Double = tan(x * PI / 180.0)
    private fun asinDeg(x: Double): Double = asin(x) * 180.0 / PI
    private fun acosDeg(x: Double): Double = acos(x) * 180.0 / PI
    private fun atanDeg(x: Double): Double = atan(x) * 180.0 / PI

    private fun normalizeDegrees(x: Double): Double {
        val v = x % 360.0
        return if (v < 0) v + 360.0 else v
    }

    // endregion
}
