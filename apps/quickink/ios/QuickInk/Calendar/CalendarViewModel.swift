/*
 * CalendarViewModel.swift
 *
 * State holder for QuickInk's full-screen calendar surface. Owns:
 *   - the visible month (driven by chevrons + pager swipes)
 *   - the selected date (driven by cell taps + the "Today" CTA)
 *   - the festival search query
 *   - per-date Panchanga rows, per-month Panchanga rows, an adjacent-
 *     month slice (drives moon-phase glyphs on spillover cells), and
 *     festival search results
 *   - QuickInk-specific: capturesByDate, the per-day list of scans /
 *     imports the user has on each Gregorian date — drives the dot
 *     indicator on cells with at least one capture, and the selected-
 *     day captures list under the calendar
 *
 * Port of Releaf Android's `CalendarViewModel.kt`. The five Kotlin
 * StateFlows + their flatMapLatest fanouts collapse to `@Published`
 * properties driven by Combine's `switchToLatest()`; the panchanga
 * repo's `ensureLoaded()` fires on init so the table is seeded before
 * the user lands.
 */

import Foundation
import Combine
import GRDB

@MainActor
public final class CalendarViewModel: ObservableObject {

    // MARK: - Published state

    /// Day shown in the detail card. Defaults to "today in IST"
    /// because the panchanga is keyed to IST — users in other
    /// timezones would otherwise see yesterday's tithi for their
    /// first interaction. Initialized in `init` so the `Self`
    /// reference doesn't trip the stored-property-default rule for
    /// classes.
    @Published public var selectedDate: Date

    /// Month rendered by the grid. Year + month tuple; defaults to
    /// selectedDate's month, moves independently when the user
    /// swipes the pager or taps the chevrons.
    @Published public var visibleMonth: YearMonth

    @Published public var searchQuery: String = ""

    @Published public private(set) var selectedDayPanchanga: [PanchangaEntity] = []
    @Published public private(set) var visibleMonthPanchanga: [PanchangaEntity] = []

    /// Panchanga rows for the visible month + its two neighbours.
    /// The grid spills 1–6 days from each adjacent month into the
    /// leading / trailing cells, and the moon-phase glyphs need to
    /// render on those spillover cells too. The festival LIST under
    /// the grid still uses `visibleMonthPanchanga`; this wider slice
    /// is just for per-cell decoration.
    @Published public private(set) var adjacentMonthsPanchanga: [PanchangaEntity] = []

    @Published public private(set) var searchResults: [PanchangaEntity] = []

    /// QuickInk-specific. Maps an ISO-8601 date string (yyyy-MM-dd in
    /// the user's local time zone) to the captures created on that
    /// day. Drives:
    ///   - the per-cell capture dot on the grid (any value with
    ///     non-empty array → coral dot)
    ///   - the SelectedDayCapturesList rendered below the panchanga
    ///     detail card
    @Published public private(set) var capturesByDate: [String: [CaptureSummary]] = [:]

    // MARK: - Internals

    private let userId: String
    private let repository: PanchangaRepository
    private let dbQueue: DatabaseQueue
    private var bag: Set<AnyCancellable> = []

    // MARK: - Init

    public init(
        userId: String,
        repository: PanchangaRepository = PanchangaRepository(),
        database: QuickInkDatabase = .shared
    ) {
        self.userId = userId
        self.repository = repository
        self.dbQueue = database.dbQueue
        let today = CalendarViewModel.istCalendar.startOfDay(for: Date())
        self.selectedDate = today
        self.visibleMonth = YearMonth.from(date: today, calendar: CalendarViewModel.istCalendar)

        // Bootstrap panchanga DB. Idempotent — short-circuits when the
        // table is already seeded at the current asset version.
        Task { [repository] in
            await repository.ensureLoaded()
        }

        bindPanchangaStreams()
        bindCapturesStream()
    }

    // MARK: - Actions

    public func selectDate(_ date: Date) {
        let day = Self.istCalendar.startOfDay(for: date)
        self.selectedDate = day
        let ym = YearMonth.from(date: day, calendar: Self.istCalendar)
        if ym != visibleMonth {
            visibleMonth = ym
        }
    }

    public func setVisibleMonth(_ ym: YearMonth) {
        visibleMonth = ym
    }

    public func setSearchQuery(_ q: String) {
        searchQuery = q
    }

    public func clearSearch() {
        searchQuery = ""
    }

    /// Snap selection + visible month to today (Asia/Kolkata).
    public func goToToday() {
        let today = Self.istCalendar.startOfDay(for: Date())
        selectedDate = today
        visibleMonth = YearMonth.from(date: today, calendar: Self.istCalendar)
    }

    // MARK: - Stream wiring

    private func bindPanchangaStreams() {
        // Selected day → list of panchanga rows.
        $selectedDate
            .map { [repository] date -> AnyPublisher<[PanchangaEntity], Never> in
                repository.observeForDate(Self.isoDate(date))
            }
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.selectedDayPanchanga = $0 }
            .store(in: &bag)

