/*
 * SettingsView.swift
 * Placeholder for the "Settings" tab. Hosts the Sign Out action for now —
 * the rest of settings (sync, appearance, account) are not yet designed.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct SettingsView: View {
    @EnvironmentObject private var authStore: AuthStore

    public init() {}

    public var body: some View {
        VStack(spacing: AppSpacing.s3) {
            Text("SETTINGS")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.coral)

            Text("Preferences")
                .font(AppText.editorialTitle)
                .foregroundStyle(AppColors.textPrimary)
                .multilineTextAlignment(.center)

            Text("Sync, appearance, and account settings are coming soon.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)
                .multilineTextAlignment(.center)

            Spacer().frame(height: AppSpacing.s6)

            AppButton("Sign out", variant: .secondary) {
                Task { await authStore.signOut() }
            }
            .fixedSize(horizontal: true, vertical: false)
        }
        .padding(AppSpacing.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    SettingsView()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
