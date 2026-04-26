/*
 * NotepadScreenViewModel.swift
 *
 * Drives the redesigned Notepad tab — a Day / Recents segmented surface
 * built on the calendar-bloom-of-trees + garden-tiles-masonry design.
 *
 * Source: NotepadRepository.observeActive(userId:) — every active entry
 * for the signed-in user. We derive three things from that stream:
 *
 *   - Today's entry + its capture / todo counts (used by the Day view's
 *     today card AND by the Recents view's hero tile)
 *   - A month-density list (one DayCount per day in the current month)
 *     used by the calendar-bloom tree grid
 *   - A "recent days" window used by the Recents masonry — last 10 days
 *     including any gaps so empty days render as hollow tiles
 */

import Foundation
import ReleafData

/// Discrete density bucket the calendar-bloom UI maps to a tint.
public enum DayDensity: Equatable {
    case empty   // no entry on this day
    case light   // has an entry but ≤ 1 captures
    case mid     // 2–3 captures
    case deep    // 4+ captures
}

/// One day in the calendar / recents views.
public struct DayCount: Equatable, Identifiable {
    public let date: Date
    public let dateString: String   // ISO yyyy-MM-dd
    public let entry: NotepadEntry?
    public let captureCount: Int
    public let openTodoCount: Int

    public var id: String { dateString }

    public var density: DayDensity {
        if entry == nil { return .empty }
        if captureCount >= 4 { return .deep }
        if captureCount >= 2 { return .mid }
        return .light
    }
}

/// Per-mode breakdown for today's chip row.
public struct TodayBreakdown: Equatable {
    public var photoCount: Int = 0
    public var scanCount: Int = 0
    public var voiceCount: Int = 0
    public var contactCount: Int = 0
    public var locationCount: Int = 0
    public var openTodoCount: Int = 0

    public var captureCount: Int {
        photoCount + scanCount + voiceCount + contactCount + locationCount
    }

    public static let empty = TodayBreakdown()
}

public struct NotepadScreenState: Equatable {
    public var isLoading: Bool = true
    public var today: NotepadEntry?
    public var todayBreakdown: TodayBreakdown = .empty
    public var monthLabel: String = ""        // e.g. "APRIL"
    public var monthLeadingBlanks: Int = 0    // Sun-based leading blanks
    public var monthDays: [DayCount] = []     // calendar days 1..N
    /// Same shape as `monthDays` for the previous month — drives the
    /// faded left peek in the calendar carousel.
    public var prevMonthLeadingBlanks: Int = 0
    public var prevMonthDays: [DayCount] = []
    /// Same shape for the next month — drives the faded right peek.
    public var nextMonthLeadingBlanks: Int = 0
    public var nextMonthDays: [DayCount] = []
    /// Date-string → entry index. The swipeable carousel uses this to
    /// derive a `[DayCount]` list for any month the user navigates to,
    /// without the VM needing to pre-compute every single month.
    public var byDate: [String: NotepadEntry] = [:]
    public var recentDays: [DayCount] = []    // last 10 days, newest first

    public static let initial = NotepadScreenState()
}

/// Build a DayCount list for any [month] from a [byDate] index — used
/// by the swipeable calendar pager to render months the user navigates
/// to. Top-level so it can be invoked from views without a VM ref.
public func daysForMonth(month: DateComponents, in calendar: Calendar, byDate: [String: NotepadEntry]) -> (leading: Int, days: [DayCount]) {
    guard let firstOfMonth = calendar.date(from: month) else { return (0, []) }
    let dayCount = calendar.range(of: .day, in: .month, for: firstOfMonth)?.count ?? 30
    let days: [DayCount] = (0..<dayCount).compactMap { offset in
        guard let date = calendar.date(byAdding: .day, value: offset, to: firstOfMonth) else { return nil }
        let key = isoDateStringPublic(date)
        let entry = byDate[key]
        return DayCount(
            date: date,
            dateString: key,
            entry: entry,
            captureCount: entry.map { totalCapturesPublic(for: $0) } ?? 0,
            openTodoCount: entry.map { openTodoCountPublic(for: $0) } ?? 0
        )
    }
    let leading = (calendar.component(.weekday, from: firstOfMonth) - 1 + 7) % 7
    return (leading, days)
}

