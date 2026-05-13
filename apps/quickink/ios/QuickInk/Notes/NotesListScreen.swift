/*
 * NotesListScreen.swift
 *
 * QuickInk's Library — the user's full scan gallery, redesigned to
 * match the editorial mock (lined-paper note cards, handwritten
 * preview snippets, date-bucketed sections).
 *
 * Visual structure (per mock):
 *   • Header — "Library" serif title, no back chevron (Library is
 *     a tab destination, accessed via the bottom-nav `Library` cell).
 *     Trailing controls live in a single pill: a sort affordance
 *     (up/down arrows menu) on the left, a grid/list segmented
 *     toggle on the right with the active half painted coral.
 *   • Sub-meta — `{count} notes` line under the title. Synced-bytes
 *     readout is a TODO (sums attachment file sizes; deferred).
 *   • Category chips — horizontal scroll, "All" leading, no count
 *     badges. Inactive chips are outlined white pills; active is
 *     filled coral.
 *   • Date sections — captures bucketed by relative day: TODAY /
 *     THIS WEEK / EARLIER. Each section renders an eyebrow header
 *     and a 2-column grid of cards.
 *   • Card design — paper-toned upper portion with lined ruling and
 *     a handwritten Caveat preview snippet (lazy-loaded OCR), small
 *     page-count chip in the top-right; white footer with serif
 *     title + accent-soft category tag + relative date.
 *
 * Mirror of Android `NotesListScreen.kt` (which still tracks the
 * older grid/list layout — Android redesign lands separately).
 */

import SwiftUI
import Combine
import GRDB

