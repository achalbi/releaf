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
    @EnvironmentObject private var uiPrefs: UiPreferences

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(spacing: AppSpacing.s6) {
                VStack(spacing: AppSpacing.s1) {
                    Text("SETTINGS")
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(AppColors.coral)

                    Text("Preferences")
                        .font(AppText.editorialTitle)
                        .foregroundStyle(AppColors.textPrimary)
                        .multilineTextAlignment(.center)
                }

                // Drive sync card.
                DriveSettingsSection()

                NotebookVariantCard(
                    selected: uiPrefs.state.notebookVariant,
                    onSelect: { uiPrefs.setNotebookVariant($0) }
                )

                AppButton("Sign out", variant: .secondary) {
                    Task { await authStore.signOut() }
                }
                .fixedSize(horizontal: true, vertical: false)
            }
            .padding(AppSpacing.s6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Notebook variant card

private struct NotebookVariantCard: View {
    let selected: NotebookListVariant
    let onSelect: (NotebookListVariant) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("NOTEBOOKS")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                Text("Layout")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Which visual treatment to use for shelves, chapters, and pages.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            HStack(spacing: AppSpacing.s3) {
                VariantOption(
                    label: "Classic",
                    subtitle: "List cards, plain chapters",
                    isActive: selected == .classic,
                    onTap: { onSelect(.classic) }
                )
                VariantOption(
                    label: "Hero cards",
                    subtitle: "Colored volumes + editorial pages",
                    isActive: selected == .variant1,
                    onTap: { onSelect(.variant1) }
                )
            }
        }
        .padding(AppSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
    }
}

private struct VariantOption: View {
    let label: String
    let subtitle: String
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text(label)
                    .font(AppText.button)
                    .foregroundStyle(isActive ? AppColors.coral : AppColors.textPrimary)
                Text(subtitle)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                    .multilineTextAlignment(.leading)
            }
            .padding(AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(isActive ? AppColors.coralSoft : AppColors.inputBg)
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(isActive ? AppColors.coral : .clear, lineWidth: 1.5)
            )
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    SettingsView()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
