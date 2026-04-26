/*
 * HomeScreenVariant1.swift
 * Editorial "Your shelves" screen — hero-card per notebook, category
 * filters, and a floating action bar. Wired to the same
 * `HomeViewModel` that powers the classic Home screen, so the data
 * source (Drive fake, later real Drive) is shared.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeScreenVariant1: View {
    @EnvironmentObject private var authStore: AuthStore
    @StateObject private var viewModel = ShelvesViewModel()
    @State private var filter: ShelfFilter = .all
    @State private var showNewBookSheet: Bool = false

    public init() {}

    public var body: some View {
        ZStack {
            AppColors.canvas.ignoresSafeArea()
            content
        }
        .task { viewModel.start() }
        .onDisappear { viewModel.stop() }
        .toolbar(.hidden, for: .navigationBar)
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state {
        case .loading:
            ProgressView().tint(AppColors.coral)
        case .loaded(let loaded):
            loadedContent(loaded)
        }
    }

    private func loadedContent(_ loaded: ShelvesViewModel.LoadedState) -> some View {
        let filtered = filter.apply(to: loaded.notebooks)
        let totals = HomeVariantMetrics(
            notebooks: loaded.notebooks,
            captureCounts: loaded.captureCounts
        )
        let booksByShelf = Dictionary(grouping: filtered) { $0.shelfId }
        let orphans = filtered.filter { nb in !loaded.shelves.contains { $0.id == nb.shelfId } }
        return VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s5) {
                    header(totals: totals)
                    TreesSavedStripView(metrics: totals.impact)
                    ShelfFilterRow(filter: $filter)
                    Divider().background(AppColors.borderDefault)

                    if loaded.shelves.isEmpty {
                        Text("No shelves yet. Tap \u{201C}+ New book\u{201D} to get started.")
                            .font(AppText.body)
                            .foregroundStyle(AppColors.textSecondary)
                            .padding(.vertical, AppSpacing.s6)
                            .frame(maxWidth: .infinity)
                    } else {
                        ForEach(loaded.shelves) { shelf in
                            ShelfSection(
                                shelf: shelf,
                                books: booksByShelf[shelf.id] ?? []
                            )
                        }
                        if !orphans.isEmpty {
                            ShelfSectionHeader(name: "Unshelved", count: orphans.count)
                            ForEach(orphans) { notebook in
                                NavigationLink(value: NotebookRoute(id: notebook.id)) {
                                    ShelfCard(notebook: notebook)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.top, AppSpacing.s5)
                .padding(.bottom, AppSpacing.s10 + AppSpacing.s6)
            }
            .overlay(alignment: .bottom) {
                ShelvesActionBar(
                    onNewNotebook: { showNewBookSheet = true },
                    onSearch: { /* TODO: search */ }
                )
                .padding(.horizontal, AppSpacing.s5)
                .padding(.bottom, AppSpacing.s4)
            }
            .sheet(isPresented: $showNewBookSheet) {
                NewBookSheet(
                    shelves: loaded.shelves,
                    onConfirm: { title, shelfId in
                        viewModel.createNotebook(title: title, shelfId: shelfId)
                    },
                    onCreateShelf: { name, completion in
                        viewModel.createShelf(name: name, onCreated: completion)
                    },
                    onDismiss: { showNewBookSheet = false }
                )
            }
        }
    }

    private func header(totals: HomeVariantMetrics) -> some View {
        // Match the typography rhythm used elsewhere in the app —
        // small uppercase eyebrow + serif editorial title in the
        // standard 26pt size — so the Library tab reads at the same
        // scale as the other top-level surfaces. Sign-out lives in
        // Settings, not the library header.
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("LIBRARY")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textTertiary)
            Text("Your shelves")
                .font(.system(size: 26, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text("\(totals.notebooks) book\(totals.notebooks == 1 ? "" : "s") · \(totals.chapters) chapter\(totals.chapters == 1 ? "" : "s") · \(totals.pages) page\(totals.pages == 1 ? "" : "s")")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
        }
    }
}

