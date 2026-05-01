/*
 * SignInScreen.swift
 * Signed-out landing page. Four sections stacked vertically:
 *   - Top: brand lockup (leaf + wordmark + tagline + hero headline +
 *          one-line value prop).
 *   - BrandProductHero: notebook + phone-scan mockups inside a card.
 *   - BrandLoopSummary: three Write / Erase / Repeat info rows.
 *   - Bottom: sign-in button + Drive-scope reassurance.
 *
 * Top padding is `AppSpacing.s10 + AppSpacing.s6` (64pt) so the hero breathes
 * under the status bar before the brand lockup begins. The page is now
 * back to vertical scrolling because the BrandLoopSummary pushes total
 * content past a single viewport on most phones; without scroll the
 * sign-in button would be clipped on smaller devices.
 *
 * To ship a real sign-in, swap the stub for a GoogleSignIn-iOS-backed
 * client inside AuthStore. The UI here doesn't change.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct SignInScreen: View {
    @EnvironmentObject private var authStore: AuthStore
    private let brandGreen = Color(hex: 0x0B3F26)
    private let brandGreenLight = Color(hex: 0x7AA874)
    private let brandGreenDeep = Color(hex: 0x0B4328)

    public init() {}

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s5) {
                    header
                    BrandProductHero()
                    BrandLoopSummary()
                    actionArea
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.top, AppSpacing.s10 + AppSpacing.s6)
                .padding(.bottom, AppSpacing.s6)
                .frame(maxWidth: 540)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            ReleafLogoRow(
                size: .lg,
                leafGradientStart: brandGreenLight,
                leafGradientEnd: brandGreenDeep,
                wordmarkColor: brandGreen
            )

            Text("WRITE. ERASE. REPEAT.")
                .font(.system(size: 12, weight: .bold, design: .default))
                .tracking(2)
                .foregroundStyle(brandGreen)

            Text("Your ideas deserve\nmore than one life.")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)

            Text("Reusable notebook + smart app companion.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var actionArea: some View {
        VStack(spacing: AppSpacing.s3) {
            AppButton("Sign in with Google") {
                // Real flow when `GIDClientID` is set in Info.plist,
                // otherwise falls back to the stub client path.
                let signIn = GoogleSignInBinding.signInAction(authStore: authStore)
                Task { await signIn() }
            }
            .frame(maxWidth: .infinity)

            if case .failed(let message) = authStore.state {
                Text(message)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.danger)
            }

            Text("Releaf only sees files it creates in your Drive.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textTertiary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        }
    }
}

private struct BrandProductHero: View {
    var body: some View {
        HStack(alignment: .bottom, spacing: AppSpacing.s3) {
            NotebookMockup()
                .frame(maxWidth: .infinity)
                .frame(height: 168)
            PhoneScanMockup()
                .frame(width: 96, height: 160)
        }
        .padding(AppSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .appShadow(.xs)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Reusable notebook and Releaf app preview")
    }
}

private struct NotebookMockup: View {
    private let brandGreen = Color(hex: 0x0B3F26)

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(brandGreen)
            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color(hex: 0xEDE4CF))
                    .frame(height: 110)
                    .overlay(alignment: .topLeading) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Plan better tomorrow")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(brandGreen)
                                .lineLimit(1)
                            ForEach(0..<4, id: \.self) { index in
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Color(hex: 0xCBBF9E))
                                    .frame(width: index == 2 ? 60 : 82, height: 2.5)
                            }
                        }
                        .padding(11)
                    }

                Text("Reusable notebook")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(AppColors.onAccent)
            }
            .padding(12)

            ReleafLogo(
                size: 26,
                filled: true,
                fillGradientStart: Color(hex: 0x7AA874),
                fillGradientEnd: brandGreen,
                lineWidth: 1
            )
            .offset(x: 90, y: 100)
        }
    }
}

private struct PhoneScanMockup: View {
    private let brandGreen = Color(hex: 0x0B3F26)

    var body: some View {
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(Color(hex: 0x101610))
            .overlay {
                VStack(spacing: 7) {
                    Capsule()
                        .fill(Color.black.opacity(0.5))
                        .frame(width: 36, height: 5)
                    VStack(alignment: .leading, spacing: 7) {
                        Text("Project Plan")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(brandGreen)
                        ForEach(0..<3, id: \.self) { index in
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(brandGreen)
                                    .frame(width: 4, height: 4)
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Color(hex: 0xD8CBB3))
                                    .frame(width: index == 2 ? 38 : 56, height: 3)
                            }
                        }
                        Spacer(minLength: 0)
                        HStack {
                            Spacer()
                            ReleafLogo(
                                size: 28,
                                filled: true,
                                fillGradientStart: Color(hex: 0xB6D3AA),
                                fillGradientEnd: Color(hex: 0x7AA874),
                                lineWidth: 0.8
                            )
                            .opacity(0.7)
                        }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(AppColors.canvas)
                    )
                    Text("Scan. Save. Share.")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(AppColors.onAccent)
                        .padding(.bottom, 4)
                }
                .padding(8)
            }
    }
}

private struct BrandLoopSummary: View {
    private let items: [(String, String, BrandIconKind)] = [
        ("Write",  "Capture thoughts, plans, and pages.",        .write),
        ("Erase",  "Wipe clean with water and start again.",     .erase),
        ("Repeat", "Digitize, search, and share anytime.",       .repeatLoop),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            Text("BUILT FOR THE LOOP")
                .font(.system(size: 13, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(Color(hex: 0x0B3F26))
            ForEach(items.indices, id: \.self) { index in
                let item = items[index]
                LoopRow(title: item.0, copy: item.1, icon: item.2)
            }
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
    }
}

// ── Helpers ─────────────────────────────────────────────────────────
// Brand-icon set + LoopRow chrome shared by BrandLoopSummary above.

private enum BrandIconKind { case write, erase, repeatLoop, leaf, phone }

private extension BrandIconKind {
    /// SF Symbol name used by the iOS LoopRow + BrandIcon. Mirrors the
    /// hand-drawn glyph set the Android Canvas BrandIcon emits for
    /// Write / Erase / Repeat / Leaf / Phone. The Releaf "erase" is a
    /// water wipe (cloth + droplets), so the symbol leans on the water
    /// metaphor — `drop.fill` is the closest single SF symbol; we
    /// could swap to a custom Path later to get the cloth + drops
    /// composition seen in the Android Canvas BrandIcon.
    var systemName: String {
        switch self {
        case .write:      return "hand.draw"          // hand-with-pencil
        case .erase:      return "sparkles"           // wipe-and-shine
        case .repeatLoop: return "arrow.triangle.2.circlepath"
        case .leaf:       return "leaf.fill"
        case .phone:      return "iphone"
        }
    }
}

private struct BrandIcon: View {
    let kind: BrandIconKind
    var size: CGFloat = 17
    var body: some View {
        Image(systemName: kind.systemName)
            .font(.system(size: size))
            .foregroundStyle(Color(hex: 0x0B3F26))
    }
}

private struct LoopRow: View {
    let title: String
    let copy: String
    let icon: BrandIconKind

    var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            ZStack {
                Circle().fill(AppColors.greenSoft)
                BrandIcon(kind: icon)
            }
            .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(Color(hex: 0x0B3F26))
                Text(copy)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
        }
    }
}

#Preview {
    SignInScreen()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