private func isoDateStringPublic(_ date: Date) -> String {
    let fmt = DateFormatter()
    fmt.calendar = Calendar(identifier: .iso8601)
    fmt.locale = Locale(identifier: "en_US_POSIX")
    fmt.dateFormat = "yyyy-MM-dd"
    return fmt.string(from: date)
}

private func totalCapturesPublic(for entry: NotepadEntry) -> Int {
    let attachments = entry.attachments.parseAttachments()
    let contacts    = entry.contacts.parseContacts()
    let locations   = entry.locations.parseLocations()
    return attachments.count + contacts.count + locations.count
}

private func openTodoCountPublic(for entry: NotepadEntry) -> Int {
    entry.todos.parseTodos().filter { !$0.done }.count
}

@MainActor
public final class NotepadScreenViewModel: ObservableObject {

    @Published public private(set) var state: NotepadScreenState = .initial

    private let userId: String
    private let repository: NotepadRepository
    private var tasks: [Task<Void, Never>] = []

    public init(
        userId: String,
        repository: NotepadRepository = NotepadRepository()
    ) {
        self.userId = userId
        self.repository = repository
    }

    public func start() {
        stop()
        tasks.append(Task { [weak self, repository, userId] in
            guard let self else { return }
            do {
                for try await entries in repository.observeActive(userId: userId) {
                    self.recompute(from: entries)
                }
            } catch {}
        })
    }

    public func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    deinit { tasks.forEach { $0.cancel() } }

    // MARK: - Actions

    public func createForToday(onCreated: @escaping (String) -> Void) {
        createForDate(Date(), onCreated: onCreated)
    }

    /// Open the entry filed under [date] if one exists, else create a
    /// fresh one filed under that date and call [onResult] with its id.
    /// Used by the quick-capture pills so a capture lands on the day
    /// the user has selected in the calendar, not always on today.
    public func openOrCreateForDate(_ date: Date, onResult: @escaping (String) -> Void) {
        let key = Self.isoDateString(date)
        if let existing = state.byDate[key] {
            onResult(existing.id)
        } else {
            createForDate(date, onCreated: onResult)
        }
    }

    private func createForDate(_ date: Date, onCreated: @escaping (String) -> Void) {
        Task { [repository, userId] in
            let entryDate = Self.isoDateString(date)
            if let entry = try? await repository.create(
                userId: userId,
                title: nil,
                notes: "",
                entryDate: entryDate
            ) {
                await MainActor.run { onCreated(entry.id) }
            }
        }
    }

    /// Import photo bytes as new Notepad entries — one entry per photo,
    /// each filed under `date` with the photo as a single Attachment.
    /// Each item's bytes are written into AttachmentStorage so the
    /// resulting `file://` URI survives across launches (the iOS
    /// PhotosPicker hands us `Data`, not a stable URL — see the
    /// Attachment.uri doc in Attachments.swift).
    public func importPhotos(
        date: Date,
        photos: [Data],
        onComplete: @escaping (Int) -> Void = { _ in }
    ) {
        guard !photos.isEmpty else { onComplete(0); return }
        Task { [repository, userId] in
            let entryDate = Self.isoDateString(date)
            let capturedAt = IsoClock.nowIso()
            var created = 0
            for data in photos {
                guard let url = AttachmentStorage.write(data, ext: "jpg") else { continue }
                let attachment = Attachment(
                    id: Uuidv7.generate(),
                    type: Attachment.typePhoto,
                    uri: url.absoluteString,
                    capturedAt: capturedAt
                )
                let json = [attachment].toJsonString()
                if (try? await repository.create(
                    userId: userId,
                    title: nil,
                    notes: "",
                    entryDate: entryDate,
                    attachments: json
                )) != nil {
                    created += 1
                }
            }
            await MainActor.run { onComplete(created) }
        }
    }

    // MARK: - Recompute

