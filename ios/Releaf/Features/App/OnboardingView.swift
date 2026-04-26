/*
 * OnboardingView.swift
 *
 * First-launch screen. Surfaces three concept tiles — capture, the
 * notebook structure, and the RE-LEAF metric — over a soft cream
 * canvas, with a single "start capturing" CTA at the bottom.
 *
 * Triggered by `UiPreferences.shared.state.hasSeenOnboarding == false`
 * when `RootView` is in the signed-in branch. Dismissing the screen
 * (via the CTA or the small "skip" text-button) calls
 * `UiPreferences.markOnboardingSeen()`, which flips the flag for
 * good — repeated calls are idempotent.
 */

import SwiftUI
import ReleafDesignSystem

public struct OnboardingView: View {
    let onContinue: () -> Void

    public init(onContinue: @escaping () -> Void) {
        self.onContinue = onContinue
    }

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s5) {
                    header
                    VStack(spacing: AppSpacing.s3) {
                        ConceptTile(
                            eyebrow: "CAPTURE",
                            title: "everything in one place",
                            body: "photos, voice, scans, to-dos and a daily note — all in one tap.",
                            iconName: "leaf.fill",
                            tint: AppColors.coral
                        )
                        ConceptTile(
                            eyebrow: "ORGANIZE",
                            title: "shelves, books, chapters",
                            body: "group what you keep into shelves and notebooks, with chapters for the in-betweens.",
                            iconName: "books.vertical",
                            tint: AppColors.themeGreenPrimary
                        )
                        ConceptTile(
                            eyebrow: "RE-LEAF",
                            title: "see the trees you save",
                            body: "every digital capture replaces a sheet of paper. we count the sheets and turn them into trees.",
                            iconName: "tree.fill",
                            tint: AppColors.themeYellowDeep
                        )
                    }
                    Spacer(minLength: AppSpacing.s4)
                    cta
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.top, AppSpacing.s8)
                .padding(.bottom, AppSpacing.s6)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            LeafEyebrow("releaf · welcome")
            Text("welcome to releaf")
                .font(.system(size: 34, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
            Text("a quieter notebook for the things you'd otherwise scribble onto paper.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)
        }
    }

    private var cta: some View {
        VStack(spacing: AppSpacing.s2) {
            AppButton("Start capturing", variant: .primary) {
                onContinue()
            }
            .frame(maxWidth: .infinity)
        }
    }
}

/// Single feature tile — eyebrow, serif title, supporting copy, and
/// a soft-tinted leaf-glyph at the leading edge.
private struct ConceptTile: View {
    let eyebrow: String
    let title: String
    let body: String
    let iconName: String
    let tint: Color

    var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            ZStack {
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(tint.opacity(0.16))
                Image(systemName: iconName)
                    .font(.system(size: 18))
                    .foregroundStyle(tint)
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 4) {
                Text(eyebrow)
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(tint)
                Text(title)
                    .font(.system(size: 20, weight: .regular, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                Text(body)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
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
    }
}

#Preview("Onboarding") {
    OnboardingView(onContinue: {})
        .environmentObject(UiPreferences.shared)
}