// MARK: - Totals

private struct HomeVariantMetrics {
    let notebooks: Int
    let chapters: Int
    let pages: Int
    let impact: TreesSavedMetrics

    init(notebooks: [Notebook], captureCounts: CaptureCountsByMode) {
        self.notebooks = notebooks.count
        self.chapters = notebooks.reduce(0) { $0 + $1.chapterCount }
        self.pages    = notebooks.reduce(0) { $0 + $1.pageCount }
        // Photos / scans / voice / contacts stay at 0 until the
        // captures-table migration lands; when it does, the shape
        // here doesn't change — only `CaptureRepository` populates
        // the new fields.
        self.impact = TreesSavedMetrics(
            notes:     captureCounts.notes,
            photos:    captureCounts.photos,
            scans:     captureCounts.scans,
            voice:     captureCounts.voice,
            contacts:  captureCounts.contacts,
            locations: captureCounts.locations
        )
    }
}

// MARK: - Filter

public enum ShelfFilter: CaseIterable {
    case all, active, archived, shared
    var label: String {
        switch self {
        case .all: return "All"
        case .active: return "Active"
        case .archived: return "Archived"
        case .shared: return "Shared"
        }
    }
    func apply(to notebooks: [Notebook]) -> [Notebook] {
        switch self {
        case .all: return notebooks
        case .active:   return notebooks.filter { $0.resolvedStatus == .active }
        case .archived: return notebooks.filter { $0.resolvedStatus == .archived }
        case .shared:   return notebooks.filter { $0.resolvedStatus == .shared }
        }
    }
}

// MARK: - Shelf section (grouping)

private struct ShelfSection: View {
    let shelf: Shelf
    let books: [Notebook]

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            ShelfSectionHeader(name: shelf.name, count: books.count)
            if books.isEmpty {
                Text("No books on this shelf yet.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
                    .padding(.vertical, AppSpacing.s2)
            } else {
                ForEach(books) { notebook in
                    NavigationLink(value: NotebookRoute(id: notebook.id)) {
                        ShelfCard(notebook: notebook)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct ShelfSectionHeader: View {
    let name: String
    let count: Int

    var body: some View {
        HStack(alignment: .bottom, spacing: AppSpacing.s2) {
            Text(name.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("· \(count) book\(count == 1 ? "" : "s")")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
        }
    }
}

private struct ShelfFilterRow: View {
    @Binding var filter: ShelfFilter
    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            ForEach(ShelfFilter.allCases, id: \.self) { option in
                let isActive = option == filter
                Button {
                    filter = option
                } label: {
                    Text(option.label)
                        .font(AppText.button)
                        .foregroundStyle(isActive ? AppColors.onPrimary : AppColors.textPrimary)
                        .padding(.horizontal, AppSpacing.s4)
                        .padding(.vertical, AppSpacing.s2)
                        .background(
                            Capsule().fill(isActive ? AppColors.actionPrimary : Color.clear)
                        )
                        .overlay(
                            Capsule().stroke(isActive ? Color.clear : AppColors.borderStrong, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)
            }
            Spacer()
        }
    }
}

// MARK: - Shelf card

private struct ShelfCard: View {
    let notebook: Notebook

    var body: some View {
        let palette = ShelfTheme.palette(for: notebook.colorToken)
        VStack(spacing: 0) {
            // Hero
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                HStack(alignment: .top) {
                    Text(eyebrow)
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(palette.onBackground)
                    Spacer()
                    Image(systemName: ShelfTheme.iconSystemName(for: notebook.iconKey))
                        .font(.system(size: 22))
                        .foregroundStyle(palette.onBackground)
                }
                Text(notebook.title)
                    .font(.system(size: 36, design: .serif))
                    .foregroundStyle(palette.onBackground)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity, alignment: .leading)

                ProgressDashes(total: 4, filled: progressFilled, palette: palette)
                    .padding(.top, AppSpacing.s1)
            }
            .padding(AppSpacing.s5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.background)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))

            // Footer (cream)
            HStack {
                Text(footerMeta)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                Spacer()
                StatusPill(status: notebook.resolvedStatus)
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.cardSolid)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
            .offset(y: -AppSpacing.s1)
        }
    }

    private var eyebrow: String {
        let shelf = notebook.shelfName ?? notebook.title.uppercased()
        // Single-volume books (no seriesId) hide the "Vol N" suffix.
        guard notebook.seriesId != nil else { return shelf }
        return "\(shelf) · VOL \(String(format: "%02d", notebook.seriesVolumeNumber))"
    }

    private var progressFilled: Int {
        // Treat paused as 1 filled, archived as 4, active ~ 2.
        switch notebook.resolvedStatus {
        case .archived: return 4
        case .paused:   return 1
        case .active, .shared: return 2
        }
    }

    private var footerMeta: String {
        let chapters = "\(notebook.chapterCount) chapter\(notebook.chapterCount == 1 ? "" : "s")"
        let edit = "last edit \(RelativeTime.short(for: notebook.updatedAt))"
        return "\(chapters) · \(edit)"
    }
}

private struct ProgressDashes: View {
    let total: Int
    let filled: Int
    let palette: ShelfPalette
    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<total, id: \.self) { i in
                Capsule()
                    .fill(i < filled ? palette.onBackground : palette.onBackgroundMuted.opacity(0.45))
                    .frame(width: 32, height: 4)
            }
        }
    }
}

