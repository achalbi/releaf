/*
 * NotebookTabView.swift
 *
 * Top-level notebook tab for the classic variant. Compared with the
 * previous plain list, this screen now:
 *   - surfaces real notebook/chapter/page totals at the top
 *   - separates notebook-title matches from page-content matches
 *   - gives page hits notebook/chapter context
 *   - keeps create/delete actions nearby without burying the list
 */

import SwiftUI
import ReleafData
import ReleafDesignSystem

public struct NotebookTabView: View {
    @StateObject private var vm = NotebookTabViewModel()
    @State private var isAdding: Bool = false
    @State private var newTitle: String = ""
    @State private var pendingDelete: ShelfRecord?
    @State private var presentingNewShelfSheet: Bool = false
    @State private var presentingArchivedSheet: Bool = false
    @State private var newShelfName: String = ""
    @State private var newShelfColorToken: String = "coral"
    @State private var newNotebookColorToken: String = "coral"
    @State private var openArchivedPageId: String? = nil
    @FocusState private var addFocused: Bool

    public init() {}

    public var body: some View {
        ZStack(alignment: .bottomTrailing) {
            DotGridBackground().ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                header
                content
            }

            if !isAdding {
                addNotebookButton
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { vm.start() }
        .onDisappear { vm.stop() }
        .confirmationDialog(
            "Delete notebook?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pendingDelete {
                Button("Delete notebook", role: .destructive) {
                    vm.delete(notebookId: pendingDelete.notebook.id)
                    self.pendingDelete = nil
                }
            }
            Button("Cancel", role: .cancel) {
                pendingDelete = nil
            }
        } message: {
            if let pendingDelete {
                Text("“\(displayNotebookTitle(pendingDelete.notebook.title))” and all of its chapters and pages will be deleted.")
            }
        }
        .sheet(isPresented: $presentingNewShelfSheet) {
            NewShelfSheet(
                name: $newShelfName,
                colorToken: $newShelfColorToken,
                onCreate: { trimmed, token in
                    vm.createShelf(name: trimmed, colorToken: token)
                    presentingNewShelfSheet = false
                    newShelfColorToken = "coral"   // reset for next open
                },
                onCancel: {
                    presentingNewShelfSheet = false
                    newShelfColorToken = "coral"
                }
            )
        }
        .sheet(isPresented: $presentingArchivedSheet) {
            ArchivedNotebooksSheet(
                onClose: { presentingArchivedSheet = false },
                onOpenPage: { id in
                    // Dismiss first so the navigation destination
                    // picks up cleanly after the sheet collapses.
                    presentingArchivedSheet = false
                    openArchivedPageId = id
                }
            )
        }
        .navigationDestination(
            isPresented: Binding(
                get: { openArchivedPageId != nil },
                set: { if !$0 { openArchivedPageId = nil } }
            )
        ) {
            if let id = openArchivedPageId {
                PageDetailView(pageId: id)
            }
        }
    }

