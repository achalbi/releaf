import SwiftUI
import ReleafDesignSystem

// MARK: - TodayHero

/// The dark-green hero card for "today". Hosts:
///   - header (date / page count)
///   - title + page-indicator pill
///   - a paged carousel of `Inset` views (one per page + a trailing new-entry slot)
///   - capture pips
///   - day timeline
///   - CTA footer
struct TodayHero: View {

    let day: RecentsDay?
    /// Tap the footer CTA when on a real page.
    var onOpenPage: (RecentsPage) -> Void = { _ in }
    /// Fires for the new-entry slot's picker cells (Photo / Scan /
    /// Voice / Todo / Contact) and for the footer "Add a page" CTA
    /// when on the trailing slot. The host wires this to whatever
    /// "compose new" routing it has — typically opening the editor;
    /// once the editor learns to focus a specific tab, the
    /// `CaptureMode` picked here is the right thing to forward.
    var onPickMode: (CaptureMode) -> Void = { _ in }
    /// Total carousel slots = page count + 1 (always a trailing new-entry slot).
    @State private var selection: Int = 0

    /// Drives the carousel's entrance peek. The carousel nudges
    /// `peekDistance` to the right on appear and springs back to 0,
    /// hinting that the inner pager swipes between pages while the
    /// hero card's frame (background, header, footer, etc.) stays
    /// anchored. Render-only via `.offset(x:)`, so the carousel's
    /// own scroll state is untouched.
    @State private var carouselPeek: CGFloat = 0
    private let peekDistance: CGFloat = 36

