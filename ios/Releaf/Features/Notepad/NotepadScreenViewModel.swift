/*
 * NotepadScreenViewModel.swift
 *
 * Drives the redesigned Notepad tab — a Day / Recents segmented surface
 * built on the calendar-bloom-of-trees + garden-tiles-masonry design.
 *
 * Source: NotepadRepository.observeActive(userId:) — every active entry
 * for the signed-in user. We derive these from that stream:
 *
 *   - Today's entry list + its capture / todo counts (used by the Day
 *     view's today card AND by the Recents view's hero tile)
 *   - A month-density list (one DayCount per day in the current month)
 *     used by the calendar-bloom tree grid
 *   - A "recent days" window used by the Recents masonry — last 10 days
 *     including any gaps so empty days render as hollow tiles
 *   - Custom category list (everything not in NotepadCategory.predefined
 *     that's still in active use) — surfaces in the top filter row
 *     alongside the predefined chips
 *
 * **Multi-entry per day.** Days can hold more than one entry — the
 * `entriesByDate` index is a list per date (newest createdAt first), and
 * `DayCount.entries` is the full ordered list. `DayCount.entry` returns
 * the most-recently-updated one for callers that just want a single
 * representative.
 *
 * **Category filter.** `selectedCategory` (nil = no filter) gates the
 * entries that flow into `byDate`, `entriesByDate`, `monthDays`, and
 * `recentDays`. The calendar bloom and the page carousel both derive
 * from those, so the filter is reflected end-to-end automatically.
 * `customCategories` is computed from the *unfiltered* set so the
 * filter chip row keeps showing every available category even after the
 * user has narrowed the view.
 */

import Foundation
import ReleafData

/// Discrete density bucket the calendar-bloom UI maps to a tint.
public enum DayDensity: Equatable {
    case empty   // no entry on this day
    case light   // has an entry but ≤ 1 captures total
    case mid     // 2–3 captures total
    case deep    // 4+ captures total
}

/// One day in the calendar / recents views. Multi-entry-aware.
/// `entries` is the full ordered list (sorted by createdAt within the
/// day so the carousel reads chronologically); `entry` returns the
/// most-recently-updated one for representative use.
public struct DayCount: Equatable, Identifiable {
    public let date: Date
    public let dateString: String   // ISO yyyy-MM-dd
    public let entries: [NotepadEntry]
    public let captureCount: Int
    public let openTodoCount: Int

    public var id: String { dateString }

    public var entry: NotepadEntry? {
        entries.max(by: { $0.updatedAt < $1.updatedAt })
    }

    public var hasEntries: Bool { !entries.isEmpty }

    public var density: DayDensity {
        if !hasEntries { return .empty }
        if captureCount >= 4 { return .deep }
        if captureCount >= 2 { return .mid }
        return .light
    }
}

/// Per-mode breakdown for today's chip row. Sums across every entry
/// filed under today, since today can now hold multiple pages.
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
    /// Most-recently-updated today-entry, for the Recents hero tile.
    public var today: NotepadEntry?
    /// Every entry filed under today (could be 0..N), oldest createdAt first.
    public var todayEntries: [NotepadEntry] = []
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
    /// Date-string → most-recently-updated entry for that date.
    /// Convenience for callers that just want "the" entry for a day;
    /// use `entriesByDate` when you need every entry.
    public var byDate: [String: NotepadEntry] = [:]
    /// Date-string → ordered list of every entry on that day (oldest
    /// createdAt first). Drives the day-page carousel.
    public var entriesByDate: [String: [NotepadEntry]] = [:]
    public var recentDays: [DayCount] = []    // last 10 days, newest first
    /// Active category filter; nil = no filter (all entries flow).
    public var selectedCategory: String? = nil
    /// Custom (non-predefined) category strings discovered across every
    /// active entry — drives the filter chip row alongside the
    /// predefined list. Computed before the filter is applied so the
    /// user can switch back to a custom they previously narrowed
    /// away from.
    public var customCategories: [String] = []

    public static let initial = NotepadScreenState()
}

