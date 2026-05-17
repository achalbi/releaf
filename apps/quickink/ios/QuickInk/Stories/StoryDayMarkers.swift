/*
 * StoryDayMarkers.swift
 *
 * Walks a story's ordered item list and emits a marker each time the
 * effective date OR time-of-day bucket changes from the previous
 * item. The reader uses these to render sticky `— MAY 4 · EVENING —`
 * dividers between items (mockup §7.4).
 *
 * Effective date: `occurred_at` if set, otherwise `created_at`.
 * Time-of-day buckets (local time of the effective date):
 *   - MORNING   (05–10)
 *   - AFTERNOON (11–16)
 *   - EVENING   (17–20)
 *   - NIGHT     (21–04)
 *
 * Mirror of Android `StoryDayMarkers.kt`.
 */

import Foundation

public struct StoryDayMarker: Equatable {
    /// The item that this marker should render *before*. Reader walks
    /// items in order; right before drawing the item whose id matches
    /// `precedingItemId`, the marker is emitted.
    public let precedingItemId: String
    /// Display label, all-caps with em-dash framing, e.g.
    /// `— MAY 4 · EVENING —`.
    public let label: String
}

public enum StoryDayMarkers {

    /// Computes the markers for a story's items. Returns markers in
    /// the same order the items appear. The very first item ALWAYS
    /// gets a marker (the reader needs an opener even when the list
    /// only has one day).
    public static func derive(from items: [StoryItem]) -> [StoryDayMarker] {
        var markers: [StoryDayMarker] = []
        var lastKey: DayKey? = nil

        for item in items {
            let iso = item.occurredAt ?? item.createdAt
            guard let dt = parseIso(iso) else { continue }
            let key = DayKey(date: dt.date, bucket: bucketOf(hour: dt.hour))
            if key != lastKey {
                markers.append(StoryDayMarker(
                    precedingItemId: item.id,
                    label: label(for: key)
                ))
                lastKey = key
            }
        }
        return markers
    }

    // MARK: - Helpers

    private struct DayKey: Equatable {
        let date: DateComponents
        let bucket: Bucket
    }

    public enum Bucket: String, CaseIterable {
        case morning   = "MORNING"
        case afternoon = "AFTERNOON"
        case evening   = "EVENING"
        case night     = "NIGHT"
    }

    private static func bucketOf(hour: Int) -> Bucket {
        switch hour {
        case 5...10:  return .morning
        case 11...16: return .afternoon
        case 17...20: return .evening
        default:      return .night
        }
    }

    private static func label(for key: DayKey) -> String {
        let month  = monthAbbrev(key.date.month ?? 1).uppercased()
        let day    = key.date.day ?? 1
        return "— \(month) \(day) · \(key.bucket.rawValue) —"
    }

    private static func monthAbbrev(_ month: Int) -> String {
        let names = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                     "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
        guard month >= 1 && month <= 12 else { return "—" }
        return names[month - 1]
    }

    /// Parses the schema's ISO-8601 timestamps. Returns the local-
    /// time hour + date components. `IsoClock.nowIso()` emits
    /// fractional-seconds Z; accept both shapes.
    private struct Parsed {
        let date: DateComponents
        let hour: Int
    }

    private static func parseIso(_ iso: String) -> Parsed? {
        let formatters: [ISO8601DateFormatter] = [
            { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }(),
            { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f }(),
        ]
        for fmt in formatters {
            if let date = fmt.date(from: iso) {
                let cal = Calendar(identifier: .gregorian)
                let comps = cal.dateComponents([.year, .month, .day, .hour], from: date)
                return Parsed(date: comps, hour: comps.hour ?? 0)
            }
        }
        return nil
    }
}
