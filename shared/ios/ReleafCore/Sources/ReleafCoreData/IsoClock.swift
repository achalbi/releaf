/*
 * IsoClock.swift
 *
 * One place to format ISO-8601 UTC timestamps with millisecond precision —
 * the shape every `created_at` / `updated_at` column in the schema wants:
 *   2026-04-21T10:15:30.123Z
 *
 * Mirrors the Kotlin `IsoClock` so both platforms stamp identical rows into
 * the shared SQLite schema defined in shared/design-system/migrations/v1_initial.sql.
 * Matches the SQL default `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')` so a row
 * inserted without app-layer timestamps from either client still reads the
 * same way.
 *
 * PR #4a moved this from `apps/releaf/ios/Releaf/Data/Notepad/IsoClock.swift`
 * into ReleafCoreData. Behavior unchanged.
 */

import Foundation

public enum IsoClock {

    /// Cached UTC ISO-8601 formatter with millisecond precision. `Date` is
    /// not itself a persisted type in our schema — timestamps are stored as
    /// TEXT so they round-trip between Android (Room) and iOS (GRDB)
    /// without platform-specific Date precision quirks.
    private static let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    /// Current UTC timestamp in `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` form.
    public static func nowIso(date: Date = Date()) -> String {
        formatter.string(from: date)
    }

    /// Today's date in the device's local zone as YYYY-MM-DD — the shape
    /// the `entry_date` column's CHECK constraint enforces.
    public static func todayLocalDate(date: Date = Date(), calendar: Calendar = .current) -> String {
        let comps = calendar.dateComponents([.year, .month, .day], from: date)
        // zero-pad month/day to match the GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
        return String(format: "%04d-%02d-%02d",
                      comps.year ?? 1970,
                      comps.month ?? 1,
                      comps.day ?? 1)
    }
}
