/*
 * NotepadView.swift
 *
 * Top-level Notepad tab — redesigned around a Day / Recents segmented
 * control:
 *
 *   • Day      → calendar bloom of trees over the current month, a
 *                today card with eyebrow + title + body + capture
 *                chips, and a quick-capture pill row (note / photo /
 *                scan / voice) above the bottom nav.
 *   • Recents  → today's plot rendered as a full-width hero tile in
 *                deep canopy + coral border, then a 2-column ragged
 *                masonry of older days.
 *
 * Backed by NotepadScreenViewModel which observes
 * NotepadRepository.observeActive(userId:). Tapping today opens or
 * creates today's entry; tapping a past day opens that entry.
 */

import SwiftUI
import PhotosUI
import ReleafDesignSystem
import ReleafData

public struct NotepadView: View {
    @EnvironmentObject private var authStore: AuthStore

    private let onOpenEntry: (String) -> Void

    public init(onOpenEntry: @escaping (String) -> Void = { _ in }) {
        self.onOpenEntry = onOpenEntry
    }

    public var body: some View {
        Group {
            if let session = authStore.session {
                NotepadDayRecentsContent(
                    userId: session.userId,
                    onOpenEntry: onOpenEntry
                )
            } else {
                Text("Sign in to see your notepad.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(DotGridBackground().ignoresSafeArea())
        // `.navigationBar` placement is iOS-only — on macOS the whole
        // toolbar is hidden via the no-arg overload.
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #else
        .toolbar(.hidden)
        #endif
    }
}

// MARK: - Inner content (owns the VM)

private enum NotepadTab: String { case day, recents }

private struct NotepadDayRecentsContent: View {
    @StateObject private var vm: NotepadScreenViewModel
    @State private var tab: NotepadTab = .day

    /// The day whose card renders below the calendar. Defaults to today
    /// and updates when the user taps any day in the calendar grid.
    @State private var selectedDate: Date = Calendar.current.startOfDay(for: Date())

    /// Pager-page offset relative to today's month (0 = today's month,
    /// -1 = previous month, +1 = next, etc.).
    @State private var monthPageOffset: Int = 0

    /// PhotosPicker selection — the "import" quick-capture pill is a
    /// PhotosPicker; when the user finishes picking, this state changes
    /// and `.onChange` loads each item's bytes and asks the VM to
    /// create one new entry per photo.
    @State private var importItems: [PhotosPickerItem] = []

    private let onOpenEntry: (String) -> Void

    init(userId: String, onOpenEntry: @escaping (String) -> Void) {
        _vm = StateObject(wrappedValue: NotepadScreenViewModel(userId: userId))
        self.onOpenEntry = onOpenEntry
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                header

                // Centered, max-280pt switch so the active pill slides
                // in a stable lane between Day and Recents instead of
                // expanding to fill every screen edge.
                DayRecentsSwitch(selected: $tab)
                    .frame(maxWidth: 280)
                    .frame(maxWidth: .infinity, alignment: .center)

                if vm.state.isLoading {
                    ProgressView()
                        .tint(AppColors.coral)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s8)
                } else {
                    switch tab {
                    case .day:     dayView
                    case .recents: recentsView
                    }
                }

                Spacer(minLength: AppSpacing.s10)
            }
            .padding(AppSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear { vm.start() }
        .onDisappear { vm.stop() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("NOTEPAD")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)

            Text(tab == .day ? "A grove of days" : "Recent garden")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)
        }
    }

    @ViewBuilder
    private var dayView: some View {
        let today = Date()
        let selectedDay = resolveSelectedDay(today: today)
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            // Calendar + legend grouped tightly — the legend reads as
            // a footnote of the calendar so it sits close (s1 gap) to
            // the grid above, while the SelectedDayCard / quick-capture
            // rows keep their normal s4 breathing room.
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                // Swipeable calendar carousel — TabView with .page style
                // gives native swipe + snap. The current page's month
                // drives the pager strip + the centered calendar.
                SwipeableCalendarCarousel(
                    anchorMonth: today,
                    pageOffset: $monthPageOffset,
                    byDate: vm.state.byDate,
                    today: today,
                    selectedDate: selectedDate,
                    onDayTap: { day in selectedDate = day.date }
                )

                // Tapping the "today" badge in the legend snaps the
                // carousel back to today's month and reselects today —
                // useful escape hatch after the user has swiped or
                // tapped a different day.
                NotepadCalendarLegend(onTodayTap: {
                    withAnimation {
                        monthPageOffset = 0
                        selectedDate = Calendar.current.startOfDay(for: Date())
                    }
                })
            }

