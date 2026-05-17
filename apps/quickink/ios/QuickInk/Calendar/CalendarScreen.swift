/*
 * CalendarScreen.swift
 *
 * QuickInk's full-screen calendar surface, pushed via the home
 * header's calendar button. Three vertical zones:
 *
 *   1. Top bar — back chevron, "Calendar" title, "Today" pill.
 *   2. Festival search — collapsible TextField; results render
 *      below as a typeahead dropdown when the query is non-empty.
 *   3. Calendar grid (CalendarPanel) + panchanga detail card for the
 *      selected date + a list of QuickInk captures on that day +
 *      monthly festival list + about-this-data note.
 *
 * The grid carries small dots under any date that has a non-empty
 * `specialDay` in the bundled Vontikoppal panchanga, plus a separate
 * accent dot for dates with at least one QuickInk capture. The
 * detail card shows masa / paksha / thithi / rahu kala / sunrise /
 * sunset for the selected date, or a "data not available" placeholder
 * when the date is outside the dataset.
 *
 * Port of Releaf Android's `CalendarScreen.kt`, with releaf-only
 * surfaces (LeafEyebrow) swapped for QuickInk's editorial header and
 * an added captures list below the panchanga card.
 */

import SwiftUI

public struct CalendarScreen: View {

    public let userId: String
    public let onBack: () -> Void
    public let onOpenCapture: (String) -> Void

    @StateObject private var vm: CalendarViewModel
    @FocusState private var searchFocused: Bool

    public init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenCapture: @escaping (String) -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenCapture = onOpenCapture
        _vm = StateObject(wrappedValue: CalendarViewModel(userId: userId))
    }

    public var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                    FestivalSearchField(
                        query: Binding(get: { vm.searchQuery }, set: { vm.setSearchQuery($0) }),
                        onClear: { vm.clearSearch() },
                        focused: $searchFocused
                    )

                    if !vm.searchQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                        SearchResultsSection(
                            query: vm.searchQuery,
                            results: vm.searchResults,
                            onSelect: { date in
                                vm.selectDate(date)
                                vm.clearSearch()
                                searchFocused = false
                            }
                        )
                    }

                    CalendarPanel(
                        visibleMonth: $vm.visibleMonth,
                        selectedDate: $vm.selectedDate,
                        eventDates: eventDateSet,
                        newMoonDates: newMoonDateSet,
                        fullMoonDates: fullMoonDateSet,
                        captureDates: captureDateSet
                    )

                    SelectedDayCard(
                        date: vm.selectedDate,
                        rows: vm.selectedDayPanchanga
                    )

                    let captures = vm.selectedDayCaptures
                    if !captures.isEmpty {
                        SelectedDayCapturesList(
                            captures: captures,
                            onOpen: onOpenCapture
                        )
                    }

                    let monthFestivals = vm.visibleMonthPanchanga.filter {
                        !$0.specialDay.trimmingCharacters(in: .whitespaces).isEmpty
                    }
                    if !monthFestivals.isEmpty {
                        MonthFestivalList(rows: monthFestivals, onTap: { date in
                            vm.selectDate(date)
                        })
                    }

                    AboutPanchanga()
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s8)
            }
        }
        .background(QuickInkColors.bg.ignoresSafeArea())
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack(alignment: .center, spacing: QuickInkSpacing.s2) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back")

            VStack(alignment: .leading, spacing: 2) {
                Text("QUICKINK · CALENDAR")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Text("Calendar")
                    .font(QuickInkText.pageTitle)
                    .foregroundStyle(QuickInkColors.ink)
            }

            Spacer()

            Button {
                vm.goToToday()
            } label: {
                Text("Today")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(QuickInkColors.accentDeep)
                    .padding(.horizontal, QuickInkSpacing.s3)
                    .padding(.vertical, 6)
                    .background(QuickInkColors.accentSoft)
                    .overlay(
                        Capsule().stroke(QuickInkColors.accent, lineWidth: 1)
                    )
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Go to today")
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        // Extra breathing room above the back chevron + title so the
        // top bar doesn't kiss the dynamic-island / status-bar zone.
        // SwiftUI already insets us for the safe area; this is the
        // additional breathing room on top of that.
        .padding(.top, QuickInkSpacing.s5)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    // MARK: - Date set helpers

    private var eventDateSet: Set<Date> {
        let cal = Calendar(identifier: .gregorian)
        return Set(vm.visibleMonthPanchanga
            .filter { !$0.specialDay.trimmingCharacters(in: .whitespaces).isEmpty }
            .compactMap { parseIsoDate($0.date) }
            .map { cal.startOfDay(for: $0) })
    }

    private var newMoonDateSet: Set<Date> {
        let cal = Calendar(identifier: .gregorian)
        return Set(vm.adjacentMonthsPanchanga
            .filter { $0.thithi.caseInsensitiveCompare("Amavasya") == .orderedSame }
            .compactMap { parseIsoDate($0.date) }
            .map { cal.startOfDay(for: $0) })
    }

    private var fullMoonDateSet: Set<Date> {
        let cal = Calendar(identifier: .gregorian)
        return Set(vm.adjacentMonthsPanchanga
            .filter { $0.thithi.caseInsensitiveCompare("Purnima") == .orderedSame }
            .compactMap { parseIsoDate($0.date) }
            .map { cal.startOfDay(for: $0) })
    }

    private var captureDateSet: Set<Date> {
        let cal = Calendar(identifier: .gregorian)
        return Set(vm.capturesByDate.keys
            .compactMap { parseIsoDate($0) }
            .map { cal.startOfDay(for: $0) })
    }

    private static let isoDateOnly: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .iso8601)
        f.timeZone = TimeZone.current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    private func parseIsoDate(_ s: String) -> Date? {
        Self.isoDateOnly.date(from: s)
    }
}

