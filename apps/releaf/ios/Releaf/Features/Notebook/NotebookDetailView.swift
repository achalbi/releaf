/*
 * NotebookDetailView.swift
 * Chapters + pages for a single notebook. Each page row pushes PageDetailView.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct NotebookDetailView: View {
    @StateObject private var viewModel: NotebookDetailViewModel
    @State private var renameDraft: String = ""
    @State private var newChapterDraft: String = ""
    @State private var chapterRenameDraft: String = ""

    public init(notebookId: String) {
        _viewModel = StateObject(wrappedValue: NotebookDetailViewModel(notebookId: notebookId))
    }

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            content
        }
        .navigationBarTitleDisplayMode(.inline)
        .hidesBottomBar()
        .task { await viewModel.load() }
        .alert(
            "Archive this notebook?",
            isPresented: $viewModel.confirmingArchive
        ) {
            Button("Archive", role: .destructive) {
                Task { await viewModel.confirmArchiveNotebook() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("All its chapters and pages move to archive together. You can restore from there.")
        }
        .sheet(isPresented: $viewModel.presentingRenameSheet) {
            NotebookRenameSheet(
                draft: $renameDraft,
                onSave: { Task { await viewModel.renameNotebook(to: renameDraft) } },
                onCancel: { viewModel.presentingRenameSheet = false }
            )
            .onAppear {
                if case .loaded(let detail) = viewModel.state {
                    renameDraft = detail.notebook.title
                }
            }
        }
        .sheet(isPresented: $viewModel.presentingNewChapterSheet) {
            NewChapterSheet(
                draft: $newChapterDraft,
                onCreate: {
                    Task {
                        await viewModel.createChapter(title: newChapterDraft)
                        newChapterDraft = ""
                    }
                },
                onCancel: {
                    viewModel.presentingNewChapterSheet = false
                    newChapterDraft = ""
                }
            )
        }
        // Per-chapter rename sheet. Bound to `renamingChapterId` —
        // presence of the id drives presentation; we pre-fill the
        // draft from the current title in `onAppear`.
        .sheet(
            isPresented: Binding(
                get: { viewModel.renamingChapterId != nil },
                set: { if !$0 { viewModel.renamingChapterId = nil } }
            )
        ) {
            ChapterRenameSheet(
                draft: $chapterRenameDraft,
                onSave: {
                    Task {
                        await viewModel.renameChapter(to: chapterRenameDraft)
                        chapterRenameDraft = ""
                    }
                },
                onCancel: {
                    viewModel.renamingChapterId = nil
                    chapterRenameDraft = ""
                }
            )
            .onAppear {
                if let id = viewModel.renamingChapterId {
                    chapterRenameDraft = viewModel.chapterTitle(id)
                }
            }
        }
        .alert(
            "Archive this chapter?",
            isPresented: Binding(
                get: { viewModel.archivingChapterId != nil },
                set: { if !$0 { viewModel.archivingChapterId = nil } }
            )
        ) {
            Button("Archive", role: .destructive) {
                Task { await viewModel.confirmArchiveChapter() }
            }
            Button("Cancel", role: .cancel) {
                viewModel.archivingChapterId = nil
            }
        } message: {
            Text("Its pages move to archive together. You can restore them from there.")
        }
        .overlay(alignment: .top) {
            if let toast = viewModel.toast {
                NotebookToast(
                    message: toast.message,
                    actionLabel: toast.actionLabel,
                    onAction: toast.actionKind.map { kind in
                        { Task { await viewModel.performToastAction(kind) } }
                    }
                )
                    .padding(.top, AppSpacing.s4)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(nanoseconds: 2_400_000_000)
                        withAnimation { viewModel.toast = nil }
                    }
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state {
        case .loading:
            ProgressView().tint(AppColors.coral)

        case .failed(let message):
            VStack(spacing: AppSpacing.s3) {
                Text(message)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                AppButton("Try again", variant: .secondary) {
                    Task { await viewModel.load() }
                }
                .fixedSize(horizontal: true, vertical: false)
            }

        case .loaded(let detail):
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s6) {
                    header(detail.notebook)
                    if let archivedAt = detail.notebook.archivedAt {
                        NotebookArchivedBanner(
                            archivedAt: archivedAt,
                            onRestore: { Task { await viewModel.restoreNotebook() } }
                        )
                    }
                    if detail.chapters.isEmpty {
                        NoChaptersCard(
                            onAdd: { viewModel.presentNewChapter() }
                        )
                    } else {
                        // Resolve the parent notebook's leaf palette
                        // once per render so each chapter section can
                        // tint its inline chip with the same color
                        // as the notebook header. Nil token → chip
                        // keeps the default soft-green.
                        let chapterPalette: ShelfPalette? =
                            detail.notebook.colorToken.map(ShelfTheme.palette(for:))
                        ForEach(detail.chapters) { chapter in
                            ChapterSection(
                                chapter: chapter,
                                palette: chapterPalette,
                                onRename: { viewModel.presentRenameChapter(chapter.id) },
                                onArchive: { viewModel.archiveChapter(chapter.id) }
                            )
                        }
                    }
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(AppSpacing.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private func header(_ notebook: Notebook) -> some View {
        // Composed top zone — leaf eyebrow on the left, overflow
        // menu on the right, big serif title (the notebook name)
        // below. Same pattern as PageDetailView and NotebookTabView
        // so all three top-level surfaces share one chrome.
        //
        // Eyebrow tint follows the notebook's color so the open
        // surface matches the chip color the user just tapped on
        // the list. Default-green when the notebook hasn't been
        // colored.
        let palette = ShelfTheme.palette(for: notebook.colorToken)
        let usesCustomTint = notebook.colorToken != nil
        return VStack(alignment: .leading, spacing: AppSpacing.s3) {
            HStack(alignment: .center) {
                LeafEyebrow(
                    "releaf · notebook",
                    glyphTint: usesCustomTint ? palette.background : nil,
                    labelTint: usesCustomTint ? palette.background : nil
                )
                Spacer()
                PageOverflowButton {
                    Button {
                        viewModel.presentRename()
                    } label: { Label("Rename notebook", systemImage: "pencil") }
                    Button {
                        viewModel.presentNewChapter()
                    } label: { Label("New chapter", systemImage: "plus.rectangle") }
                    Divider()
                    Button(role: .destructive) {
                        viewModel.archiveNotebook()
                    } label: { Label("Archive notebook", systemImage: "archivebox") }
                }
            }

            Text(notebook.title.lowercased())
                .font(.system(size: 32, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(2)
                .minimumScaleFactor(0.7)

            if let description = notebook.description {
                Text(description)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
            }
        }
    }
}

// MARK: - ChapterSection

private struct ChapterSection: View {
    let chapter: Chapter
    /// Resolved parent-notebook palette — when set, the page-count
    /// chip and the inline rename pencil tint to it so each chapter
    /// reads as part of the same family as the notebook header.
    /// Nil → fall back to default soft-green chrome.
    let palette: ShelfPalette?
    let onRename: () -> Void
    let onArchive: () -> Void

    var body: some View {
        // Pre-resolve the chip background + foreground colors so
        // the conditional code below stays readable. When a palette
        // is supplied we use its background as the tint hue and
        // the existing soft-tone strategy: ~16% alpha for the fill
        // works well across all four leaf themes.
        let chipFill: Color = palette.map { $0.background.opacity(0.16) } ?? AppColors.greenSoft
        let chipText: Color = palette?.background ?? AppColors.greenText

        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            // Header row: chapter eyebrow on the left + small
            // overflow on the right. Overflow uses the same
            // PageOverflowButton chrome as the notebook-level menu
            // so the visual vocabulary is consistent.
            HStack(alignment: .center, spacing: AppSpacing.s2) {
                Text(chapter.title.uppercased())
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                // Small inline page-count chip so users can see at
                // a glance how full each chapter is without
                // expanding it. Hidden when the chapter is empty —
                // the empty-state copy below covers that case.
                if !chapter.pages.isEmpty {
                    Text("\(chapter.pages.count)")
                        .font(AppText.tag)
                        .foregroundStyle(chipText)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(chipFill))
                }
                Spacer(minLength: AppSpacing.s2)
                // Inline rename pencil — single-tap shortcut for
                // the most common chapter edit. The overflow still
                // hosts the full action set (rename + archive),
                // but rename gets a direct affordance because it's
                // the one users reach for routinely. Tints to the
                // notebook palette so every interactive eyebrow on
                // the screen reads as one family.
                Button(action: onRename) {
                    Image(systemName: "pencil")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(chipText)
                        .frame(width: 26, height: 26)
                        .background(
                            Circle().fill(chipFill)
                        )
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Rename chapter"))
                PageOverflowButton {
                    Button(action: onRename) {
                        Label("Rename chapter", systemImage: "pencil")
                    }
                    Divider()
                    Button(role: .destructive, action: onArchive) {
                        Label("Archive chapter", systemImage: "archivebox")
                    }
                }
            }

            if chapter.pages.isEmpty {
                Text("No pages yet.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
            } else {
                ForEach(chapter.pages) { page in
                    NavigationLink(value: PageRoute(id: page.id)) {
                        PagePreviewRow(
                            title: page.title,
                            meta: metaLine(page),
                            photoCount: page.counts.photos
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// meta = "{capturedOn · } {counts}" — e.g. "Sun, Apr 19 · 3 photos · 2 to-dos"
private func metaLine(_ page: PageSummary) -> String {
    let counts = countsLine(page.counts)
    guard let captured = page.capturedOn, !captured.isEmpty else { return counts }
    return counts == "Empty page" ? captured : "\(captured) · \(counts)"
}

private func countsLine(_ c: PageCounts) -> String {
    var chips: [String] = []
    if c.photos > 0           { chips.append("\(c.photos) photo\(c.photos == 1 ? "" : "s")") }
    if c.voiceNotes > 0       { chips.append("\(c.voiceNotes) voice") }
    if c.todoItems > 0        { chips.append("\(c.todoItems) to-do\(c.todoItems == 1 ? "" : "s")") }
    if c.scannedDocuments > 0 { chips.append("\(c.scannedDocuments) scan\(c.scannedDocuments == 1 ? "" : "s")") }
    if c.contacts > 0         { chips.append("\(c.contacts) contact\(c.contacts == 1 ? "" : "s")") }
    if c.locations > 0        { chips.append("\(c.locations) place\(c.locations == 1 ? "" : "s")") }
    return chips.isEmpty ? "Empty page" : chips.joined(separator: " · ")
}

// MARK: - Archived banner

/// Soft-green banner surfaced at the top of an already-archived
/// notebook. Same vocabulary as the Page archived banner — green
/// soft fill, archive-box glyph, relative-time line, Restore pill.
private struct NotebookArchivedBanner: View {
    let archivedAt: Date
    let onRestore: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: AppSpacing.s3) {
            Image(systemName: "archivebox.fill")
                .font(.system(size: 14))
                .foregroundStyle(AppColors.greenText)
            VStack(alignment: .leading, spacing: 1) {
                Text("ARCHIVED")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.greenText)
                Text(archivedAt, format: .relative(presentation: .named))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            Spacer(minLength: AppSpacing.s2)
            Button(action: onRestore) {
                Text("Restore")
                    .font(AppText.button)
                    .foregroundStyle(AppColors.greenText)
                    .padding(.horizontal, AppSpacing.s3)
                    .padding(.vertical, AppSpacing.s1)
                    .background(Capsule().fill(AppColors.cardSolid))
                    .overlay(
                        Capsule().stroke(AppColors.greenText.opacity(0.4), lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, AppSpacing.s3)
        .padding(.vertical, AppSpacing.s2)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.greenSoft)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("Notebook is archived. Tap Restore to bring it back."))
    }
}

// MARK: - Overflow sheets + toast

/// Compact sheet for renaming a notebook. Mirrors the
/// NewShelfSheet pattern in the notebook tab — leaf eyebrow +
/// lowercase serif title + focused text input + Cancel / Save row.
private struct NotebookRenameSheet: View {
    @Binding var draft: String
    let onSave: () -> Void
    let onCancel: () -> Void
    @FocusState private var titleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("RENAME")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("rename notebook")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)

            TextField("Notebook title", text: $draft)
                .textInputAutocapitalization(.sentences)
                .focused($titleFocused)
                .padding(AppSpacing.s3)
                .background(AppColors.inputBg)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))

            HStack(spacing: AppSpacing.s3) {
                AppButton("Cancel", variant: .secondary, action: onCancel)
                AppButton("Save", variant: .primary, action: onSave)
            }
            .padding(.top, AppSpacing.s2)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .onAppear { titleFocused = true }
    }
}

/// Compact sheet for adding a new chapter. Same chrome as the
/// rename sheet; copy explains chapters as the in-notebook
/// grouping above pages.
private struct NewChapterSheet: View {
    @Binding var draft: String
    let onCreate: () -> Void
    let onCancel: () -> Void
    @FocusState private var titleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("NEW CHAPTER")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("name the chapter")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text("Chapters group pages inside a notebook — \"week 1\", \"recipes\", \"april\".")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)

            TextField("Chapter name", text: $draft)
                .textInputAutocapitalization(.sentences)
                .focused($titleFocused)
                .padding(AppSpacing.s3)
                .background(AppColors.inputBg)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))

            HStack(spacing: AppSpacing.s3) {
                AppButton("Cancel", variant: .secondary, action: onCancel)
                AppButton("Create chapter", variant: .primary, action: onCreate)
            }
            .padding(.top, AppSpacing.s2)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .onAppear { titleFocused = true }
    }
}

/// Sibling of NewChapterSheet — renames an existing chapter. Same
/// chrome and detents so the two sheets feel like one editing
/// surface in two modes.
private struct ChapterRenameSheet: View {
    @Binding var draft: String
    let onSave: () -> Void
    let onCancel: () -> Void
    @FocusState private var titleFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("RENAME CHAPTER")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text("rename the chapter")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text("Pick a name that captures what these pages are about.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)

            TextField("Chapter name", text: $draft)
                .textInputAutocapitalization(.sentences)
                .focused($titleFocused)
                .padding(AppSpacing.s3)
                .background(AppColors.inputBg)
                .overlay(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .stroke(AppColors.borderDefault, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))

            HStack(spacing: AppSpacing.s3) {
                AppButton("Cancel", variant: .secondary, action: onCancel)
                AppButton("Save", variant: .primary, action: onSave)
            }
            .padding(.top, AppSpacing.s2)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .onAppear { titleFocused = true }
    }
}

/// Empty-state card surfaced when a notebook has no chapters
/// yet. Mirrors the `EmptyStateCard` pattern from the notebook
/// list — leaf glyph + warmer copy + a primary CTA pill that
/// opens the existing create-chapter sheet. Saves the user from
/// hunting for the overflow's "New chapter" item on first
/// arrival.
private struct NoChaptersCard: View {
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            ZStack {
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.greenSoft)
                Image(systemName: "leaf.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(AppColors.themeGreenDeep)
            }
            .frame(width: 44, height: 44)

            Text("No chapters yet")
                .font(AppText.sectionTitle)
                .foregroundStyle(AppColors.textPrimary)

            Text("Chapters group pages inside a notebook — \"week 1\", \"recipes\", \"morning walks\".")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)

            Button(action: onAdd) {
                HStack(spacing: AppSpacing.s2) {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .semibold))
                    Text("New chapter")
                        .font(AppText.button)
                }
                .foregroundStyle(AppColors.onAccent)
                .padding(.horizontal, AppSpacing.s4)
                .padding(.vertical, AppSpacing.s2)
                .background(Capsule().fill(AppColors.coral))
            }
            .buttonStyle(.plain)
            .padding(.top, AppSpacing.s2)
            .accessibilityLabel(Text("New chapter"))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
    }
}

/// Same shape as the page-detail toast — small auto-dismissing
/// pill. Copy is on the ViewModel; this is just the surface.
/// Optional `actionLabel` + `onAction` add a trailing pill (e.g.
/// "Undo" after archive) so the user can act on the toast without
/// chasing a separate menu.
private struct NotebookToast: View {
    let message: String
    let actionLabel: String?
    let onAction: (() -> Void)?

    init(message: String, actionLabel: String? = nil, onAction: (() -> Void)? = nil) {
        self.message = message
        self.actionLabel = actionLabel
        self.onAction = onAction
    }

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Text(message)
                .font(.system(size: 14, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            if let label = actionLabel, let onAction {
                Button(action: onAction) {
                    Text(label.uppercased())
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.coral)
                        .padding(.horizontal, AppSpacing.s2)
                        .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(label))
            }
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s2)
        .background(AppColors.cardSolid)
        .overlay(
            Capsule().stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(Capsule())
        .appShadow(.md)
    }
}

#Preview {
    NavigationStack {
        NotebookDetailView(notebookId: "nb-1")
    }
}