    private var header: some View {
        // Composed top zone — leaf eyebrow on the left, overflow menu
        // on the right, big serif title. No view toggle here: this
        // screen is the shelves list and there's no second layout to
        // toggle to. Stat tiles + search field stay below the title.
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            HStack(alignment: .center) {
                LeafEyebrow("releaf · shelves")
                Spacer()
                PageOverflowButton {
                    Button {
                        newShelfName = ""
                        presentingNewShelfSheet = true
                    } label: { Label("New shelf", systemImage: "books.vertical") }
                    Button {
                        isAdding = true
                    } label: { Label("New notebook", systemImage: "book") }
                    Divider()
                    Menu {
                        ForEach(NotebookTabViewModel.SortMode.allCases, id: \.self) { mode in
                            Button {
                                vm.sortMode = mode
                            } label: {
                                if vm.sortMode == mode {
                                    Label(mode.label, systemImage: "checkmark")
                                } else {
                                    Label(mode.label, systemImage: mode.systemIcon)
                                }
                            }
                        }
                    } label: {
                        Label("Sort by…", systemImage: "arrow.up.arrow.down")
                    }
                    Button {
                        presentingArchivedSheet = true
                    } label: { Label("Archived", systemImage: "archivebox") }
                }
            }

            Text("your shelves")
                .font(.system(size: 32, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Text(headerSummary)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)

            StatGrid(items: [
                StatItem(label: "Notebooks", value: "\(vm.metrics.notebooks)", tone: .green,   mode: .overview),
                StatItem(label: "Chapters",  value: "\(vm.metrics.chapters)",  tone: .neutral, mode: nil),
                StatItem(label: "Pages",     value: "\(vm.metrics.pages)",     tone: .green,   mode: nil),
            ], valueDesign: .serif)

            NotebookSearchField(
                query: Binding(get: { vm.query }, set: { vm.query = $0 }),
                onClear: vm.clearQuery
            )

            Text(searchSummary)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var content: some View {
        if vm.isLoading {
            VStack {
                Spacer()
                ProgressView().tint(AppColors.coral)
                Spacer()
            }
            .frame(maxWidth: .infinity)
        } else {
            notebookList
        }
    }

    private var notebookList: some View {
        let trimmedQuery = vm.query.trimmingCharacters(in: .whitespacesAndNewlines)
        let notebookMatches = vm.filteredShelves

        return List {
            if isAdding {
                createNotebookCard
                    .notebookListRow(bottom: AppSpacing.s3)
            }

            if trimmedQuery.isEmpty {
                if notebookMatches.isEmpty && !isAdding {
                    EmptyStateCard(
                        title: "No notebooks yet",
                        subtitle: "Notebooks group chapters and pages in one place — perfect for a project, a trip, or a season.",
                        actionLabel: "Create your first notebook",
                        onAction: { withAnimation { isAdding = true } }
                    )
                    .notebookListRow()
                } else if !notebookMatches.isEmpty {
                    // One header card per shelf, titled with the
                    // shelf name and badged with "N books". Shelves
                    // with no books are skipped; orphaned rows
                    // (book shelf got deleted mid-snapshot) fall
                    // under an "Unshelved" heading.
                    let booksByShelf: [String: [ShelfRecord]] = Dictionary(
                        grouping: notebookMatches,
                        by: { $0.notebook.shelfId }
                    )
                    let orderedShelves = vm.shelfDirectory.filter { booksByShelf[$0.id]?.isEmpty == false }
                    let orphanIds = booksByShelf.keys.filter { id in
                        !vm.shelfDirectory.contains(where: { $0.id == id })
                    }
                    let orphanBooks = orphanIds.flatMap { booksByShelf[$0] ?? [] }

                    ForEach(orderedShelves) { shelf in
                        let books = booksByShelf[shelf.id] ?? []
                        SectionHeaderCard(
                            title: shelf.name,
                            subtitle: "Open a book to continue working at the chapter or page level.",
                            badge: "\(books.count) book\(books.count == 1 ? "" : "s")"
                        )
                        .notebookListRow(bottom: AppSpacing.s2)

                        ForEach(books, id: \.notebook.id) { record in
                            NavigationLink(value: NotebookRoute(id: record.notebook.id)) {
                                NotebookShelfRow(
                                    summary: record,
                                    shelfColorHex: shelf.colorHex
                                )
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = record
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                            .notebookListRow(bottom: AppSpacing.s3)
                        }
                    }

                    if !orphanBooks.isEmpty {
                        SectionHeaderCard(
                            title: "Unshelved",
                            subtitle: "Books whose shelf was deleted. Move them into a shelf from the edit screen.",
                            badge: "\(orphanBooks.count) book\(orphanBooks.count == 1 ? "" : "s")"
                        )
                        .notebookListRow(bottom: AppSpacing.s2)
                        ForEach(orphanBooks, id: \.notebook.id) { record in
                            NavigationLink(value: NotebookRoute(id: record.notebook.id)) {
                                NotebookShelfRow(
                                    summary: record,
                                    shelfColorHex: nil
                                )
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = record
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                            .notebookListRow(bottom: AppSpacing.s3)
                        }
                    }
                }
            } else {
                if notebookMatches.isEmpty && vm.matchingPages.isEmpty {
                    EmptyStateCard(
                        title: "No matches",
                        subtitle: "Nothing in notebook titles or page contents matches “\(trimmedQuery)”."
                    )
                    .notebookListRow()
                } else {
                    if !notebookMatches.isEmpty {
                        SectionHeaderCard(
                            title: "Notebook titles",
                            subtitle: "Matches found in notebook names.",
                            badge: "\(notebookMatches.count)"
                        )
                        .notebookListRow(bottom: AppSpacing.s2)

                        ForEach(notebookMatches, id: \.notebook.id) { shelf in
                            NavigationLink(value: NotebookRoute(id: shelf.notebook.id)) {
                                NotebookShelfRow(
                                    summary: shelf,
                                    shelfColorHex: vm.shelfDirectory.first(
                                        where: { $0.id == shelf.notebook.shelfId }
                                    )?.colorHex
                                )
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                Button(role: .destructive) {
                                    pendingDelete = shelf
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                            .notebookListRow(bottom: AppSpacing.s3)
                        }
                    }

                    if !vm.matchingPages.isEmpty {
                        SectionHeaderCard(
                            title: "Matching pages",
                            subtitle: "Page content hits grouped with their notebook and chapter.",
                            badge: "\(vm.matchingPages.count)"
                        )
                        .notebookListRow(bottom: AppSpacing.s2)

                        ForEach(vm.matchingPages) { hit in
                            NavigationLink(value: PageRoute(id: hit.id)) {
                                PagePreviewRow(
                                    title: displayPageTitle(hit),
                                    description: pageContext(hit),
                                    meta: "Updated \(relativeTime(hit.updatedAt))"
                                )
                            }
                            .buttonStyle(.plain)
                            .notebookListRow(bottom: AppSpacing.s3)
                        }
                    }
                }
            }

            Color.clear
                .frame(height: AppSpacing.s10 + AppSpacing.s6)
                .notebookListRow(bottom: 0)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.clear)
    }

    private var createNotebookCard: some View {
        Card(radius: AppRadius.lg) {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                HStack(alignment: .center, spacing: AppSpacing.s2) {
                    Text("New notebook")
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Spacer()
                    Button(action: cancelAdd) {
                        Image(systemName: "xmark")
                            .font(.system(size: 12))
                            .foregroundStyle(AppColors.textTertiary)
                            .frame(width: 28, height: 28)
                            .background(
                                Circle()
                                    .fill(AppColors.subtle)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Cancel")
                }

                Text("Start with a clear title. Chapters and pages can come after.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)

                HStack(spacing: AppSpacing.s2) {
                    Image(systemName: "book.closed")
                        .font(.system(size: 16))
                        .foregroundStyle(AppColors.coral)

                    TextField(
                        "",
                        text: $newTitle,
                        prompt: Text("Notebook title").foregroundColor(AppColors.textTertiary)
                    )
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
                    .tint(AppColors.coral)
                    .submitLabel(.done)
                    .focused($addFocused)
                    .onSubmit(commitAdd)
                }
                .padding(.horizontal, AppSpacing.s4)
                .padding(.vertical, AppSpacing.s3)
                .background(AppColors.cardSolid)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.pill, style: .continuous)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: AppRadius.pill, style: .continuous))

                // Leaf-theme color row — same shape as the new-shelf
                // sheet so users learn the picker once.
                VStack(alignment: .leading, spacing: AppSpacing.s2) {
                    Text("COLOR")
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(AppColors.textSecondary)
                    LeafColorPicker(
                        selection: $newNotebookColorToken,
                        showPreview: true
                    )
                }

                HStack(spacing: AppSpacing.s2) {
                    Button("Cancel", action: cancelAdd)
                        .font(AppText.button)
                        .foregroundStyle(AppColors.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(
                            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                                .fill(AppColors.cardSolid)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                                .stroke(AppColors.borderStrong, lineWidth: 1)
                        )
                        .buttonStyle(.plain)

                    Button(action: commitAdd) {
                        Text("Create")
                            .font(AppText.button)
                            .foregroundStyle(AppColors.onAccent)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, AppSpacing.s3)
                            .background(
                                RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                                    .fill(canCreateNotebook ? AppColors.coral : AppColors.muted)
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!canCreateNotebook)
                    .opacity(canCreateNotebook ? 1 : 0.72)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .appShadow(.sm)
        .onAppear { addFocused = true }
    }

    private var addNotebookButton: some View {
        Button {
            isAdding = true
        } label: {
            HStack(spacing: AppSpacing.s2) {
                Image(systemName: "plus")
                    .font(.system(size: 16))
                Text("New notebook")
                    .font(AppText.button)
            }
            .foregroundStyle(AppColors.onAccent)
            .padding(.horizontal, AppSpacing.s5)
            .padding(.vertical, AppSpacing.s3)
            .background(AppColors.coral)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .appShadow(.fab)
        .padding(.trailing, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s4)
        .accessibilityLabel("Create notebook")
    }

    private var canCreateNotebook: Bool {
        !newTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var headerSummary: String {
        if vm.metrics.notebooks == 0 {
            return "Keep long-form notes organized by notebook, then branch into chapters and pages."
        }
        return "\(vm.metrics.notebooks) notebooks · \(vm.metrics.chapters) chapters · \(vm.metrics.pages) pages"
    }

    private var searchSummary: String {
        let trimmed = vm.query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return "Search notebook titles or scan page content across everything you’ve written."
        }

        let notebookMatches = vm.filteredShelves.count
        let pageMatches = vm.matchingPages.count
        return "\(notebookMatches) notebook match\(notebookMatches == 1 ? "" : "es") · \(pageMatches) page hit\(pageMatches == 1 ? "" : "s")"
    }

    private func commitAdd() {
        let trimmed = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        vm.addNotebook(title: trimmed, colorToken: newNotebookColorToken)
        newTitle = ""
        newNotebookColorToken = "coral"
        isAdding = false
    }

    private func cancelAdd() {
        newTitle = ""
        newNotebookColorToken = "coral"
        isAdding = false
    }
}

private struct NotebookSearchField: View {
    @Binding var query: String
    let onClear: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15))
                .foregroundStyle(AppColors.textTertiary)

            TextField("Search notebooks or page content…", text: $query)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)

            if !query.isEmpty {
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 16))
                        .foregroundStyle(AppColors.textTertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s3)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.pill, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.pill, style: .continuous))
    }
}

private struct SectionHeaderCard: View {
    let title: String
    let subtitle: String
    let badge: String

    var body: some View {
        Card(radius: AppRadius.lg) {
            HStack(alignment: .top, spacing: AppSpacing.s3) {
                VStack(alignment: .leading, spacing: AppSpacing.s1) {
                    Text(title)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Text(subtitle)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textTertiary)
                }

                Spacer(minLength: AppSpacing.s3)

                Text(badge)
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.coralDeep)
                    .padding(.horizontal, AppSpacing.s3)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(AppColors.coralSoft))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .appShadow(.xs)
    }
}

/// Compact shelf divider used inside the grouped notebooks list.
/// Smaller than `SectionHeaderCard` so it reads as a sub-section
/// within the Current-notebooks card, not a new top-level section.
private struct NotebookShelfSectionHeader: View {
    let name: String
    let count: Int

    var body: some View {
        HStack(alignment: .bottom, spacing: AppSpacing.s2) {
            Text(name.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coralDeep)
            Text("· \(count) book\(count == 1 ? "" : "s")")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
            Spacer()
        }
        .padding(.vertical, AppSpacing.s1)
    }
}

private struct EmptyStateCard: View {
    let title: String
    let subtitle: String
    /// Optional CTA pill rendered under the body copy. When
    /// `actionLabel` and `onAction` are both provided, the card
    /// gives the user a one-tap path forward instead of leaving
    /// them to find the floating + elsewhere on the screen.
    let actionLabel: String?
    let onAction: (() -> Void)?

    init(
        title: String,
        subtitle: String,
        actionLabel: String? = nil,
        onAction: (() -> Void)? = nil
    ) {
        self.title = title
        self.subtitle = subtitle
        self.actionLabel = actionLabel
        self.onAction = onAction
    }

    var body: some View {
        Card(radius: AppRadius.lg) {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                ZStack {
                    RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                        .fill(AppColors.coralSoft)
                    Image(systemName: "leaf.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(AppColors.coralDeep)
                }
                .frame(width: 44, height: 44)

                Text(title)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Text(subtitle)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)

                if let label = actionLabel, let onAction {
                    Button(action: onAction) {
                        HStack(spacing: AppSpacing.s2) {
                            Image(systemName: "plus")
                                .font(.system(size: 13, weight: .semibold))
                            Text(label)
                                .font(AppText.button)
                        }
                        .foregroundStyle(AppColors.onAccent)
                        .padding(.horizontal, AppSpacing.s4)
                        .padding(.vertical, AppSpacing.s2)
                        .background(Capsule().fill(AppColors.coral))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, AppSpacing.s2)
                    .accessibilityLabel(Text(label))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .appShadow(.xs)
    }
}

private struct NotebookShelfRow: View {
    let summary: ShelfRecord
    /// Hex of the parent shelf — used as a per-shelf fallback when
    /// the notebook itself has no color set, so all books on the
    /// same shelf read as one visual family in the list.
    let shelfColorHex: String?

    var body: some View {
        let notebook = summary.notebook

        HStack(alignment: .top, spacing: AppSpacing.s3) {
            NotebookColorChip(color: notebookColor)

            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                HStack(alignment: .center, spacing: AppSpacing.s2) {
                    Text(displayNotebookTitle(notebook.title))
                        .font(AppText.button)
                        .foregroundStyle(AppColors.textPrimary)
                        .lineLimit(1)

                    Spacer(minLength: AppSpacing.s2)

                    ShelfStatusPill(
                        label: summary.pageCount == 0 ? "Empty" : "Active",
                        color: summary.pageCount == 0 ? AppColors.warning : AppColors.success,
                        background: summary.pageCount == 0 ? AppColors.warningSoft : AppColors.successSoft
                    )

                    Image(systemName: "chevron.right")
                        .font(.system(size: 13))
                        .foregroundStyle(AppColors.textTertiary)
                }

                if let helper = helperText {
                    Text(helper)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(2)
                }

                HStack(spacing: AppSpacing.s3) {
                    CountChip(
                        systemIcon: "text.book.closed",
                        text: "\(summary.chapterCount) chapter\(summary.chapterCount == 1 ? "" : "s")"
                    )
                    CountChip(
                        systemIcon: "doc.text",
                        text: "\(summary.pageCount) page\(summary.pageCount == 1 ? "" : "s")"
                    )
                }

                Text("Updated \(relativeTime(notebook.updatedAt))")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(AppSpacing.s4)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .appShadow(.xs)
        .contentShape(Rectangle())
    }

    private var notebookColor: Color {
        // Preference order: notebook's own color → parent shelf's
        // color → coral fallback. The shelf step is what makes
        // every book on the same shelf read as one visual family
        // when the user hasn't picked a color per book.
        if let hex = summary.notebook.colorHex,
           let parsed = Color(hexString: hex) {
            return parsed
        }
        if let hex = shelfColorHex,
           let parsed = Color(hexString: hex) {
            return parsed
        }
        return AppColors.coral
    }

    private var helperText: String? {
        if summary.chapterCount == 0 { return "No chapters yet." }
        if summary.pageCount == 0 { return "No pages yet." }
        return nil
    }
}

private struct NotebookColorChip: View {
    let color: Color

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(color.opacity(0.16))
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(color.opacity(0.24), lineWidth: 1)
            Image(systemName: "book.closed")
                .font(.system(size: 18))
                .foregroundStyle(color)
        }
        .frame(width: 42, height: 42)
    }
}

private struct ShelfStatusPill: View {
    let label: String
    let color: Color
    let background: Color

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 6, height: 6)
            Text(label)
                .font(AppText.tag)
                .foregroundStyle(color)
        }
        .padding(.horizontal, AppSpacing.s2)
        .padding(.vertical, 4)
        .background(Capsule().fill(background))
    }
}

