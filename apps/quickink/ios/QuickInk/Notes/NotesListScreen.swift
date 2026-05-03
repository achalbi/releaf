/*
 * NotesListScreen.swift
 *
 * QuickInk's Library — the user's full scan gallery. Same data
 * source as the home rail (`captures` via `CaptureListViewModel`)
 * but unbounded, with category chips (wrapping pill row, count next
 * to each label) off the live `categories` table.
 *
 * Tap → `ScanDetailScreen` for the selected capture (preview +
 * OCR-on-demand). The notepad-entries-driven editor is no longer
 * the destination — captures are the canonical artifact.
 *
 * Mirror of Android `NotesListScreen.kt`.
 */

import SwiftUI

struct NotesListScreen: View {

    let userId: String
    let onBack: () -> Void
    let onOpenScan: (_ captureId: String) -> Void

    @StateObject private var capturesVM: CaptureListViewModel
    @StateObject private var categoriesVM: CategoryListViewModel

    @State private var viewMode: ViewMode = .grid
    @State private var sort: SortOrder = .newest
    @State private var activeCategory: String = "All"

    enum ViewMode { case grid, list }
    /// Library always sorts on `capture.created_at` — only the
    /// direction is user-selectable. Category remains a filter
    /// (chips), not a sort key.
    enum SortOrder { case newest, oldest }

    init(
        userId: String,
        onBack: @escaping () -> Void,
        onOpenScan: @escaping (_ captureId: String) -> Void
    ) {
        self.userId = userId
        self.onBack = onBack
        self.onOpenScan = onOpenScan

        _capturesVM   = StateObject(wrappedValue: CaptureListViewModel(userId: userId))
        _categoriesVM = StateObject(wrappedValue: CategoryListViewModel(userId: userId))
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            filterChips
                .padding(.top, QuickInkSpacing.s2)
                .padding(.bottom, QuickInkSpacing.s3)

            if filteredSorted.isEmpty {
                Spacer()
                emptyState
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                        flatBody
                    }
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.top, QuickInkSpacing.s2)
                    .padding(.bottom, QuickInkSpacing.s7)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            capturesVM.start()
            categoriesVM.start()
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text("Library")
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()

