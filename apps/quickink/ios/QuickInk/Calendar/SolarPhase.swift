/*
 * SolarPhase.swift
 *
 * Pure sunrise / sunset calculator. Given a latitude, longitude, and
 * an instant, returns the bracketing pair of solar events (today's
 * sunrise + today's sunset, or yesterday's sunset + today's sunrise,
 * etc.) and whether we're currently in the .day or .night half of
 * the 24h cycle. The DaylightStatusBar consumes this — the bar's
 * dot position is `(now − anchorLeft) / (anchorRight − anchorLeft)`.
 *
 * Algorithm: Almanac for Computers (NOAA-distributed reference
 * implementation). The published formulas are the standard
 * non-iterative civil sunrise/sunset, accurate to about a minute
 * across all populated latitudes — good enough for a status-bar
 * meter that ticks once a minute.
 *
 *   1. Day-of-year for the date (0–365)
 *   2. Convert longitude to an hour offset, approximate sunrise/set
 *      time of day in those terms
 *   3. Sun's mean anomaly → true longitude
 *   4. Sun's right ascension (quadrant-corrected, deg → hours)
 *   5. Sun's declination
 *   6. Local hour angle from official zenith (90°50' — accounts for
 *      atmospheric refraction + the sun's apparent diameter)
 *   7. Local mean time → UT → Date in UTC
 *
 * Polar fallback: if `cos(H)` falls outside [-1, 1] the sun never
 * rises (polar night) or never sets (midnight sun) on the requested
 * date — `sunrise/sunset(...)` return nil and `compute(...)` collapses
 * to a flat 24h day so the bar still renders something reasonable.
 *
 * Mirror of Android's `SolarPhase.kt`. When you change the math
 * here, change it there.
 */

import Foundation

/// Snapshot of the current solar phase plus the two flanking
/// sunrise / sunset events. Returned from `SolarPhaseCalculator.compute`;
/// consumed by `DaylightStatusBar` to position its sun/moon marker.
public struct SolarPhase: Equatable, Sendable {

    /// Which half of the 24h cycle the `now` instant falls in.
    public enum Phase: Sendable {
        /// Between sunrise and sunset.
        case day
        /// Between sunset and the next sunrise.
        case night
    }

    public let phase:        Phase
    /// Left anchor of the bar — sunrise (day) or sunset (night).
    public let anchorLeft:   Date
    /// Right anchor of the bar — sunset (day) or next sunrise (night).
    public let anchorRight:  Date
    /// Current instant the calculation was made for.
    public let now:          Date

    /// 0...1 position from `anchorLeft` to `anchorRight`. Clamped so
    /// off-by-a-few-seconds inputs (we ticked just past sunset, the
    /// caller hasn't refreshed yet) don't push the dot off the rail.
    public var fraction: Double {
        let total = anchorRight.timeIntervalSince(anchorLeft)
        guard total > 0 else { return 0 }
        let elapsed = now.timeIntervalSince(anchorLeft)
        return max(0, min(1, elapsed / total))
    }

    /// Time elapsed since `anchorLeft`. Drives the "8h 49m in" caption.
    public var elapsed: TimeInterval { now.timeIntervalSince(anchorLeft) }
    /// Time remaining until `anchorRight`. Drives the "4h 23m left" caption.
    public var remaining: TimeInterval { anchorRight.timeIntervalSince(now) }
}

public enum SolarPhaseCalculator {

    /// Standard "official" zenith — accounts for atmospheric refraction
    /// (~34') and the sun's apparent semidiameter (~16'). The sun's
    /// upper limb crosses the horizon at this angle, which is what
    /// most people call "sunrise/sunset" in everyday usage.
    private static let officialZenith: Double = 90.833