private struct CountChip: View {
    let systemIcon: String
    let text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemIcon)
                .font(.system(size: 13))
                .foregroundStyle(AppColors.textSecondary)
            Text(text)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
                .lineLimit(1)
        }
    }
}

private extension View {
    func notebookListRow(bottom: CGFloat = AppSpacing.s3) -> some View {
        listRowInsets(EdgeInsets(
            top: 0,
            leading: AppSpacing.s4,
            bottom: bottom,
            trailing: AppSpacing.s4
        ))
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}

private func displayNotebookTitle(_ title: String) -> String {
    let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? "Untitled" : trimmed
}

private func displayPageTitle(_ hit: PageSearchHit) -> String {
    if let title = hit.title?.trimmingCharacters(in: .whitespacesAndNewlines),
       !title.isEmpty {
        return title
    }

    let firstLine = hit.notes
        .components(separatedBy: .newlines)
        .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .first { !$0.isEmpty }

    return firstLine ?? "Untitled page"
}

private func pageContext(_ hit: PageSearchHit) -> String {
    "\(displayNotebookTitle(hit.notebookTitle)) · \(displayChapterTitle(hit.chapterTitle))"
}

private func relativeTime(_ isoDate: String) -> String {
    guard let date = ISO8601DateFormatter.withFractionalSeconds.date(from: isoDate) ??
        ISO8601DateFormatter().date(from: isoDate) else {
        return isoDate
    }
    return NotebookRelativeTime.short(for: date)
}

private func displayChapterTitle(_ title: String) -> String {
    let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? "Untitled chapter" : trimmed
}

private enum NotebookRelativeTime {
    static func short(for date: Date, now: Date = Date()) -> String {
        let delta = max(0, Int(now.timeIntervalSince(date)))
        if delta < 60 { return "just now" }
        if delta < 3600 { return "\(delta / 60)m ago" }
        if delta < 86_400 { return "\(delta / 3600)h ago" }
        let days = delta / 86_400
        if days == 1 { return "yesterday" }
        if days < 7 { return "\(days)d ago" }
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d"
        return formatter.string(from: date)
    }
}

private extension ISO8601DateFormatter {
    static let withFractionalSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

private extension Color {
    init?(hexString: String) {
        let cleaned = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        guard cleaned.count == 6,
              let rgb = UInt64(cleaned, radix: 16) else {
            return nil
        }
        let r = Double((rgb >> 16) & 0xFF) / 255.0
        let g = Double((rgb >> 8) & 0xFF) / 255.0
        let b = Double(rgb & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b)
    }
}

// MARK: - Overflow sheets

/// Compact sheet that takes a shelf name + color and creates it
/// via the NotebookTabViewModel. Title in the sheet is the same
/// lowercase serif used elsewhere; the color row uses the shared
/// `LeafColorPicker`.
private struct NewShelfSheet: View {
    @Binding var name: String
    @Binding var colorToken: String
    let onCreate: (String, String) -> Void
    let onCancel: () -> Void
    @FocusState private var nameFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("NEW SHELF")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("name your shelf")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text("Shelves group notebooks by area of life — \"work\", \"garden\", \"daily\".")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)