            Menu {
                Button("Newest first") { sort = .newest }
                Button("Oldest first") { sort = .oldest }
            } label: {
                Image(systemName: "arrow.up.arrow.down")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Sort")

            Button(action: { viewMode = (viewMode == .grid ? .list : .grid) }) {
                Image(systemName: viewMode == .grid ? "list.bullet" : "square.grid.2x2")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Toggle grid/list view")
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    // MARK: - Filter chips

    /// Per-category counts used to label the chips. Keyed by
    /// lower-cased category name so user-typed casing on
    /// `capture.category` doesn't fragment buckets.
    private var countByCategory: [String: Int] {
        Dictionary(grouping: capturesVM.captures) {
            ($0.category ?? "").lowercased()
        }
        .mapValues(\.count)
    }

    @ViewBuilder
    private var filterChips: some View {
        // Wrapping pill row — every chip stays on screen, no
        // horizontal scroll. Chip names stay case-sensitive on
        // display but we filter case-insensitively below —
        // `captures.category` may be user-typed.
        let counts = countByCategory
        WrappingHStack(
            spacing: QuickInkSpacing.s2,
            lineSpacing: QuickInkSpacing.s2
        ) {
            chip(label: "All", count: capturesVM.captures.count, active: activeCategory == "All") {
                activeCategory = "All"
            }
            ForEach(categoriesVM.categories, id: \.id) { cat in
                chip(
                    label: cat.name,
                    count: counts[cat.name.lowercased()] ?? 0,
                    active: cat.name == activeCategory
                ) {
                    activeCategory = cat.name
                }
            }
        }
        .padding(.horizontal, QuickInkSpacing.s5)
    }

    @ViewBuilder
    private func chip(label: String, count: Int, active: Bool, action: @escaping () -> Void) -> some View {
        let onChip = active ? QuickInkColors.textOnAccent : QuickInkColors.ink
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s2) {
                Text(label)
                    .font(QuickInkText.label)
                    .foregroundStyle(onChip)
                Text("\(count)")
                    .font(QuickInkText.caption)
                    .foregroundStyle(onChip.opacity(active ? 0.85 : 0.6))
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(active ? QuickInkColors.accent : QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Filter / sort / group

    private var filteredSorted: [CaptureSummary] {
        let filtered: [CaptureSummary] = {
            if activeCategory == "All" { return capturesVM.captures }
            let needle = activeCategory.lowercased()
            return capturesVM.captures.filter { ($0.category ?? "").lowercased() == needle }
        }()

        switch sort {
        case .newest: return filtered // VM is already newest-first.
        case .oldest: return filtered.reversed()
        }
    }

    /// Flat scan list — every capture in the active filter, sorted
    /// newest-first (or oldest-first if the user flipped the sort).
    /// No day buckets; a single grid or list runs the full length.
    @ViewBuilder
    private var flatBody: some View {
        if viewMode == .grid {
            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                ],
                spacing: QuickInkSpacing.s3
            ) {
                ForEach(filteredSorted) { capture in
                    Button(action: { onOpenScan(capture.id) }) {
                        LibraryScanGridCard(capture: capture)
                    }
                    .buttonStyle(.plain)
                }
            }
        } else {
            VStack(spacing: QuickInkSpacing.s3) {
                ForEach(filteredSorted) { capture in
                    Button(action: { onOpenScan(capture.id) }) {
                        LibraryScanListRow(capture: capture)
                    }
                    .buttonStyle(.plain)
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

// MARK: - Cards

/// Grid-layout card showing the actual scan preview JPEG. Mirror of
/// the home rail's `RecentScanThumb` but at the larger Library grid
/// scale.
struct LibraryScanGridCard: View {
    let capture: CaptureSummary

    var body: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            ZStack(alignment: .topTrailing) {
                if let image = loadedPreview {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    placeholder
                }

                if capture.pageCount > 1 {
                    Text("\(capture.pageCount) pages")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 2)
                        .background(
                            RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                                .fill(QuickInkColors.ink.opacity(0.55))
                        )
                        .padding(QuickInkSpacing.s2)
                }
            }
            .frame(height: 180)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(capture.category ?? "Scan")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(friendlyMonthDay(capture.createdAt))
                    .font(QuickInkText.caption)
                    .foregroundStyle(QuickInkColors.muted)
            }
        }
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

    @ViewBuilder
    private var placeholder: some View {
        ZStack {
            QuickInkColors.paper2
            Image(systemName: "doc.text.fill")
                .font(.system(size: 36))
                .foregroundStyle(QuickInkColors.muted)
        }
    }
}

/// Dense list-layout row for scans. Same data as the grid card but
/// stacked horizontally so users see more entries per scroll.
struct LibraryScanListRow: View {
    let capture: CaptureSummary

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
                Text(capture.category ?? "Scan")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)

                HStack(spacing: QuickInkSpacing.s2) {
                    Text(friendlyMonthDay(capture.createdAt))
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
}

/// `2026-05-02T14:30:00.000Z` → `May 2`. Falls back to the raw
/// timestamp's date prefix on parse failures. Shared by the
/// Library's two cards + (eventually) the Search timeline.
fileprivate func friendlyMonthDay(_ iso: String) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = formatter.date(from: iso) {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        return f.string(from: date)
    }
    return String(iso.prefix(10))
}

// MARK: - WrappingHStack

/// Flow layout that wraps children onto multiple rows when the
/// container width is exceeded. Counterpart to Compose's `FlowRow`
/// on Android, which is what the chip row above uses. Children keep
/// their intrinsic widths; spacing/lineSpacing control the gap
/// between adjacent items and between rows.
struct WrappingHStack: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let rows = layoutRows(maxWidth: maxWidth, subviews: subviews)
        let height = rows.reduce(0) { $0 + $1.height } +
            CGFloat(max(0, rows.count - 1)) * lineSpacing
        let usedWidth = rows.map(\.width).max() ?? 0
        // Hand back the proposed width so the layout fills its
        // container horizontally — chips stay left-aligned within.
        let width = proposal.width ?? usedWidth
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = layoutRows(maxWidth: bounds.width, subviews: subviews)
        var y = bounds.minY
        for row in rows {
            var x = bounds.minX
            for index in row.indices {
                let size = row.sizes[index - row.startIndex]
                subviews[index].place(
                    at: CGPoint(x: x, y: y),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(size)
                )
                x += size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Row {
        let indices: Range<Int>
        let sizes: [CGSize]
        let width: CGFloat
        let height: CGFloat
        var startIndex: Int { indices.lowerBound }
    }

    private func layoutRows(maxWidth: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var currentSizes: [CGSize] = []
        var currentStart = 0
        var currentWidth: CGFloat = 0
        var currentHeight: CGFloat = 0

        for (index, subview) in subviews.enumerated() {
            let size = subview.sizeThatFits(.unspecified)
            let prospective = currentWidth == 0 ? size.width : currentWidth + spacing + size.width
            if prospective > maxWidth, !currentSizes.isEmpty {
                rows.append(Row(
                    indices: currentStart..<index,
                    sizes: currentSizes,
                    width: currentWidth,
                    height: currentHeight
                ))
                currentSizes = [size]
                currentStart = index
                currentWidth = size.width
                currentHeight = size.height
            } else {
                currentSizes.append(size)
                currentWidth = prospective
                currentHeight = max(currentHeight, size.height)
            }
        }
        if !currentSizes.isEmpty {
            rows.append(Row(
                indices: currentStart..<(currentStart + currentSizes.count),
                sizes: currentSizes,
                width: currentWidth,
                height: currentHeight
            ))
        }
        return rows
    }
}
