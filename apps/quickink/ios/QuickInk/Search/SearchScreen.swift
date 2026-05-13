/*
 * SearchScreen.swift
 *
 * QuickInk's Search surface — captures-first search, redesigned to
 * match the editorial mock:
 *
 *   • Top bar — circular back button + rounded search input. The
 *     input has a leading magnifier glyph and a trailing clear (✕)
 *     button while the user is typing. Submitting the query (return
 *     key) commits it to the recent-searches MRU list.
 *   • Result-count strip — "{n} results · across titles + content"
 *     on the left, a Filter affordance on the right (sliders icon).
 *     The filter is a future surface; the affordance is rendered so
 *     the layout matches the mock and lands later as a single edit.
 *   • Sectioned results:
 *       - IN PAGE CONTENT — full-width cards per OCR match, with a
 *         lined-paper thumbnail, title, date, and the OCR snippet
 *         with the matched query highlighted in coral. Comes from
 *         `CaptureRepository.search` hits whose `ocrSnippet != nil`.
 *       - IN TITLES — compact icon rows for category-name matches.
 *         Comes from the same search call's hits where `ocrSnippet
 *         is nil` (category-substring branch). Captures don't have
 *         a true "title" column today; the category is the closest
 *         human-readable label, so it's what we surface here.
 *   • Recent searches — pill row at the top of the empty-query view.
 *     Backed by `SettingsState.recentSearches`. Tapping a pill
 *     replays the search.
 *
 * Search itself goes through `fts_ocr_text` MATCH via
 * `CaptureRepository.search`. Searching debounces 250ms while the
 * user types so we don't spam the FTS engine on every keystroke.
 *
 * Mirror of Android `SearchScreen.kt`.
 */

import SwiftUI

