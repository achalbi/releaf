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
    /// Variant of `onOpenEntry` that also carries a [CaptureMode] hint
    /// — fires when the Recents new-entry picker is tapped so the host
    /// can deep-link the editor to the matching feature section
    /// (Photos / Scans / Voice / Todo / Contacts). Defaults to
    /// dropping the mode and falling back to `onOpenEntry`.
    private let onOpenEntryWithMode: (String, CaptureMode) -> Void

    public init(
        onOpenEntry: @escaping (String) -> Void = { _ in },
        onOpenEntryWithMode: ((String, CaptureMode) -> Void)? = nil
    ) {
        self.onOpenEntry = onOpenEntry
        self.onOpenEntryWithMode = onOpenEntryWithMode ?? { id, _ in onOpenEntry(id) }
    }

    public var body: some View {
        Group {
            if let session = authStore.session {
                NotepadDayRecentsContent(
                    userId: session.userId,
                    onOpenEntry: onOpenEntry,
                    onOpenEntryWithMode: onOpenEntryWithMode
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

    /// Currently-selected page within the selected day's carousel.
    /// Drives where quick-capture taps land — a scan / photo / voice
    /// goes onto the page the user is *looking at* rather than always
    /// at the day's first entry. Nil when the user is on the trailing
    /// "+ new page" card (no live entry to target — quick-capture
    /// creates one). Reset when the day changes; clamped when the
    /// active entry vanishes (e.g. it was filtered out by a category
    /// change).
    @State private var selectedPageEntryId: String? = nil

    /// PhotosPicker selection — the "import" quick-capture pill is a
    /// PhotosPicker; when the user finishes picking, this state changes
    /// and `.onChange` loads each item's bytes and asks the VM to
    /// create one new entry per photo.
    @State private var importItems: [PhotosPickerItem] = []

    private let onOpenEntry: (String) -> Void
    private let onOpenEntryWithMode: (String, CaptureMode) -> Void

    init(
        userId: String,
        onOpenEntry: @escaping (String) -> Void,
        onOpenEntryWithMode: @escaping (String, CaptureMode) -> Void
    ) {
        _vm = StateObject(wrappedValue: NotepadScreenViewModel(userId: userId))
        self.onOpenEntry = onOpenEntry
        self.onOpenEntryWithMode = onOpenEntryWithMode
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

                // Category filter row — predefined categories plus any
                // customs the user has typed. Tapping a chip narrows
                // every downstream surface (calendar density, day card,
                // recents masonry) to that category; tapping the active
                // chip again clears the filter back to "All".
                CategoryFilterRow(
                    selected: vm.state.selectedCategory,
                    customs:  vm.state.customCategories,
                    onPick:   { vm.setCategoryFilter($0) }
                )

                if vm.state.isLoading {
                    ProgressView()
                        .tint(AppColors.coral)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s8)
                } else {
                    switch tab {
                    case .day:     dayView
                    // New Recents implementation lives at
                    // Features/Notepad/Recents/. We adapt the live VM
                    // state into a RecentsDayStats snapshot here so the
                    // screen stays a pure view of data instead of
                    // reaching into the repository itself. Legacy
                    // `recentsView` computed property is preserved for
                    // rollback — change `RecentsScreen(...)` back to
                    // `recentsView` to revert.
                    case .recents:
                        RecentsScreen(
                            stats: RecentsAdapter.fromState(vm.state, today: Date()),
                            onOpenPage: { page in onOpenEntry(page.id) },
                            // Picker cells (Photo / Scan / Voice / Todo /
                            // Contact) and the new-entry footer CTA all
                            // funnel through onPickMode. We forward the
                            // mode through `onOpenEntryWithMode` so the
                            // editor opens scrolled to the matching
                            // feature section.
                            onPickMode: { mode in
                                vm.createNewPageOn(Date()) { newId in
                                    onOpenEntryWithMode(newId, mode)
                                }
                            }
                        )
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
        // Use the shared `LeafEyebrow` component (leaf glyph + label
        // in an HStack) so the eyebrow has the exact same shape and
        // vertical position as the Library tab's "releaf · shelves"
        // eyebrow. Plain `Text("NOTEPAD")` here would sit at a
        // slightly different y because it lacks the glyph + HStack
        // wrapping that LeafEyebrow brings.
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            LeafEyebrow("releaf · notepad")

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

            // Selected-day pager — N entry cards (one per notepad
            // entry filed under this day) plus a trailing "+ new
            // page" card. Selection is reported back via
            // [selectedPageEntryId] so the quick-capture pills can
            // target the page the user is currently viewing instead
            // of always landing on the day's first entry.
            MultiEntryDayCarousel(
                day: selectedDay,
                today: today,
                selectedPageEntryId: $selectedPageEntryId,
                onTapEntry: { id in onOpenEntry(id) },
                onAddPage:  {
                    vm.createNewPageOn(selectedDate) { newId in
                        selectedPageEntryId = newId
                        onOpenEntry(newId)
                    }
                }
            )
            // Reset the page selection when the user navigates to a
            // different day. Without this, `selectedPageEntryId`
            // would dangle pointing at an entry that's no longer in
            // the current day's list.
            .onChange(of: selectedDate) { _ in
                selectedPageEntryId = nil
            }
            // Clamp when a previously-selected entry vanishes (filter
            // change, soft delete, etc.). Re-evaluating the entries
            // list on every observation tick keeps this in sync
            // without leaking stale ids into quick-capture targets.
            .onChange(of: selectedDay.entries) { entries in
                if let current = selectedPageEntryId,
                   !entries.contains(where: { $0.id == current }) {
                    selectedPageEntryId = nil
                }
            }

            // Quick capture pills — fire on the page the carousel
            // above is currently pointing at; fall back to
            // openOrCreate when the day is empty.
            let effectiveEntryId = selectedPageEntryId
                ?? selectedDay.entries.first?.id
            QuickCapturePills(onCapture: { _ in
                if let id = effectiveEntryId {
                    vm.openEntry(id: id, onResult: onOpenEntry)
                } else {
                    vm.openOrCreateForDate(selectedDate, onResult: onOpenEntry)
                }
            })
        }
    }

    /// Resolve a [DayCount] for the currently-selected date by reading
    /// the VM's entriesByDate index. Falls back to an empty placeholder
    /// so the UI keeps rendering before the first observe-fire.
    private func resolveSelectedDay(today: Date) -> DayCount {
        let key = Self.isoDateString(selectedDate)
        let entries = vm.state.entriesByDate[key] ?? vm.state.byDate[key].map { [$0] } ?? []
        let captures = entries.reduce(0) { $0 + entryCaptureCount($1) }
        let openTodos = entries.reduce(0) { $0 + entryOpenTodoCount($1) }
        return DayCount(
            date: selectedDate,
            dateString: key,
            entries: entries,
            captureCount: captures,
            openTodoCount: openTodos
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
                entries: vm.state.todayEntries,
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
            // Track color matches the recents stats strip
            // (`Color.bgSurfaceMuted` = #EFE7CD) so the inactive
            // segment — which is `Color.clear` on top of the track —
            // picks up the same muted-cream tone as the strip
            // directly below the row, keeping the header area in
            // one color family.
            Capsule()
                .fill(Color.bgSurfaceMuted)
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

// MARK: - Quick capture buttons

private struct QuickCapturePills: View {
    let onCapture: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("QUICK CAPTURE")
                .font(AppText.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.horizontal, 2)

            // Icon-only action buttons replace the previous text
            // pills. Each button is a soft rounded-square in the
            // recents leaf palette (#DDEACD bg + deep-green icon)
            // — matches the hero pip row, EarlierGrid pips, and the
            // new-entry slot picker cells, so every "capture this
            // kind" affordance across the notepad reads as one
            // family.
            HStack(spacing: AppSpacing.s2) {
                button(icon: "note.text",                     label: "Note")     { onCapture("note") }
                button(icon: CaptureMode.photos.systemIcon,   label: "Photo")    { onCapture("photo") }
                button(icon: CaptureMode.scans.systemIcon,    label: "Scan")     { onCapture("scan") }
                button(icon: CaptureMode.voice.systemIcon,    label: "Voice")    { onCapture("voice") }
                button(icon: CaptureMode.todo.systemIcon,     label: "Todos")    { onCapture("todos") }
                button(icon: CaptureMode.contacts.systemIcon, label: "Contacts") { onCapture("contacts") }
                button(icon: CaptureMode.location.systemIcon, label: "Location") { onCapture("location") }
            }
        }
    }

    private func button(icon systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(AppColors.themeGreenDeep)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color(red: 0xDD/255, green: 0xEA/255, blue: 0xCD/255))
                )
                .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
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

// MARK: - Multi-entry day carousel (Phase 4-5)

/// Horizontal pager of uniform-size page cards for the selected day.
/// **Each card represents a separate notepad entry filed under this
/// day** — i.e. the user's "pages of the day" — not sub-pages of a
/// single entry. The trailing "+ new page" card creates a fresh
/// entry on this day (omitted for future days, where back-filling
/// forward entries isn't allowed).
///
/// Selection: as the user swipes, the bound `selectedPageEntryId`
/// updates to the entry id under the centered card (or nil when the
/// user is on the trailing "+ new page" card). The screen's
/// quick-capture pills use this id to route their captures onto the
/// page the user is actively viewing.
private struct MultiEntryDayCarousel: View {
    let day: DayCount
    let today: Date
    @Binding var selectedPageEntryId: String?
    let onTapEntry: (String) -> Void
    let onAddPage: () -> Void

    @State private var pagerSelection: Int = 0

    var body: some View {
        let cal       = Calendar.current
        let isToday   = cal.isDate(day.date, inSameDayAs: today)
        let isFuture  = day.date > today
        let entries   = day.entries
        let showNew   = !isFuture
        // Card slot ids — `entries.count` real entry slots followed
        // by the +1 "new" slot when applicable. For empty days, slot
        // 0 is the placeholder.
        let slotCount = entries.isEmpty
            ? 1                               // placeholder card
            : entries.count + (showNew ? 1 : 0)

        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            // Centered date header above the carousel.
            Text("\(headerEyebrow(isToday: isToday, isFuture: isFuture)) · \(Self.dayHeader(day.date))")
                .font(AppText.eyebrow)
                .foregroundStyle(AppColors.coral)
                .frame(maxWidth: .infinity, alignment: .center)

            TabView(selection: $pagerSelection) {
                ForEach(0..<slotCount, id: \.self) { idx in
                    cardForSlot(
                        index: idx,
                        entries: entries,
                        isToday: isToday,
                        isFuture: isFuture
                    )
                    .padding(.horizontal, AppSpacing.s4)
                    .tag(idx)
                }
            }
            #if os(iOS)
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))
            #endif
            // Fixed-height carousel so the surrounding ScrollView
            // doesn't fight the TabView for vertical space.
            .frame(height: 220)
            .onChange(of: pagerSelection) { newIndex in
                let pickedId = entries.indices.contains(newIndex) ? entries[newIndex].id : nil
                if pickedId != selectedPageEntryId {
                    selectedPageEntryId = pickedId
                }
            }
            .onAppear {
                // Initial alignment: snap to whichever entry was
                // already selected at the screen level (e.g. the
                // user just created a new page and we want to land
                // on it). Falls back to slot 0 when no match.
                let idx = entries.firstIndex(where: { $0.id == selectedPageEntryId }) ?? 0
                if idx != pagerSelection { pagerSelection = idx }
            }
        }
    }

    @ViewBuilder
    private func cardForSlot(
        index: Int,
        entries: [NotepadEntry],
        isToday: Bool,
        isFuture: Bool
    ) -> some View {
        if entries.isEmpty {
            // Placeholder for empty days — also doubles as the
            // "+ new page" affordance on today / past days.
            placeholderCard(isToday: isToday, isFuture: isFuture)
        } else if index < entries.count {
            entryCard(
                entry: entries[index],
                isFirstAndToday: isToday && index == 0,
                pageNumber: index + 1,
                totalEntries: entries.count
            )
        } else {
            newPageCard()
        }
    }

    private func entryCard(
        entry: NotepadEntry,
        isFirstAndToday: Bool,
        pageNumber: Int,
        totalEntries: Int
    ) -> some View {
        let title = entry.title?.nonEmpty ?? "Untitled"
        let preview = (entry.description?.nonEmpty)
            ?? entry.notes
                .split(whereSeparator: \.isNewline)
                .map { String($0).trimmingCharacters(in: .whitespaces) }
                .first(where: { !$0.isEmpty })
                .map { String($0.prefix(140)) }
        return PageCardChrome(
            eyebrow:       "PAGE \(pageNumber)",
            indicator:     "\(pageNumber) / \(totalEntries)",
            title:         title,
            copy:          preview ?? "",
            emptyHint:     "Empty page — tap to write.",
            accentAlpha:   isFirstAndToday ? 0.55 : 0.30,
            background:    AppColors.cardSolid,
            titleColor:    AppColors.textPrimary,
            chips: AnyView(EntryCountsRow(entry: entry)),
            category:      entry.category?.trimmingCharacters(in: .whitespaces).nonEmpty?.uppercased(),
            onTap: { onTapEntry(entry.id) }
        )
    }

    private func newPageCard() -> some View {
        PageCardChrome(
            eyebrow:     "NEW",
            indicator:   nil,
            title:       "+ new page",
            copy:        "Tap to add a fresh page to this day.",
            emptyHint:   "",
            accentAlpha: 0.45,
            background:  AppColors.canvas,
            titleColor:  AppColors.coralDeep,
            chips:       nil,
            category:    nil,
            onTap:       onAddPage
        )
    }

    private func placeholderCard(isToday: Bool, isFuture: Bool) -> some View {
        let (title, copy) = isFuture
            ? ("Untitled", "No entry — yet.")
            : (isToday ? "+ new page" : "+ new page",
               isToday ? "Tap to start today's note." : "Tap to add a page on this day.")
        return PageCardChrome(
            eyebrow:     isFuture ? "UPCOMING" : "NEW",
            indicator:   nil,
            title:       title,
            copy:        copy,
            emptyHint:   "",
            accentAlpha: isToday ? 0.55 : 0.30,
            background:  isFuture ? AppColors.cardSolid : AppColors.canvas,
            titleColor:  isFuture ? AppColors.textPrimary : AppColors.coralDeep,
            chips:       nil,
            category:    nil,
            onTap:       isFuture ? {} : onAddPage
        )
    }

    private func headerEyebrow(isToday: Bool, isFuture: Bool) -> String {
        if isToday { return "TODAY" }
        if isFuture { return "UPCOMING" }
        return "SELECTED"
    }

    private static func dayHeader(_ date: Date) -> String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "EEEE · MMM d"
        return fmt.string(from: date).uppercased()
    }
}

