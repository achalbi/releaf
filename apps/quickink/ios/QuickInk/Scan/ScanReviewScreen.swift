/*
 * ScanReviewScreen.swift
 *
 * Shown while OCR runs on a fresh capture, then while the user
 * acknowledges the completed result. Reads `ScanFlowController`'s
 * state and renders accordingly:
 *
 *   - .recognizing  → progress UI ("Recognizing page X of Y")
 *   - .complete     → summary + "Done" CTA returning to Home
 *   - .failed       → error + "Done" returning to Home
 *
 * Per-page editable text review (the user can edit the
 * recognized text before save) lands in Slice 4 alongside the
 * notes editor wrappers. For Slice 3 the screen is review-only —
 * the OCR result is auto-persisted as it lands; this screen just
 * surfaces progress and the final summary.
 */

import SwiftUI
import ReleafCoreDesignSystem

struct ScanReviewScreen: View {
    @ObservedObject var controller: ScanFlowController

    var body: some View {
        VStack(spacing: AppSpacing.s5) {
            Spacer()

            switch controller.state {
            case .idle:
                // Shouldn't render — `QuickInkRoot` swaps to
                // HomeScreen on `.idle`. Defensive empty body.
                EmptyView()

            case .recognizing(_, let totalPages, let completedPages):
                recognizingBody(completed: completedPages, total: totalPages)

            case .complete(_, let totalPages, let successCount):
                completeBody(success: successCount, total: totalPages)

            case .failed(let message):
                failedBody(message: message)
            }

            Spacer()

            // Done button — visible only on terminal states. Blocks
            // dismissal mid-OCR so a half-recognized capture isn't
            // left dangling on Home; users wait for the pipeline.
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
    }

    private var isRecognizing: Bool {
        if case .recognizing = controller.state { return true }
        return false
    }

    @ViewBuilder
    private func recognizingBody(completed: Int, total: Int) -> some View {
        ProgressView()
            .scaleEffect(1.5)
            .tint(AppColors.themeGreenPrimary)

        VStack(spacing: AppSpacing.s2) {
            Text("Recognizing text")
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)

            Text("Page \(completed) of \(total)")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
        }
    }

    @ViewBuilder
    private func completeBody(success: Int, total: Int) -> some View {
        Image(systemName: "checkmark.circle.fill")
            .font(.system(size: 64))
            .foregroundStyle(AppColors.themeGreenPrimary)

        VStack(spacing: AppSpacing.s2) {
            Text("Saved")
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)

            Text("Recognized text on \(success) of \(total) pages.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppSpacing.s5)
        }
    }

    @ViewBuilder
    private func failedBody(message: String) -> some View {
        Image(systemName: "exclamationmark.triangle.fill")
            .font(.system(size: 64))
            .foregroundStyle(AppColors.warning)

        VStack(spacing: AppSpacing.s2) {
            Text("Couldn't save")
                .font(AppText.pageTitle)
                .foregroundStyle(AppColors.textPrimary)

            Text(message)
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, AppSpacing.s5)
        }
    }
}
