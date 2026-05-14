/*
 * CalendarPanel.swift
 *
 * Month-view calendar grid — the centerpiece of QuickInk's Calendar
 * screen. Renders the visible month with day-of-week headers (Mon-
 * first), today emphasised, holidays + panchanga events marked with
 * dots, and QuickInk captures surfaced as a small accent dot. A list
 * under the grid shows the Indian government holidays falling in the
 * visible month.
 *
 * Port of Releaf Android's `CalendarPanel.kt`. Kotlin Compose's
 * `VerticalPager` collapses to SwiftUI's `TabView(selection:).tab-
 * ViewStyle(.page)` (horizontal here — natural on iOS where TabView
 * is horizontal-first; chevrons mirror to ◀/▶ to match). A bounded
 * ±120-month range backs the pager (≈20 years either side of today's
 * anchor) — TabView eager-renders its `ForEach`, so an unbounded
 * range would be wasteful; 240 pages cover any realistic roaming
 * range without blowing up the first-paint cost.
 */

import SwiftUI

/// Number of months either side of the anchor the pager covers.
/// ±120 ≈ 20 years either side. Bumping this only costs the
/// SwiftUI view-builder eval for additional pages; the GRDB
/// observations are scoped to the visible month and don't fan out.
private let pageHalfRange: Int = 120

public struct CalendarPanel: View {

    // Hoisted state (driven by the host's ViewModel).
    @Binding public var visibleMonth: YearMonth
    @Binding public var selectedDate: Date

    /// Dates that carry a non-empty panchanga `specialDay` — painted
    /// as a small dot under the day number.
    public let eventDates: Set<Date>
    /// Amavasya dates — dark moon disc in the top-right.
    public let newMoonDates: Set<Date>
    /// Purnima dates — light moon disc in the top-right.
    public let fullMoonDates: Set<Date>
    /// Dates with at least one QuickInk capture — painted as an
    /// accent dot under the day number (merges with the panchanga
    /// event dot when both signals fire on the same day).
    public let captureDates: Set<Date>

    public init(
        visibleMonth: Binding<YearMonth>,
        selectedDate: Binding<Date>,
        eventDates: Set<Date> = [],
        newMoonDates: Set<Date> = [],
        fullMoonDates: Set<Date> = [],
        captureDates: Set<Date> = []
    ) {
        self._visibleMonth = visibleMonth
        self._selectedDate = selectedDate
        self.eventDates = eventDates
        self.newMoonDates = newMoonDates
        self.fullMoonDates = fullMoonDates
        self.captureDates = captureDates
    }

    /// Anchor month — page-0 of the bounded TabView. Pinned to
    /// today's month at first appear so the pager opens on the
    /// current month.
    @State private var anchor: YearMonth = YearMonth.from(date: Date(), calendar: Self.localCalendar)
    @State private var pageOffset: Int = 0

    public var body: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            header

            // Day-of-week header — Monday first, Sunday last.
            weekdayStrip

            // Month-pager grid.
            TabView(selection: $pageOffset) {
                ForEach(-pageHalfRange...pageHalfRange, id: \.self) { offset in
                    let pageMonth = anchor.adding(months: offset)
                    monthGrid(for: pageMonth)
                        .tag(offset)
                }
            }
            #if os(iOS)
            .tabViewStyle(.page(indexDisplayMode: .never))
            #endif
            // Pager height = max rows across the prev/visible/next
            // window. Avoids clipping mid-swipe (a 4-row Feb → 5-row
            // Mar transition would otherwise drop March's last row
            // when the pager is sized to Feb's height).
            .frame(height: pagerHeight * CGFloat(maxRowsInWindow))