    /// Compute the current phase given coordinates and an instant.
    /// `calendar` controls which day's sunrise/sunset are computed
    /// and how "midnight" is resolved — pass `.current` in production
    /// so day boundaries follow the user's local time zone.
    public static func compute(
        latitude:  Double,
        longitude: Double,
        at instant: Date = Date(),
        calendar:  Calendar = .current
    ) -> SolarPhase {
        let todayMidnight = calendar.startOfDay(for: instant)
        let todaySunrise  = sunrise(date: todayMidnight, latitude: latitude, longitude: longitude, calendar: calendar)
        let todaySunset   = sunset (date: todayMidnight, latitude: latitude, longitude: longitude, calendar: calendar)

        // Day phase — current instant sits between today's sunrise
        // and today's sunset.
        if let sr = todaySunrise, let ss = todaySunset, instant >= sr, instant < ss {
            return SolarPhase(phase: .day, anchorLeft: sr, anchorRight: ss, now: instant)
        }

        // Pre-sunrise — yesterday's sunset → today's sunrise.
        if let sr = todaySunrise, instant < sr {
            let yesterdayMidnight = calendar.date(byAdding: .day, value: -1, to: todayMidnight) ?? todayMidnight
            if let lastSunset = sunset(date: yesterdayMidnight, latitude: latitude, longitude: longitude, calendar: calendar) {
                return SolarPhase(phase: .night, anchorLeft: lastSunset, anchorRight: sr, now: instant)
            }
        }

        // Post-sunset — today's sunset → tomorrow's sunrise.
        if let ss = todaySunset, instant >= ss {
            let tomorrowMidnight = calendar.date(byAdding: .day, value: 1, to: todayMidnight) ?? todayMidnight
            if let nextSunrise = sunrise(date: tomorrowMidnight, latitude: latitude, longitude: longitude, calendar: calendar) {
                return SolarPhase(phase: .night, anchorLeft: ss, anchorRight: nextSunrise, now: instant)
            }
        }

        // Polar fallback — the sun didn't rise or set today (and the
        // bracketing days didn't supply one either). Render a flat
        // 24h "day" anchored at midnight so the bar still has
        // something coherent to show. Real-world QuickInk users
        // outside the Arctic / Antarctic circles never hit this.
        let tomorrowMidnight = calendar.date(byAdding: .day, value: 1, to: todayMidnight) ?? todayMidnight
        return SolarPhase(
            phase:       .day,
            anchorLeft:  todayMidnight,
            anchorRight: tomorrowMidnight,
            now:         instant
        )
    }

    /// Sunrise instant for the date component of `date` at the given
    /// coordinates, returned as an absolute `Date`. Returns nil at
    /// latitudes where the sun doesn't rise on the given date.
    public static func sunrise(
        date:      Date,
        latitude:  Double,
        longitude: Double,
        calendar:  Calendar = .current
    ) -> Date? {
        solarEvent(rising: true, date: date, latitude: latitude, longitude: longitude, calendar: calendar)
    }

    /// Sunset instant. Symmetric with `sunrise`.
    public static func sunset(
        date:      Date,
        latitude:  Double,
        longitude: Double,
        calendar:  Calendar = .current
    ) -> Date? {
        solarEvent(rising: false, date: date, latitude: latitude, longitude: longitude, calendar: calendar)
    }

    // MARK: - Algorithm

