/*
 * NotebookDetailView.swift
 * Chapters + pages for a single notebook. Each page row pushes PageDetailView.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct NotebookDetailView: View {
    @StateObject private var viewModel: NotebookDetailViewModel

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
                    if detail.chapters.isEmpty {
                        Text("No chapters yet.")
                            .font(AppText.body)
                            .foregroundStyle(AppColors.textSecondary)
                    } else {
                        ForEach(detail.chapters) { chapter in
                            ChapterSection(chapter: chapter)
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
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("NOTEBOOK")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            Text(notebook.title)
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)

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

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text(chapter.title.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)

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

#Preview {
    NavigationStack {
        NotebookDetailView(notebookId: "nb-1")
    }
}
