/*
 * PageDetailViewVariant1.swift
 * Editorial single-page view — colored breadcrumb header, prose body
 * with tag pills and a pull-quote block, and a floating action bar.
 * Shares `PageDetailViewModel` with the classic screen.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct PageDetailViewVariant1: View {
    @StateObject private var viewModel: ShelfPageViewModel
    @Environment(\.dismiss) private var dismiss

    public init(pageId: String) {
        _viewModel = StateObject(wrappedValue: ShelfPageViewModel(pageId: pageId))
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
        case .loaded(let page):
            Loaded(page: page, onBack: { dismiss() })
        }
    }
}

private struct Loaded: View {
    let page: Page
    let onBack: () -> Void

    var body: some View {
        let palette = ShelfTheme.palette(for: "green") // hero color follows notebook
        VStack(spacing: 0) {
            header(palette: palette)
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    dateEyebrow
                    title
                    tagRow
                    prose
                    if let quoteNote = quoteNote {
                        PullQuote(note: quoteNote, palette: palette)
                    }
                    photoGrid
                    Spacer(minLength: AppSpacing.s10 + AppSpacing.s6)
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.top, AppSpacing.s5)
            }
        }
        .overlay(alignment: .bottom) {
            PageActionBar(
                pageIndex: 3,
                pageCount: 6,
                onPrev: {},
                onNext: {}
            )
        }
    }

    // MARK: - Header

    private func header(palette: ShelfPalette) -> some View {
        HStack(alignment: .center) {
            Button(action: onBack) {
                HStack(spacing: AppSpacing.s2) {
                    Image(systemName: "chevron.left")
                    Text(breadcrumb.uppercased())
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                }
                .foregroundStyle(palette.onBackground)
            }
            .buttonStyle(.plain)
            Spacer()
            Text(pageCounter)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(palette.onBackground)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s4)
        .frame(maxWidth: .infinity)
        .background(palette.background)
    }

    private var breadcrumb: String {
        // The page carries notebookId + chapterId only; we use the
        // notebook title + chapter position we'd fetch separately.
        // For the fake, the seed page matches "Plant log" / "Ch. 07".
        "Plant log / Ch. 07"
    }

    private var pageCounter: String { "PAGE 03 / 06" }

    // MARK: - Body sections

    private var dateEyebrow: some View {
        Text((page.capturedOn ?? "").uppercased())
            .font(AppText.eyebrow)
            .tracking(AppLetterSpacing.eyebrow)
            .foregroundStyle(AppColors.themeGreenDeep)
    }

    private var title: some View {
        Text(page.title)
            .font(.system(size: 34, design: .serif))
            .foregroundStyle(AppColors.textPrimary)
            .lineLimit(3)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var tagRow: some View {
        if !page.tags.isEmpty {
            HStack(spacing: AppSpacing.s2) {
                ForEach(Array(page.tags.enumerated()), id: \.offset) { index, tag in
                    TagPill(label: tag, accent: index < 2)
                }
                Spacer()
            }
        }
    }

    @ViewBuilder
    private var prose: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            ForEach(proseNotes) { note in
                Text(note.body)
                    .font(.system(size: 17, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var proseNotes: [Note] {
        page.notes.filter { !$0.body.hasPrefix("NOTE TO SELF") }
    }

    private var quoteNote: Note? {
        page.notes.first(where: { $0.body.hasPrefix("NOTE TO SELF") })
    }

    @ViewBuilder
    private var photoGrid: some View {
        if !page.photos.isEmpty {
            let columns = [GridItem(.flexible(), spacing: AppSpacing.s3),
                           GridItem(.flexible(), spacing: AppSpacing.s3)]
            LazyVGrid(columns: columns, spacing: AppSpacing.s3) {
                ForEach(page.photos) { photo in
                    PhotoTile(photo: photo)
                }
            }
            .padding(.top, AppSpacing.s2)
        }
    }
}

// MARK: - Tag pill

private struct TagPill: View {
    let label: String
    /// `true` = green fill; `false` = neutral fill.
    let accent: Bool

    var body: some View {
        Text(label)
            .font(AppText.tag)
            .foregroundStyle(accent ? AppColors.greenText : AppColors.textSecondary)
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, 5)
            .background(
                Capsule().fill(accent ? AppColors.successSoft : AppColors.neutralSoft)
            )
    }
}

// MARK: - Pull quote

private struct PullQuote: View {
    let note: Note
    let palette: ShelfPalette

    var body: some View {
        let parts = note.body.split(separator: "\n", maxSplits: 1).map(String.init)
        let header = parts.first ?? "NOTE TO SELF"
        let body = parts.count > 1 ? parts[1] : ""
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            Rectangle()
                .fill(palette.background)
                .frame(width: 3)
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text(header)
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                Text(body)
                    .font(.system(size: 17, design: .serif).italic())
                    .foregroundStyle(AppColors.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(.vertical, AppSpacing.s2)
    }
}

// MARK: - Photo tile

private struct PhotoTile: View {
    let photo: Photo
    var body: some View {
        let palette = ShelfTheme.palette(for: "green")
        ZStack(alignment: .bottomLeading) {
            LinearGradient(
                colors: [palette.accentSoft, palette.background],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            .frame(height: 180)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))

            if let caption = photo.caption {
                Text(caption)
                    .font(AppText.tag)
                    .foregroundStyle(palette.onBackground)
                    .padding(AppSpacing.s3)
            }
        }
    }
}

// MARK: - Bottom action bar

private struct PageActionBar: View {
    let pageIndex: Int
    let pageCount: Int
    let onPrev: () -> Void
    let onNext: () -> Void

    var body: some View {
        HStack(spacing: AppSpacing.s4) {
            HStack(spacing: AppSpacing.s4) {
                Image(systemName: "line.3.horizontal")
                Image(systemName: "photo")
                Image(systemName: "plus")
            }
            .font(.system(size: 16))
            .foregroundStyle(AppColors.textPrimary)

            Spacer()

            HStack(spacing: 6) {
                ForEach(0..<pageCount, id: \.self) { i in
                    Circle()
                        .fill(i == pageIndex - 1 ? AppColors.themeGreenPrimary : AppColors.textPrimary.opacity(0.85))
                        .frame(width: i == pageIndex - 1 ? 10 : 6,
                               height: i == pageIndex - 1 ? 10 : 6)
                }
            }

            Spacer()

            Button(action: onNext) {
                HStack(spacing: AppSpacing.s2) {
                    Text("Next")
                        .font(AppText.button)
                    Image(systemName: "arrow.right")
                }
                .foregroundStyle(AppColors.onPrimary)
                .padding(.horizontal, AppSpacing.s4)
                .padding(.vertical, AppSpacing.s2)
                .background(Capsule().fill(AppColors.actionPrimary))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.top, AppSpacing.s3)
        .padding(.bottom, AppSpacing.s4)
        .background(
            Rectangle()
                .fill(AppColors.canvas)
                .overlay(
                    Rectangle()
                        .fill(AppColors.borderDefault)
                        .frame(height: 1),
                    alignment: .top
                )
        )
    }
}

#Preview {
    NavigationStack {
        PageDetailViewVariant1(pageId: "pg-1")
    }
}