/// Build a (leadingBlanks, [DayCount]) pair for any month from the
/// VM's index. Multi-entry-aware via `entriesByDate`. Top-level so
/// it can be invoked from views without a VM ref.
public func daysForMonth(
    month: DateComponents,
    in calendar: Calendar,
    byDate: [String: NotepadEntry],
    entriesByDate: [String: [NotepadEntry]] = [:]
) -> (leading: Int, days: [DayCount]) {
    guard let firstOfMonth = calendar.date(from: month) else { return (0, []) }
    let dayCount = calendar.range(of: .day, in: .month, for: firstOfMonth)?.count ?? 30
    let days: [DayCount] = (0..<dayCount).compactMap { offset in
        guard let date = calendar.date(byAdding: .day, value: offset, to: firstOfMonth) else { return nil }
        let key = isoDateStringPublic(date)
        let entries = entriesByDate[key]
            ?? byDate[key].map { [$0] }
            ?? []
        let captures = entries.reduce(0) { $0 + totalCapturesPublic(for: $1) }
        let openTodos = entries.reduce(0) { $0 + openTodoCountPublic(for: $1) }
        return DayCount(
            date: date,
            dateString: key,
            entries: entries,
            captureCount: captures,
            openTodoCount: openTodos
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

    /// Latest snapshot from the repository — kept around so the filter
    /// can be re-applied without waiting for the next observation tick.
    private var lastEntries: [NotepadEntry] = []

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

    // MARK: - Filter

    /// Set or clear the active category filter. Predefined names are
    /// canonicalised to their display casing via
    /// `NotepadCategory.displayName` so "home" and "Home" don't form
    /// separate filter targets. Pass nil (or blank) to clear.
    public func setCategoryFilter(_ name: String?) {
        let canonical = NotepadCategory.displayName(name)
        if state.selectedCategory == canonical { return }
        state.selectedCategory = canonical
        // Re-derive against the latest snapshot so the calendar +
        // carousel narrow without waiting for the next repository tick.
        recompute(from: lastEntries)
    }

    // MARK: - Actions

    public func createForToday(onCreated: @escaping (String) -> Void) {
        createForDate(Date(), onCreated: onCreated)
    }

    /// Open the most-recently-updated entry filed under [date] if one
    /// exists, else create a fresh one filed under that date and call
    /// [onResult] with its id. Used by the quick-capture pills as a
    /// fallback when no page is currently selected on the day's
    /// carousel.
    public func openOrCreateForDate(_ date: Date, onResult: @escaping (String) -> Void) {
        let key = Self.isoDateString(date)
        if let existing = state.byDate[key] {
            onResult(existing.id)
        } else {
            createForDate(date, onCreated: onResult)
        }
    }

    /// Open an existing entry by id. Trivial — exists so the screen
    /// can route quick-capture taps through the VM (keeps onOpenEntry
    /// routing in one place) when the user has selected a specific
    /// page on the day-pager.
    public func openEntry(id: String, onResult: @escaping (String) -> Void) {
        onResult(id)
    }

    /// Add a brand-new page to [date] alongside any existing pages.
    /// Inherits the active filter category so the new page is visible
    /// in the current view.
    public func createNewPageOn(_ date: Date, onCreated: @escaping (String) -> Void) {
        createForDate(date, onCreated: onCreated)
    }

    private func createForDate(_ date: Date, onCreated: @escaping (String) -> Void) {
        Task { [repository, userId, category = state.selectedCategory] in
            let entryDate = Self.isoDateString(date)
            if let entry = try? await repository.create(
                userId: userId,
                title: nil,
                notes: "",
                entryDate: entryDate,
                category: category
            ) {
                await MainActor.run { onCreated(entry.id) }
            }
        }
    }

    /// Import photo bytes as new Notepad entries — one entry per photo,
    /// each filed under `date` with the photo as a single Attachment.
    /// Each item's bytes are written into AttachmentStorage so the
    /// resulting `file://` URI survives across launches.
    /// Inherits the active filter category so imported entries stay
    /// visible under the current narrowing.
    public func importPhotos(
        date: Date,
        photos: [Data],
        onComplete: @escaping (Int) -> Void = { _ in }
    ) {
        guard !photos.isEmpty else { onComplete(0); return }
        Task { [repository, userId, category = state.selectedCategory] in
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
                    category: category,
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
        lastEntries = entries

        // Customs come from the UNFILTERED set so the filter chip row
        // still surfaces categories the user has narrowed away from —
        // otherwise selecting "Work" would empty the chip row of any
        // other custom name and the user couldn't pivot back.
        let customs = NotepadCategory.deriveCustomCategories(from: entries)

        // Apply the filter. Nil = pass everything; non-nil compares
        // case-insensitive against `entry.category`.
        let filter = state.selectedCategory
        let filtered: [NotepadEntry] = {
            guard let filter, !filter.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return entries
            }
            let trimmed = filter.trimmingCharacters(in: .whitespacesAndNewlines)
            return entries.filter { entry in
                guard let raw = entry.category?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
                    return false
                }
                return raw.caseInsensitiveCompare(trimmed) == .orderedSame
            }
        }()

        // Multi-entry-per-day. Sort within a day by createdAt so the
        // carousel reads chronologically (oldest leftmost / first page).
        var entriesByDate: [String: [NotepadEntry]] = [:]
        for entry in filtered {
            entriesByDate[entry.entryDate, default: []].append(entry)
        }
        for (key, list) in entriesByDate {
            entriesByDate[key] = list.sorted(by: { $0.createdAt < $1.createdAt })
        }

        // Representative entry per day — most recently updated.
        let byDate: [String: NotepadEntry] = entriesByDate.mapValues { list in
            list.max(by: { $0.updatedAt < $1.updatedAt }) ?? list[0]
        }

        let today = Date()
        let todayString = Self.isoDateString(today)
        let todayEntries = entriesByDate[todayString] ?? []
        let todayLatest = byDate[todayString]
        let todayBreakdown = Self.breakdown(forList: todayEntries)

        // Month-day list — calendar days 1..N for the current month.
        var calendar = Calendar(identifier: .gregorian)
        calendar.firstWeekday = 1 // Sunday-based
        let (monthLeading, monthDays) = Self.daysForMonth(
            containing: today, in: calendar, entriesByDate: entriesByDate
        )

        // Prev / next month — same shape, used for the faded carousel peeks.
        let prevAnchor = calendar.date(byAdding: .month, value: -1, to: today) ?? today
        let nextAnchor = calendar.date(byAdding: .month, value:  1, to: today) ?? today
        let (prevLeading, prevDays) = Self.daysForMonth(
            containing: prevAnchor, in: calendar, entriesByDate: entriesByDate
        )
        let (nextLeading, nextDays) = Self.daysForMonth(
            containing: nextAnchor, in: calendar, entriesByDate: entriesByDate
        )

        // Month label, all-uppercase, e.g. "APRIL".
        let monthFormatter = DateFormatter()
        monthFormatter.locale = Locale(identifier: "en_US_POSIX")
        monthFormatter.dateFormat = "LLLL"
        let monthLabel = monthFormatter.string(from: today).uppercased()

        // Recent days — last 10 including today, newest first.
        let recent: [DayCount] = (0..<10).compactMap { offset in
            guard let date = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            return Self.dayCountFor(date: date, entriesByDate: entriesByDate)
        }

        state = NotepadScreenState(
            isLoading: false,
            today: todayLatest,
            todayEntries: todayEntries,
            todayBreakdown: todayBreakdown,
            monthLabel: monthLabel,
            monthLeadingBlanks: monthLeading,
            monthDays: monthDays,
            prevMonthLeadingBlanks: prevLeading,
            prevMonthDays: prevDays,
            nextMonthLeadingBlanks: nextLeading,
            nextMonthDays: nextDays,
            byDate: byDate,
            entriesByDate: entriesByDate,
            recentDays: recent,
            selectedCategory: filter,
            customCategories: customs
        )
    }

    /// Build a (leadingBlanks, days) pair for the month containing
    /// [anchor] from the multi-entry index.
    private static func daysForMonth(
        containing anchor: Date,
        in calendar: Calendar,
        entriesByDate: [String: [NotepadEntry]]
    ) -> (leading: Int, days: [DayCount]) {
        let interval = calendar.dateInterval(of: .month, for: anchor)
            ?? DateInterval(start: anchor, duration: 0)
        let firstOfMonth = interval.start
        let dayCount = calendar.range(of: .day, in: .month, for: anchor)?.count ?? 30
        let days: [DayCount] = (0..<dayCount).map { offset in
            let date = calendar.date(byAdding: .day, value: offset, to: firstOfMonth) ?? anchor
            return dayCountFor(date: date, entriesByDate: entriesByDate)
        }
        let leading = (calendar.component(.weekday, from: firstOfMonth) - 1 + 7) % 7
        return (leading, days)
    }

    // MARK: - Helpers

    private static func dayCountFor(
        date: Date,
        entriesByDate: [String: [NotepadEntry]]
    ) -> DayCount {
        let key = isoDateString(date)
        let entries = entriesByDate[key] ?? []
        let captures = entries.reduce(0) { $0 + totalCaptures(for: $1) }
        let openTodos = entries.reduce(0) { $0 + openTodoCount(for: $1) }
        return DayCount(
            date: date,
            dateString: key,
            entries: entries,
            captureCount: captures,
            openTodoCount: openTodos
        )
    }

    /// Sum a TodayBreakdown across [entries]. Used to derive the today
    /// card's chip row when today holds multiple pages.
    private static func breakdown(forList entries: [NotepadEntry]) -> TodayBreakdown {
        guard !entries.isEmpty else { return .empty }
        var photo = 0; var scan = 0; var voice = 0
        var contact = 0; var location = 0; var openTodo = 0
        for entry in entries {
            let attachments = entry.attachments.parseAttachments()
            let contacts    = entry.contacts.parseContacts()
            let locations   = entry.locations.parseLocations()
            let todos       = entry.todos.parseTodos()
            photo    += attachments.filter { $0.type == Attachment.typePhoto }.count
            scan     += attachments.filter { $0.type == Attachment.typeScan  }.count
            voice    += attachments.filter { $0.type == Attachment.typeVoice }.count
            contact  += contacts.count
            location += locations.count
            openTodo += todos.filter { !$0.done }.count
        }
        return TodayBreakdown(
            photoCount:    photo,
            scanCount:     scan,
            voiceCount:    voice,
            contactCount:  contact,
            locationCount: location,
            openTodoCount: openTodo
        )
    }

    static func totalCaptures(for entry: NotepadEntry) -> Int {
        let attachments = entry.attachments.parseAttachments()
        let contacts    = entry.contacts.parseContacts()
        let locations   = entry.locations.parseLocations()
        return attachments.count + contacts.count + locations.count
    }

    static func openTodoCount(for entry: NotepadEntry) -> Int {
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