            // Selected-day card — populates from whichever day the
            // user last tapped. Defaults to today on first open.
            SelectedDayCard(
                day: selectedDay,
                today: today,
                breakdown: Calendar.current.isDate(selectedDate, inSameDayAs: today)
                    ? vm.state.todayBreakdown
                    : nil,
                onTap: { tapSelected(selectedDay, today: today) }
            )

            // Quick capture pills — including a PhotosPicker-backed
            // "import" pill that creates one new entry per picked photo.
            QuickCapturePills(onCapture: { _ in
                vm.openOrCreateForDate(selectedDate, onResult: onOpenEntry)
            })
        }
    }

    /// Resolve a [DayCount] for the currently-selected date by reading
    /// the VM's byDate index. Falls back to an empty placeholder so the
    /// UI keeps rendering before the first observe-fire.
    private func resolveSelectedDay(today: Date) -> DayCount {
        let key = Self.isoDateString(selectedDate)
        let entry = vm.state.byDate[key]
        return DayCount(
            date: selectedDate,
            dateString: key,
            entry: entry,
            captureCount: entry.map { entryCaptureCount($0) } ?? 0,
            openTodoCount: entry.map { entryOpenTodoCount($0) } ?? 0
        )
    }

    private func entryCaptureCount(_ entry: NotepadEntry) -> Int {
        let attachments = entry.attachments.parseAttachments()
        let contacts    = entry.contacts.parseContacts()
        let locations   = entry.locations.parseLocations()
        return attachments.count + contacts.count + locations.count
    }

    private func entryOpenTodoCount(_ entry: NotepadEntry) -> Int {
        entry.todos.parseTodos().filter { !$0.done }.count
    }

    private static func isoDateString(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .iso8601)
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: date)
    }

    /// Selected-day card tap: open the entry's editor if one exists;
    /// if today + missing, create + open. Past days without entries
    /// are no-op (we don't create back-dated entries from the calendar).
    private func tapSelected(_ day: DayCount, today: Date) {
        if let entry = day.entry {
            onOpenEntry(entry.id)
        } else if Calendar.current.isDate(day.date, inSameDayAs: today) {
            vm.createForToday(onCreated: onOpenEntry)
        }
    }

    @ViewBuilder
    private var recentsView: some View {
        let calendar = Calendar.current
        let today = vm.state.recentDays.first(where: { calendar.isDateInToday($0.date) })
            ?? DayCount(
                date: Date(),
                dateString: ISO8601DateFormatter.localDate(Date()),
                entry: vm.state.today,
                captureCount: vm.state.todayBreakdown.captureCount,
                openTodoCount: vm.state.todayBreakdown.openTodoCount
            )
        let earlier = vm.state.recentDays.filter { !calendar.isDateInToday($0.date) }

        NotepadGardenTiles(
            today: today,
            earlier: earlier,
            onTodayTap: tapToday,
            onDayTap: { day in tapDay(day) }
        )
    }

    // MARK: - Actions

    private func tapToday() {
        if let entry = vm.state.today {
            onOpenEntry(entry.id)
        } else {
            vm.createForToday(onCreated: onOpenEntry)
        }
    }

    private func tapDay(_ day: DayCount) {
        if let entry = day.entry {
            onOpenEntry(entry.id)
        }
    }

    private func handleImportSelection(_ p: [PhotosPickerItem]) {
        guard !p.isEmpty else { return }
        importItems = []
        _ = p // TODO load
    }

    private func createNewEntry() {
        vm.createForToday(onCreated: onOpenEntry)
    }
}

// MARK: - Segmented switch

private struct DayRecentsSwitch: View {
    @Binding var selected: NotepadTab