    private func recompute(from entries: [NotepadEntry]) {
        // Group entries by date (most-recently-updated wins per day).
        var byDate: [String: NotepadEntry] = [:]
        for entry in entries {
            if let existing = byDate[entry.entryDate] {
                if entry.updatedAt > existing.updatedAt {
                    byDate[entry.entryDate] = entry
                }
            } else {
                byDate[entry.entryDate] = entry
            }
        }

        let today = Date()
        let todayString = Self.isoDateString(today)
        let todayEntry = byDate[todayString]
        let todayBreakdown = todayEntry.map { Self.breakdown(for: $0) } ?? .empty

        // Month-day list — calendar days 1..N for the current month.
        var calendar = Calendar(identifier: .gregorian)
        calendar.firstWeekday = 1 // Sunday-based
        let (monthLeading, monthDays) = Self.daysForMonth(containing: today, in: calendar, byDate: byDate)

        // Prev / next month — same shape, used for the faded carousel peeks.
        let prevAnchor = calendar.date(byAdding: .month, value: -1, to: today) ?? today
        let nextAnchor = calendar.date(byAdding: .month, value:  1, to: today) ?? today
        let (prevLeading, prevDays) = Self.daysForMonth(containing: prevAnchor, in: calendar, byDate: byDate)
        let (nextLeading, nextDays) = Self.daysForMonth(containing: nextAnchor, in: calendar, byDate: byDate)

        // Month label, all-uppercase, e.g. "APRIL".
        let monthFormatter = DateFormatter()
        monthFormatter.locale = Locale(identifier: "en_US_POSIX")
        monthFormatter.dateFormat = "LLLL"
        let monthLabel = monthFormatter.string(from: today).uppercased()

        // Recent days — last 10 including today, newest first.
        let recent: [DayCount] = (0..<10).compactMap { offset in
            guard let date = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            return Self.dayCountFor(date: date, byDate: byDate)
        }

        state = NotepadScreenState(
            isLoading: false,
            today: todayEntry,
            todayBreakdown: todayBreakdown,
            monthLabel: monthLabel,
            monthLeadingBlanks: monthLeading,
            monthDays: monthDays,
            prevMonthLeadingBlanks: prevLeading,
            prevMonthDays: prevDays,
            nextMonthLeadingBlanks: nextLeading,
            nextMonthDays: nextDays,
            byDate: byDate,
            recentDays: recent
        )
    }

    /// Build a (leadingBlanks, days) pair for the month containing
    /// [anchor]. Used three times (prev / current / next) to feed the
    /// calendar carousel.
    private static func daysForMonth(
        containing anchor: Date,
        in calendar: Calendar,
        byDate: [String: NotepadEntry]
    ) -> (leading: Int, days: [DayCount]) {
        let interval = calendar.dateInterval(of: .month, for: anchor)
            ?? DateInterval(start: anchor, duration: 0)
        let firstOfMonth = interval.start
        let dayCount = calendar.range(of: .day, in: .month, for: anchor)?.count ?? 30
        let days: [DayCount] = (0..<dayCount).map { offset in
            let date = calendar.date(byAdding: .day, value: offset, to: firstOfMonth) ?? anchor
            return dayCountFor(date: date, byDate: byDate)
        }
        let leading = (calendar.component(.weekday, from: firstOfMonth) - 1 + 7) % 7
        return (leading, days)
    }

    // MARK: - Helpers

    private static func dayCountFor(date: Date, byDate: [String: NotepadEntry]) -> DayCount {
        let key = isoDateString(date)
        let entry = byDate[key]
        let captures = entry.map { totalCaptures(for: $0) } ?? 0
        let openTodos = entry.map { openTodoCount(for: $0) } ?? 0
        return DayCount(
            date: date,
            dateString: key,
            entry: entry,
            captureCount: captures,
            openTodoCount: openTodos
        )
    }

    private static func breakdown(for entry: NotepadEntry) -> TodayBreakdown {
        let attachments = entry.attachments.parseAttachments()
        let contacts    = entry.contacts.parseContacts()
        let locations   = entry.locations.parseLocations()
        let todos       = entry.todos.parseTodos()
        return TodayBreakdown(
            photoCount: attachments.filter { $0.type == Attachment.typePhoto }.count,
            scanCount:  attachments.filter { $0.type == Attachment.typeScan  }.count,
            voiceCount: attachments.filter { $0.type == Attachment.typeVoice }.count,
            contactCount:  contacts.count,
            locationCount: locations.count,
            openTodoCount: todos.filter { !$0.done }.count
        )
    }

    private static func totalCaptures(for entry: NotepadEntry) -> Int {
        breakdown(for: entry).captureCount
    }

    private static func openTodoCount(for entry: NotepadEntry) -> Int {
        entry.todos.parseTodos().filter { !$0.done }.count
    }

    private static func isoDateString(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .iso8601)
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: date)
    }
}