    private var pageCount: Int { day?.pages.count ?? 0 }
    private var totalSlots: Int { pageCount + 1 }
    private var isNewSlotActive: Bool { selection == pageCount }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "h:mm a"
        return f
    }()

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        // Bullet separator inside the date so the header reads
        // "SUN · APR 26 · 8:15 PM" — matches the reference design.
        f.dateFormat = "EEE ' · ' MMM d"
        return f
    }()

    /// Inverted = outline-style hero. True when the carousel is on the
    /// trailing new-entry slot. All chrome inside flips to dark green
    /// on cream so the new-entry slot reads as an outlined card.
    private var inverted: Bool { isNewSlotActive }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            titleRow
            carousel
            pipsRow
            timeline
            footer
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(inverted ? Color.bgCanvas : Color.green800)
        )
        .overlay(
            // Outline-style: 1.5pt green border when inverted; no
            // border on the regular dark-green card.
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(
                    inverted ? Color.green800 : Color.clear,
                    lineWidth: 1.5
                )
        )
        // Tap-anywhere-to-open. Mirrors the footer CTA's behaviour
        // so the whole card is the affordance: real page → open it;
        // new-entry slot → open the editor at its default tab. The
        // carousel's horizontal-swipe gesture and child Buttons
        // (picker cells, footer button) all win over this — they
        // consume their own gestures before this fires.
        .contentShape(Rectangle())
        .onTapGesture(perform: handleFooterTap)
        .onAppear {
            // Default selection: latest page if there is one, else the new slot.
            selection = max(0, pageCount - 1)
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(headerLabel)
                .font(Typography.microFont)
                .kerning(1.0)
                .foregroundColor(inverted ? .textGreenMuted : .textOnDarkSubtle)
            Spacer()
            // Right rail: the active page's category (Home / Work /
            // Recipes / Personal). New-entry slot stays as "new".
            // Pages with no tag drop the label entirely.
            if let label = rightHeaderLabel {
                Text(label)
                    .font(Typography.captionFont)
                    .foregroundColor(inverted ? .green600 : .textOnDarkMuted)
            }
        }
    }

    private var rightHeaderLabel: String? {
        if isNewSlotActive { return "new" }
        let tag = activePage?.tags.first?.label.trimmingCharacters(in: .whitespacesAndNewlines)
        return (tag?.isEmpty == false) ? tag : nil
    }

    private var headerLabel: String {
        guard let day else { return "TODAY" }
        let datePart = TodayHero.dateFormatter.string(from: day.date).uppercased()
        if let active = activePage {
            // The system locale renders "AM/PM" lowercase on some devices;
            // force uppercase so the eyebrow reads consistently.
            let time = TodayHero.timeFormatter.string(from: active.createdAt).uppercased()
            return "\(datePart) · \(time)"
        }
        return datePart
    }

    private var activePage: RecentsPage? {
        guard let pages = day?.pages, !pages.isEmpty, selection < pages.count else { return nil }
        return pages[selection]
    }

    // MARK: Title + pill

    private var titleRow: some View {
        HStack(alignment: .firstTextBaseline) {
            // The big serif heading tracks the active page so it always
            // names what you're looking at. On the new-entry slot the
            // title is intentionally hidden — the inset's picker cells
            // are the focus there, so we just render the indicator pill.
            if !isNewSlotActive, let label = titleLabel {
                Text(label)
                    .font(Typography.h2Font)
                    .foregroundColor(inverted ? .green800 : .textOnDark)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
            PageIndicatorPill(
                pageCount: pageCount,
                selection: selection,
                isNewSlot: isNewSlotActive,
                inverted: inverted
            )
        }
    }

    private var titleLabel: String? {
        if let active = activePage {
            let t = active.title.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { return t }
        }
        let theme = (day?.theme ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return theme.isEmpty ? "today" : theme
    }

    // MARK: Carousel

    private var carousel: some View {
        TabView(selection: $selection) {
            if let pages = day?.pages {
                ForEach(Array(pages.enumerated()), id: \.offset) { idx, page in
                    PageInset(page: page)
                        .padding(.horizontal, 2)
                        .tag(idx)
                }
            }
            NewEntryInset(onPickMode: onPickMode, inverted: inverted)
                .padding(.horizontal, 2)
                .tag(pageCount)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: insetHeight)
        .offset(x: carouselPeek)
        .task {
            // Peek the carousel on arrival to hint at horizontal
            // swipe. The frame around it (header, title, pips,
            // timeline, footer) stays put.
            withAnimation(.easeOut(duration: 0.28)) {
                carouselPeek = peekDistance
            }
            try? await Task.sleep(nanoseconds: 280_000_000)
            withAnimation(.spring(response: 0.55, dampingFraction: 0.62)) {
                carouselPeek = 0
            }
        }
    }

    private var insetHeight: CGFloat {
        // Media-bearing insets are tallest. Use a fixed comfortable height that
        // accommodates either media or the new-entry slot.
        260
    }

    // MARK: Pips

    private var pipsRow: some View {
        // One pip per non-zero capture surface on the *active page*
        // — swiping the carousel updates the row. The new-entry slot
        // has no live page, so the row collapses to nothing there.
        // The six attachment-style surfaces use `CaptureMode.systemIcon`
        // so the row reads as one family with the editor's tab bar
        // and the picker cells. The trailing `notes` pip uses
        // `note.text` since notes is the page's body, not a
        // picker-cell surface.
        let counts = activePage?.captureCounts ?? CaptureCounts()
        return HStack(spacing: 8) {
            if counts.photos    > 0 { CapturePip(systemName: CaptureMode.photos.systemIcon,   count: counts.photos,    inverted: inverted) }
            if counts.scans     > 0 { CapturePip(systemName: CaptureMode.scans.systemIcon,    count: counts.scans,     inverted: inverted) }
            if counts.voice     > 0 { CapturePip(systemName: CaptureMode.voice.systemIcon,    count: counts.voice,     inverted: inverted) }
            if counts.todos     > 0 { CapturePip(systemName: CaptureMode.todo.systemIcon,     count: counts.todos,     inverted: inverted) }
            if counts.contacts  > 0 { CapturePip(systemName: CaptureMode.contacts.systemIcon, count: counts.contacts,  inverted: inverted) }
            if counts.locations > 0 { CapturePip(systemName: CaptureMode.location.systemIcon, count: counts.locations, inverted: inverted) }
            if counts.notes     > 0 { CapturePip(systemName: "note.text",                     count: counts.notes,     inverted: inverted) }
            Spacer()
        }
    }

    // MARK: Timeline

    private var timeline: some View {
        let anchor: Color = inverted ? .textGreenMuted : .textOnDarkSubtle
        return HStack(spacing: 6) {
            Text("12a")
                .font(Typography.microWideFont)
                .foregroundColor(anchor)
            DayTimeline(
                pages: day?.pages ?? [],
                activeIndex: selection,
                isNewSlotActive: isNewSlotActive,
                inverted: inverted
            )
            .frame(maxWidth: .infinity)
            .frame(height: 28)
            Text("12a")
                .font(Typography.microWideFont)
                .foregroundColor(anchor)
        }
    }

    // MARK: Footer

    private var footer: some View {
        let dividerColor: Color = inverted ? .green200 : .onDark25
        let labelColor:   Color = inverted ? .green800 : .textOnDark
        let buttonBg:     Color = inverted ? .green800 : .onDark16
        let buttonFg:     Color = inverted ? .bgCanvas : .textOnDark
        return VStack(spacing: 12) {
            Rectangle()
                .fill(dividerColor)
                .frame(height: 1)
            // Whole row is the tap target — Open page X / Add a page.
            // Per the typography brief we use medium, not semibold/bold.
            Button(action: handleFooterTap) {
                HStack {
                    Text(footerLabel)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(labelColor)
                    Spacer()
                    ZStack {
                        // Both colour roles flip together so the button
                        // stays legible against the surrounding hero
                        // card in either palette.
                        Circle()
                            .fill(buttonBg)
                            .frame(width: 32, height: 32)
                        Image(systemName: isNewSlotActive ? "plus" : "arrow.right")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(buttonFg)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var footerLabel: String {
        if isNewSlotActive {
            return "Add a page"
        }
        return "Open page \(selection + 1)"
    }

    private func handleFooterTap() {
        if isNewSlotActive {
            // No specific tab for the footer CTA — let the host open
            // the editor at its default. The 5 picker chips fire
            // onPickMode with their own CaptureMode for tab-specific
            // routing.
            onPickMode(.overview)
        } else if let page = activePage {
            onOpenPage(page)
        }
    }
}

// MARK: - PageIndicatorPill

struct PageIndicatorPill: View {
    let pageCount: Int
    let selection: Int
    let isNewSlot: Bool
    /// Inverted = outline-style hero (dark green on cream). The pill
    /// flips to a light green tint with dark green dots / label.
    var inverted: Bool = false

    /// Cap the rendered dot count. A long day (e.g. 16 pages) would
    /// otherwise stretch the pill past its available width and force
    /// the label to wrap one character per line. Above the cap we
    /// drop the dots and let "X of N" carry the indicator alone.
    private static let maxRenderedDots: Int = 6
    private var showDots: Bool {
        pageCount >= 1 && pageCount <= Self.maxRenderedDots
    }

    var body: some View {
        HStack(spacing: 6) {
            if showDots || isNewSlot {
                HStack(spacing: 4) {
                    if showDots {
                        ForEach(0..<pageCount, id: \.self) { i in
                            Circle()
                                .fill(dotColor(at: i))
                                .frame(width: 5, height: 5)
                        }
                    }
                    // The trailing dot only appears on the new-entry slot.
                    if isNewSlot {
                        Circle()
                            .fill(inverted ? Color.green800 : Color.bgCanvas)
                            .frame(width: 6, height: 6)
                    }
                }
            }
            Text(pillLabel)
                .font(Typography.microWideFont)
                .kerning(0.6)
                .foregroundColor(labelColor)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(
            Capsule(style: .continuous)
                .fill(inverted ? Color.green100 : Color.onDark16)
        )
    }

    private var labelColor: Color {
        if inverted { return isNewSlot ? .green800 : .green600 }
        return isNewSlot ? .bgCanvas : .textOnDarkMuted
    }

    private func dotColor(at index: Int) -> Color {
        // Fill 1→active position
        let filled = index <= selection
        if inverted {
            return filled ? .green800 : .green200
        }
        return filled ? .textOnDark : .onDark30
    }

    private var pillLabel: String {
        if isNewSlot { return "new" }
        let n = max(1, pageCount)
        return "\(min(selection + 1, n)) of \(n)"
    }
}

// MARK: - PageInset (media + text variants combined)

/// The hero's inset card — description-only for every capture type.
/// The page title rides up into the hero's title row (so the big
/// serif heading tracks the active page); the day-level capture pip
/// row below this inset signals the type mix; the 16:9 media tile
/// was removed so a photo / scan page renders the same chrome as a
/// journal / voice / mood page. Falls back to the title when the
/// page has no description so the inset never reads as empty.
struct PageInset: View {
    let page: RecentsPage

    var body: some View {
        let text: String = {
            let d = page.description.trimmingCharacters(in: .whitespacesAndNewlines)
            return d.isEmpty ? page.title : d
        }()
        return Text(text)
            .font(Typography.bodySmallFont)
            .foregroundColor(.textOnDarkMuted)
            .lineLimit(5)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(Color.onDark10)
            )
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

// MARK: - NewEntryInset

struct NewEntryInset: View {
    /// Optional capture-mode tap handler. Each cell maps to a
    /// PageDetails tab; the host wires this to whatever editor-open
    /// routing it has. Defaults to a no-op so previews still render.
    var onPickMode: (CaptureMode) -> Void = { _ in }
    /// Inverted = outline-style hero. Drops the cream-on-dark
    /// treatment in favour of dark-green-on-cream so the inset
    /// matches the surrounding outlined card.
    var inverted: Bool = false

    var body: some View {
        let insetBg: Color    = inverted ? Color.green100.opacity(0.5) : .onDark10
        let dashed: Color     = inverted ? .green800 : Color.bgCanvas.opacity(0.55)
        let dashedAlpha: Double = inverted ? 0.65 : 1.0
        let eyebrow: Color    = inverted ? .green800 : .bgCanvas
        let microcopy: Color  = inverted ? .green600 : .textOnDarkMuted
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Text("NEW ENTRY")
                    .font(Typography.microWideFont)
                    .kerning(1.4)
                    .foregroundColor(eyebrow)
                Spacer()
            }

            HStack(spacing: 8) {
                // Five PageDetails-tab shortcuts. Symbols come from the
                // canonical CaptureMode.systemIcon — same glyphs used by
                // the page editor's tab bar — so the picker reads as
                // one family with the existing UI.
                pickerCell(mode: .photos,   label: "Photo")
                pickerCell(mode: .scans,    label: "Scan")
                pickerCell(mode: .voice,    label: "Voice")
                pickerCell(mode: .todo,     label: "Todo")
                pickerCell(mode: .contacts, label: "Contact")
            }

            Text("Tap any type to plant a new page in today's garden")
                .font(Typography.captionFont)
                .foregroundColor(microcopy)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .fill(insetBg)
        )
        .overlay(
            // Dashed border — cream on the solid hero, dark green on
            // the outlined hero. Both at low alpha for a soft outline.
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(
                    dashed.opacity(dashedAlpha),
                    style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                )
        )
    }

    @ViewBuilder
    private func pickerCell(
        mode: CaptureMode,
        label: String
    ) -> some View {
        // Two palettes:
        //   - solid hero (inverted=false): cream icon/label on
        //     translucent cream cell — pops against the dark hero.
        //   - outlined hero (inverted=true): cream icon/label on dark
        //     green cell — pops against the cream outlined hero.
        let cellBg: Color = inverted ? .green800 : .onDark10
        let icon: Color   = inverted ? .bgCanvas : .textOnDark
        let text: Color   = inverted ? Color.bgCanvas.opacity(0.85) : .textOnDarkMuted
        Button {
            onPickMode(mode)
        } label: {
            VStack(spacing: 6) {
                Image(systemName: mode.systemIcon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(icon)
                Text(label)
                    .font(Typography.microFont)
                    .foregroundColor(text)
            }
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(cellBg)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - CapturePip

struct CapturePip: View {
    let systemName: String
    let count: Int
    var inverted: Bool = false

    var body: some View {
        let bg: Color  = inverted ? .green100 : .onDark16
        let fg: Color  = inverted ? .green800 : .textOnDark
        let count_: Color = inverted ? .green600 : .textOnDarkMuted
        return HStack(spacing: 5) {
            Image(systemName: systemName)
                .font(.system(size: 10, weight: .medium))
                .foregroundColor(fg)
            Text("\(count)")
                .font(Typography.captionFont)
                .foregroundColor(count_)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(bg))
    }
}

// MARK: - DayTimeline

struct DayTimeline: View {
    let pages: [RecentsPage]
    let activeIndex: Int
    let isNewSlotActive: Bool
    var inverted: Bool = false

    private static let calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC") ?? c.timeZone
        return c
    }()

    var body: some View {
        let trackColor: Color = inverted ? .green200 : .onDark14
        let activeDot: Color  = inverted ? .green800 : .textOnDark
        let inactiveDot: Color = inverted ? Color.green400.opacity(0.55) : Color.textOnDarkSubtle.opacity(0.85)
        let newDot: Color     = inverted ? .green800 : .bgCanvas
        return GeometryReader { geo in
            let width = geo.size.width
            let h = geo.size.height
            let barY = h / 2
            ZStack(alignment: .leading) {
                // Track — thicker per the latest design notes (was 2pt).
                Capsule()
                    .fill(trackColor)
                    .frame(height: 4)
                    .position(x: width / 2, y: barY)

                // Page dots
                ForEach(Array(pages.enumerated()), id: \.offset) { idx, page in
                    let xRel = ratio(for: page.createdAt)
                    let isActive = (idx == activeIndex && !isNewSlotActive)
                    let size: CGFloat = isActive ? 9 : 5
                    Circle()
                        .fill(isActive ? activeDot : inactiveDot)
                        .frame(width: size, height: size)
                        .position(x: max(size/2, min(width - size/2, width * xRel)), y: barY)
                }

                // Trailing dot for the new-entry slot.
                let newSize: CGFloat = isNewSlotActive ? 9 : 6
                Circle()
                    .fill(newDot)
                    .frame(width: newSize, height: newSize)
                    .position(x: width - newSize/2, y: barY)
            }
        }
    }

    /// Ratio along the full 24-hour day axis (12am → next 12am).
    private func ratio(for date: Date) -> CGFloat {
        let comps = DayTimeline.calendar.dateComponents([.hour, .minute], from: date)
        let minutes = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
        let total = 24 * 60
        return CGFloat(min(total, max(0, minutes))) / CGFloat(total)
    }
}

// MARK: - Previews

#if DEBUG
#Preview("With pages") {
    TodayHero(day: MockData.today)
        .padding()
        .background(Color.bgCanvas)
}

#Preview("Empty day") {
    TodayHero(day: nil)
        .padding()
        .background(Color.bgCanvas)
}
#endif
