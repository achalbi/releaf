/*
 * RootView.swift
 * Top-level dispatch — sign-in screen or the signed-in NavigationStack.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct RootView: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var uiPrefs: UiPreferences

    public init() {}

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            switch authStore.state {
            case .signedOut, .failed:
                SignInScreen()
            case .signingIn:
                SplashScreen()
            case .signedIn:
                // Onboarding gates the main shell on first launch.
                // Once dismissed (CTA on the onboarding view), the
                // flag persists in UiPreferences so subsequent
                // launches go straight to MainShell.
                if !uiPrefs.state.hasSeenOnboarding {
                    OnboardingView(onContinue: {
                        uiPrefs.markOnboardingSeen()
                    })
                } else {
                    MainShell()
                }
            }
        }
    }
}

/// Type-safe navigation keys. `NavigationLink(value:)` dispatches to the matching
/// `.navigationDestination(for:)` above.
public struct NotebookRoute: Hashable {
    public let id: String
    public init(id: String) { self.id = id }
}

public struct PageRoute: Hashable {
    public let id: String
    public init(id: String) { self.id = id }
}

/// Singleton-style tag for the workspace Tasks destination. No
/// payload — the screen pulls from [TaskRepository] scoped to the
/// signed-in user.
public struct TasksRoute: Hashable {
    public init() {}
}

/// Singleton-style tag for the Contacts directory destination.
/// Payload-free: the screen reads the signed-in user from
/// [AuthStore] and pulls contacts from the aggregator.
public struct ContactsRoute: Hashable {
    public init() {}
}

/// Singleton-style tag for the Call History destination.
/// Payload-free: the screen reads the signed-in user from
/// [AuthStore] and observes the local call-history log.
public struct CallHistoryRoute: Hashable {
    public init() {}
}

/// Singleton-style tag for the full Activity Log destination.
/// Payload-free: the screen reads the signed-in user from
/// [AuthStore] and reuses [RecentActivityViewModel] with the larger
/// `FULL_LIMIT` window.
public struct ActivityRoute: Hashable {
    public init() {}
}

#Preview("Signed out") {
    RootView()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