        // Visible month → list of panchanga rows in that month.
        $visibleMonth
            .map { [repository] ym -> AnyPublisher<[PanchangaEntity], Never> in
                repository.observeForMonth(prefix: ym.monthPrefix)
            }
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.visibleMonthPanchanga = $0 }
            .store(in: &bag)

        // Visible month → prev + curr + next month rows (drives moon-
        // phase glyphs on spillover cells).
        $visibleMonth
            .map { [repository] ym -> AnyPublisher<[PanchangaEntity], Never> in
                let prev = repository.observeForMonth(prefix: ym.previous.monthPrefix)
                let curr = repository.observeForMonth(prefix: ym.monthPrefix)
                let next = repository.observeForMonth(prefix: ym.next.monthPrefix)
                return Publishers.CombineLatest3(prev, curr, next)
                    .map { $0 + $1 + $2 }
                    .eraseToAnyPublisher()
            }
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.adjacentMonthsPanchanga = $0 }
            .store(in: &bag)

        // Search query → matching panchanga rows.
        $searchQuery
            .map { [repository] q -> AnyPublisher<[PanchangaEntity], Never> in
                repository.searchSpecialDay(q)
            }
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.searchResults = $0 }
            .store(in: &bag)
    }

    private func bindCapturesStream() {
        // Local-zone ISO date bucketing for all of the user's
        // captures. The query reads `id, created_at` only — we don't
        // need the full CaptureSummary surface to compute the
        // per-date map, and pulling all columns would tax the
        // observation more on each insert / delete. But we DO want
        // the full surface for the selected-day list below the
        // calendar, so the inner observation pulls the same wide
        // SELECT the Home rail uses (capped at 365 days — more than
        // a year of scans on a single user is unusual + bounded).
        let userId = self.userId
        ValueObservation
            .tracking { db in
                try CaptureSummary.fetchAll(db, sql: """
                    SELECT id, title, preview_uri, pdf_uri, page_count, created_at, source
                    FROM captures
                    WHERE user_id = ? AND deleted_at IS NULL
                    ORDER BY created_at DESC
                    """, arguments: [userId])
            }
            .publisher(in: dbQueue)
            .catch { _ in Just<[CaptureSummary]>([]) }
            .receive(on: DispatchQueue.main)
            .map { (captures: [CaptureSummary]) -> [String: [CaptureSummary]] in
                Self.bucketByLocalDate(captures)
            }
            .sink { [weak self] in self?.capturesByDate = $0 }
            .store(in: &bag)
    }

    // MARK: - Date helpers

    /// IST-anchored calendar — every grid / panchanga lookup goes
    /// through here so the calendar's "today" matches what the
    /// dataset is keyed to.
    private static let istCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Kolkata") ?? .current
        cal.firstWeekday = 2 // Monday-first (matches Android)
        return cal
    }()

    private static let isoDayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = istCalendar.timeZone
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    /// Local-zone ISO date string for the capture-bucketing index.
    /// Uses the device's local time zone (not IST) so a scan taken at
    /// 11pm local reads as "today" on the local-time grid.
    private static let localIsoDayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone.current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    public static func isoDate(_ date: Date) -> String {
        isoDayFormatter.string(from: date)
    }

    public static func localIsoDate(_ date: Date) -> String {
        localIsoDayFormatter.string(from: date)
    }

    /// Two ISO-8601 parsers — with and without fractional seconds —
    /// covering both shapes the captures table emits.
    private static let isoParser1: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoParser2: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static func bucketByLocalDate(_ captures: [CaptureSummary]) -> [String: [CaptureSummary]] {
        var out: [String: [CaptureSummary]] = [:]
        for capture in captures {
            let raw = capture.createdAt
            guard let date = isoParser1.date(from: raw) ?? isoParser2.date(from: raw) else { continue }
            let key = localIsoDayFormatter.string(from: date)
            out[key, default: []].append(capture)
        }
        return out
    }

    /// Convenience for the screen — returns the selected day's
    /// captures (or empty list when none exist for that date).
    public var selectedDayCaptures: [CaptureSummary] {
        capturesByDate[Self.localIsoDate(selectedDate)] ?? []
    }
}

// MARK: - YearMonth value type

public struct YearMonth: Equatable, Hashable {
    public let year: Int
    public let month: Int  // 1...12

    public init(year: Int, month: Int) {
        self.year = year
        self.month = month
    }

    public static func from(date: Date, calendar: Calendar) -> YearMonth {
        let comps = calendar.dateComponents([.year, .month], from: date)
        return YearMonth(year: comps.year ?? 1970, month: comps.month ?? 1)
    }

    /// "yyyy-MM-" — matches the LIKE pattern the panchanga DAO uses.
    public var monthPrefix: String {
        return String(format: "%04d-%02d-", year, month)
    }

    public var previous: YearMonth {
        if month == 1 { return YearMonth(year: year - 1, month: 12) }
        return YearMonth(year: year, month: month - 1)
    }

    public var next: YearMonth {
        if month == 12 { return YearMonth(year: year + 1, month: 1) }
        return YearMonth(year: year, month: month + 1)
    }

    /// First day of the month as a Date, in the given calendar.
    public func firstDay(in calendar: Calendar) -> Date {
        var comps = DateComponents()
        comps.year = year
        comps.month = month
        comps.day = 1
        return calendar.date(from: comps) ?? Date()
    }

    /// Number of days in this month, in the given calendar.
    public func numberOfDays(in calendar: Calendar) -> Int {
        let first = firstDay(in: calendar)
        return calendar.range(of: .day, in: .month, for: first)?.count ?? 30
    }

    /// Distance (in months) from `other` to `self`. Positive = later.
    public func monthsSince(_ other: YearMonth) -> Int {
        return (year - other.year) * 12 + (month - other.month)
    }

    public func adding(months: Int) -> YearMonth {
        let totalMonths = year * 12 + (month - 1) + months
        let newYear = totalMonths >= 0 ? totalMonths / 12 : ((totalMonths - 11) / 12)
        let newMonthIndex = ((totalMonths % 12) + 12) % 12
        return YearMonth(year: newYear, month: newMonthIndex + 1)
    }

    public func contains(_ date: Date, calendar: Calendar) -> Bool {
        let comps = calendar.dateComponents([.year, .month], from: date)
        return comps.year == year && comps.month == month
    }
}
