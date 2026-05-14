/*
 * IndianHolidays.swift
 *
 * Hard-coded list of Indian government / national holidays painted on
 * the Calendar grid. Covers 2026 and 2027 from the published GoI list
 * (some lunar holidays shift each year; refresh annually).
 *
 * Port of the inline `IndianHolidays` object in Releaf Android's
 * `CalendarPanel.kt`. Same date set, surfaced as a Swift struct +
 * static array so both per-month and per-date lookups stay constant-
 * time / O(n) over a tiny list.
 *
 * Extend `entries` when 2028 arrives.
 */

import Foundation

public struct IndianHoliday: Equatable, Hashable, Identifiable {
    public let date: Date
    public let name: String

    public var id: Date { date }

    public init(date: Date, name: String) {
        self.date = date
        self.name = name
    }
}

public enum IndianHolidays {

    public static let entries: [IndianHoliday] = [
        // 2026
        h(2026,  1,  1, "New Year's Day"),
        h(2026,  1, 14, "Makar Sankranti / Pongal"),
        h(2026,  1, 26, "Republic Day"),
        h(2026,  3,  3, "Holi"),
        h(2026,  3, 21, "Ram Navami"),
        h(2026,  3, 31, "Eid-ul-Fitr"),
        h(2026,  4,  3, "Good Friday"),
        h(2026,  4, 14, "Dr Ambedkar Jayanti"),
        h(2026,  5,  1, "Labour Day"),
        h(2026,  6,  7, "Eid al-Adha (Bakrid)"),
        h(2026,  7,  5, "Muharram"),
        h(2026,  8, 15, "Independence Day"),
        h(2026,  8, 27, "Janmashtami"),
        h(2026,  9, 14, "Eid-e-Milad"),
        h(2026, 10,  2, "Gandhi Jayanti"),
        h(2026, 10, 20, "Dussehra"),
        h(2026, 11,  8, "Diwali"),
        h(2026, 11, 24, "Guru Nanak Jayanti"),
        h(2026, 12, 25, "Christmas Day"),
        // 2027 (selected highlights — extend as needed)
        h(2027,  1,  1, "New Year's Day"),
        h(2027,  1, 26, "Republic Day"),
        h(2027,  3, 22, "Holi"),
        h(2027,  8, 15, "Independence Day"),
        h(2027, 10,  2, "Gandhi Jayanti"),
        h(2027, 10, 28, "Diwali"),
        h(2027, 12, 25, "Christmas Day"),
    ]

    /// Holidays whose date falls within `month` (year + month, day
    /// ignored).
    public static func forMonth(year: Int, month: Int) -> [IndianHoliday] {
        let cal = Self.gregorianCalendar
        return entries.filter { h in
            let comps = cal.dateComponents([.year, .month], from: h.date)
            return comps.year == year && comps.month == month
        }
    }

    /// Holiday matching the exact day (year + month + day) of `date`,
    /// or `nil` if none.
    public static func forDate(_ date: Date) -> IndianHoliday? {
        let cal = Self.gregorianCalendar
        let target = cal.dateComponents([.year, .month, .day], from: date)
        return entries.first { h in
            let comps = cal.dateComponents([.year, .month, .day], from: h.date)
            return comps.year == target.year
                && comps.month == target.month
                && comps.day == target.day
        }
    }

    /// Set of holiday dates in `year, month` and the months adjacent
    /// to it — drives the per-cell ring + dot on the calendar grid
    /// (the grid spills cells from prev/next months and those
    /// spillover cells need to mark holidays too).
    public static func dateSet(aroundYear year: Int, month: Int) -> Set<Date> {
        let cal = Self.gregorianCalendar
        let center = cal.date(from: DateComponents(year: year, month: month, day: 1)) ?? Date()
        let prev = cal.date(byAdding: .month, value: -1, to: center) ?? center
        let next = cal.date(byAdding: .month, value:  1, to: center) ?? center
        let prevComps = cal.dateComponents([.year, .month], from: prev)
        let nextComps = cal.dateComponents([.year, .month], from: next)
        let all = forMonth(year: prevComps.year ?? year, month: prevComps.month ?? month)
            + forMonth(year: year, month: month)
            + forMonth(year: nextComps.year ?? year, month: nextComps.month ?? month)
        return Set(all.map { cal.startOfDay(for: $0.date) })
    }

    // MARK: - Helpers

    private static let gregorianCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Kolkata") ?? .current
        return cal
    }()

    private static func h(_ y: Int, _ m: Int, _ d: Int, _ name: String) -> IndianHoliday {
        let comps = DateComponents(
            calendar: gregorianCalendar,
            timeZone: gregorianCalendar.timeZone,
            year: y, month: m, day: d
        )
        let date = gregorianCalendar.date(from: comps) ?? Date()
        return IndianHoliday(date: date, name: name)
    }
}