struct SearchScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenScan: (_ captureId: String) -> Void
    @ObservedObject var settings: SettingsState
    /// Tab navigation callbacks for the floating bottom nav. Search
    /// paints itself active; tapping it is a no-op.
    let onHome: () -> Void
    let onWorkspace: () -> Void
    let onScan: () -> Void
    let onSearch: () -> Void
    let onSettings: () -> Void

    @StateObject private var capturesVM: CaptureListViewModel
    @State private var queryDraft: String = ""
    @State private var liveQuery: String = ""
    @State private var hits: [SearchHit] = []
    @State private var isSearching = false

    private let repository = CaptureRepository()

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenScan: @escaping (_ captureId: String) -> Void,
        settings: SettingsState,
        onHome: @escaping () -> Void = {},
        onWorkspace: @escaping () -> Void = {},
        onScan: @escaping () -> Void = {},
        onSearch: @escaping () -> Void = {},
        onSettings: @escaping () -> Void = {}
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenScan = onOpenScan
        self.settings = settings
        self.onHome = onHome
        self.onWorkspace = onWorkspace
        self.onScan = onScan
        self.onSearch = onSearch
        self.onSettings = onSettings
        _capturesVM = StateObject(wrappedValue: CaptureListViewModel(userId: userId))
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            if !liveQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                resultCountStrip
            }

            if liveQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                emptyQueryView
            } else if isSearching && hits.isEmpty {
                loadingState
            } else if hits.isEmpty {
                noMatchesState
            } else {
                resultsView
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            QuickInkBottomNavBar(
                activeTab:  .search,
                onHome:     onHome,
                onWorkspace:  onWorkspace,
                onScan:     onScan,
                onSearch:   { /* current tab */ },
                onSettings: onSettings
            )
        }
        .task { capturesVM.start() }
        .onChange(of: queryDraft) { newValue in
            // Debounce queryDraft → liveQuery → search. 250ms feels
            // responsive while still saving the FTS engine from a
            // round-trip per keystroke.
            Task {
                try? await Task.sleep(nanoseconds: 250_000_000)
                if newValue == queryDraft {
                    await runSearch(query: newValue)
                }
            }
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            // Back — small white circle with chevron.
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .frame(width: 40, height: 40)
                    .background(QuickInkColors.surface)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(QuickInkColors.border, lineWidth: 1))
            }
            .accessibilityLabel("Back")
            .buttonStyle(.plain)

            // Search input — rounded white pill, coral 1.5pt border,
            // leading magnifier and trailing clear button.
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)

                TextField("Search scans & OCR text", text: $queryDraft)
                    .font(QuickInkText.body)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit { commitToRecents() }

                if !queryDraft.isEmpty {
                    Button(action: {
                        queryDraft = ""
                        liveQuery = ""
                        hits = []
                    }) {
                        ZStack {
                            Circle()
                                .fill(QuickInkColors.borderSoft)
                                .frame(width: 22, height: 22)
                            Image(systemName: "xmark")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(QuickInkColors.inkSoft)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Clear")
                }
            }
            .padding(.horizontal, QuickInkSpacing.s3)
            .frame(height: 40)
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                    .stroke(QuickInkColors.accent, lineWidth: 1.5)
            )
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.top, QuickInkSpacing.s2)
        .padding(.bottom, QuickInkSpacing.s2)
    }

    // MARK: - Result-count strip

    @ViewBuilder
    private var resultCountStrip: some View {
        HStack {
            Text(resultCountText)
                .font(QuickInkText.meta)
                .foregroundStyle(QuickInkColors.muted)
            Spacer()
            // TODO(filter): open filter sheet — categories, date
            // range, page-count threshold. For now the affordance is
            // rendered so the design lands as one edit later.
            Button(action: { /* no-op */ }) {
                HStack(spacing: QuickInkSpacing.s1) {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 14, weight: .medium))
                    Text("Filter").font(QuickInkText.label)
                }
                .foregroundStyle(QuickInkColors.ink)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.vertical, QuickInkSpacing.s2)
    }

    private var resultCountText: String {
        if isSearching && hits.isEmpty { return "Searching…" }
        if hits.isEmpty                { return "No results" }
        return "\(hits.count) results · across titles + content"
    }

    // MARK: - Empty-query view (recent searches + recent notes)

    @ViewBuilder
    private var emptyQueryView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                if !settings.recentSearches.isEmpty {
                    sectionEyebrow(icon: "clock", label: "RECENT SEARCHES") {
                        Button("Clear") {
                            settings.clearRecentSearches()
                        }
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                        .buttonStyle(.plain)
                    }
                    recentSearchPills
                }

                if capturesVM.captures.isEmpty {
                    emptyTimelineState
                } else {
                    sectionEyebrow(icon: "doc.text", label: "RECENT NOTES")
                    VStack(spacing: 0) {
                        ForEach(capturesVM.captures.prefix(8)) { capture in
                            Button(action: { open(captureId: capture.id) }) {
                                CompactRow(
                                    title: searchHitTitle(capture),
                                    subtitle: relativeDate(capture.createdAt) +
                                        (capture.pageCount > 1 ? " · \(capture.pageCount) pages" : "")
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s3)
            .padding(.bottom, QuickInkSpacing.s7)
        }
    }

    @ViewBuilder
    private var recentSearchPills: some View {
        // ScrollView horizontal so a long list of recents wraps
        // off-screen rather than stacking and bloating the page.
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: QuickInkSpacing.s2) {
                ForEach(settings.recentSearches, id: \.self) { q in
                    Button(action: {
                        queryDraft = q
                    }) {
                        Text(q)
                            .font(QuickInkText.label)
                            .foregroundStyle(QuickInkColors.ink)
                            .padding(.horizontal, QuickInkSpacing.s4)
                            .padding(.vertical, QuickInkSpacing.s2)
                            .background(QuickInkColors.surface)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                                    .stroke(QuickInkColors.border, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Results view

    @ViewBuilder
    private var resultsView: some View {
        let pageHits  = hits.filter { ($0.ocrSnippet ?? "").trimmingCharacters(in: .whitespaces).isEmpty == false }
        let titleHits = hits.filter { ($0.ocrSnippet ?? "").trimmingCharacters(in: .whitespaces).isEmpty }

        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
                if !pageHits.isEmpty {
                    sectionEyebrow(icon: "doc.text", label: "IN PAGE CONTENT")
                    VStack(spacing: QuickInkSpacing.s3) {
                        ForEach(pageHits, id: \.capture.id) { hit in
                            Button(action: { open(captureId: hit.capture.id) }) {
                                PageContentResultCard(hit: hit, query: liveQuery)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                if !titleHits.isEmpty {
                    sectionEyebrow(icon: "book", label: "IN TITLES")
                    VStack(spacing: QuickInkSpacing.s2) {
                        ForEach(titleHits, id: \.capture.id) { hit in
                            Button(action: { open(captureId: hit.capture.id) }) {
                                TitleResultRow(hit: hit)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s3)
            .padding(.bottom, QuickInkSpacing.s7)
        }
    }

    // MARK: - States

    @ViewBuilder
    private var emptyTimelineState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 28))
                .foregroundStyle(QuickInkColors.muted)
            Text("Nothing to search yet")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Text("Capture a scan from Home and search by category or OCR text.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, QuickInkSpacing.s7)
    }

    @ViewBuilder
    private var loadingState: some View {
        VStack {
            Spacer()
            ProgressView().tint(QuickInkColors.accent)
            Spacer()
        }
    }

    @ViewBuilder
    private var noMatchesState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Spacer()
            Image(systemName: "doc.text.magnifyingglass")
                .font(.system(size: 32))
                .foregroundStyle(QuickInkColors.muted)
            Text("No matches")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Text("Try a different word or check the spelling.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
        }
    }

    // MARK: - Section eyebrow

    @ViewBuilder
    private func sectionEyebrow<Trailing: View>(
        icon: String,
        label: String,
        @ViewBuilder trailing: () -> Trailing
    ) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(QuickInkColors.muted)
            Text(label)
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)
            Spacer()
            trailing()
        }
    }

    @ViewBuilder
    private func sectionEyebrow(icon: String, label: String) -> some View {
        sectionEyebrow(icon: icon, label: label) { EmptyView() }
    }

    // MARK: - Open + recents commit

    private func open(captureId: String) {
        commitToRecents()
        onOpenScan(captureId)
    }

    private func commitToRecents() {
        let trimmed = queryDraft.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        settings.pushRecentSearch(trimmed)
    }

    // MARK: - Search runner

    private func runSearch(query: String) async {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        liveQuery = trimmed
        guard !trimmed.isEmpty else {
            hits = []
            return
        }
        isSearching = true
        defer { isSearching = false }
        do {
            hits = try await repository.search(userId: userId, query: trimmed)
        } catch {
            print("SearchScreen.runSearch failed: \(error)")
            hits = []
        }
    }
}

// MARK: - Page-content result card (full width)

/// Mock's "IN PAGE CONTENT" card: lined-paper thumbnail on the left,
/// title + date row on the top right, OCR snippet with the query
/// highlighted in coral underneath, and a category tag pinned to the
/// bottom-left.
private struct PageContentResultCard: View {
    let hit: SearchHit
    let query: String

    var body: some View {
        let capture = hit.capture
        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
            // Lined-paper thumbnail — same posture as the Library
            // card's preview, just smaller.
            QuickInkLinedPaper(tone: paperTone)
                .frame(width: 64, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )

            VStack(alignment: .leading, spacing: QuickInkSpacing.s1) {
                HStack {
                    Text(searchHitTitle(capture))
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                    Spacer()
                    Text(relativeDate(capture.createdAt))
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }

                if let snippet = hit.ocrSnippet, !snippet.isEmpty {
                    highlightedSnippet(snippet, query: query)
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .lineLimit(3)
                }

                if let cat = capture.category, !cat.isEmpty {
                    Text(cat)
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 3)
                        .background(QuickInkColors.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                }
            }
        }
        .padding(QuickInkSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    private var paperTone: Color {
        QuickInkColors.paper(for: abs(hit.capture.id.hashValue))
    }

    /// Render `snippet` with each whitespace-delimited query token
    /// highlighted in accent + bold. Case-insensitive matching.
    private func highlightedSnippet(_ snippet: String, query: String) -> Text {
        let terms = query
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
            .filter { !$0.isEmpty }
        guard !terms.isEmpty else { return Text(snippet) }

        var result = Text("")
        var index = snippet.startIndex
        while index < snippet.endIndex {
            // Find the earliest match across all terms from `index`
            // forward.
            var nextMatch: (Range<String.Index>, String)? = nil
            for term in terms {
                if let r = snippet.range(of: term, options: .caseInsensitive, range: index..<snippet.endIndex) {
                    if nextMatch == nil || r.lowerBound < nextMatch!.0.lowerBound {
                        nextMatch = (r, term)
                    }
                }
            }
            guard let (range, _) = nextMatch else {
                result = result + Text(snippet[index..<snippet.endIndex])
                break
            }
            if range.lowerBound > index {
                result = result + Text(snippet[index..<range.lowerBound])
            }
            // `.foregroundColor` (iOS 13+) instead of `.foregroundStyle`
            // (iOS 17+ for the Text-returning overload). Deployment
            // target is iOS 16 — `.foregroundStyle` on Text would
            // only return `some View`, breaking the Text + Text
            // concatenation we need for inline highlights.
            result = result + Text(snippet[range])
                .foregroundColor(QuickInkColors.accent)
                .fontWeight(.semibold)
            index = range.upperBound
        }
        return result
    }
}

// MARK: - Title result row (compact)

/// Mock's "IN TITLES" row — small leading icon, title + secondary
/// line, trailing chevron. Used for category-name matches.
private struct TitleResultRow: View {
    let hit: SearchHit

    var body: some View {
        let capture = hit.capture
        HStack(alignment: .center, spacing: QuickInkSpacing.s3) {
            ZStack {
                RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                    .fill(QuickInkColors.accentSoft)
                    .frame(width: 40, height: 40)
                Image(systemName: "doc.text")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
            }
            VStack(alignment: .leading, spacing: 2) {
                // Title Case — keeps search results consistent with
                // library grid + list rows. `.capitalized` is per-
                // word, splits on whitespace.
                Text(searchHitTitle(capture).capitalized)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(secondaryLine)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
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

    private var secondaryLine: String {
        let parts = [hit.capture.category, relativeDate(hit.capture.createdAt)]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Compact row reused by the "RECENT NOTES" preview list

private struct CompactRow: View {
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .center, spacing: QuickInkSpacing.s3) {
            Image(systemName: "doc.text")
                .font(.system(size: 14))
                .foregroundStyle(QuickInkColors.muted)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(subtitle)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(QuickInkColors.muted)
        }
        .padding(.vertical, QuickInkSpacing.s2)
    }
}

// MARK: - Date helper

/// "Today" / "Yesterday" / weekday for the current week / `MMM d`
/// otherwise. Matches the Android Search and the Library card's
/// relative-date treatment so all three surfaces speak the same
/// dialect.
/// Display title for a capture in search rows: prefer the user-set
/// `title`, fall back to `category`, then to "Untitled scan". Keeps
/// the three search row variants in sync without re-deriving the
/// chain at each call site.
fileprivate func searchHitTitle(_ capture: CaptureSummary) -> String {
    if let raw = capture.title?.trimmingCharacters(in: .whitespaces),
       !raw.isEmpty {
        return raw
    }
    return capture.category ?? "Untitled scan"
}

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
        f.dateFormat = "EEE"
    } else {
        f.dateFormat = "MMM d"
    }
    return f.string(from: date)
}