// MARK: - Festival search field

private struct FestivalSearchField: View {
    @Binding var query: String
    let onClear: () -> Void
    var focused: FocusState<Bool>.Binding

    var body: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 14))
                .foregroundStyle(QuickInkColors.muted)
            TextField("Search festivals", text: $query)
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.ink)
                .tint(QuickInkColors.accent)
                .focused(focused)
                .submitLabel(.search)
            if !query.isEmpty {
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(QuickInkColors.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, QuickInkSpacing.s2)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
    }
}

// MARK: - Selected-day panchanga card

private struct SelectedDayCard: View {
    let date: Date
    let rows: [PanchangaEntity]

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Self.longDateFormatter.string(from: date).uppercased())
                        .font(QuickInkText.eyebrow)
                        .tracking(QuickInkLetterSpacing.eyebrow)
                        .foregroundStyle(QuickInkColors.accentDeep)
                    Text(Self.serifDateFormatter.string(from: date))
                        .font(QuickInkFont.serif(22, weight: .regular))
                        .foregroundStyle(QuickInkColors.ink)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text("Panchanga")
                    .font(QuickInkFont.serif(16, weight: .regular, italic: true))
                    .foregroundStyle(QuickInkColors.accent)
            }
            Spacer().frame(height: 2)

            if rows.isEmpty {
                Text("Panchanga data not available for this date.")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text("The bundled Vontikoppal dataset covers 2026-03-19 to 2027-04-06.")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.muted)
            } else {
                ForEach(rows, id: \.id) { row in
                    PanchangaRowView(row: row, date: date)
                }
            }
        }
        .padding(QuickInkSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
    }

    private static let longDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MMM d, yyyy"
        return f
    }()

    private static let serifDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEEE, d MMMM"
        return f
    }()
}

private struct PanchangaRowView: View {
    let row: PanchangaEntity
    let date: Date

    var body: some View {
        let rahuKala = rahuKalaFor(date)
        let sun = sunriseSunsetFor(date)
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            HStack(spacing: QuickInkSpacing.s3) {
                DetailField(label: "Masa",   value: row.masa)
                DetailField(label: "Paksha", value: row.paksha)
            }
            HStack(spacing: QuickInkSpacing.s3) {
                DetailField(
                    label: "Thithi",
                    value: row.thithi + " (" + row.thithiNum + ")"
                )
                DetailField(
                    label: "Rahu Kala",
                    value: rahuKala,
                    valueColor: QuickInkColors.accent
                )
            }
            HStack(spacing: QuickInkSpacing.s3) {
                DetailField(label: "Sunrise", value: sun.sunrise)
                DetailField(label: "Sunset",  value: sun.sunset)
            }
            if !row.specialDay.trimmingCharacters(in: .whitespaces).isEmpty {
                HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
                    Circle()
                        .fill(QuickInkColors.accent)
                        .frame(width: 6, height: 6)
                        .padding(.top, 6)
                    Text(row.specialDay)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                }
            }
        }
    }
}

private struct DetailField: View {
    let label: String
    let value: String
    var valueColor: Color = QuickInkColors.ink

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased())
                .font(QuickInkText.caption)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)
            Text(value)
                .font(QuickInkText.body)
                .foregroundStyle(valueColor)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Selected-day captures list (QuickInk-specific)