/// Single uniform card chrome reused by both real entries and the
/// trailing "+ new page" affordance. Mirrors the `PageCard` helper
/// on the Android side so the two surfaces feel identical.
private struct PageCardChrome: View {
    let eyebrow: String
    let indicator: String?
    let title: String
    /// Renamed from `body` — collided with SwiftUI's required
    /// `var body: some View` on the View conformance.
    let copy: String
    let emptyHint: String
    let accentAlpha: Double
    let background: Color
    let titleColor: Color
    let chips: AnyView?
    /// Page category — rendered as a small uppercase label in the
    /// card's top-right corner so users can identify the page's
    /// category at a glance. `nil` (the placeholder + new-page
    /// affordances) drops the label entirely.
    let category: String?
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .topTrailing) {
                VStack(alignment: .leading, spacing: AppSpacing.s2) {
                    Text(eyebrow)
                        .font(AppText.eyebrow)
                        .foregroundStyle(AppColors.coral)
                    Text(title)
                        .font(.system(size: 18, weight: .medium, design: .serif))
                        .foregroundStyle(titleColor)
                    let displayBody = copy.isEmpty ? emptyHint : copy
                    Text(displayBody)
                        .font(.system(size: 13, design: .serif))
                        .foregroundStyle(copy.isEmpty
                            ? AppColors.textTertiary
                            : AppColors.textSecondary)
                        .lineLimit(2)
                    if let chips { chips }
                    Spacer(minLength: 0)
                }
                .padding(AppSpacing.s5)
                .frame(maxWidth: .infinity, alignment: .leading)
                // Top-right corner: category on top (when present),
                // page-position indicator below it. Plain uppercase
                // text — no pill — keeps the corner light and pairs
                // visually with the eyebrow at the opposite corner.
                if category != nil || indicator != nil {
                    VStack(alignment: .trailing, spacing: 2) {
                        if let category {
                            Text(category)
                                .font(AppText.tag)
                                .foregroundStyle(AppColors.themeGreenDeep)
                                .lineLimit(1)
                        }
                        if let indicator {
                            Text(indicator)
                                .font(AppText.tag)
                                .foregroundStyle(AppColors.textSecondary)
                        }
                    }
                    .padding(AppSpacing.s4)
                }
            }
            .background(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .fill(background)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .stroke(AppColors.coral.opacity(accentAlpha), lineWidth: 1.2)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Per-entry counts row — drives the chip strip on each entry card
/// in the day carousel.
private struct EntryCountsRow: View {
    let entry: NotepadEntry

    var body: some View {
        let captures = NotepadScreenViewModel.totalCaptures(for: entry)
        let openTodos = NotepadScreenViewModel.openTodoCount(for: entry)
        if captures == 0 && openTodos == 0 {
            EmptyView()
        } else {
            HStack(spacing: AppSpacing.s2) {
                if captures > 0 {
                    Text("\(captures) captures")
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.themeGreenDeep)
                }
                if captures > 0 && openTodos > 0 {
                    Text("·")
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.textSecondary)
                }
                if openTodos > 0 {
                    Text("\(openTodos) todos")
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.coralDeep)
                }
            }
        }
    }
}

