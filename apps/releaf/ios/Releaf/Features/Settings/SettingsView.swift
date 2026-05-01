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

                // Category management — rename / delete the custom
                // category labels the user has typed into notepad
                // entries. Predefined categories are listed
                // read-only.
                CategoryManagementSection()

                TimelineStyleCard(
                    selected: uiPrefs.state.timelineStyle,
                    onSelect: { uiPrefs.setTimelineStyle($0) }
                )

                FontWeightCard(
                    selected: uiPrefs.state.fontWeight,
                    onSelect: { uiPrefs.setFontWeight($0) }
                )

                ShowOnboardingCard {
                    // Surface the welcome screen again next time we
                    // hit the signed-in branch. RootView reads this
                    // flag and routes through OnboardingView when
                    // it's false. The user is bumped out of the
                    // current screen by the next state change.
                    uiPrefs.resetOnboarding()
                }

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

// MARK: - Timeline style card

/// Two-option picker for which renderer the Home activity timeline
/// uses. Identical data shape (`RecentActivityViewModel`) — the picker
/// just toggles the visual treatment between the classic dot-on-rail
/// card and the bramble vine variant.
private struct TimelineStyleCard: View {
    let selected: TimelineStyle
    let onSelect: (TimelineStyle) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("HOME")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                Text("Timeline style")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Which renderer to use for the recent-activity card on Home.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            HStack(spacing: AppSpacing.s3) {
                VariantOption(
                    label: "Classic",
                    subtitle: "Dot-on-rail timeline",
                    isActive: selected == .classic,
                    onTap: { onSelect(.classic) }
                )
                VariantOption(
                    label: "Bramble",
                    subtitle: "Vine with leaves and berries",
                    isActive: selected == .bramble,
                    onTap: { onSelect(.bramble) }
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

// MARK: - Font-weight card

/// Picker for the global typographic weight. Writes `AppFontWeight`
/// into `UiPreferences`; `ReleafApp` reads it and applies the
/// resolved `Font.Weight` via `.appFontWeight(_:)` at the root, so
/// every text view inherits the new weight on next render.
private struct FontWeightCard: View {
    let selected: AppFontWeight
    let onSelect: (AppFontWeight) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("TYPOGRAPHY")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                Text("Font weight")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Applies to every text style across the app.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            HStack(spacing: AppSpacing.s2) {
                ForEach(AppFontWeight.allCases, id: \.self) { option in
                    FontWeightOption(
                        weight: option,
                        isActive: selected == option,
                        onTap: { onSelect(option) }
                    )
                }
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

private struct FontWeightOption: View {
    let weight: AppFontWeight
    let isActive: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: AppSpacing.s1) {
                // Each chip previews its own weight — picking
                // "Medium" reads heavier than "Light" right here.
                Text("Aa")
                    .font(.system(size: 22))
                    .fontWeight(weight.fontWeight)
                    .foregroundStyle(isActive ? AppColors.coral : AppColors.textPrimary)
                Text(weight.label)
                    .font(AppText.meta)
                    .foregroundStyle(isActive ? AppColors.coral : AppColors.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, AppSpacing.s3)
            .padding(.horizontal, AppSpacing.s2)
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

private extension AppFontWeight {
    var label: String {
        switch self {
        case .light:    return "Light"
        case .regular:  return "Regular"
        case .medium:   return "Medium"
        case .semibold: return "SemiBold"
        }
    }
}

// MARK: - Show-onboarding card

/// Lets the user re-open the first-launch welcome flow. Tapping the
/// row resets `hasSeenOnboarding` to false; on the next state cycle
/// `RootView` swaps in `OnboardingView`. The action is destructive
/// in flow only, not data — no notebook content is touched.
private struct ShowOnboardingCard: View {
    let onShow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text("WELCOME")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                Text("Show onboarding again")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Reopen the welcome screen with the three concept tiles.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }

            Button(action: onShow) {
                HStack(spacing: AppSpacing.s2) {
                    Image(systemName: "leaf.fill")
                        .font(.system(size: 14))
                        .foregroundStyle(AppColors.coral)
                    Text("Show now")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.coral)
                }
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, AppSpacing.s2)
                .background(
                    Capsule().fill(AppColors.coralSoft)
                )
                .overlay(
                    Capsule().stroke(AppColors.coral.opacity(0.4), lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
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

#Preview {
    SettingsView()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
        .environmentObject(UiPreferences.shared)
}
