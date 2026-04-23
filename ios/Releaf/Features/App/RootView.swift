/*
 * RootView.swift
 * Top-level dispatch — sign-in screen or the signed-in NavigationStack.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct RootView: View {
    @EnvironmentObject private var authStore: AuthStore

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
                MainShell()
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

#Preview("Signed out") {
    RootView()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
