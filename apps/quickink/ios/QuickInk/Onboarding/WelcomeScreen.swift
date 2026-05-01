/*
 * WelcomeScreen.swift
 *
 * Onboarding step 1/3. Brand intro + "Get started" CTA. Uses an
 * SF Symbol as the hero glyph for now; the brand-pass illustration
 * lands when QuickInk's onboarding-illustration scaffolding
 * extracts from Releaf (Phase 4 polish item, see file header on
 * `OnboardingState.swift`).
 */

import SwiftUI
import ReleafCoreDesignSystem

struct WelcomeScreen: View {
    let onContinue: () -> Void

    var body: some View {
        VStack(spacing: AppSpacing.s5) {
            Spacer()

            Image(systemName: "doc.text.viewfinder")
                .font(.system(size: 80))
                .foregroundStyle(AppColors.themeGreenPrimary)

            VStack(spacing: AppSpacing.s2) {
                Text("Welcome to QuickInk")
                    .font(AppText.pageTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Text("Scan documents, search the text, never lose a page.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s5)
            }

            Spacer()

            Button(action: onContinue) {
                Text("Get Started")
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
    }
}