            TextField("Shelf name", text: $name)
                .textInputAutocapitalization(.words)
                .focused($nameFocused)
                .padding(AppSpacing.s3)
                .background(AppColors.inputBg)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))

            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text("COLOR")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                LeafColorPicker(selection: $colorToken, showPreview: true)
            }

            HStack(spacing: AppSpacing.s3) {
                AppButton("Cancel", variant: .secondary, action: onCancel)
                AppButton("Create shelf", variant: .primary) {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    onCreate(
                        trimmed.isEmpty ? "Untitled shelf" : trimmed,
                        colorToken
                    )
                }
            }
            .padding(.top, AppSpacing.s2)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .onAppear { nameFocused = true }
    }
}

/// Cross-notebook archive picker. Fetches archived pages from
/// `DriveRepository.listArchivedPages()` on appear, renders each
/// row with title + breadcrumb (notebook / chapter) + a Restore
/// button. Self-contained: holds its own @State + repo so the
/// surrounding NotebookTabViewModel doesn't take on a Drive
/// dependency for this single feature.
private struct ArchivedNotebooksSheet: View {
    let onClose: () -> Void
    let onOpenPage: (String) -> Void

    @State private var rows: [ArchivedPage] = []
    @State private var isLoading: Bool = true

    private let repository: DriveRepository = LocalDriveRepository.shared

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            Text("ARCHIVED")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("nothing's lost")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text("Pages you've archived stay here, sorted by when they went in. Restore brings them back to their original chapter.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.bottom, AppSpacing.s2)

            content
                .frame(maxWidth: .infinity)

            Spacer(minLength: AppSpacing.s2)
            AppButton("Done", variant: .primary, action: onClose)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .task { await load() }
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && rows.isEmpty {
            HStack {
                Spacer()
                ProgressView().tint(AppColors.themeGreenPrimary)
                Spacer()
            }
            .padding(.vertical, AppSpacing.s5)
        } else if rows.isEmpty {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text("Nothing here")
                    .font(.system(size: 18, weight: .regular, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                Text("Archive a page from its overflow menu and it'll show up here.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .padding(.vertical, AppSpacing.s4)
        } else {
            ScrollView {
                VStack(spacing: AppSpacing.s2) {
                    ForEach(rows) { row in
                        ArchivedPageRow(
                            row: row,
                            onOpen: { onOpenPage(row.id) },
                            onRestore: { Task { await restore(id: row.id) } }
                        )
                    }
                }
            }
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        rows = (try? await repository.listArchivedPages()) ?? []
    }

    private func restore(id: String) async {
        _ = try? await repository.restorePage(id: id)
        await load()
    }
}

private struct ArchivedPageRow: View {
    let row: ArchivedPage
    let onOpen: () -> Void
    let onRestore: () -> Void

    var body: some View {
        // The row body is a tappable region that opens the page;
        // the Restore pill handles its own tap and stops the open
        // gesture from firing via `.buttonStyle(.plain)` and
        // explicit `.contentShape` on the outer button.
        Button(action: onOpen) {
            HStack(alignment: .center, spacing: AppSpacing.s3) {
                Image(systemName: "archivebox.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(AppColors.greenText)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.title)
                        .font(AppText.button)
                        .foregroundStyle(AppColors.textPrimary)
                        .lineLimit(1)
                    Text("\(row.notebookTitle) / \(row.chapterTitle)")
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(1)
                    Text(row.archivedAt, format: .relative(presentation: .named))
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.textTertiary)
                }
                Spacer(minLength: AppSpacing.s2)
                Button(action: onRestore) {
                    Text("Restore")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.greenText)
                        .padding(.horizontal, AppSpacing.s3)
                        .padding(.vertical, AppSpacing.s1)
                        .background(Capsule().fill(AppColors.greenSoft))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, AppSpacing.s2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.canvas)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Open page")
    }
}