            // Selected-date holiday / month-list / moon-phase legend.
            footerSection
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.top, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .onAppear {
            // Sync the pager to the externally-driven visibleMonth on
            // first appear. The anchor stays pinned at today's month
            // so page indices remain stable across the screen's life.
            pageOffset = visibleMonth.monthsSince(anchor)
        }
        .onChange(of: visibleMonth) { newMonth in
            let target = newMonth.monthsSince(anchor)
            if target != pageOffset {
                withAnimation(.easeInOut(duration: 0.22)) { pageOffset = target }
            }
        }
        .onChange(of: pageOffset) { newOffset in
            let target = anchor.adding(months: newOffset)
            if target != visibleMonth {
                visibleMonth = target
            }
        }
    }

    // MARK: - Header

    @ViewBuilder
    private var header: some View {
        HStack(alignment: .center) {
            Text(monthHeaderText(visibleMonth))
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 2) {
                chevronButton(systemName: "chevron.left") {
                    visibleMonth = visibleMonth.previous
                }
                chevronButton(systemName: "chevron.right") {
                    visibleMonth = visibleMonth.next
                }
            }
        }
    }

    @ViewBuilder
    private func chevronButton(systemName: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(QuickInkColors.ink)
                .frame(width: 28, height: 28)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Weekday strip

    @ViewBuilder
    private var weekdayStrip: some View {
        // Monday-first weekday initials. Matches Android's narrow
        // Mon-Tue-Wed-Thu-Fri-Sat-Sun.
        HStack(spacing: 0) {
            ForEach(Self.weekdayInitials, id: \.self) { letter in
                Text(letter)
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.muted)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Month grid

    /// Pager-height tuning. 36pt per row matches the Android DayCell.
    private let pagerHeight: CGFloat = 36
    /// Six rows covers every month (28-31 days + up to 6 leading
    /// blanks fit in 5–6 rows; the max-window trick at render-time
    /// keeps the pager sized for the tallest adjacent month).
    private var maxRowsInWindow: Int {
        let curr = rowsForMonth(visibleMonth)
        let prev = rowsForMonth(visibleMonth.previous)
        let next = rowsForMonth(visibleMonth.next)
        return max(curr, max(prev, next))
    }

    @ViewBuilder
    private func monthGrid(for month: YearMonth) -> some View {
        let cal = Self.localCalendar
        let first = month.firstDay(in: cal)
        // Compose-side `dayOfWeek.value - 1` (Mon=0..Sun=6). SwiftUI's
        // `Calendar.component(.weekday:)` returns 1=Sun..7=Sat — we
        // convert to Mon=0 by mapping (weekday + 5) % 7.
        let weekday = cal.component(.weekday, from: first)
        let leading = ((weekday + 5) % 7)
        let daysInMonth = month.numberOfDays(in: cal)
        let rows = rowsForMonth(month)

        // Holiday set for prev + visible + next months so spillover
        // cells still surface a holiday signal.
        let holidaySet: Set<Date> = IndianHolidays.dateSet(
            aroundYear: month.year, month: month.month
        )

        VStack(spacing: 0) {
            ForEach(0..<rows, id: \.self) { rowIndex in
                HStack(spacing: 0) {
                    ForEach(0..<7, id: \.self) { col in
                        let cellIndex = rowIndex * 7 + col
                        let dayNum = cellIndex - leading + 1
                        let cellDate = dateForCell(
                            month: month,
                            firstOfMonth: first,
                            dayNum: dayNum,
                            daysInMonth: daysInMonth,
                            calendar: cal
                        )
                        let isOutsideMonth = !month.contains(cellDate, calendar: cal)
                        let cellDay = cal.startOfDay(for: cellDate)
                        let isToday = cal.isDate(cellDate, inSameDayAs: Date())
                        let isSelected = cal.isDate(cellDate, inSameDayAs: selectedDate)
                        let isHoliday = holidaySet.contains(cellDay)
                        let weekdayOfCell = cal.component(.weekday, from: cellDate)
                        let isWeekend = weekdayOfCell == 1 || weekdayOfCell == 7
                        let hasEvent = eventDates.contains(cellDay)
                        let isNewMoon = newMoonDates.contains(cellDay)
                        let isFullMoon = fullMoonDates.contains(cellDay)
                        let hasCapture = captureDates.contains(cellDay)
                        DayCell(
                            day: cal.component(.day, from: cellDate),
                            isToday: isToday,
                            isSelected: isSelected,
                            isHoliday: isHoliday,
                            isWeekend: isWeekend,
                            isOutsideMonth: isOutsideMonth,
                            hasEvent: hasEvent,
                            hasCapture: hasCapture,
                            isNewMoon: isNewMoon,
                            isFullMoon: isFullMoon
                        )
                        .frame(maxWidth: .infinity, minHeight: pagerHeight)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            selectedDate = cal.startOfDay(for: cellDate)
                            if isOutsideMonth {
                                visibleMonth = YearMonth.from(date: cellDate, calendar: cal)
                            }
                        }
                    }
                }
            }
        }
    }

    private func dateForCell(
        month: YearMonth,
        firstOfMonth: Date,
        dayNum: Int,
        daysInMonth: Int,
        calendar: Calendar
    ) -> Date {
        if dayNum < 1 {
            // Leading fill: tail of previous month.
            return calendar.date(byAdding: .day, value: dayNum - 1, to: firstOfMonth) ?? firstOfMonth
        } else if dayNum > daysInMonth {
            // Trailing fill: start of next month.
            let nextMonth = month.next
            return calendar.date(from: DateComponents(year: nextMonth.year, month: nextMonth.month, day: dayNum - daysInMonth)) ?? firstOfMonth
        } else {
            return calendar.date(from: DateComponents(year: month.year, month: month.month, day: dayNum)) ?? firstOfMonth
        }
    }

    private func rowsForMonth(_ ym: YearMonth) -> Int {
        let cal = Self.localCalendar
        let first = ym.firstDay(in: cal)
        let weekday = cal.component(.weekday, from: first)
        let leading = ((weekday + 5) % 7)
        let totalCells = leading + ym.numberOfDays(in: cal)
        return (totalCells + 6) / 7
    }

    // MARK: - Footer (selected holiday / month holiday list + moon legend)

    @ViewBuilder
    private var footerSection: some View {
        let selectedHoliday = IndianHolidays.forDate(selectedDate)
        let monthHolidays = IndianHolidays.forMonth(year: visibleMonth.year, month: visibleMonth.month)
        let showMoonLegend = !newMoonDates.isEmpty || !fullMoonDates.isEmpty

        if let h = selectedHoliday {
            HolidayRow(date: h.date, name: h.name, isFocused: true)
            if showMoonLegend {
                HStack {
                    Spacer()
                    MoonPhaseLegend()
                }
            }
        } else if !monthHolidays.isEmpty {
            HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Holidays this month")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.muted)
                    ForEach(monthHolidays) { h in
                        HolidayRow(date: h.date, name: h.name, isFocused: false)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if showMoonLegend {
                    MoonPhaseLegend()
                }
            }
        } else {
            HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
                Text("No public holidays this month")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if showMoonLegend {
                    MoonPhaseLegend()
                }
            }
        }
    }

    // MARK: - Helpers

    /// Locale-stable calendar used for grid math. `Monday`-first to
    /// match Android. Not IST — the grid renders in the device's
    /// local zone (the panchanga lookups still use the IST date
    /// string via the VM's `isoDate` formatter).
    private static let localCalendar: Calendar = {
        var cal = Calendar(identifier: .gregorian)
        cal.firstWeekday = 2 // Monday
        return cal
    }()

    private static let weekdayInitials = ["M", "T", "W", "T", "F", "S", "S"]

    private static let monthYearFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "LLLL yyyy"
        return f
    }()

    private func monthHeaderText(_ ym: YearMonth) -> String {
        let date = ym.firstDay(in: Self.localCalendar)
        return Self.monthYearFormatter.string(from: date)
    }
}

