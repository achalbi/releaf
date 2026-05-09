/*
 * AuthStore.swift
 * App-wide auth state. Wraps a `GoogleAuthClient` and persists the current
 * session.
 *
 * Persistence: sessions are stored in the iOS Keychain via
 * `KeychainTokenStore`. The Keychain item is configured with
 * `kSecAttrAccessibleAfterFirstUnlock`, which lets the background sync
 * refresh task read it after a cold launch.
 */

import Foundation
import Combine

@MainActor
public final class AuthStore: ObservableObject {
    public enum State: Equatable {
        case signedOut
        case signingIn
        case signedIn(GoogleAuthSession)
        case failed(String)
    }

    public static let shared = AuthStore()

    @Published public private(set) var state: State = .signedOut

    private let client: GoogleAuthClient

    public init(client: GoogleAuthClient = StubGoogleAuthClient()) {
        self.client = client
        restore()
    }

    public var isSignedIn: Bool {
        if case .signedIn = state { return true }
        return false
    }

    public var session: GoogleAuthSession? {
        if case .signedIn(let s) = state { return s }
        return nil
    }

    public func signIn() async {
        state = .signingIn
        do {
            let session = try await client.signIn()
            persist(session)
            state = .signedIn(session)
        } catch GoogleAuthError.cancelled {
            state = .signedOut
        } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? "Sign-in failed")
        }
    }

    public func signOut() async {
        await client.signOut()
        KeychainTokenStore.clear()
        state = .signedOut
    }

    // MARK: - External sign-in bridge

    /// Adopt a session obtained from an external flow (e.g. a
    /// `RealGoogleAuthClient` driven by a SwiftUI screen that owns the
    /// presenting view controller). Mirrors Android's equivalent on
    /// `AuthStore.kt`.
    public func adoptSession(_ session: GoogleAuthSession) {
        persist(session)
        state = .signedIn(session)
    }

    public func failSignIn(_ message: String) {
        state = .failed(message)
    }

    public func cancelSignIn() {
        state = .signedOut
    }

    public func beginExternalSignIn() {
        state = .signingIn
    }

    /// Pass-through to the underlying ``GoogleAuthClient/idToken()``.
    /// The QuickInk analytics flush task uses this to authenticate
    /// every request to api-quickink.thoughtbasics.com — the
    /// backend's `GoogleTokenVerifier` reads RS256 JWTs minted by
    /// GoogleSignIn-iOS.
    ///
    /// The store doesn't cache here — the SDK owns the cache plus
    /// `refreshTokensIfNeeded` semantics; calling repeatedly while
    /// the cached token is fresh is effectively free.
    public func idToken() async throws -> String {
        return try await client.idToken()
    }

    // MARK: - Persistence

    private func restore() {
        if let session = KeychainTokenStore.load() {
            state = .signedIn(session)
        }
    }

    private func persist(_ session: GoogleAuthSession) {
        try? KeychainTokenStore.save(session)
    }
}
