/*
 * SearchScreen.swift
 *
 * Full-screen search surface — focused state with a query input,
 * grouped results, and a recent searches chip rail when the query
 * is empty.
 *
 * Hooks `NotepadListViewModel.query` (already wired to FTS5 via the
 * shared `NotepadRepository`) — typing in the search field pushes
 * into `vm.query` and re-renders against `vm.entries` which the VM
 * filters server-side.
 *
 * Result groups (per the mockup):
 *   - OCR content matches: rows where `entry.notes` contains the
 *     query, snippet centered around the match.
 *   - Title matches: rows where `entry.title` contains the query.
 *
 * The VM's `entries` array is the union of FTS5 hits, so we
 * partition into the two groups locally rather than running two
 * queries — the FTS5 round-trip already happened.
 *
 * Recent searches persist in UserDefaults under the key
 * `quickink.search.recent`; up to 8 entries, most-recent first.
 *
 * Mirror of Android `SearchScreen.kt`.
 */

import SwiftUI
import ReleafCoreNotes

struct SearchScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenEntry: (_ entryId: String) -> Void

    @StateObject private var vm: NotepadListViewModel
    @State private var query: String = ""
    @State private var recentSearches: [String] = SearchScreen.loadRecents()
    @FocusState private var queryFieldFocused: Bool

    private static let recentsKey = "quickink.search.recent"
    private static let recentsLimit = 8

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenEntry: @escaping (_ entryId: String) -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenEntry = onOpenEntry

        let repository = NotepadRepository(dbQueue: QuickInkDatabase.shared.dbQueue)
        _vm = StateObject(
            wrappedValue: NotepadListViewModel(
                repository: repository,
                userId:     userId
            )
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            Divider().background(QuickInkColors.border)

            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                recentChipsView
            } else if vm.entries.isEmpty {
                emptyResults
            } else {
                resultsList
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            vm.start()
            queryFieldFocused = true
        }
        .onChange(of: query) { newValue in
            // Push the trimmed query into the VM. The shared VM
            // debounces internally and runs FTS5 against
            // notepad_entries_fts when non-empty.
            vm.query = newValue
        }
    }

    // MARK: - Search bar

    @ViewBuilder
    private var searchBar: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.muted)
                TextField("Search notes & OCR text", text: $query)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .focused($queryFieldFocused)
                    .submitLabel(.search)
                    .onSubmit { commitRecentSearch() }
                if !query.isEmpty {
                    Button(action: { query = "" }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundStyle(QuickInkColors.muted)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
            .padding(.trailing, QuickInkSpacing.s3)
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    // MARK: - Recent chips (empty query state)

    @ViewBuilder
    private var recentChipsView: some View {
        if recentSearches.isEmpty {
            VStack(spacing: QuickInkSpacing.s3) {
                Spacer()
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 36))
                    .foregroundStyle(QuickInkColors.muted)
                Text("Search your notes")
                    .font(QuickInkText.heading)
                    .foregroundStyle(QuickInkColors.ink)
                Text("Find pages by title or anything inside the OCR text.")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, QuickInkSpacing.s7)
                Spacer()
            }
        } else {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                HStack {
                    Text("RECENT")
                        .font(QuickInkText.eyebrow)
                        .tracking(QuickInkLetterSpacing.eyebrow)
                        .foregroundStyle(QuickInkColors.muted)
                    Spacer()
                    Button("Clear") {
                        clearRecents()
                    }
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.accent)
                }

                FlowLayout(spacing: QuickInkSpacing.s2) {
                    ForEach(recentSearches, id: \.self) { term in
                        Button(action: { query = term }) {
                            HStack(spacing: 6) {
                                Image(systemName: "clock")
                                    .font(.system(size: 11))
                                Text(term)
                                    .font(QuickInkText.label)
                            }
                            .foregroundStyle(QuickInkColors.ink)
                            .padding(.horizontal, QuickInkSpacing.s3)
                            .padding(.vertical, QuickInkSpacing.s2)
                            .background(QuickInkColors.borderSoft)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s4)
        }
    }

    // MARK: - Results

    @ViewBuilder
    private var resultsList: some View {
        let q = query.lowercased()
        let titleHits = vm.entries.filter {
            ($0.title?.lowercased().contains(q) ?? false)
        }
        let contentHits = vm.entries.filter {
            $0.notes.lowercased().contains(q)
        }

        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                if !titleHits.isEmpty {
                    section(label: "TITLES") {
                        ForEach(titleHits, id: \.id) { entry in
                            Button(action: {
                                commitRecentSearch()
                                onOpenEntry(entry.id)
                            }) {
                                titleHitRow(for: entry)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if !contentHits.isEmpty {
                    section(label: "OCR CONTENT") {
                        ForEach(contentHits, id: \.id) { entry in
                            Button(action: {
                                commitRecentSearch()
                                onOpenEntry(entry.id)
                            }) {
                                ocrHitRow(for: entry)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s4)
            .padding(.bottom, QuickInkSpacing.s7)
        }
    }

    @ViewBuilder
    private func section<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text(label)
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)
            VStack(spacing: QuickInkSpacing.s2) {
                content()
            }
        }
    }

    @ViewBuilder
    private func titleHitRow(for entry: NotepadEntry) -> some View {
        HStack(spacing: QuickInkSpacing.s3) {
            Image(systemName: "doc.text")
                .font(.system(size: 16))
                .foregroundStyle(QuickInkColors.accent)
                .frame(width: 32, height: 32)
                .background(QuickInkColors.accentSoft)
                .clipShape(Circle())

            VStack(alignment: .leading, spacing: 2) {
                highlightedText(entry.title ?? "Untitled", query: query, baseStyle: QuickInkText.label)
                Text(entry.entryDate)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            Spacer()
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    @ViewBuilder
    private func ocrHitRow(for entry: NotepadEntry) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack {
                Text(entry.title?.isEmpty == false ? entry.title! : "Untitled")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Spacer()
                Text(entry.entryDate)
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
            highlightedSnippet(from: entry.notes, query: query)
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .lineLimit(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    @ViewBuilder
    private var emptyResults: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Spacer()
            Image(systemName: "magnifyingglass")
                .font(.system(size: 36))
                .foregroundStyle(QuickInkColors.muted)
            Text("No matches")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Text("Try a different word, or check your spelling.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, QuickInkSpacing.s7)
            Spacer()
        }
    }

    // MARK: - Highlighting

    /// Render `text` with the query substring highlighted in coral.
    /// Falls back to plain text when AttributedString isn't
    /// available (iOS 16 has it; we're on .iOS(.v16) floor).
    @ViewBuilder
    private func highlightedText(_ text: String, query: String, baseStyle: Font) -> some View {
        let attributed = makeHighlighted(text: text, query: query)
        Text(attributed)
            .font(baseStyle)
            .foregroundStyle(QuickInkColors.ink)
    }

    /// Snippet view — extracts ~120 chars centered around the
    /// first match.
    @ViewBuilder
    private func highlightedSnippet(from text: String, query: String) -> some View {
        let snippet = makeSnippet(from: text, query: query, contextChars: 60)
        let attributed = makeHighlighted(text: snippet, query: query)
        Text(attributed)
    }

    private func makeSnippet(from text: String, query: String, contextChars: Int) -> String {
        guard let range = text.range(of: query, options: .caseInsensitive) else {
            return String(text.prefix(contextChars * 2))
        }
        let lower = max(text.startIndex, text.index(range.lowerBound, offsetBy: -contextChars, limitedBy: text.startIndex) ?? text.startIndex)
        let upper = min(text.endIndex, text.index(range.upperBound, offsetBy: contextChars, limitedBy: text.endIndex) ?? text.endIndex)
        var snippet = String(text[lower..<upper])
        if lower != text.startIndex { snippet = "…" + snippet }
        if upper != text.endIndex   { snippet = snippet + "…" }
        return snippet
    }

    private func makeHighlighted(text: String, query: String) -> AttributedString {
        var attributed = AttributedString(text)
        guard !query.isEmpty,
              let range = attributed.range(of: query, options: .caseInsensitive) else {
            return attributed
        }
        attributed[range].foregroundColor = QuickInkColors.accent
        attributed[range].font = .system(size: 16, weight: .semibold).bold()
        return attributed
    }

    // MARK: - Recent searches persistence

    private func commitRecentSearch() {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }

        var current = recentSearches.filter { $0.lowercased() != trimmed.lowercased() }
        current.insert(trimmed, at: 0)
        if current.count > Self.recentsLimit {
            current = Array(current.prefix(Self.recentsLimit))
        }
        recentSearches = current
        UserDefaults.standard.set(current, forKey: Self.recentsKey)
    }

    private func clearRecents() {
        recentSearches = []
        UserDefaults.standard.removeObject(forKey: Self.recentsKey)
    }

    private static func loadRecents() -> [String] {
        UserDefaults.standard.stringArray(forKey: recentsKey) ?? []
    }
}

// MARK: - Simple flow layout

/// Minimal flow layout — wraps children onto multiple rows when
/// the row width is exceeded. Used by the recent-searches chip
/// rail. iOS 16 has the official `Layout` protocol.
struct FlowLayout: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        var maxRowWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth, rowWidth > 0 {
                totalHeight += rowHeight + spacing
                maxRowWidth = max(maxRowWidth, rowWidth - spacing)
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        maxRowWidth = max(maxRowWidth, rowWidth - spacing)
        return CGSize(width: maxRowWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let maxWidth = bounds.width
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x + size.width > bounds.minX + maxWidth, x > bounds.minX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