// MARK: - Category filter row (Phase 6)

/// Horizontally-scrollable row of category filter chips. The first
/// chip is "All" (clears the filter); after that come the predefined
/// + custom categories merged into a single ordered list, with the
/// user's preferred display order applied (Settings → Categories).
/// Tapping a chip narrows every downstream surface to that category;
/// tapping the active chip — or "All" — clears back to the
/// unfiltered view.
private struct CategoryFilterRow: View {
    @EnvironmentObject private var uiPrefs: UiPreferences

    let selected: String?
    let customs: [String]
    let onPick: (String?) -> Void

    var body: some View {
        let ordered = NotepadCategory.applyOrder(
            userOrder: uiPrefs.state.notepadCategoryOrder,
            customs:   customs
        )
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: AppSpacing.s2) {
                FilterChip(
                    label:    "All",
                    isActive: selected == nil,
                    onTap:    { onPick(nil) }
                )
                ForEach(ordered, id: \.self) { name in
                    let active = selected.map { $0.caseInsensitiveCompare(name) == .orderedSame } ?? false
                    FilterChip(
                        label:    name,
                        isActive: active,
                        // Tap on already-active = clear (toggle-off
                        // pattern, matches the Pen / Eraser toggle in
                        // the drawing toolbar so all chip-style
                        // affordances behave the same way).
                        onTap:    { onPick(active ? nil : name) }
                    )
                }
            }
        }
    }
}

private struct FilterChip: View {
    let label: String
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        // Custom and predefined chips share the same idle styling —
        // the user wanted the chip row to read as one homogeneous
        // list rather than two visually-distinct groups. The idle
        // background uses the recents palette's leaf-green chip
        // token (`Color.bgChip` = #DDEACD), the same shade used by
        // the stats strip, week pulse cells, and tall featured
        // earlier card, so the filter row sits in the same color
        // family as everything stacked beneath it.
        let bg: Color = isActive ? AppColors.themeGreenDeep : Color.bgChip
        let fg: Color = isActive ? Color.white               : AppColors.themeGreenDeep
        Button(action: onTap) {
            Text(label)
                .font(AppText.meta)
                .foregroundStyle(fg)
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, 6)
                .background(
                    RoundedRectangle(cornerRadius: AppSpacing.s3, style: .continuous)
                        .fill(bg)
                )
        }
        .buttonStyle(.plain)
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
