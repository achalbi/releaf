/*
 * NotebookDetailViewVariant1.swift
 * Editorial chapters screen — colored hero block with notebook stats,
 * then a cream body listing chapters with serif numerals.
 * Shares `NotebookDetailViewModel` with the classic screen.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct NotebookDetailViewVariant1: View {
    @StateObject private var viewModel: ShelfDetailViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showAddVolumeSheet: Bool = false

    public init(notebookId: String) {
        _viewModel = StateObject(wrappedValue: ShelfDetailViewModel(notebookId: notebookId))
    }

    public var body: some View {
        ZStack {
            AppColors.canvas.ignoresSafeArea()
            content
        }
        .toolbar(.hidden, for: .navigationBar)
        .hidesBottomBar()
        .task { viewModel.start() }
        .onDisappear { viewModel.stop() }
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
                AppButton("Back", variant: .secondary) { dismiss() }
                    .fixedSize(horizontal: true, vertical: false)
            }
        case .loaded(let detail):
            loaded(detail)
        }
    }

    private func loaded(_ detail: NotebookDetail) -> some View {
        let palette = ShelfTheme.palette(for: detail.notebook.colorToken)
        let sorted = detail.chapters.sorted { $0.position > $1.position }
        let currentChapter = sorted.max(by: { $0.updatedAt < $1.updatedAt }) ?? sorted.first
        return ScrollView {
            VStack(spacing: 0) {
                hero(notebook: detail.notebook,
                     chapters: detail.chapters,
                     palette: palette,
                     currentChapter: currentChapter,
                     onAddVolume: { showAddVolumeSheet = true })

                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    HStack {
                        Text("CHAPTERS")
                            .font(AppText.eyebrow)
                            .tracking(AppLetterSpacing.eyebrow)
                            .foregroundStyle(AppColors.themeGreenDeep)
                        Spacer()
                        HStack(spacing: AppSpacing.s4) {
                            Text("Sort ↓")
                                .font(AppText.meta)
                                .foregroundStyle(AppColors.textSecondary)
                            Text("Filter")
                                .font(AppText.meta)
                                .foregroundStyle(AppColors.textSecondary)
                        }
                    }
                    .padding(.top, AppSpacing.s5)

                    if sorted.isEmpty {
                        Text("No chapters yet.")
                            .font(AppText.body)
                            .foregroundStyle(AppColors.textSecondary)
                    } else {
                        VStack(spacing: AppSpacing.s2) {
                            ForEach(sorted) { chapter in
                                let isCurrent = chapter.id == currentChapter?.id
                                ChapterRow(
                                    chapter: chapter,
                                    palette: palette,
                                    isCurrent: isCurrent,
                                    onCreateFirstPage: { cid in
                                        viewModel.createPage(in: cid)
                                    }
                                )
                            }
                        }
                    }

                    NewChapterButton(palette: palette) {
                        viewModel.createChapter()
                    }
                    .padding(.top, AppSpacing.s3)

                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(.horizontal, AppSpacing.s5)
            }
        }
        .sheet(isPresented: $showAddVolumeSheet) {
            AddVolumeSheet(
                bookTitle: detail.notebook.title,
                onConfirm: { label in
                    viewModel.addVolume(volumeName: label)
                    showAddVolumeSheet = false
                },
                onDismiss: { showAddVolumeSheet = false }
            )
            .presentationDetents([.medium])
        }
    }

    private func hero(
        notebook: Notebook,
        chapters: [Chapter],
        palette: ShelfPalette,
        currentChapter: Chapter?,
        onAddVolume: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack(spacing: AppSpacing.s2) {
                Button {
                    dismiss()
                } label: {
                    HStack(spacing: AppSpacing.s2) {
                        Image(systemName: "chevron.left")
                        Text(breadcrumbText(for: notebook))
                            .font(AppText.eyebrow)
                            .tracking(AppLetterSpacing.eyebrow)
                    }
                    .foregroundStyle(palette.onBackground)
                }
                .buttonStyle(.plain)
                Spacer()
                Button(action: onAddVolume) {
                    Text("+ Volume")
                        .font(AppText.button)
                        .foregroundStyle(palette.onBackground)
                }
                .buttonStyle(.plain)
            }

            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text(notebookEyebrow(for: notebook))
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(palette.onBackground)

                Text(notebook.title)
                    .font(.system(size: 42, design: .serif))
                    .foregroundStyle(palette.onBackground)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)

                Text(notebookMeta(notebook))
                    .font(AppText.meta)
                    .foregroundStyle(palette.onBackgroundMuted)
            }

            Rectangle()
                .fill(palette.onBackgroundMuted.opacity(0.35))
                .frame(height: 1)

            HStack(alignment: .top, spacing: AppSpacing.s5) {
                statBlock(label: "READING",
                          value: readingValue(currentChapter),
                          palette: palette)
                statBlock(label: "LAST EDIT",
                          value: RelativeTime.short(for: notebook.updatedAt),
                          palette: palette)
                statBlock(label: "TAGGED",
                          value: firstTag(currentChapter) ?? "—",
                          palette: palette)
                Spacer()
            }
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.top, AppSpacing.s5)
        .padding(.bottom, AppSpacing.s6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background)
    }

    private func statBlock(label: String, value: String, palette: ShelfPalette) -> some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Text(label)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(palette.onBackgroundMuted)
            Text(value)
                .font(.system(size: 22, design: .serif))
                .foregroundStyle(palette.onBackground)
        }
    }

    private func breadcrumbText(for notebook: Notebook) -> String {
        let shelf = notebook.shelfName ?? "SHELF"
        return "SHELVES / \(shelf)"
    }

    private func notebookEyebrow(for notebook: Notebook) -> String {
        let shelf = notebook.shelfName ?? notebook.title.uppercased()
        if let vol = notebook.volumeNumber {
            return "\(shelf) · VOL \(String(format: "%02d", vol))"
        }
        return shelf
    }

    private func notebookMeta(_ nb: Notebook) -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM"
        let started = "Started \(fmt.string(from: nb.updatedAt))"
        return "\(started) · \(nb.chapterCount) chapters · \(nb.pageCount) pages"
    }

    private func readingValue(_ chapter: Chapter?) -> String {
        guard let chapter else { return "—" }
        return String(format: "Ch. %02d", chapter.position)
    }

    private func firstTag(_ chapter: Chapter?) -> String? {
        chapter?.pages.lazy.compactMap { $0.tags.first }.first
    }
}

// MARK: - Chapter row

private struct ChapterRow: View {
    let chapter: Chapter
    let palette: ShelfPalette
    let isCurrent: Bool
    /// Invoked when the user taps a chapter with no pages yet — the
    /// VM creates a page; the stream redraws with the new row.
    let onCreateFirstPage: (String) -> Void

    var body: some View {
        Group {
            if let firstPage = chapter.pages.first {
                NavigationLink(value: PageRoute(id: firstPage.id)) { rowContent }
                    .buttonStyle(.plain)
            } else {
                Button {
                    onCreateFirstPage(chapter.id)
                } label: {
                    rowContent
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var rowContent: some View {
        HStack(alignment: .top, spacing: AppSpacing.s4) {
                Text(String(format: "%02d", chapter.position))
                    .font(.system(size: 38, design: .serif))
                    .foregroundStyle(isCurrent ? AppColors.textPrimary : palette.background.opacity(0.55))
                    .frame(width: 64, alignment: .leading)

                VStack(alignment: .leading, spacing: AppSpacing.s1) {
                    Text(chapter.title)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(metaLine)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isCurrent {
                    Text("now")
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.greenText)
                        .padding(.horizontal, AppSpacing.s3)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(AppColors.successSoft))
                }
            }
            .padding(.vertical, AppSpacing.s3)
            .padding(.horizontal, AppSpacing.s3)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(isCurrent ? AppColors.cardSolid : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .strokeBorder(isCurrent ? AppColors.borderDefault : Color.clear, lineWidth: 1)
            )
            .overlay(alignment: .leading) {
                if isCurrent {
                    Rectangle()
                        .fill(palette.background)
                        .frame(width: 3)
                        .clipShape(Capsule())
                }
            }
    }

    private var metaLine: String {
        var parts: [String] = []
        parts.append("\(chapter.pages.count) page\(chapter.pages.count == 1 ? "" : "s")")
        parts.append("edited \(RelativeTime.short(for: chapter.updatedAt))")
        let photos = chapter.pages.reduce(0) { $0 + $1.counts.photos }
        if photos > 0 { parts.append("\(photos) photo\(photos == 1 ? "" : "s")") }
        return parts.joined(separator: " · ")
    }
}

private struct NewChapterButton: View {
    let palette: ShelfPalette
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
            HStack {
                Text("+ New chapter")
                    .font(AppText.button)
                    .foregroundStyle(palette.background)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppSpacing.s3)
            }
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(palette.background, lineWidth: 1.5)
            )
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Add volume sheet

private struct AddVolumeSheet: View {
    let bookTitle: String
    let onConfirm: (String?) -> Void
    let onDismiss: () -> Void

    @State private var label: String = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(
                        "A new volume will be added to \u{201C}\(bookTitle)\u{201D}. " +
                        "Leave the label blank to use the default \u{201C}\(bookTitle) vol N\u{201D}."
                    )
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                }
                Section("Volume label (optional)") {
                    TextField("e.g. 2026", text: $label)
                        .textInputAutocapitalization(.sentences)
                }
            }
            .navigationTitle("Add a new volume")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add volume") {
                        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
                        onConfirm(trimmed.isEmpty ? nil : trimmed)
                    }
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        NotebookDetailViewVariant1(notebookId: "nb-1")
    }
}
