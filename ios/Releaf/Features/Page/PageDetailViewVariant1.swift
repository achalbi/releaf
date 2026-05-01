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

    @State private var showVoiceSheet: Bool = false

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
                    voiceNotesAffordance
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
        .sheet(isPresented: $showVoiceSheet) {
            // Use the .large detent only — the recorder's recording
            // stage is taller than .medium, so the bottom hint and
            // cancel cue would clip at the half-height detent.
            VoiceNotesSheet(page: page)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
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

    // MARK: - Voice notes affordance
    //
    // Variant1 is an editorial single-page reading view, so the
    // capture-mode tab bar from the Classic variant doesn't exist.
    // This inline card is the only entry point users on Variant1
    // have for voice notes — both for recording new ones and seeing
    // existing ones. Tapping it presents `VoiceNotesSheet` as a
    // bottom modal.
    private var voiceNotesAffordance: some View {
        Button {
            showVoiceSheet = true
        } label: {
            HStack(spacing: AppSpacing.s3) {
                MicDiscBadge()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Voice notes")
                        .font(.system(size: 15, design: .serif))
                        .foregroundStyle(AppColors.textPrimary)
                    Text(voiceMeta)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textTertiary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(AppColors.textTertiary)
            }
            .padding(AppSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
        .padding(.top, AppSpacing.s3)
    }

    private var voiceMeta: String {
        let count = page.voiceNotes.count
        switch count {
        case 0:  return "Tap to record · 2 min max"
        case 1:  return "1 recorded · tap to listen or record"
        default: return "\(count) recorded · tap to listen or record"
        }
    }
}

// MARK: - Mic disc badge (inline)
//
// Tiny coral disc with a centered mic glyph. Reads the active accent
// palette so theme changes repaint it. Used in the voice-notes
// affordance card and could be lifted into ReleafDesignSystem if
// other surfaces start using the same mark.
private struct MicDiscBadge: View {
    @Environment(\.accentPalette) private var accent
    var body: some View {
        ZStack {
            Circle()
                .fill(accent.primary)
                .frame(width: 36, height: 36)
            Image(systemName: "mic.fill")
                .font(.system(size: 14, weight: .regular))
                .foregroundStyle(
                    Color(red: 0xFB / 255, green: 0xF8 / 255, blue: 0xEC / 255)
                )
        }
    }
}

// MARK: - Voice notes sheet
//
// Bottom modal that hosts both the existing-notes list (read-only,
// for playback) and `VoicePageRecorder` so users on Variant1 can
// record new voice notes without leaving the editorial reading view.
// Presented at `.medium` detent by default with `.large` available
// for users with many notes on a single page.
private struct VoiceNotesSheet: View {
    let page: Page

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                Text("VOICE NOTES")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textTertiary)
                    .padding(.top, AppSpacing.s4)

                if !page.voiceNotes.isEmpty {
                    VStack(spacing: AppSpacing.s3) {
                        ForEach(page.voiceNotes) { note in
                            VoiceNoteSheetCard(note: note)
                        }
                    }
                }

                VoicePageRecorder(
                    isEmpty: page.voiceNotes.isEmpty,
                    onSave: { _ in
                        // TODO: persist via the parent view model so the
                        // new VoiceNote shows in the list above without
                        // a manual refresh. Sheet stays open so the
                        // user can keep recording or replay the new
                        // note immediately.
                    },
                    onCancel: { /* no-op — clip discarded */ }
                )
                .padding(.top, AppSpacing.s2)

                Spacer(minLength: AppSpacing.s6)
            }
            .padding(.horizontal, AppSpacing.s5)
        }
        .background(AppColors.canvas)
    }
}

// MARK: - Voice-note row used by the sheet
//
// Compact card mirroring the Classic-variant `VoiceCard` but kept
// local so this file doesn't reach across into PageDetailView's
// private types. Plays/pauses are stubs for now — wiring is the
// view model's job.
private struct VoiceNoteSheetCard: View {
    let note: VoiceNote
    @Environment(\.accentPalette) private var accent

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            ZStack {
                Circle()
                    .fill(accent.primary)
                    .frame(width: 32, height: 32)
                Image(systemName: "play.fill")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(
                        Color(red: 0xFB / 255, green: 0xF8 / 255, blue: 0xEC / 255)
                    )
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(formatDurationMs(note.durationMs))
                    .font(.system(size: 14, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                if let transcription = note.transcription, !transcription.isEmpty {
                    Text("\u{201C}\(transcription)\u{201D}")
                        .font(AppText.meta.italic())
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
    }

    private func formatDurationMs(_ ms: Int) -> String {
        let total = ms / 1000
        return String(format: "%d:%02d", total / 60, total % 60)
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
