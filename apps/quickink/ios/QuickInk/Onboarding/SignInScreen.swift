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

struct SignInScreen: View {
    @ObservedObject var state: OnboardingState
    @ObservedObject var authStore: AuthStore
    let onSignedIn: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)

            DriveIllustration()
                .frame(maxWidth: .infinity)
                .frame(height: 320)

            Spacer(minLength: QuickInkSpacing.s4)

            VStack(spacing: QuickInkSpacing.s3) {
                Text("Synced privately\nto your Drive")
                    .font(QuickInkText.onboardingTitle)
                    .foregroundStyle(QuickInkColors.ink)
                    .multilineTextAlignment(.center)
                    .lineSpacing(2)
                    .padding(.horizontal, QuickInkSpacing.s5)

                Text("Your notebook follows you across devices. We never see your pages.")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, QuickInkSpacing.s7)
            }
            .padding(.bottom, QuickInkSpacing.s4)

            // Drive toggle — soft surface card row.
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Back up to Google Drive")
                        .font(QuickInkText.label)
                        .foregroundStyle(QuickInkColors.ink)
                    Text("Recommended")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }
                Spacer()
                Toggle("", isOn: $state.driveBackupEnabled)
                    .labelsHidden()
                    .tint(QuickInkColors.accent)
                    .disabled(isSigningIn)
            }
            .padding(QuickInkSpacing.s4)
            .quickInkCard()
            .padding(.horizontal, QuickInkSpacing.s5)

            Spacer()

            // Page indicator dots — third one active.
            HStack(spacing: QuickInkSpacing.s2) {
                Circle()
                    .fill(QuickInkColors.border)
                    .frame(width: 8, height: 8)
                Circle()
                    .fill(QuickInkColors.border)
                    .frame(width: 8, height: 8)
                Circle()
                    .fill(QuickInkColors.accent)
                    .frame(width: 24, height: 8)
            }
            .padding(.bottom, QuickInkSpacing.s5)

            footer
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.bottom, QuickInkSpacing.s7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
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
                .tint(QuickInkColors.accent)
                .scaleEffect(1.2)
                .padding(.vertical, QuickInkSpacing.s3)
        } else {
            VStack(spacing: QuickInkSpacing.s2) {
                if let message = errorMessage {
                    Text(message)
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.danger)
                        .multilineTextAlignment(.center)
                }

                Button(action: signIn) {
                    // Per latest design pass, the leading
                    // `person.crop.circle` glyph is dropped — the
                    // mock CTA is text-only with the same serif
                    // styling as the hero, letting the button feel
                    // editorial rather than chrome-y. Swap to a real
                    // Google "G" brand mark if/when the brand pass
                    // calls for it.
                    Text("Continue with Google")
                        .font(QuickInkFont.serif(18, weight: .medium))
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .quickInkOnboardingCTA()
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
        // `GoogleSignInBinding.signInAction(authStore:)` returns a
        // closure that runs the real Google flow when `GIDClientID`
        // is present in Info.plist (production / dev builds with the
        // Xcode app target) and falls through to `authStore.signIn()`
        // (stub) otherwise — keeping SwiftUI previews and CLI builds
        // working. This swap is the third of the three steps listed
        // in `QuickInkAuthBinding.swift`'s file header.
        let action = GoogleSignInBinding.signInAction(authStore: authStore)
        Task { await action() }
    }

    private func commitChoices() {
        state.markComplete()
        SettingsState.commitOnboardingChoices(
            driveBackupEnabled: state.driveBackupEnabled
        )
    }
}