// MARK: - DayCell

private struct DayCell: View {
    let day: Int
    let isToday: Bool
    let isSelected: Bool
    let isHoliday: Bool
    let isWeekend: Bool
    let isOutsideMonth: Bool
    let hasEvent: Bool
    let hasCapture: Bool
    let isNewMoon: Bool
    let isFullMoon: Bool

    var body: some View {
        // Two festival signals at play:
        //   - `isHoliday`  → Indian government holiday set. Carries a
        //                    coral RING so the date reads as a public
        //                    calendar event at a glance.
        //   - `hasEvent`   → panchanga `specialDay` row. Carries a
        //                    coral DOT under the number; no ring.
        // Both push the foreground text to coral via `isFestiveDay`
        // so the cell colour stays in sync with the bulleted list
        // below the grid regardless of which signal triggered.
        let isFestiveDay = isHoliday || hasEvent

        // Ring color priority: selected > today > holiday > nothing.
        let ringColor: Color = {
            if isOutsideMonth { return .clear }
            if isSelected     { return QuickInkColors.accentDeep }
            if isToday        { return QuickInkColors.success }
            if isHoliday      { return QuickInkColors.accent }
            return .clear
        }()
        let ringWidth: CGFloat = (isSelected && !isOutsideMonth) ? 2.5 : 2.0

        // Foreground priority — for in-month: today > festive >
        // selected > weekend > default. Outside-month: muted by
        // default, but outside-month festive renders in `warning`
        // (yellow) so the user can still spot upcoming / past
        // festive days in spillover rows.
        let fg: Color = {
            if isOutsideMonth && isFestiveDay { return QuickInkColors.warning }
            if isOutsideMonth                 { return QuickInkColors.muted }
            if isToday                        { return QuickInkColors.success }
            if isFestiveDay                   { return QuickInkColors.accent }
            if isSelected                     { return QuickInkColors.accentDeep }
            if isWeekend                      { return QuickInkColors.danger }
            return QuickInkColors.ink
        }()
        let emphasised = !isOutsideMonth && (isToday || isSelected || isFestiveDay || hasCapture)

        // Event indicator dot — picks up the cell's fg color so a
        // festive day's coral dot reads against the same color the
        // number is painted in.
        let showDot = hasEvent || hasCapture
        let dotColor: Color = {
            if !showDot                    { return .clear }
            if isOutsideMonth              { return QuickInkColors.muted }
            if isToday                     { return QuickInkColors.success }
            return QuickInkColors.accent
        }()

        ZStack {
            // Number inside the optional ring.
            ZStack {
                Circle()
                    .strokeBorder(ringColor, lineWidth: ringWidth)
                    .frame(width: 30, height: 30)
                Text("\(day)")
                    .font(.system(size: 13, weight: emphasised ? .bold : .regular))
                    .foregroundStyle(fg)
            }

            // Capture / event dot — 4pt circle 8pt below the number.
            if showDot {
                Circle()
                    .fill(dotColor)
                    .frame(width: 4, height: 4)
                    .offset(y: 13)
            }

            // Moon-phase glyph — small filled disc in the top-right.
            if isNewMoon || isFullMoon {
                let moonFill = isFullMoon ? Color.white : QuickInkColors.ink
                let moonBorder = isFullMoon ? QuickInkColors.ink : QuickInkColors.inkSoft
                Circle()
                    .fill(moonFill)
                    .frame(width: 7, height: 7)
                    .overlay(Circle().strokeBorder(moonBorder, lineWidth: 0.7))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    .padding(.top, 2)
                    .padding(.trailing, 2)
            }
        }
    }
}

// MARK: - HolidayRow

private struct HolidayRow: View {
    let date: Date
    let name: String
    let isFocused: Bool

    var body: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Circle()
                .fill(QuickInkColors.accent)
                .frame(width: 6, height: 6)
            Text("\(dayNumber)")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(QuickInkColors.ink)
                .frame(width: 20, alignment: .leading)
            Text(name)
                .font(isFocused ? QuickInkText.body : QuickInkText.meta)
                .foregroundStyle(isFocused ? QuickInkColors.ink : QuickInkColors.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var dayNumber: Int {
        Calendar.current.component(.day, from: date)
    }
}

// MARK: - MoonPhaseLegend

private struct MoonPhaseLegend: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            row(fill: .white, border: QuickInkColors.ink, label: "Full moon")
            row(fill: QuickInkColors.ink, border: QuickInkColors.inkSoft, label: "New moon")
        }
    }

    @ViewBuilder
    private func row(fill: Color, border: Color, label: String) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(fill)
                .frame(width: 7, height: 7)
                .overlay(Circle().strokeBorder(border, lineWidth: 0.7))
            Text(label)
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.muted)
        }
    }
}
