/*
 * QuickInkAuthBinding.swift
 *
 * Factory for QuickInk's `AuthStore`. Counterpart to Android's
 * `QuickInkAuthBinding.kt`. iOS's binding is much thinner because
 * the GoogleSignIn-iOS SDK handles its own UI presentation —
 * there's no Activity-result-launcher equivalent to wire up.
 *
 * Slice 4.1 ships with the **stub** client. The real swap is one
 * line away — replace
 *
 *     AuthStore(client: StubGoogleAuthClient())
 *
 * with
 *
 *     AuthStore(client: RealGoogleAuthClient(iosClientId: "<client-id>"))
 *
 * once the Xcode app target lands with:
 *   - `Info.plist` `CFBundleURLTypes` entry for the OAuth callback
 *     URL scheme (the reverse-DNS form of the iOS client ID).
 *   - `@main App.onOpenURL { GIDSignIn.sharedInstance.handle(url:) }`
 *     so the system delivers the OAuth callback back to the SDK.
 *   - The bundle ID `app.quickink.mobile` registered against the iOS
 *     client ID in QuickInk's Google Cloud project.
 *
 * Releaf's iOS surfaces still wire the stub everywhere too
 * (`RootView.swift`, `MainShell.swift`, etc.) — the iOS real-client
 * swap is a Releaf-side gap as well, gated on the same Xcode app
 * target work. When that lands, both apps swap together.
 */

import Foundation
import ReleafCoreAuth

@MainActor
public func makeQuickInkAuthStore() -> AuthStore {
    // Cached so every caller (`QuickInkRoot`'s @StateObject,
    // `QuickInkSyncEnvironment.install(authStore:)`, etc.) sees
    // the same underlying instance. Mirror of Releaf's
    // `AuthStore.shared` posture — `ReleafApp.swift` passes the
    // exact same instance to its @StateObject and to
    // `SyncEnvironment.install(authStore:)`. Without this cache
    // the @StateObject and the SyncEnvironment observer would
    // see two different AuthStore instances and only one would
    // ever mutate.
    QuickInkAuthStoreCache.shared
}

/// Process-scoped AuthStore singleton for QuickInk. Lives behind
/// the `makeQuickInkAuthStore()` factory so the swap to a real
/// client is still a one-line edit (same posture documented in the
/// file header).
@MainActor
private enum QuickInkAuthStoreCache {
    // Stub today; see file header for the swap.
    static let shared: AuthStore = AuthStore(client: StubGoogleAuthClient())
}
