/*
 * DriveSettingsSection.swift
 *
 * iOS settings card for Google Drive sync state + manual controls.
 * Mirror of Android's `DriveSettingsSection.kt`.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct DriveSettingsSection: View {
    @EnvironmentObject private var authStore: AuthStore
    @ObservedObject private var stateStore = SyncStateStore.shared

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("DRIVE")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)

                Text("Google Drive sync")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Text("Your notebooks live in your Drive under a `Releaf/` folder. Releaf can only see files it creates.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            // ---- Connection ----
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Connection")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                    Text(connectionLabel())
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                }
                Spacer()
            }

            // ---- Last sync ----
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Last sync")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                    Text(stateStore.state.lastFullSyncAt ?? "—")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                }
                Spacer()
                if stateStore.state.pendingCount > 0 {
                    Text("\(stateStore.state.pendingCount) pending")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.coral)
                }
            }

            HStack(spacing: AppSpacing.s3) {
                AppButton("Sync now", variant: .secondary) {
                    if isSignedIn {
                        SyncEnvironment.shared.scheduler.requestImmediate()
                    }
                }
                .frame(maxWidth: .infinity)

                AppButton("Restore from Drive", variant: .secondary) {
                    if isSignedIn {
                        SyncEnvironment.shared.scheduler.requestImmediate()
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(AppSpacing.s4)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .strokeBorder(AppColors.borderDefault, lineWidth: 1)
        )
    }

    private var isSignedIn: Bool {
        if case .signedIn = authStore.state { return true }
        return false
    }

    private func connectionLabel() -> String {
        switch authStore.state {
        case .signedIn(let s): return s.email
        case .signingIn:       return "Signing in…"
        case .failed:          return "Sign-in failed"
        case .signedOut:       return "Not connected"
        }
    }
}

#Preview {
    DriveSettingsSection()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
        .padding()
}
