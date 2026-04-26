/*
 * OnboardingQuickGuideCard.swift
 *
 * Persistent "Quick Guide" widget rendered at the top of the signed-in
 * Home screen so users can replay the onboarding wizard whenever they
 * want. Visually mirrors the guide card in the design spec.
 */

import SwiftUI
import ReleafDesignSystem

public struct OnboardingQuickGuideCard: View {
    @Environment(\.accentPalette) private var accent
    let onShowIntro: () -> Void

    public init(onShowIntro: @escaping () -> Void) {
        self.onShowIntro = onShowIntro
    }

    public var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 3) {
                    Text("✦")
                        .font(.system(size: 12))
                        .foregroundStyle(accent.primary)
                    Text("QUICK GUIDE")
                        .font(OnboardTokens.badge)
                        .tracking(0.6)
                        .foregroundStyle(accent.primary)
                }
                Text("New to Releaf? See how it works.")
                    .font(.system(size: 15))
                    .foregroundStyle(OnboardTokens.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("A 60-second walkthrough experience.")
                    .font(OnboardTokens.ctaLabel)
                    .foregroundStyle(OnboardTokens.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            Button(action: onShowIntro) {
                HStack(spacing: 3) {
                    Text("▶").font(.system(size: 10)).foregroundStyle(.white)
                    Text("Show intro")
                        .font(OnboardTokens.button)
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(accent.primary)
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 18)
        .background(OnboardTokens.modalBg)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(OnboardTokens.borderRest, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