struct NotesListScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenScan: (_ captureId: String) -> Void
    /// Tab navigation callbacks for the floating bottom nav. The
    /// Library tab paints itself active; tapping it is a no-op
    /// (we're already here). The other callbacks switch tabs at the
    /// route level — see QuickInkRoot's tab wiring.
    let onHome: () -> Void
    let onWorkspace: () -> Void
    let onScan: () -> Void
    let onSearch: () -> Void
    let onSettings: () -> Void

    @StateObject private var capturesVM:   CaptureListViewModel
    @StateObject private var categoriesVM: TagListViewModel

    /// Per-capture primary-tag-name lookup. Replaces the pre-A.3c
    /// `captures.category` reads used by the category-strip filter
    /// + the Library card / list-row badges.
    @State private var primaryTagByCapture: [String: String] = [:]
    @State private var primaryTagCancellable: AnyCancellable? = nil

    @State private var viewMode: ViewMode = .grid
    @State private var activeCategory: String = "All"

    // Date-range filter — replaces the previous newest/oldest sort
    // affordance. `nil` on either side means "no filter on that
    // side". Apply commits both endpoints atomically; Clear sets
    // both to nil. The DAO already returns `created_at DESC`, so
    // user-controlled sort direction was thin value next to a real
    // date filter.
    @State private var dateRangeStart: Date? = nil
    @State private var dateRangeEnd: Date? = nil
    @State private var showDatePicker: Bool = false

    enum ViewMode { case grid, list }

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenScan: @escaping (_ captureId: String) -> Void,
        onHome: @escaping () -> Void = {},
        onWorkspace: @escaping () -> Void = {},
        onScan: @escaping () -> Void = {},
        onSearch: @escaping () -> Void = {},
        onSettings: @escaping () -> Void = {}
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenScan = onOpenScan
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onSearch = onSearch
        self.onSettings = onSettings

        _capturesVM   = StateObject(wrappedValue: CaptureListViewModel(userId: userId))
        _categoriesVM = StateObject(wrappedValue: TagListViewModel(userId: userId))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)

            categoryChips
                .padding(.top, QuickInkSpacing.s4)

            if filteredSorted.isEmpty {
                Spacer()
                emptyState
                    .frame(maxWidth: .infinity)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                        ForEach(dateBuckets, id: \.title) { bucket in
                            section(title: bucket.title, items: bucket.items)
                        }
                    }
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.top, QuickInkSpacing.s5)
                    .padding(.bottom, QuickInkSpacing.s7)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(QuickInkColors.bg.ignoresSafeArea())
        // `.safeAreaInset` hosts the floating bar without manual
        // bottom padding — the inset extends the screen's safe area
        // automatically so scroll content never sits behind the bar.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            QuickInkBottomNavBar(
                activeTab:  .workspace,
                onHome:     onHome,
                onWorkspace:  { /* current tab */ },
                onScan:     onScan,
                onSearch:   onSearch,
                onSettings: onSettings
            )
        }
        .task {
            capturesVM.start()
            categoriesVM.start()
            if primaryTagCancellable == nil {
                primaryTagCancellable = CaptureTagRepository()
                    .observePrimaryTagNames(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { map in primaryTagByCapture = map }
                    )
            }
        }
    }

    // MARK: - Header (title + date-filter/view pill + meta)

    @ViewBuilder
    private var header: some View {
        HStack(alignment: .center) {
            Text("Library")
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)
            Spacer()
            controlPill
        }

        Text("\(capturesVM.captures.count) notes")
            .font(QuickInkText.meta)
            .foregroundStyle(QuickInkColors.muted)
            .padding(.top, QuickInkSpacing.s2)

        // Active-range readout + Clear button. Surfaces below the
        // count so the user can see and cancel the filter without
        // re-opening the date picker.
        if dateRangeStart != nil || dateRangeEnd != nil {
            HStack {
                Text(formatDateRange(dateRangeStart, dateRangeEnd))
                    .font(QuickInkText.meta)
                    .foregroundStyle(QuickInkColors.accent)
                Spacer()
                Button("Clear") {
                    dateRangeStart = nil
                    dateRangeEnd = nil
                }
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.accent)
            }
            .padding(.top, QuickInkSpacing.s1)
        }
    }

    /// Trailing pill in the header. Two halves: a calendar button
    /// (opens the date-range picker sheet) on the left, a grid/list
    /// segmented toggle on the right. The active half of the toggle
    /// paints coral; the inactive half stays cream. The calendar
    /// button paints coral itself when a range is active, doubling
    /// as a status indicator.
    ///
    /// Replaced the previous newest/oldest sort menu — captures come
    /// back from the VM in `created_at DESC` order already, so
    /// user-controlled sort direction added little next to a real
    /// date filter.
    @ViewBuilder
    private var controlPill: some View {
        let rangeActive = dateRangeStart != nil || dateRangeEnd != nil
        HStack(spacing: 0) {
            Button {
                showDatePicker = true
            } label: {
                Image(systemName: "calendar")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(rangeActive ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                    .frame(width: 44, height: 36)
            }
            .accessibilityLabel("Filter by date")
            .background(rangeActive ? QuickInkColors.accent : QuickInkColors.surface)
            .clipShape(Circle())
            .overlay(
                Circle().stroke(rangeActive ? QuickInkColors.accent : QuickInkColors.border, lineWidth: 1)
            )
            .padding(.trailing, QuickInkSpacing.s2)
            .sheet(isPresented: $showDatePicker) {
                DateRangePickerSheet(
                    initialStart: dateRangeStart,
                    initialEnd:   dateRangeEnd,
                    onApply: { start, end in
                        dateRangeStart = start
                        dateRangeEnd = end
                        showDatePicker = false
                    },
                    onClear: {
                        dateRangeStart = nil
                        dateRangeEnd = nil
                        showDatePicker = false
                    }
                )
            }

            // Grid/list segmented pill — two halves, each tappable.
            HStack(spacing: 0) {
                segment(
                    icon: "square.grid.2x2.fill",
                    label: "Grid",
                    active: viewMode == .grid
                ) { viewMode = .grid }

                segment(
                    icon: "list.bullet",
                    label: "List",
                    active: viewMode == .list
                ) { viewMode = .list }
            }
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
        }
    }

    @ViewBuilder
    private func segment(icon: String, label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(active ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                .frame(width: 44, height: 36)
                .background(active ? QuickInkColors.accent : Color.clear)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    // MARK: - Category chips

    @ViewBuilder
    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: QuickInkSpacing.s2) {
                chip(label: "All", active: activeCategory == "All") {
                    activeCategory = "All"
                }
                ForEach(categoriesVM.categories, id: \.id) { cat in
                    chip(label: cat.name, active: cat.name == activeCategory) {
                        activeCategory = cat.name
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
        }
    }

    @ViewBuilder
    private func chip(label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(QuickInkText.label)
                .foregroundStyle(active ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.vertical, QuickInkSpacing.s2)
                .background(active ? QuickInkColors.accent : QuickInkColors.surface)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                        .stroke(active ? Color.clear : QuickInkColors.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Filter / bucket

    private var filteredSorted: [CaptureSummary] {
        let byCategory: [CaptureSummary] = {
            if activeCategory == "All" { return capturesVM.captures }
            let needle = activeCategory.lowercased()
            return capturesVM.captures.filter {
                (primaryTagByCapture[$0.id] ?? "").lowercased() == needle
            }
        }()
        let rangeActive = dateRangeStart != nil || dateRangeEnd != nil
        guard rangeActive else {
            // VM yields newest-first; nothing further to do.
            return byCategory
        }

        // End is widened to "end of day" so a single-day pick (start
        // == end at midnight) still matches captures from later in
        // the same calendar day.
        let cal = Calendar.current
        let lowerBound = dateRangeStart.map { cal.startOfDay(for: $0) }
        let upperBound = dateRangeEnd.flatMap {
            cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: $0))
        }
        let isoParser = ISO8601DateFormatter()
        isoParser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoFallback = ISO8601DateFormatter()
        isoFallback.formatOptions = [.withInternetDateTime]

        return byCategory.filter { capture in
            let parsed = isoParser.date(from: capture.createdAt)
                ?? isoFallback.date(from: capture.createdAt)
            guard let date = parsed else { return false }
            if let lower = lowerBound, date < lower { return false }
            if let upper = upperBound, date >= upper { return false }
            return true
        }
    }

    /// Bucket the filtered list into TODAY / THIS WEEK / EARLIER
    /// sections. Empty buckets are dropped so the UI doesn't render
    /// a header with nothing under it.
    private struct Bucket: Identifiable {
        let title: String
        let items: [CaptureSummary]
        var id: String { title }
    }

    private var dateBuckets: [Bucket] {
        let calendar = Calendar.current
        let now = Date()
        let isoFractional = ISO8601DateFormatter()
        isoFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoBasic = ISO8601DateFormatter()
        isoBasic.formatOptions = [.withInternetDateTime]

        var today: [CaptureSummary] = []
        var thisWeek: [CaptureSummary] = []
        var earlier: [CaptureSummary] = []

        for capture in filteredSorted {
            let date = isoFractional.date(from: capture.createdAt) ?? isoBasic.date(from: capture.createdAt) ?? now
            if calendar.isDateInToday(date) {
                today.append(capture)
            } else if isWithinSameWeek(date, now: now, calendar: calendar) {
                thisWeek.append(capture)
            } else {
                earlier.append(capture)
            }
        }

        var buckets: [Bucket] = []
        if !today.isEmpty    { buckets.append(Bucket(title: "TODAY",     items: today)) }
        if !thisWeek.isEmpty { buckets.append(Bucket(title: "THIS WEEK", items: thisWeek)) }
        if !earlier.isEmpty  { buckets.append(Bucket(title: "EARLIER",   items: earlier)) }
        return buckets
    }

    /// True when `date` falls in the same calendar week as `now`
    /// (week start follows the user's locale via Calendar.current),
    /// excluding "today" — today gets its own bucket above.
    private func isWithinSameWeek(_ date: Date, now: Date, calendar: Calendar) -> Bool {
        if calendar.isDateInToday(date) { return false }
        return calendar.isDate(date, equalTo: now, toGranularity: .weekOfYear)
    }

    // MARK: - Section + grid

    @ViewBuilder
    private func section(title: String, items: [CaptureSummary]) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text(title)
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            if viewMode == .grid {
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                        GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    ],
                    spacing: QuickInkSpacing.s3
                ) {
                    ForEach(items) { capture in
                        Button(action: { onOpenScan(capture.id) }) {
                            LibraryNoteCard(
                                capture:        capture,
                                primaryTagName: primaryTagByCapture[capture.id]
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                VStack(spacing: QuickInkSpacing.s3) {
                    ForEach(items) { capture in
                        Button(action: { onOpenScan(capture.id) }) {
                            LibraryScanListRow(
                                capture:        capture,
                                primaryTagName: primaryTagByCapture[capture.id]
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    // MARK: - Empty state

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            ZStack {
                Circle()
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 80, height: 80)
                Image(systemName: "doc.text")
                    .font(.system(size: 32))
                    .foregroundStyle(QuickInkColors.accent)
            }
            Text(activeCategory == "All" ? "Your library is empty" : "No \(activeCategory) scans yet")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Text(activeCategory == "All"
                 ? "Tap the ⚡ on Home to capture your first page."
                 : "Scans you tag \(activeCategory) on the review screen will collect here.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, QuickInkSpacing.s7)
        }
    }
}

// MARK: - Library note card (mock-style)

/// Editorial note card matching the Library mock: paper-toned upper
/// portion with lined ruling and a handwritten Caveat preview snippet
/// (lazy-loaded from the first OCR row), a small page-count chip in
/// the top-right, and a white footer with serif title + accent-soft
/// category tag + relative date.
struct LibraryNoteCard: View {
    let capture: CaptureSummary
    /// Primary-tag-name shown in the card footer + used as the
    /// title-cascade fallback. Replaces the pre-A.3c
    /// `captures.category` field. Nil → card omits the badge and
    /// the cascade stops at "Untitled scan".
    let primaryTagName: String?

    @State private var ocrSnippet: String? = nil

    /// Pick a stable paper tone per capture id so the wall of cards
    /// looks like a varied stack of notebooks rather than one tone
    /// repeating. Hash → 0..2 selector.
    private var paperTone: Color {
        let seed = abs(capture.id.hashValue)
        return QuickInkColors.paper(for: seed)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Lined-paper preview
            ZStack(alignment: .topTrailing) {
                QuickInkLinedPaper(tone: paperTone)
                    .clipped()

                // Handwritten preview text — OCR snippet when loaded,
                // friendly placeholder while the .task fetches it.
                Text(displayedPreview)
                    .font(QuickInkText.handwritten)
                    .italic()
                    .foregroundStyle(QuickInkColors.ink.opacity(0.78))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .padding(.horizontal, QuickInkSpacing.s3)
                    .padding(.top, QuickInkSpacing.s3)
                    .frame(maxWidth: .infinity, alignment: .topLeading)

                HStack(spacing: 4) {
                    Image(systemName: capture.source == "import" ? "photo" : "camera.fill")
                        .font(.system(size: 10))
                    Text(capture.source == "import" ? "Import" : "Scan")
                        .font(QuickInkText.caption)
                }
                .foregroundStyle(capture.source == "import" ? QuickInkColors.textOnAccent : QuickInkColors.ink.opacity(0.7))
                .padding(.horizontal, QuickInkSpacing.s2)
                .padding(.vertical, 3)
                .background(capture.source == "import" ? QuickInkColors.accent : QuickInkColors.surface.opacity(0.9))
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                .padding(QuickInkSpacing.s2)
                .frame(maxWidth: .infinity, alignment: .topLeading)

                if capture.pageCount > 1 {
                    Text("\(capture.pageCount)p")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.ink.opacity(0.65))
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 3)
                        .background(QuickInkColors.surface.opacity(0.85))
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                        .padding(QuickInkSpacing.s2)
                }
            }
            .frame(height: 175)

            // White footer with title + tag + date. Library card
            // titles render in Inter (UI sans), not the editorial
            // serif — card titles are functional (scannable, dense
            // grid) and the editorial serif felt precious for the
            // file-list context. Size dropped to 10pt so the title
            // sits closer to the meta-tier scale on the same card
            // and stops competing with the thumbnail for focus.
            VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                // `.capitalized` is Swift's per-word Title Case —
                // first letter upper, rest lower, word boundary is
                // whitespace. Normalises whatever case OCR / the
                // user supplied. Note: brand-cased words like
                // "iPhone" come out "Iphone"; accept that for now,
                // OCR rarely surfaces them as the first line.
                // SwiftUI has no `.textCase(.titleCase)` modifier,
                // so we transform the string itself rather than the
                // render — fine for VoiceOver since the human-
                // readable title is the same word.
                Text(displayedTitle.capitalized)
                    .font(QuickInkFont.ui(14, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)

                HStack {
                    if let tag = primaryTagName, !tag.isEmpty {
                        Text(tag)
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.accent)
                            .padding(.horizontal, QuickInkSpacing.s2)
                            .padding(.vertical, 3)
                            .background(QuickInkColors.accentSoft)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                    }
                    Spacer()
                    Text(relativeDate(capture.createdAt))
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }
            }
            .padding(QuickInkSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(QuickInkColors.surface)
        }
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .task(id: capture.id) {
            await loadOcrSnippet()
        }
    }

    /// First useful OCR snippet for the handwritten preview area.
    /// Truncated on the view side via `lineLimit(2)`. While the
    /// async fetch is in flight the card shows a soft placeholder
    /// — the `.task` is throttled by id so it runs once per card
    /// per appear, not on every recomposition.
    private var displayedPreview: String {
        if let snippet = ocrSnippet, !snippet.isEmpty {
            return snippet
        }
        return "Tap to read scan…"
    }

    /// Title shown in the white footer. Cascade:
    /// (1) the user-set `capture.title` (trimmed, non-empty) —
    ///     explicit user intent always wins;
    /// (2) the OCR snippet's first line (≤40 chars) — gives the wall
    ///     a handwritten preview when the user hasn't titled the scan;
    /// (3) the category; (4) "Untitled scan".
    private var displayedTitle: String {
        if let raw = capture.title?.trimmingCharacters(in: .whitespaces),
           !raw.isEmpty {
            return raw
        }
        if let snippet = ocrSnippet,
           let firstLine = snippet
                .split(separator: "\n", omittingEmptySubsequences: true)
                .first
                .map({ String($0).trimmingCharacters(in: .whitespaces) }),
           !firstLine.isEmpty {
            return String(firstLine.prefix(40))
        }
        if let tag = primaryTagName, !tag.isEmpty {
            return tag
        }
        return "Untitled scan"
    }

    /// Lazy fetch of the first OCR row for this capture. One-shot
    /// SELECT, no observation — OCR text doesn't change after capture.
    /// Failures fall back silently to the placeholder; the user can
    /// tap into ScanDetailScreen for a richer view either way.
    private func loadOcrSnippet() async {
        if ocrSnippet != nil { return }
        let captureId = capture.id
        let dbQueue = QuickInkDatabase.shared.dbQueue
        do {
            let text = try await dbQueue.read { db -> String? in
                try String.fetchOne(db, sql: """
                    SELECT text
                    FROM ocr_results
                    WHERE capture_id = ? AND deleted_at IS NULL
                    ORDER BY page_index ASC
                    LIMIT 1
                    """, arguments: [captureId])
            }
            await MainActor.run { self.ocrSnippet = text }
        } catch {
            // Silent — placeholder text remains.
            print("LibraryNoteCard.loadOcrSnippet failed: \(error)")
        }
    }
}

// MARK: - Library list row (used when viewMode == .list)

/// Dense list-layout row for scans. Same data as the card but
/// stacked horizontally so users see more entries per scroll.
struct LibraryScanListRow: View {
    let capture: CaptureSummary
    /// Primary-tag-name title-cascade fallback. Replaces the
    /// pre-A.3c `captures.category` field. Nil → cascade stops at
    /// "Untitled scan".
    var primaryTagName: String? = nil

    var body: some View {
        HStack(alignment: .center, spacing: QuickInkSpacing.s3) {
            ZStack {
                if let image = loadedPreview {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    QuickInkColors.paper2
                    Image(systemName: "doc.text.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(QuickInkColors.muted)
                }
            }
            .frame(width: 56, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )

            VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                // `.capitalized` → Title Case. Same normalisation as
                // the grid view card title; keeps grid and list
                // views consistent.
                Text(rowDisplayTitle.capitalized)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)

                HStack(spacing: QuickInkSpacing.s2) {
                    Text(capture.source == "import" ? "Import" : "Scan")
                        .font(QuickInkText.caption)
                        .foregroundStyle(capture.source == "import" ? QuickInkColors.textOnAccent : QuickInkColors.muted)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 1)
                        .background(
                            RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                                .fill(capture.source == "import" ? QuickInkColors.accent : QuickInkColors.borderSoft)
                        )
                    Text(relativeDate(capture.createdAt))
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                    if capture.pageCount > 1 {
                        Text("• \(capture.pageCount) pages")
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.muted)
                    }
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(QuickInkColors.muted)
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    private var loadedPreview: UIImage? {
        guard let raw = capture.previewUri, !raw.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: raw), url.isFileURL { return url.path }
            return raw
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    /// List-mode row title cascade: user-set title → primary tag →
    /// "Scan". OCR snippet isn't fetched in list mode (rows are
    /// dense — no per-row Flow), so we don't include it here.
    private var rowDisplayTitle: String {
        if let raw = capture.title?.trimmingCharacters(in: .whitespaces),
           !raw.isEmpty {
            return raw
        }
        return primaryTagName ?? "Scan"
    }
}

// MARK: - Date formatting

/// "Today" / "Yesterday" / weekday for the current week / `MMM d`
/// otherwise. Used in the card footer for compact relative dates,
/// matching the mock's "Today / May 1 / Apr 28" treatment.
fileprivate func relativeDate(_ iso: String) -> String {
    let isoFractional = ISO8601DateFormatter()
    isoFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    let isoBasic = ISO8601DateFormatter()
    isoBasic.formatOptions = [.withInternetDateTime]
    guard let date = isoFractional.date(from: iso) ?? isoBasic.date(from: iso) else {
        return String(iso.prefix(10))
    }
    let calendar = Calendar.current
    if calendar.isDateInToday(date)     { return "Today" }
    if calendar.isDateInYesterday(date) { return "Yesterday" }

    let f = DateFormatter()
    if calendar.isDate(date, equalTo: Date(), toGranularity: .weekOfYear) {
        f.dateFormat = "EEE" // weekday short
    } else {
        f.dateFormat = "MMM d"
    }
    return f.string(from: date)
}

// ────────────────────────────────────────────────────────────────────
// Date-range picker sheet
// ────────────────────────────────────────────────────────────────────

/// Two `DatePicker`s wrapped in a sheet — start, then end. SwiftUI
/// has no native date-range picker as a single component (unlike
/// Material3's `DateRangePicker` on Android), so we hand-roll this.
/// End is constrained to `>= start`, so an inverted range can't
/// happen. Apply commits both endpoints; Clear resets to no filter.
private struct DateRangePickerSheet: View {
    let initialStart: Date?
    let initialEnd:   Date?
    let onApply: (_ start: Date?, _ end: Date?) -> Void
    let onClear: () -> Void

    @State private var start: Date
    @State private var end:   Date
    @Environment(\.dismiss) private var dismiss

    init(
        initialStart: Date?,
        initialEnd: Date?,
        onApply: @escaping (Date?, Date?) -> Void,
        onClear: @escaping () -> Void
    ) {
        self.initialStart = initialStart
        self.initialEnd   = initialEnd
        self.onApply      = onApply
        self.onClear      = onClear
        // Seed the pickers with the existing range, falling back to
        // "today" so the user has something to anchor against on
        // first open.
        let today = Calendar.current.startOfDay(for: Date())
        _start = State(initialValue: initialStart ?? today)
        _end   = State(initialValue: initialEnd ?? (initialStart ?? today))
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Filter scans by date range")) {
                    DatePicker(
                        "Start",
                        selection: $start,
                        displayedComponents: .date
                    )
                    DatePicker(
                        "End",
                        selection: $end,
                        in: start...,
                        displayedComponents: .date
                    )
                }
            }
            .navigationTitle("Date range")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear", action: onClear)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { onApply(start, end) }
                }
            }
        }
    }
}

/// Render an active date-range filter as a compact label like
/// "May 1 – May 4". Falls back to a single-side label when only one
/// endpoint is set ("From May 1" / "Until May 4").
private func formatDateRange(_ start: Date?, _ end: Date?) -> String {
    let f = DateFormatter()
    f.dateFormat = "MMM d"
    let s = start.map { f.string(from: $0) }
    let e = end.map   { f.string(from: $0) }
    switch (s, e) {
    case let (.some(a), .some(b)) where a == b: return a
    case let (.some(a), .some(b)):              return "\(a) – \(b)"
    case let (.some(a), .none):                 return "From \(a)"
    case let (.none,    .some(b)):              return "Until \(b)"
    default:                                    return ""
    }
}
