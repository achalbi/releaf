/*
 * RahuKala.swift
 *
 * Computes the Rahu Kala — a daily inauspicious window in Hindu
 * tradition — for a Gregorian date, anchored to actual sunrise +
 * sunset at Mysuru (the city the Vontikoppal Panchanga is published
 * from). Also exposes formatted sunrise / sunset strings used by the
 * Calendar's selected-day card.
 *
 * Port of Releaf Android's `RahuKala.kt`. Android depends on the
 * pure-JVM `commons-suncalc` library; this port computes sunrise /
 * sunset inline via the classic Almanac-for-Computers (USNO 1990)
 * algorithm — ≈60 lines, accurate to ±1-2 minutes at sub-polar
 * latitudes, no SPM dependency to add.
 *
 * Output format: "HH:mm – HH:mm" in IST. Falls back to the published
 * fixed-table approximation (assumes a 06:00 sunrise / 18:00 sunset
 * split) only when the solar calc can't resolve a rise / set —
 * happens only at polar latitudes, but the fallback keeps the call
 * site total.
 *
 * Mysuru constants live at the top of this file; if a per-user
 * location preference is added later, swap them.
 */

import Foundation

// MARK: - Mysuru constants

/// Origin city of the Vontikoppal Panchanga (12.2958°N, 76.6394°E).
/// Rahu Kala drifts by a few minutes elsewhere in Karnataka and
/// ~10–15 min elsewhere in India because daylight length varies
/// with latitude.
private let mysuruLatitude:  Double = 12.2958
private let mysuruLongitude: Double = 76.6394

private let istTimeZone: TimeZone = TimeZone(identifier: "Asia/Kolkata") ?? TimeZone(secondsFromGMT: 5 * 3600 + 30 * 60)!

private let timeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = istTimeZone
    f.dateFormat = "HH:mm"
    return f
}()

private let placeholder = "—"

// MARK: - Public API

/// Display-formatted sunrise + sunset for `date` at Mysuru in IST.
/// Returns a pair of "HH:mm" strings, or the placeholder for either
/// field if the solar calculator can't resolve that event.
public func sunriseSunsetFor(_ date: Date) -> (sunrise: String, sunset: String) {
    let times = computeSunTimes(date: date, latitude: mysuruLatitude, longitude: mysuruLongitude)
    let rise = times.sunrise.map(timeFormatter.string(from:)) ?? placeholder
    let set  = times.sunset.map(timeFormatter.string(from:))  ?? placeholder
    return (rise, set)
}

/// Raw sunrise + sunset `Date` pair for `date` at the given
/// (`latitude`, `longitude`), defaulting to Mysuru. Returns `nil`
/// for either event when the solar calculator can't resolve it
/// (polar latitudes only). Exposed publicly so the home
/// `DaylightHero` can drive its now-marker off the same numbers
/// `sunriseSunsetFor` formats — and so a future per-user
/// `LocationService`-driven lat/lon is a single call-site swap.
public func sunTimesFor(
    _ date: Date,
    latitude: Double = 12.2958,   // Mysuru — matches mysuruLatitude
    longitude: Double = 76.6394   // Mysuru — matches mysuruLongitude
) -> (sunrise: Date?, sunset: Date?) {
    computeSunTimes(date: date, latitude: latitude, longitude: longitude)
}

/// Sunrise-anchored Rahu Kala window for `date` at Mysuru.
/// Returns "HH:mm – HH:mm" in IST. Falls back to the published fixed-
/// table when the solar calculator can't resolve a rise/set pair
/// (polar latitudes only — never expected at Mysuru).
public func rahuKalaFor(_ date: Date) -> String {
    let times = computeSunTimes(date: date, latitude: mysuruLatitude, longitude: mysuruLongitude)
    guard let sunrise = times.sunrise, let sunset = times.sunset else {
        return fixedFallback(weekday: weekdayIndex(of: date))
    }
    let daylight = sunset.timeIntervalSince(sunrise)
    let slot = daylight / 8.0
    let slotIndex = rahuSlotIndex(weekday: weekdayIndex(of: date))
    let start = sunrise.addingTimeInterval(slot * Double(slotIndex - 1))
    let end   = start.addingTimeInterval(slot)
    return "\(timeFormatter.string(from: start)) – \(timeFormatter.string(from: end))"
}

// MARK: - Weekday helpers

/// 1 = Sunday … 7 = Saturday — matches `Calendar.component(.weekday:)`.
private func weekdayIndex(of date: Date) -> Int {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = istTimeZone
    return cal.component(.weekday, from: date)
}

/// Slot index (1..8) of Rahu Kala within the daylight eight-fold
/// split, keyed by weekday. Standard panchanga rule:
///   Sunday → 8, Monday → 2, Tuesday → 7, Wednesday → 5,
///   Thursday → 6, Friday → 4, Saturday → 3.
private func rahuSlotIndex(weekday: Int) -> Int {
    switch weekday {
    case 1: return 8 // Sunday
    case 2: return 2 // Monday
    case 3: return 7 // Tuesday
    case 4: return 5 // Wednesday
    case 5: return 6 // Thursday
    case 6: return 4 // Friday
    case 7: return 3 // Saturday
    default: return 8
    }
}

