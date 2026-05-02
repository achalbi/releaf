/*
 * SearchScreen.swift
 *
 * QuickInk's Search surface — captures-first search:
 *   - Empty query → timeline of all the user's captures, newest
 *     first, grouped under "28TH APR, 2026" day headers.
 *   - Non-empty query → a list of capture cards. Each card surfaces
 *     either a category-substring match or the OCR snippet (with
 *     the matched span emphasised) that pulled it in.
 *
 * Tap → `ScanDetailScreen`. The notepad-entries-driven timeline
 * was retired alongside Library — captures are the canonical
 * artifact users browse and search.
 *
 * OCR text matches go through `fts_ocr_text` MATCH via
 * `CaptureRepository.search`. Searching debounces ~250ms while
 * typing so we don't spam the FTS engine on every keystroke.
 *
 * Mirror of Android `SearchScreen.kt`.
 */

import SwiftUI

struct SearchScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenScan: (_ captureId: String) -> Void

    @StateObject private var capturesVM: CaptureListViewModel
    @State private var queryDraft: String = ""
    @State private var liveQuery: String = ""
    @State private var hits: [SearchHit] = []
    @State private var isSearching = false

    private let repository = CaptureRepository()

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenScan: @escaping (_ captureId: String) -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenScan = onOpenScan
        _capturesVM = StateObject(wrappedValue: CaptureListViewModel(userId: userId))
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Divider().background(QuickInkColors.border)

            if liveQuery.trimmingCharacters(in: .whitespaces).isEmpty {
                timelineView
            } else {
                resultsView
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task { capturesVM.start() }
        // Debounce queryDraft → liveQuery → search. 250ms feels
        // responsive while still saving the FTS engine from a
        // round-trip per keystroke.
        .onChange(of: queryDraft) { newValue in
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
                TextField("Search scans & OCR text", text: $queryDraft)
                    .font(QuickInkText.body)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                if !queryDraft.isEmpty {
                    Button(action: {
                        queryDraft = ""
                        liveQuery = ""
                        hits = []
                    }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(QuickInkColors.muted)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    // MARK: - Empty-query timeline

    @ViewBuilder
    private var timelineView: some View {
        if capturesVM.captures.isEmpty {
            emptyState
        } else {
            let grouped = Dictionary(grouping: capturesVM.captures.sorted { $0.createdAt > $1.createdAt }) {
                dayKey(for: $0.createdAt)
            }
            let sortedDates = grouped.keys.sorted(by: >)

            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                    ForEach(sortedDates, id: \.self) { date in
                        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                            Text(formatDayHeader(date))
                                .font(QuickInkText.eyebrow)
                                .tracking(QuickInkLetterSpacing.eyebrow)
                                .foregroundStyle(QuickInkColors.muted)
                            VStack(spacing: QuickInkSpacing.s3) {
                                ForEach(grouped[date] ?? []) { capture in
                                    Button(action: { onOpenScan(capture.id) }) {
                                        LibraryScanListRow(capture: capture)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s7)
            }
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Spacer()
            Image(systemName: "magnifyingglass")
                .font(.system(size: 32))
                .foregroundStyle(QuickInkColors.muted)
            Text("Nothing to search yet")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
            Text("Capture a scan from Home and search by category or OCR text.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
                .padding(.horizontal, QuickInkSpacing.s7)
            Spacer()
        }
    }

    // MARK: - Non-empty results

    @ViewBuilder
    private var resultsView: some View {
        if isSearching && hits.isEmpty {
            VStack {
                Spacer()
                ProgressView().tint(QuickInkColors.accent)
                Spacer()
            }
        } else if hits.isEmpty {
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
        } else {
            ScrollView {
                VStack(spacing: QuickInkSpacing.s3) {
                    ForEach(hits) { hit in
                        Button(action: { onOpenScan(hit.capture.id) }) {
                            SearchResultCard(hit: hit, query: liveQuery)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s7)
            }
        }
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

    // MARK: - Date helpers

    private func dayKey(for createdAt: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: createdAt) else { return createdAt }
        let f = DateFormatter()
        f.calendar = .init(identifier: .gregorian)
        f.locale   = .init(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    private func formatDayHeader(_ ymd: String) -> String {
        let parser = DateFormatter()
        parser.dateFormat = "yyyy-MM-dd"
        parser.locale = Locale(identifier: "en_US_POSIX")
        guard let date = parser.date(from: ymd) else { return ymd }
        let day = Calendar.current.component(.day, from: date)
        let suffix = ordinalSuffix(for: day)
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM, yyyy"
        return "\(day)\(suffix) \(formatter.string(from: date).uppercased())"
    }

    private func ordinalSuffix(for day: Int) -> String {
        let mod100 = day % 100
        if (11...13).contains(mod100) { return "TH" }
        switch day % 10 {
        case 1:  return "ST"
        case 2:  return "ND"
        case 3:  return "RD"
        default: return "TH"
        }
    }
}

// MARK: - Result card

/// Capture card showing the preview thumb on the left + a brief
/// metadata block on the right. When the hit came from the FTS5
/// pass, the OCR snippet sits below with the matched query
/// highlighted in `accent`.
private struct SearchResultCard: View {
    let hit: SearchHit
    let query: String

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
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

                VStack(alignment: .leading, spacing: 4) {
                    Text(hit.capture.category ?? "Scan")
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                        .lineLimit(1)
                    Text(hit.ocrSnippet == nil ? "Category match" : "OCR match")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(QuickInkColors.muted)
            }

            if let snippet = hit.ocrSnippet, !snippet.isEmpty {
                highlighted(snippet: snippet, query: query)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.horizontal, QuickInkSpacing.s2)
            }
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
        guard let raw = hit.capture.previewUri, !raw.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: raw), url.isFileURL { return url.path }
            return raw
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    /// Render `snippet` with the user's query highlighted in
    /// accent. Matching is case-insensitive and applies to whole-
    /// word substrings — FTS5 returned a snippet already centred
    /// on the match, so we just need to colour the term back in.
    private func highlighted(snippet: String, query: String) -> Text {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return Text(snippet) }
        let terms = trimmed
            .split(whereSeparator: { $0.isWhitespace })
            .map { String($0) }
            .filter { !$0.isEmpty }

        var result = Text("")
        var rest = snippet[...]
        outer: while !rest.isEmpty {
            var earliest: Range<Substring.Index>? = nil
            for term in terms {
                if let range = rest.range(of: term, options: .caseInsensitive) {
                    if earliest == nil || range.lowerBound < earliest!.lowerBound {
                        earliest = range
                    }
                }
            }
            guard let range = earliest else {
                result = result + Text(String(rest))
                break outer
            }
            result = result
                + Text(String(rest[..<range.lowerBound]))
                + Text(String(rest[range])).foregroundColor(QuickInkColors.accent).bold()
            rest = rest[range.upperBound...]
        }
        return result
    }
}
