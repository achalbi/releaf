/*
 * CategoryEntriesScreen.swift
 *
 * Per-category browse surface. Routed to from the Home screen's
 * category grid: tap "Ideas" → land here scoped to category="Ideas".
 * Lists every active scan whose `capture.category` matches, sorted
 * latest-first by `createdAt`, grouped under "28TH APR, 2026" day
 * headers — same visual language as the SearchScreen timeline so
 * users learn the pattern once.
 *
 * Match is case-insensitive against `capture.category` so "Ideas" /
 * "ideas" / "IDEAS" all bucket together. Tap → `ScanDetailScreen`.
 *
 * Mirror of Android `CategoryEntriesScreen.kt`.
 */

import SwiftUI
import Combine

struct CategoryEntriesScreen: View {

    let userId: String
    let categoryName: String
    let onBack: () -> Void
    let onOpenScan: (_ captureId: String) -> Void

    @StateObject private var vm: CaptureListViewModel
    /// Per-capture primary-tag-name lookup — kept for the timeline
    /// chips that still want to surface a "primary" badge per
    /// capture. The screen's filter no longer uses it; see
    /// `captureIdsWithTag` below.
    @State private var primaryTagByCapture: [String: String] = [:]
    @State private var primaryTagCancellable: AnyCancellable? = nil
    /// Live set of capture ids that carry the requested tag in
    /// `capture_tags`. Filtering by this instead of the per-capture
    /// primary lets the screen show every doc tagged with the
    /// supplied name, not only docs where it's the first attached.
    @State private var captureIdsWithTag: Set<String> = []
    @State private var captureIdsCancellable: AnyCancellable? = nil

    init(
        userId: String,
        categoryName: String,
        onBack: @escaping () -> Void,
        onOpenScan: @escaping (_ captureId: String) -> Void
    ) {
        self.userId = userId
        self.categoryName = categoryName
        self.onBack = onBack
        self.onOpenScan = onOpenScan
        _vm = StateObject(wrappedValue: CaptureListViewModel(userId: userId))
    }

    /// Captures scoped to this category — every capture that has
    /// the supplied tag attached in `capture_tags`, not just docs
    /// where it happens to be the primary tag.
    private var capturesInCategory: [CaptureSummary] {
        vm.captures.filter { captureIdsWithTag.contains($0.id) }
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Divider().background(QuickInkColors.border)

            if capturesInCategory.isEmpty {
                emptyState
            } else {
                timelineView
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            vm.start()
            if primaryTagCancellable == nil {
                primaryTagCancellable = CaptureTagRepository()
                    .observePrimaryTagNames(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { map in primaryTagByCapture = map }
                    )
            }
            if captureIdsCancellable == nil {
                captureIdsCancellable = CaptureTagRepository()
                    .observeCaptureIdsForTagName(userId: userId, tagName: categoryName)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { ids in captureIdsWithTag = ids }
                    )
            }
        }
    }

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

            Text(categoryName)
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
        .padding(.bottom, QuickInkSpacing.s3)
    }

    @ViewBuilder
    private var timelineView: some View {
        // Group by the local-date prefix of `createdAt` so all scans
        // taken on the same calendar day cluster under one header.
        let sorted = capturesInCategory.sorted { $0.createdAt > $1.createdAt }
        let grouped = Dictionary(grouping: sorted) { dayKey(for: $0.createdAt) }
        let sortedDates = grouped.keys.sorted(by: >)

        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                ForEach(sortedDates, id: \.self) { date in
                    VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                        Text(formatDayHeader(date))
                            .font(QuickInkText.eyebrow)
                            .tracking(QuickInkLetterSpacing.eyebrow)
                            .foregroundStyle(QuickInkColors.muted)
                        VStack(spacing: QuickInkSpacing.s2) {
                            ForEach(grouped[date] ?? []) { capture in
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
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.top, QuickInkSpacing.s4)
            .padding(.bottom, QuickInkSpacing.s7)
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            Spacer()
            Image(systemName: "doc.text")
                .font(.system(size: 32))
                .foregroundStyle(QuickInkColors.muted)
            Text("No \(categoryName) scans yet")
                .font(QuickInkText.heading)
                .foregroundStyle(QuickInkColors.ink)
                .multilineTextAlignment(.center)
            Text("Scans tagged \(categoryName) on the post-scan review screen will collect here.")
                .font(QuickInkText.body)
                .foregroundStyle(QuickInkColors.inkSoft)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s7)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Date helpers

    /// Local-date key (`YYYY-MM-DD`) for a `createdAt` ISO timestamp.
    /// Falls back to the input on parse failure.
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

    private func formatDayHeader(_ entryDate: String) -> String {
        let parser = DateFormatter()
        parser.dateFormat = "yyyy-MM-dd"
        parser.locale = Locale(identifier: "en_US_POSIX")
        guard let date = parser.date(from: entryDate) else { return entryDate }
        let day = Calendar.current.component(.day, from: date)
        let suffix = ordinalSuffix(for: day)
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM, yyyy"
        let monthYear = formatter.string(from: date).uppercased()
        return "\(day)\(suffix) \(monthYear)"
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