private struct SelectedDayCapturesList: View {
    let captures: [CaptureSummary]
    let onOpen: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("Scans on this day")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            VStack(spacing: QuickInkSpacing.s2) {
                ForEach(captures) { capture in
                    Button {
                        onOpen(capture.id)
                    } label: {
                        CaptureRow(capture: capture)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(QuickInkSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
    }
}

private struct CaptureRow: View {
    let capture: CaptureSummary

    var body: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            thumbnail
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )

            VStack(alignment: .leading, spacing: 2) {
                Text(displayTitle.capitalized)
                    .font(QuickInkText.cardTitle)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                HStack(spacing: 4) {
                    Text({
                            switch capture.source {
                            case "import": return "Import"
                            case "photo":  return "Photo"
                            default:       return "Scan"
                            }
                        }())
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.accentDeep)
                    Text("·")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                    Text(capture.pageCount == 1 ? "1 page" : "\(capture.pageCount) pages")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(QuickInkColors.muted)
        }
        .padding(.vertical, QuickInkSpacing.s1)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let image = loadedImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
        } else {
            ZStack {
                QuickInkColors.paper2
                Image(systemName: "doc.text.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(QuickInkColors.muted)
            }
        }
    }

    private var loadedImage: UIImage? {
        guard let raw = capture.previewUri, !raw.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: raw), url.isFileURL { return url.path }
            return raw
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    private var displayTitle: String {
        let trimmed = (capture.title ?? "").trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? "Scan" : trimmed
    }
}

// MARK: - Month festival list

private struct MonthFestivalList: View {
    let rows: [PanchangaEntity]
    let onTap: (Date) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("Festivals & observances")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Spacer().frame(height: 2)
            ForEach(rows, id: \.id) { row in
                Button {
                    if let date = isoDateParser.date(from: row.date) {
                        onTap(date)
                    }
                } label: {
                    FestivalRow(row: row)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(QuickInkSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
    }

    private var isoDateParser: DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .iso8601)
        f.dateFormat = "yyyy-MM-dd"
        return f
    }
}

private struct FestivalRow: View {
    let row: PanchangaEntity

    var body: some View {
        HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
            Circle()
                .fill(QuickInkColors.accent)
                .frame(width: 6, height: 6)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.specialDay)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Text(buildSubtitle())
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private func buildSubtitle() -> String {
        var parts: [String] = []
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.calendar = Calendar(identifier: .iso8601)
        parser.dateFormat = "yyyy-MM-dd"
        let display = DateFormatter()
        display.locale = Locale(identifier: "en_US_POSIX")
        display.dateFormat = "d MMM"
        if let d = parser.date(from: row.date) {
            parts.append(display.string(from: d))
        }
        parts.append("\(row.masa) \(row.paksha) \(row.thithi)")
        return parts.joined(separator: " · ")
    }
}

// MARK: - Search results

private struct SearchResultsSection: View {
    let query: String
    let results: [PanchangaEntity]
    let onSelect: (Date) -> Void

    private let resultCap: Int = 20

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("Results for \u{201C}\(query)\u{201D}")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            if results.isEmpty {
                Text("No festivals match.")
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.muted)
            } else {
                let capped = Array(results.prefix(resultCap))
                ForEach(capped, id: \.id) { row in
                    Button {
                        if let d = isoDateParser.date(from: row.date) {
                            onSelect(d)
                        }
                    } label: {
                        SearchResultRow(row: row)
                    }
                    .buttonStyle(.plain)
                }
                if results.count > resultCap {
                    Text("\(results.count - resultCap) more matches — refine the query to narrow.")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.muted)
                }
            }
        }
        .padding(QuickInkSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
    }

    private var isoDateParser: DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .iso8601)
        f.dateFormat = "yyyy-MM-dd"
        return f
    }
}

private struct SearchResultRow: View {
    let row: PanchangaEntity

    var body: some View {
        HStack(alignment: .top, spacing: QuickInkSpacing.s2) {
            Circle()
                .fill(QuickInkColors.accent)
                .frame(width: 6, height: 6)
                .padding(.top, 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.specialDay)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                Text(subtitle)
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    private var subtitle: String {
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.calendar = Calendar(identifier: .iso8601)
        parser.dateFormat = "yyyy-MM-dd"
        let display = DateFormatter()
        display.locale = Locale(identifier: "en_US_POSIX")
        display.dateFormat = "d MMM"
        var bits: [String] = []
        if let d = parser.date(from: row.date) {
            bits.append(display.string(from: d))
        }
        bits.append("\(row.masa) \(row.paksha) \(row.thithi)")
        return bits.joined(separator: " · ")
    }
}

// MARK: - About

private struct AboutPanchanga: View {
    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
            Text("ABOUT THIS DATA")
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)
            Text("Panchanga data derived from the printed Ontikoppal Panchanga, published by Ontikoppal Panchanga Mandira, Mysore. OCR-derived and unofficial; verify important ritual dates against the original.")
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
        .padding(.top, QuickInkSpacing.s2)
        .padding(.bottom, QuickInkSpacing.s2)
    }
}
