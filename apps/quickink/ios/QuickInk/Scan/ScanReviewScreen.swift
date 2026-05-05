/*
 * ScanReviewScreen.swift
 *
 * Shown after the user finishes a scan. Layout (top → bottom):
 *
 *   1. Big category-button grid  — the user picks a category
 *      (or none) for the in-flight capture. Tap-to-toggle
 *      persists immediately via `controller.setCategory(name)`.
 *   2. Saved page preview        — the first-page JPEG the
 *      scanner produced, so the user can confirm what was saved
 *      while still on this surface.
 *   3. Status indicator          — small progress / saved /
 *      failed badge. The hero used to be the progress UI; now
 *      it sits beneath the actionable affordances.
 *   4. Done button                — terminal-state-only.
 *
 * `captures.category` is per-capture, so the user can change
 * their mind any number of times during review.
 */

import SwiftUI
import ReleafCoreDesignSystem

struct ScanReviewScreen: View {
    @ObservedObject var controller: ScanFlowController
    let userId: String

    @StateObject private var categoriesVM: CategoryListViewModel

    init(controller: ScanFlowController, userId: String) {
        self.controller = controller
        self.userId = userId
        _categoriesVM = StateObject(
            wrappedValue: CategoryListViewModel(userId: userId)
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: QuickInkSpacing.s5) {
                    if !categoriesVM.categories.isEmpty,
                       !isFailed {
                        categoryButtonsGrid
                    }
                    if !isFailed {
                        savedImagePreview
                    }
                    statusIndicator
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s8)
                .padding(.bottom, QuickInkSpacing.s5)
            }

            if !isRecognizing {
                Button(action: { controller.dismiss() }) {
                    Text("Done")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(AppColors.themeGreenPrimary)
                        .clipShape(Capsule())
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.bottom, AppSpacing.s5)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
        .task { categoriesVM.start() }
    }

    private var isRecognizing: Bool {
        if case .recognizing = controller.state { return true }
        return false
    }

    private var isFailed: Bool {
        if case .failed = controller.state { return true }
        return false
    }

    // MARK: - Category buttons

    /// Two-column grid of bigger category buttons. Replaces the
    /// previous compact chip row — the picker is now the primary
    /// affordance on this screen, so it gets full-width buttons
    /// with serif headings instead of small pills.
    @ViewBuilder
    private var categoryButtonsGrid: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text("CATEGORY")
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                ],
                spacing: QuickInkSpacing.s3
            ) {
                ForEach(categoriesVM.categories, id: \.id) { cat in
                    categoryButton(
                        name: cat.name,
                        selected: cat.name == controller.selectedCategory
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func categoryButton(name: String, selected: Bool) -> some View {
        Button(action: {
            // Tap-to-toggle: tapping the active button clears the
            // selection so the user can leave the capture
            // un-categorised. Tapping a different one switches.
            controller.setCategory(selected ? nil : name)
        }) {
            // Compact button — was minHeight 64 / padding s3 /
            // QuickInkText.heading, which made the picker feel like a
            // hero grid. Shrunk to 44pt with cardTitle so the page
            // reads as a scan review with categories beneath, not a
            // category-picker hero. Mirrors Android.
            Text(name)
                .font(QuickInkText.cardTitle)
                .foregroundStyle(selected ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.horizontal, QuickInkSpacing.s2)
                .padding(.vertical, QuickInkSpacing.s2)
                .background(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .fill(selected ? QuickInkColors.accent : QuickInkColors.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .strokeBorder(selected ? QuickInkColors.accent : QuickInkColors.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(name)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    // MARK: - Saved image

    /// The first-page JPEG from the just-captured scan. Loaded
    /// from the local `file://` URL the scanner produced; falls
    /// back to a paper placeholder if the file isn't readable.
    @ViewBuilder
    private var savedImagePreview: some View {
        if let image = loadedPreview {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity)
                .frame(maxHeight: 360)
                .background(QuickInkColors.surface)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
        } else {
            ZStack {
                QuickInkColors.paper2
                Image(systemName: "doc.text.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 240)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        }
    }

    private var loadedPreview: UIImage? {
        guard let url = controller.previewImageURL else { return nil }
        let path = url.isFileURL ? url.path : url.absoluteString
        return UIImage(contentsOfFile: path)
    }

    // MARK: - Status

    @ViewBuilder
    private var statusIndicator: some View {
        switch controller.state {
        case .idle:
            EmptyView()

        case .recognizing(_, let total, let completed):
            HStack(spacing: QuickInkSpacing.s2) {
                ProgressView()
                    .tint(QuickInkColors.accent)
                Text("Recognizing page \(completed) of \(total)")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            .frame(maxWidth: .infinity)

        case .complete(_, let total, let success):
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(QuickInkColors.success)
                Text("Saved — text on \(success) of \(total) pages")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            .frame(maxWidth: .infinity)

        case .failed(let message):
            VStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(QuickInkColors.warning)
                Text("Couldn't save")
                    .font(QuickInkText.heading)
                    .foregroundStyle(QuickInkColors.ink)
                Text(message)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, QuickInkSpacing.s5)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, QuickInkSpacing.s5)
        }
    }
}
