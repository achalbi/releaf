/*
 * RahuKala.kt
 *
 * Computes the Rahu Kala (a daily inauspicious window in Hindu
 * tradition) for a Gregorian date, anchored to actual sunrise +
 * sunset at Mysuru — the city the Vontikoppal Panchanga is
 * published from.
 *
 * Calculation:
 *   1. daylight = sunset − sunrise
 *   2. slot     = daylight / 8
 *   3. start    = sunrise + (slotIndex − 1) × slot
 *   4. end      = start + slot
 *
 * `slotIndex` is 1..8 keyed by weekday — the standard panchanga
 * table (Monday = 2, Tuesday = 7, etc.). Sunrise/sunset come from
 * commons-suncalc, a pure-JVM solar/lunar calculator.
 *
 * Output format: "HH:mm – HH:mm" in IST. Returns the published
 * fixed-table approximation (assumes 6 AM sunrise / 6 PM sunset)
 * when commons-suncalc can't resolve a rise/set — happens only at
 * polar latitudes, but the fallback keeps the call site total.
 */

package app.releaf.mobile.features.calendar

import org.shredzone.commons.suncalc.SunTimes
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mysuru — origin city of the Vontikoppal Panchanga (12.2958°N,
 * 76.6394°E). Rahu Kala drifts by a few minutes elsewhere in
 * Karnataka and ~10–15 min elsewhere in India because daylight
 * length varies with latitude. If a per-user location preference
 * is added later, swap these constants for the user's lat/lon.
 */
private const val MYSURU_LAT = 12.2958
private const val MYSURU_LON = 76.6394

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Slot index (1..8) of Rahu Kala within the daylight eight-fold
 * split, keyed by weekday. Sourced from the standard panchanga
 * rule:
 *   Sunday → 8, Monday → 2, Tuesday → 7, Wednesday → 5,
 *   Thursday → 6, Friday → 4, Saturday → 3.
 */
private fun rahuSlotIndex(day: DayOfWeek): Int = when (day) {
    DayOfWeek.SUNDAY    -> 8
    DayOfWeek.MONDAY    -> 2
    DayOfWeek.TUESDAY   -> 7
    DayOfWeek.WEDNESDAY -> 5
    DayOfWeek.THURSDAY  -> 6
    DayOfWeek.FRIDAY    -> 4
    DayOfWeek.SATURDAY  -> 3
}

/**
 * Display-formatted sunrise + sunset times for [date] at Mysuru
 * in IST. Returns a pair of "HH:mm" strings, or "—" for either
 * field if the solar calculator can't resolve that event (polar
 * latitudes — never expected at Mysuru). Recomputes a single
 * `SunTimes` per call so cell rendering can `remember(date)` the
 * result without paying for two suncalc passes.
 */
fun sunriseSunsetFor(date: LocalDate): Pair<String, String> {
    val anchor = date.atStartOfDay(IST)
    val times = SunTimes.compute()
        .on(anchor)
        .at(MYSURU_LAT, MYSURU_LON)
        .execute()
    val sunrise = times.rise?.format(TimeFormatter) ?: PLACEHOLDER
    val sunset  = times.set?.format(TimeFormatter)  ?: PLACEHOLDER
    return sunrise to sunset
}

private const val PLACEHOLDER = "—"

/**
 * Sunrise-anchored Rahu Kala window for [date] at Mysuru.
 * Returns "HH:mm – HH:mm" in IST. Falls back to the published
 * fixed-table when the solar calculator can't resolve a rise/set
 * pair (polar latitudes only — never expected to fire here).
 */
fun rahuKalaFor(date: LocalDate): String {
    // Anchor the calculation at local midnight in IST so the
    // "next" sunrise commons-suncalc returns is unambiguously this
    // date's. Without a starting instant the library defaults to
    // UTC, which can drift the rise/set by a day on either side
    // of the IST midnight.
    val anchor = date.atStartOfDay(IST)
    val times = SunTimes.compute()
        .on(anchor)
        .at(MYSURU_LAT, MYSURU_LON)
        .execute()
    val sunrise = times.rise ?: return fixedFallback(date.dayOfWeek)
    val sunset  = times.set  ?: return fixedFallback(date.dayOfWeek)

    val daylight = Duration.between(sunrise, sunset)
    val slotDuration = daylight.dividedBy(8L)
    val slotIndex = rahuSlotIndex(date.dayOfWeek)
    val start = sunrise.plus(slotDuration.multipliedBy((slotIndex - 1).toLong()))
    val end = start.plus(slotDuration)

    return "${start.format(TimeFormatter)} – ${end.format(TimeFormatter)}"
}

/**
 * Published-table approximation, used only when the solar calc
 * can't resolve a sunrise/sunset for the given date and location
 * (polar latitudes; never expected at Mysuru). Times match the
 * conventional table assuming a 6 AM sunrise / 6 PM sunset.
 */
private fun fixedFallback(day: DayOfWeek): String = when (day) {
    DayOfWeek.SUNDAY    -> "16:30 – 18:00"
    DayOfWeek.MONDAY    -> "07:30 – 09:00"
    DayOfWeek.TUESDAY   -> "15:00 – 16:30"
    DayOfWeek.WEDNESDAY -> "12:00 – 13:30"
    DayOfWeek.THURSDAY  -> "13:30 – 15:00"
    DayOfWeek.FRIDAY    -> "10:30 – 12:00"
    DayOfWeek.SATURDAY  -> "09:00 – 10:30"
}