    var body: some View {
        HStack(spacing: 0) {
            segment(label: "Day",     value: .day)
            segment(label: "Recents", value: .recents)
        }
        .padding(2)
        .background(
            Capsule()
                .fill(AppColors.canvas)
        )
        .overlay(
            Capsule()
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }

    private func segment(label: String, value: NotepadTab) -> some View {
        let isActive = selected == value
        return Button(action: { selected = value }) {
            Text(label)
                .font(AppText.button)
                .foregroundStyle(isActive ? AppColors.onAccent : AppColors.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppSpacing.s2)
                .background(
                    Capsule()
                        .fill(isActive ? AppColors.themeGreenDeep : Color.clear)
                )
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Today card (Day view)

private struct SelectedDayCard: View {
    let day: DayCount
    let today: Date
    /// Per-mode chip breakdown — populated only when the selected day
    /// is today (the VM only computes the breakdown for today's entry).
    let breakdown: TodayBreakdown?
    let onTap: () -> Void

    var body: some View {
        let cal     = Calendar.current
        let isToday = cal.isDate(day.date, inSameDayAs: today)
        let isFuture = day.date > today
        let eyebrow = isToday ? "TODAY" : (isFuture ? "UPCOMING" : "SELECTED")
        let entry   = day.entry
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text("\(eyebrow) · \(Self.dayHeader(day.date))")
                    .font(AppText.eyebrow)
                    .foregroundStyle(AppColors.coral)

                Text(entry?.title?.nonEmpty ?? (isToday ? "Today's entry" : "Untitled"))
                    .font(.system(size: 18, weight: .medium, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)

                if let preview = Self.notesPreview(entry, limit: 140) {
                    Text(preview)
                        .font(.system(size: 13, design: .serif))
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(2)
                } else {
                    Text(emptyMessage(isToday: isToday, isFuture: isFuture))
                        .font(.system(size: 13, design: .serif))
                        .foregroundStyle(AppColors.textTertiary)
                }

                if let b = breakdown, b.captureCount > 0 || b.openTodoCount > 0 {
                    HStack(spacing: AppSpacing.s2) {
                        if b.photoCount > 0 {
                            chipPill("photo · \(b.photoCount)",
                                     bg: Color(red: 0xFC/255, green: 0xEA/255, blue: 0xE0/255),
                                     fg: Color(red: 0x99/255, green: 0x3C/255, blue: 0x1D/255))
                        }
                        if b.scanCount > 0 {
                            chipPill("scan · \(b.scanCount)",
                                     bg: Color(red: 0xD9/255, green: 0xED/255, blue: 0xE2/255),
                                     fg: Color(red: 0x1E/255, green: 0x59/255, blue: 0x43/255))
                        }
                        if b.voiceCount > 0 {
                            chipPill("voice · \(b.voiceCount)",
                                     bg: Color(red: 0xFA/255, green: 0xEE/255, blue: 0xDA/255),
                                     fg: Color(red: 0x85/255, green: 0x4F/255, blue: 0x0B/255))
                        }
                        if b.openTodoCount > 0 {
                            Text("+ \(b.openTodoCount) todos")
                                .font(AppText.tag)
                                .foregroundStyle(AppColors.coralDeep)
                        }
                    }
                } else if day.captureCount > 0 || day.openTodoCount > 0 {
                    HStack(spacing: AppSpacing.s2) {
                        if day.captureCount > 0 {
                            Text("\(day.captureCount) captures")
                                .font(AppText.tag)
                                .foregroundStyle(AppColors.themeGreenDeep)
                        }
                        if day.captureCount > 0 && day.openTodoCount > 0 {
                            Text("·").font(AppText.tag).foregroundStyle(AppColors.textSecondary)
                        }
                        if day.openTodoCount > 0 {
                            Text("\(day.openTodoCount) todos")
                                .font(AppText.tag)
                                .foregroundStyle(AppColors.coralDeep)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.s5)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .stroke(AppColors.coral.opacity(isToday ? 0.55 : 0.30), lineWidth: 1.2)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func emptyMessage(isToday: Bool, isFuture: Bool) -> String {
        if isToday { return "Nothing captured yet — tap to start today's note." }
        if isFuture { return "No entry — yet." }
        return "No entry on this day."
    }

    private func chipPill(_ label: String, bg: Color, fg: Color) -> some View {
        Text(label)
            .font(AppText.tag)
            .foregroundStyle(fg)
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, 4)
            .background(Capsule().fill(bg))
    }

    private static func notesPreview(_ entry: NotepadEntry?, limit: Int) -> String? {
        guard let entry else { return nil }
        let first = entry.notes
            .split(whereSeparator: \.isNewline)
            .map { String($0).trimmingCharacters(in: .whitespaces) }
            .first(where: { !$0.isEmpty })
        guard let first else { return nil }
        return String(first.prefix(limit))
    }

    private static func dayHeader(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "EEEE · MMM d"
        return fmt.string(from: date).uppercased()
    }
}

// MARK: - Quick capture pills

private struct QuickCapturePills: View {
    let onCapture: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("QUICK CAPTURE")
                .font(AppText.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.horizontal, 2)

            HStack(spacing: AppSpacing.s2) {
                pill("note") { onCapture("note") }
                pill("photo") { onCapture("photo") }
                pill("scan") { onCapture("scan") }
                pill("voice") { onCapture("voice") }
            }
        }
    }

    private func pill(_ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(AppText.button)
                .foregroundStyle(AppColors.themeGreenDeep)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(
                    Capsule().fill(AppColors.cardSolid)
                )
                .overlay(
                    Capsule().stroke(AppColors.borderDefault, lineWidth: 0.6)
                )
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Month pager strip

private struct MonthPagerStrip: View {
    let month: Date

    var body: some View {
        let cal = Calendar.current
        let prev = cal.date(byAdding: .month, value: -1, to: month) ?? month
        let next = cal.date(byAdding: .month, value:  1, to: month) ?? month
        let short = DateFormatter()
        short.locale = Locale(identifier: "en_US_POSIX")
        short.dateFormat = "MMM"
        let long = DateFormatter()
        long.locale = Locale(identifier: "en_US_POSIX")
        long.dateFormat = "LLLL"
        return HStack(spacing: 0) {
            Text("‹  \(short.string(from: prev).uppercased())  ·  ")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textSecondary)
            Text(long.string(from: month).uppercased())
                .font(AppText.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("  ·  \(short.string(from: next).uppercased())  ›")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .center)
    }
}

// MARK: - Calendar carousel (current ± faded peeks)

/// Swipeable calendar carousel — TabView with .page style provides
/// the native horizontal swipe gesture with snap-to-page behavior.
/// Each page renders a full-width calendar for one month; the user
/// can swipe through months and tap any day to populate the
/// SelectedDayCard below.
private struct SwipeableCalendarCarousel: View {
    /// Today's anchor month — the carousel's "page 0".
    let anchorMonth: Date
    /// Two-way offset binding so the surrounding view can show the
    /// pager-strip label for the currently-centered month.
    @Binding var pageOffset: Int
    let byDate: [String: NotepadEntry]
    let today: Date
    let selectedDate: Date
    let onDayTap: (DayCount) -> Void

    private let pageRange = -24...24

    var body: some View {
        VStack(spacing: AppSpacing.s2) {
            // Pager strip reflects the currently-centered page so the
            // user sees which month they've swiped into.
            let centeredMonth = monthDate(forOffset: pageOffset)
            MonthPagerStrip(month: centeredMonth)

            // Weekday strip rendered ONCE above the pager — same fix
            // as Android. Otherwise the prev / next page's own strips
            // bleed into the centered page during swipe transitions.
            NotepadCalendarWeekdayStrip()
                .padding(.horizontal, 8)

            ZStack {
                TabView(selection: $pageOffset) {
                    ForEach(Array(pageRange), id: \.self) { offset in
                        let pageMonthDate = monthDate(forOffset: offset)
                        let resolved = daysForMonthDate(pageMonthDate)
                        let isCenter = offset == pageOffset
                        NotepadCalendarBloom(
                            leadingBlanks: resolved.leading,
                            days: resolved.days,
                            today: today,
                            onDayTap: { day in onDayTap(day) },
                            showLegend: false,
                            showWeekdayStrip: false,
                            // Only the centered page gets the green
                            // selection ring — side peeks aren't
                            // tappable, so highlighting their cells
                            // would just add noise.
                            selectedDate: isCenter ? selectedDate : nil
                        )
                        .padding(.horizontal, 8)
                        .tag(offset)
                    }
                }
                #if os(iOS)
                .tabViewStyle(.page(indexDisplayMode: .never))
                #endif
                .frame(height: 220)

                // Subtle chevron hints — non-interactive overlay so the
                // user discovers the swipe gesture even before they try
                // it. Coral so the affordance ties into the today pin.
                HStack {
                    Text("‹")
                        .font(.system(size: 22, weight: .medium, design: .serif))
                        .foregroundStyle(AppColors.coral.opacity(0.55))
                        .padding(.leading, 4)
                    Spacer()
                    Text("›")
                        .font(.system(size: 22, weight: .medium, design: .serif))
                        .foregroundStyle(AppColors.coral.opacity(0.55))
                        .padding(.trailing, 4)
                }
                .allowsHitTesting(false)
            }
        }
    }

    private func monthDate(forOffset offset: Int) -> Date {
        Calendar.current.date(byAdding: .month, value: offset, to: anchorMonth) ?? anchorMonth
    }

    private func daysForMonthDate(_ date: Date) -> (leading: Int, days: [DayCount]) {
        let cal = Calendar.current
        let comps = cal.dateComponents([.year, .month], from: date)
        return daysForMonth(month: comps, in: cal, byDate: byDate)
    }
}

// MARK: - Helpers

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private extension ISO8601DateFormatter {
    static func localDate(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .iso8601)
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: date)
    }
}
}

    }
}
}
}
Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: date)
    }
}
}

    }
}
}
}
t.string(from: date)
    }
}
}

    }
}
}
}
 }
}
}
}
}
}
}
}
Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: date)
    }
}
}

    }
}
}
}
t.string(from: date)
    }
}
}

    }
}
}
}
 }
}
}
}
}
}
