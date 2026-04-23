/*
 * SignInScreen.swift
 * Sign in with Google — placeholder UI. Tapping the button calls
 * `AuthStore.signIn()`, which today drives the `StubGoogleAuthClient`.
 *
 * To ship a real sign-in, swap the stub for a GoogleSignIn-iOS-backed
 * client inside AuthStore. The UI here doesn't change.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct SignInScreen: View {
    @EnvironmentObject private var authStore: AuthStore
    @Environment(\.accentPalette) private var accent

    public init() {}

    public var body: some View {
        VStack(spacing: AppSpacing.s6) {
            Spacer()

            VStack(alignment: .center, spacing: AppSpacing.s3) {
                ReleafLogoRow(size: .md)

                Text("Capture your day")
                    .font(AppText.editorialTitle)
                    .foregroundStyle(AppColors.textPrimary)
                    .multilineTextAlignment(.center)

                Text("Notes, photos, voice, to-dos, scans, contacts, places — all in one page. Stored in your own Google Drive.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s6)
            }

            Spacer()

            VStack(spacing: AppSpacing.s3) {
                AppButton("Sign in with Google") {
                    Task { await authStore.signIn() }
                }

                if case .failed(let message) = authStore.state {
                    Text(message)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.danger)
                }

                Text("Releaf only sees files it creates in your Drive.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, AppSpacing.s6)
            .padding(.bottom, AppSpacing.s8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DotGridBackground())
    }
}

#Preview {
    SignInScreen()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