    private static func solarEvent(
        rising:    Bool,
        date:      Date,
        latitude:  Double,
        longitude: Double,
        calendar:  Calendar
    ) -> Date? {
        let comps = calendar.dateComponents([.year, .month, .day], from: date)
        guard
            let year  = comps.year,
            let month = comps.month,
            let day   = comps.day
        else { return nil }

        // Day-of-year, 1-based. Build it from (year, month, day) ourselves
        // rather than asking the calendar — `ordinality(of: .day, in: .year)`
        // returns nil under some locales / leap-year edge cases.
        let dayOfYear = dayOfYear(year: year, month: month, day: day)

        let lngHour = longitude / 15.0

        // Approximate the event's local time of day in fractional hours,
        // which seeds the iteration. (The algorithm is non-iterative —
        // one pass with these seeds is accurate to ~1 minute.)
        let t: Double = rising
            ? Double(dayOfYear) + ((6.0  - lngHour) / 24.0)
            : Double(dayOfYear) + ((18.0 - lngHour) / 24.0)

        // Sun's mean anomaly (degrees), then true longitude.
        let M = (0.9856 * t) - 3.289
        var L = M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634
        L = normalizeDegrees(L)

        // Right ascension — atan flattens the L into the same quadrant
        // every time, so we explicitly nudge RA into L's quadrant.
        var RA = atanDeg(0.91764 * tanDeg(L))
        RA = normalizeDegrees(RA)
        let Lquadrant  = floor(L  / 90.0) * 90.0
        let RAquadrant = floor(RA / 90.0) * 90.0
        RA = RA + (Lquadrant - RAquadrant)
        RA = RA / 15.0   // degrees → hours

        // Sun's declination.
        let sinDec = 0.39782 * sinDeg(L)
        let cosDec = cosDeg(asinDeg(sinDec))

        // Local hour angle from the official zenith.
        let cosH = (cosDeg(officialZenith) - (sinDec * sinDeg(latitude))) /
                   (cosDec * cosDeg(latitude))

        // Polar conditions — sun doesn't rise (cosH > 1) or doesn't
        // set (cosH < -1) on this date at this latitude. Caller
        // decides what to render.
        if cosH > 1.0 || cosH < -1.0 { return nil }

        let H: Double = rising
            ? (360.0 - acosDeg(cosH))
            : acosDeg(cosH)
        let Hhours = H / 15.0

        // Local mean time of the event in hours.
        let T = Hhours + RA - (0.06571 * t) - 6.622

        // Convert to UT, wrap into [0, 24).
        var UT = T - lngHour
        UT = ((UT.truncatingRemainder(dividingBy: 24.0)) + 24.0)
            .truncatingRemainder(dividingBy: 24.0)

        // Build the Date in UTC, then return — Foundation handles the
        // user's time-zone display via the formatter at render time.
        var utcCalendar = Calendar(identifier: .gregorian)
        utcCalendar.timeZone = TimeZone(identifier: "UTC")!
        let hour      = Int(UT)
        let minuteRaw = (UT - Double(hour)) * 60.0
        let minute    = Int(minuteRaw)
        let second    = Int((minuteRaw - Double(minute)) * 60.0)

        var utc = DateComponents()
        utc.year   = year
        utc.month  = month
        utc.day    = day
        utc.hour   = hour
        utc.minute = minute
        utc.second = second
        return utcCalendar.date(from: utc)
    }

    // MARK: - Trig helpers (degrees, since the published formulas use degrees)

    private static func sinDeg (_ x: Double) -> Double { sin(x * .pi / 180.0) }
    private static func cosDeg (_ x: Double) -> Double { cos(x * .pi / 180.0) }
    private static func tanDeg (_ x: Double) -> Double { tan(x * .pi / 180.0) }
    private static func asinDeg(_ x: Double) -> Double { asin(x) * 180.0 / .pi }
    private static func acosDeg(_ x: Double) -> Double { acos(x) * 180.0 / .pi }
    private static func atanDeg(_ x: Double) -> Double { atan(x) * 180.0 / .pi }

    private static func normalizeDegrees(_ x: Double) -> Double {
        let v = x.truncatingRemainder(dividingBy: 360.0)
        return v < 0 ? v + 360.0 : v
    }

    /// Day-of-year (1 = Jan 1) for a Gregorian date. Independent of
    /// `Calendar` so leap-year handling is testable and the same in
    /// Swift and Kotlin.
    private static func dayOfYear(year: Int, month: Int, day: Int) -> Int {
        let daysInMonth: [Int] = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        var doy = day
        if month > 1 {
            for m in 0..<(month - 1) {
                doy += daysInMonth[m]
            }
        }
        if month > 2, isLeapYear(year) {
            doy += 1
        }
        return doy
    }

    private static func isLeapYear(_ y: Int) -> Bool {
        (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)
    }
}
