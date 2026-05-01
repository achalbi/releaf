/*
 * RecentsAdapter.swift
 *
 * Bridge between the existing Notepad data layer (NotepadEntry +
 * NotepadScreenState) and the new Recents screen's snapshot model
 * (RecentsDayStats). The new screen is intentionally a pure view of a
 * snapshot — this enum does the mapping so the screen never knows
 * about NotepadEntry or attachment JSON.
 *
 * The shape mismatch:
 *
 *   • A NotepadEntry can carry multiple attachments (photo / scan /
 *     voice) plus notes, todos, contacts, and locations.
 *   • A RecentsPage has an optional dominant `type` (.photo or .voice
 *     when an attachment backs it; nil for notes-only pages) and an
 *     optional media URI.
 *
 * One NotepadEntry → one RecentsPage:
 *
 *   • any scan attachment        → .photo + .scan
 *   • any photo attachment       → .photo + .camera
 *   • any voice attachment       → .voice + .native
 *   • notes-only / empty         → nil    + .native
 *
 * Per-page capture *counts* — distinct from the dominant `type`
 * above — are derived field-by-field from the entry's storage
 * columns: `attachments` (split into photos vs scans vs voice),
 * `todos`, `contacts`, `locations`. See `computeCaptureCounts`.
 */

import Foundation
import ReleafData

public enum RecentsAdapter {

    /// Build a snapshot from the live VM state for the given anchor day.
    /// Pass `today = Date()` from the host so date math stays consistent
    /// with the rest of the app's "today" semantics.
    public static func fromState(_ state: NotepadScreenState, today: Date) -> RecentsDayStats {
        let calendar  = isoCalendar
        let todayKey  = isoDateString(today, calendar: calendar)
        let monthComps = calendar.dateComponents([.year, .month], from: today)

        // --- TODAY ---
        let todayEntries = state.entriesByDate[todayKey] ?? []
        let todayDay: RecentsDay
        if todayEntries.isEmpty {
            // Brief: hero never dead-ends. An empty today still produces
            // a RecentsDay so the screen renders the new-entry slot.
            todayDay = RecentsDay(id: todayKey, date: today, theme: "", pages: [])
        } else {
            todayDay = buildRecentsDay(entries: todayEntries, date: today, dateString: todayKey)
        }

        // --- THIS WEEK (7 cells, oldest → newest, ending today) ---
        let weekPulse: [RecentsWeekDay] = (0..<7).compactMap { i in
            guard let d = calendar.date(byAdding: .day, value: -(6 - i), to: today) else { return nil }
            let key   = isoDateString(d, calendar: calendar)
            let count = state.entriesByDate[key]?.count ?? 0
            let isToday = calendar.isDate(d, inSameDayAs: today)
            return RecentsWeekDay(date: d, pageCount: count, isToday: isToday)
        }

        // --- EARLIER ---
        let earlier: [RecentsDay] = state.recentDays
            .filter { !calendar.isDate($0.date, inSameDayAs: today) }
            .map { dc in
                buildRecentsDay(entries: dc.entries, date: dc.date, dateString: dc.dateString)
            }

        // --- TOTALS ---
        let daysInMonth = calendar.range(of: .day, in: .month, for: today)?.count ?? 30
        let bloomedThisMonth = state.entriesByDate.reduce(into: 0) { acc, kv in
            guard !kv.value.isEmpty else { return }
            guard let d = parseDay(kv.key, calendar: calendar) else { return }
            let comps = calendar.dateComponents([.year, .month], from: d)
            if comps.year == monthComps.year && comps.month == monthComps.month {
                acc += 1
            }
        }
        let dayStreak = computeStreak(entriesByDate: state.entriesByDate, today: today, calendar: calendar)
        let topTheme  = computeTopTheme(entriesByDate: state.entriesByDate, monthComps: monthComps, calendar: calendar)
        let totals    = RecentsTotals(
            dayStreak: dayStreak,
            bloomedThisMonth: bloomedThisMonth,
            daysInMonth: daysInMonth,
            topTheme: topTheme
        )

        return RecentsDayStats(
            today: todayDay,
            weekPulse: weekPulse,
            earlier: earlier,
            totals: totals
        )
    }

    // MARK: - Internals

    private static let isoCalendar: Calendar = {
        var c = Calendar(identifier: .iso8601)
        c.timeZone = TimeZone.current
        return c
    }()

