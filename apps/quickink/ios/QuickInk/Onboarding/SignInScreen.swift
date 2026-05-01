/*
 * SignInScreen.swift
 *
 * Onboarding step 3/3 — Google Sign-In + Drive backup toggle.
 * Per QUICKINK_PROPOSAL.md §1, screen 3 carries the Drive toggle
 * (a v8 lock decision). v1 default is on — Drive sync is the
 * value prop; opting out is for users who explicitly don't want
 * cloud backup.
 *
 * Phase 4 Slice 4.1 — sign-in is wired through `AuthStore`. The
 * button taps `await authStore.signIn()`; whichever client is
 * bound (stub today via `QuickInkAuthBinding.makeQuickInkAuthStore()`,
 * `RealGoogleAuthClient` once the Xcode app target lands and the
 * binding swaps) drives the round-trip. State transitions
 * (`signingIn` → `signedIn` / `failed`) flow through the
 * `@Published state` on `AuthStore`; this screen renders a loading
 * indicator while the round-trip's in flight and a coral error
 * message on failure.
 *
 * The screen is also reused by `QuickInkRoot.ReSignInGate` for
 * the sign-out → re-sign-in flow (Option A). The Drive toggle
 * stays functional in that path — toggling it overwrites
 * Settings, same as the first-run flow.
 *
 * Mirror of Android `SignInScreen.kt`.
 */

import SwiftUI
import ReleafCoreAuth
import ReleafCoreDesignSystem

struct SignInScreen: View {
    @ObservedObject var state: OnboardingState
    @ObservedObject var authStore: AuthStore
    let onSignedIn: () -> Void

    var body: some View {
        VStack(spacing: AppSpacing.s5) {
            Spacer()

            Image(systemName: "icloud.and.arrow.up")
                .font(.system(size: 64))
                .foregroundStyle(AppColors.themeGreenPrimary)

            VStack(spacing: AppSpacing.s2) {
                Text("Sign in to back up")
                    .font(AppText.pageTitle)
                    .foregroundStyle(AppColors.textPrimary)

                Text("Sign in with Google so your scans sync to Drive and follow you across devices.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s5)
            }

            // Drive toggle. Held in OnboardingState; persisted
            // into Settings on success via `commitChoices` below.
            Toggle(isOn: $state.driveBackupEnabled) {
                Text("Back up to Google Drive")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
            }
            .padding(.horizontal, AppSpacing.s5)
            .disabled(isSigningIn)

            Spacer()

            footer
                .padding(.horizontal, AppSpacing.s5)
                .padding(.bottom, AppSpacing.s5)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
        .onChange(of: authStore.state) { newValue in
            // On successful sign-in, persist the onboarding
            // bookkeeping (markComplete + commit Drive choice into
            // Settings) and fire `onSignedIn`. The handler runs on
            // both the first-run and the ReSignInGate paths;
            // markComplete is idempotent and the Settings write is
            // user-facing-correct in both cases (it reflects the
            // user's current Drive choice).
            //
            // One-arg `onChange(of:perform:)` is the iOS 16 form;
            // the two-arg `(oldValue, newValue)` overload is
            // iOS 17+. Stay on the older shape so the QuickInk
            // package's `.iOS(.v16)` floor compiles cleanly.
            if case .signedIn = newValue {
                commitChoices()
                onSignedIn()
            }
        }
    }

    @ViewBuilder
    private var footer: some View {
        if isSigningIn {
            ProgressView()
                .tint(AppColors.themeGreenPrimary)
        } else {
            VStack(spacing: AppSpacing.s2) {
                if let message = errorMessage {
                    Text(message)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.coralDeep)
                        .multilineTextAlignment(.center)
                }

                Button(action: signIn) {
                    Text("Sign in with Google")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(AppColors.themeGreenPrimary)
                        .clipShape(Capsule())
                }
            }
        }
    }

    private var isSigningIn: Bool {
        if case .signingIn = authStore.state { return true }
        return false
    }

    private var errorMessage: String? {
        if case .failed(let message) = authStore.state { return message }
        return nil
    }

    private func signIn() {
        Task { await authStore.signIn() }
    }

    private func commitChoices() {
        state.markComplete()
        SettingsState.commitOnboardingChoices(
            driveBackupEnabled: state.driveBackupEnabled
        )
    }
}
