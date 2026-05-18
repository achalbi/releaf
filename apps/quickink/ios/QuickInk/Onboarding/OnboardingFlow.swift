/*
 * OnboardingFlow.swift
 *
 * Top-level container for QuickInk's 3-screen onboarding. Drives
 * an `OnboardingState` and routes to the active step's screen.
 * Routing is state-driven (switch on `state.step`) rather than
 * NavigationStack-based — the screens advance forward only and a
 * full nav graph would be ceremony for three forward-only steps.
 *
 * The flow is presented by `QuickInkRoot` when
 * `OnboardingState.isCompleted()` is false; on completion it calls
 * `onComplete` and `QuickInkRoot` swaps to the main shell.
 *
 * Phase 4 Slice 4.1 — `authStore` flows through to `SignInScreen`
 * so the third step can run the real OAuth round-trip (or the
 * stub via `StubGoogleAuthClient`, which is what
 * `QuickInkAuthBinding` currently constructs).
 */

import SwiftUI
import ReleafCoreAuth

public struct OnboardingFlow: View {

    @StateObject private var state = OnboardingState()
    @ObservedObject var authStore: AuthStore
    private let onComplete: () -> Void

    public init(authStore: AuthStore, onComplete: @escaping () -> Void) {
        self.authStore = authStore
        self.onComplete = onComplete
    }

    public var body: some View {
        switch state.step {
        case .welcome:
            WelcomeScreen(onContinue: { state.advance() })
        case .permissions:
            PermissionsScreen(onContinue: { state.advance() })
        case .location:
            LocationPermissionScreen(onContinue: { state.advance() })
        case .languages:
            LanguagesScreen(
                state:      state,
                onContinue: { state.advance() }
            )
        case .signIn:
            SignInScreen(
                state:      state,
                authStore:  authStore,
                onSignedIn: {
                    // Commit the picked transcription-language
                    // allowlist into the now-keyable
                    // `profile_settings` row. Fire-and-forget — the
                    // sign-in callback advances synchronously to the
                    // home shell; the DB write isn't user-blocking.
                    if case .signedIn(let session) = authStore.state {
                        let ordered = TranscriptionLanguages.supported
                            .filter { state.selectedLanguageCodes.contains($0.code) }
                        let encoded = TranscriptionLanguages.encode(ordered)
                        let userId = session.userId
                        Task.detached(priority: .background) {
                            try? await ProfileSettingsRepository()
                                .setTranscriptionLanguages(
                                    userId: userId,
                                    codes:  encoded
                                )
                        }
                    }
                    onComplete()
                }
            )
        }
    }
}