    private static let dayKeyFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .iso8601)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone.current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private static let createdAtParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let createdAtParserNoFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static func isoDateString(_ date: Date, calendar: Calendar) -> String {
        dayKeyFormatter.string(from: date)
    }

    private static func parseDay(_ key: String, calendar: Calendar) -> Date? {
        dayKeyFormatter.date(from: key)
    }

    private static func parseCreatedAt(_ iso: String) -> Date {
        createdAtParser.date(from: iso)
            ?? createdAtParserNoFraction.date(from: iso)
            ?? Date(timeIntervalSince1970: 0)
    }

    private static func buildRecentsDay(
        entries: [NotepadEntry],
        date: Date,
        dateString: String
    ) -> RecentsDay {
        // Theme = the most-recently-updated entry's title; fall back to
        // the first non-blank title in the day. Empty string lets the
        // hero render its "today" placeholder.
        let theme = entries
            .max(by: { $0.updatedAt < $1.updatedAt })?
            .title?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty
            ?? entries.compactMap {
                $0.title?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            }.first
            ?? ""
        // Sort pages by clock time so the carousel and timeline read
        // left-to-right by creation moment. Each page carries its
        // own attachment-level capture counts.
        let pages = entries
            .sorted(by: { $0.createdAt < $1.createdAt })
            .map { entry in
                buildRecentsPage(
                    entry: entry,
                    dayId: dateString,
                    captureCounts: computeCaptureCounts(entry: entry)
                )
            }
        // Day-level captureCounts is the element-wise sum of each
        // page's per-page counts — the hero pip row reads from the
        // *active page's* counts (per-page), but `RecentsDay` keeps
        // the day total available for any future surface that needs it.
        let dayCounts = pages.reduce(CaptureCounts()) { acc, p in
            acc + p.captureCounts
        }
        return RecentsDay(
            id: dateString,
            date: date,
            theme: theme,
            pages: pages,
            captureCounts: dayCounts
        )
    }

    /// Per-entry capture counts — one tally per capture surface plus
    /// a 0/1 `notes` tick for whether the page's free-text body is
    /// non-blank. The hero pip row renders one pip per non-zero
    /// field; the EarlierGrid card footer renders the sum.
    private static func computeCaptureCounts(entry: NotepadEntry) -> CaptureCounts {
        let attachments = entry.attachments.parseAttachments()
        let hasNotes = !entry.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return CaptureCounts(
            photos:    attachments.filter { $0.type == "photo" }.count,
            scans:     attachments.filter { $0.type == "scan"  }.count,
            voice:     attachments.filter { $0.type == "voice" }.count,
            todos:     entry.todos.parseTodos().count,
            contacts:  entry.contacts.parseContacts().count,
            locations: entry.locations.parseLocations().count,
            notes:     hasNotes ? 1 : 0
        )
    }

    private static func buildRecentsPage(
        entry: NotepadEntry,
        dayId: String,
        captureCounts: CaptureCounts
    ) -> RecentsPage {
        let attachments = entry.attachments.parseAttachments()
        let firstScan  = attachments.first(where: { $0.type == "scan" })
        let firstPhoto = attachments.first(where: { $0.type == "photo" })
        let firstVoice = attachments.first(where: { $0.type == "voice" })

        // Notes-only and empty entries get `type = nil` — they have
        // no attachment-backed dominant flavour. Their content mix
        // (todos, contacts, locations, body text) lives in
        // `captureCounts` and the page's body fields.
        let type: CaptureType?
        let source: PageSource
        switch true {
        case firstScan  != nil: type = .photo; source = .scan
        case firstPhoto != nil: type = .photo; source = .camera
        case firstVoice != nil: type = .voice; source = .native
        default:                type = nil;    source = .native
        }

        let mediaURL: URL? = {
            let raw = firstScan?.uri ?? firstPhoto?.uri
            return raw.flatMap { URL(string: $0) }
        }()
        let durationSec: Int? = firstVoice?.durationMs.map { Int($0 / 1000) }
        let tags: [Tag] = [tagFor(entry.category)].compactMap { $0 }

        return RecentsPage(
            id: entry.id,
            dayId: dayId,
            type: type,
            source: source,
            createdAt: parseCreatedAt(entry.createdAt),
            updatedAt: parseCreatedAt(entry.updatedAt),
            title: entry.title?.trimmingCharacters(in: .whitespaces).nonEmpty ?? "Untitled",
            description: entry.description ?? "",
            tags: tags,
            mediaURL: mediaURL,
            durationSec: durationSec,
            captureCounts: captureCounts
        )
    }

    /// Map an existing category string to one of the four Tag values
    /// the Recents screen knows about. Categories with no Tag analogue
    /// (Health/Travel/Ideas + arbitrary customs) drop quietly.
    private static func tagFor(_ category: String?) -> Tag? {
        guard let raw = category?.trimmingCharacters(in: .whitespaces).lowercased(), !raw.isEmpty else {
            return nil
        }
        switch raw {
        case "home":     return .home
        case "work":     return .work
        case "personal": return .personal
        case "recipes":  return .recipes
        default:         return nil
        }
    }

    /// Walk back from `today` until we hit a date with no entries.
    private static func computeStreak(
        entriesByDate: [String: [NotepadEntry]],
        today: Date,
        calendar: Calendar
    ) -> Int {
        var streak = 0
        var d = today
        while true {
            let key = isoDateString(d, calendar: calendar)
            let list = entriesByDate[key] ?? []
            if list.isEmpty { break }
            streak += 1
            guard let prev = calendar.date(byAdding: .day, value: -1, to: d) else { break }
            d = prev
        }
        return streak
    }

    /// Most-frequent Tag among entries created in the given calendar month.
    private static func computeTopTheme(
        entriesByDate: [String: [NotepadEntry]],
        monthComps: DateComponents,
        calendar: Calendar
    ) -> Tag? {
        var counts: [Tag: Int] = [:]
        for (key, list) in entriesByDate {
            guard let d = parseDay(key, calendar: calendar) else { continue }
            let dc = calendar.dateComponents([.year, .month], from: d)
            guard dc.year == monthComps.year && dc.month == monthComps.month else { continue }
            for entry in list {
                if let tag = tagFor(entry.category) {
                    counts[tag, default: 0] += 1
                }
            }
        }
        return counts.max(by: { $0.value < $1.value })?.key
    }
}

// MARK: - Helpers

private extension String {
    /// Returns nil for empty / whitespace-only strings.
    var nonEmpty: String? { isEmpty ? nil : self }
}