private struct StatusPill: View {
    let status: NotebookStatus
    var body: some View {
        let (bg, fg, label): (Color, Color, String) = {
            switch status {
            case .active:
                return (AppColors.successSoft, AppColors.greenText, "active")
            case .paused:
                return (.clear, AppColors.textSecondary, "paused")
            case .archived:
                return (AppColors.neutralSoft, AppColors.neutral, "archived")
            case .shared:
                return (AppColors.infoSoft, AppColors.info, "shared")
            }
        }()
        return Text(label)
            .font(AppText.tag)
            .foregroundStyle(fg)
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, 5)
            .background(Capsule().fill(bg))
            .overlay(Capsule().stroke(AppColors.borderDefault, lineWidth: status == .paused ? 1 : 0))
    }
}

// MARK: - Action bar

private struct ShelvesActionBar: View {
    let onNewNotebook: () -> Void
    let onSearch: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Button(action: onNewNotebook) {
                HStack(spacing: AppSpacing.s2) {
                    Image(systemName: "plus")
                    Text("New notebook")
                        .font(AppText.button)
                }
                .foregroundStyle(AppColors.onPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, AppSpacing.s3)
                .background(Capsule().fill(AppColors.actionPrimary))
            }
            .buttonStyle(.plain)

            Button(action: onSearch) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16))
                    .foregroundStyle(AppColors.textPrimary)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(AppColors.cardSolid))
                    .overlay(Circle().stroke(AppColors.borderStrong, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Search")
        }
    }
}

// MARK: - Relative time helper (local to variant-1)

enum RelativeTime {
    static func short(for date: Date, now: Date = Date()) -> String {
        let delta = max(0, Int(now.timeIntervalSince(date)))
        if delta < 60 { return "just now" }
        if delta < 3600 { return "\(delta / 60)m ago" }
        if delta < 86_400 { return "\(delta / 3600)h ago" }
        let days = delta / 86_400
        if days == 1 { return "yesterday" }
        if days < 7 { return "\(days)d ago" }
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM d"
        return fmt.string(from: date)
    }
}

#Preview {
    NavigationStack {
        HomeScreenVariant1()
            .environmentObject(AuthStore(client: StubGoogleAuthClient()))
    }
}