/// Published-table approximation, used only when the solar calc
/// can't resolve a sunrise/sunset for the given date and location.
/// Times match the conventional table assuming 6 AM sunrise / 6 PM
/// sunset.
private func fixedFallback(weekday: Int) -> String {
    switch weekday {
    case 1: return "16:30 – 18:00" // Sunday
    case 2: return "07:30 – 09:00" // Monday
    case 3: return "15:00 – 16:30" // Tuesday
    case 4: return "12:00 – 13:30" // Wednesday
    case 5: return "13:30 – 15:00" // Thursday
    case 6: return "10:30 – 12:00" // Friday
    case 7: return "09:00 – 10:30" // Saturday
    default: return "—"
    }
}

// MARK: - Solar calculation (USNO Almanac for Computers, 1990)

/// Computes sunrise + sunset for `date` at (`latitude`, `longitude`).
/// Returns `nil` for either when the date is at a polar latitude
/// where the sun never rises or never sets. Sub-polar latitudes
/// always return non-nil values accurate to ±1-2 minutes — adequate
/// for the panchanga's display purpose.
///
/// Algorithm: classic Almanac-for-Computers approach used in many
/// open-source sun calculators. The math is in degrees (converted to
/// radians for trig) and the answer is a UTC Date — the caller
/// formats into IST via `timeFormatter`.
private func computeSunTimes(date: Date, latitude: Double, longitude: Double) -> (sunrise: Date?, sunset: Date?) {
    // Anchor the calculation to the calendar-local date at Mysuru.
    // We use IST for the date components, then return a UTC Date for
    // each event so the public formatter can render it in IST.
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = istTimeZone
    let ordinal = cal.ordinality(of: .day, in: .year, for: date) ?? 0
    let dayOfYear = Double(ordinal)
    if dayOfYear <= 0 { return (nil, nil) }

    let lngHour = longitude / 15.0
    /// Official sunrise/sunset zenith (90° + atmospheric refraction
    /// correction of 50 arc-minutes). Equivalent to a solar altitude
    /// of −0.833°. Matches commons-suncalc's "official" angle so the
    /// times line up with the Android reference output.
    let zenith: Double = 90.833

    func eventTime(rising: Bool) -> Date? {
        // Approximate time of day (in hours) — 6h for rise, 18h for set.
        let t = dayOfYear + ((rising ? 6.0 : 18.0) - lngHour) / 24.0

        // Sun's mean anomaly.
        let M = (0.9856 * t) - 3.289

        // Sun's true longitude.
        var L = M
            + 1.916 * sin(toRadians(M))
            + 0.020 * sin(toRadians(2 * M))
            + 282.634
        L = normalize360(L)

        // Sun's right ascension (RA).
        var RA = toDegrees(atan(0.91764 * tan(toRadians(L))))
        RA = normalize360(RA)

        // RA must be in the same quadrant as L.
        let lQuad  = floor(L  / 90.0) * 90.0
        let raQuad = floor(RA / 90.0) * 90.0
        RA = RA + (lQuad - raQuad)
        RA = RA / 15.0  // → hours

        // Sun's declination.
        let sinDec = 0.39782 * sin(toRadians(L))
        let cosDec = cos(asin(sinDec))

        // Sun's local hour angle.
        let cosH = (cos(toRadians(zenith)) - (sinDec * sin(toRadians(latitude))))
                 / (cosDec * cos(toRadians(latitude)))
        if cosH > 1.0 || cosH < -1.0 {
            return nil  // Polar — no rise/set on this date.
        }
        var H = rising ? (360.0 - toDegrees(acos(cosH))) : toDegrees(acos(cosH))
        H = H / 15.0  // → hours

        // Local mean time → UTC hours of the event.
        let localT = H + RA - (0.06571 * t) - 6.622
        var ut = localT - lngHour
        ut = normalize24(ut)

        // Compose into a Date. The date components come from the
        // local-IST calendar so that DST-style boundary days don't
        // straddle two UTC dates; the hour/minute/second are UTC.
        var components = cal.dateComponents([.year, .month, .day], from: date)
        components.timeZone = TimeZone(identifier: "UTC")
        let hour = Int(ut.rounded(.down))
        let minutesFloat = (ut - Double(hour)) * 60.0
        let minute = Int(minutesFloat.rounded(.down))
        let second = Int(((minutesFloat - Double(minute)) * 60.0).rounded(.toNearestOrEven))
        components.hour = max(0, min(23, hour))
        components.minute = max(0, min(59, minute))
        components.second = max(0, min(59, second))

        var utcCal = Calendar(identifier: .gregorian)
        utcCal.timeZone = TimeZone(identifier: "UTC")!
        return utcCal.date(from: components)
    }

    return (eventTime(rising: true), eventTime(rising: false))
}

// MARK: - Math helpers

private func toRadians(_ degrees: Double) -> Double { degrees * .pi / 180.0 }
private func toDegrees(_ radians: Double) -> Double { radians * 180.0 / .pi }

private func normalize360(_ value: Double) -> Double {
    let r = value.truncatingRemainder(dividingBy: 360.0)
    return r < 0 ? r + 360.0 : r
}

private func normalize24(_ value: Double) -> Double {
    let r = value.truncatingRemainder(dividingBy: 24.0)
    return r < 0 ? r + 24.0 : r
}
