/*
 * AuthStore.swift
 * App-wide auth state. Wraps a `GoogleAuthClient` and persists the current
 * session so the app can resume without re-prompting.
 *
 * For now persistence is UserDefaults-based (so previews work). Before ship:
 * move the access/refresh tokens into Keychain.
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
    private let defaults: UserDefaults
    private let storageKey = "releaf.auth.session"

    public init(
        client: GoogleAuthClient = StubGoogleAuthClient(),
        defaults: UserDefaults = .standard
    ) {
        self.client = client
        self.defaults = defaults
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
        defaults.removeObject(forKey: storageKey)
        state = .signedOut
    }

    // MARK: - Persistence (UserDefaults placeholder — move to Keychain)

    private func restore() {
        guard
            let data = defaults.data(forKey: storageKey),
            let stored = try? JSONDecoder().decode(StoredSession.self, from: data)
        else { return }
        let session = stored.toDomain()
        state = .signedIn(session)
    }

    private func persist(_ session: GoogleAuthSession) {
        let stored = StoredSession(session)
        if let data = try? JSONEncoder().encode(stored) {
            defaults.set(data, forKey: storageKey)
        }
    }

    private struct StoredSession: Codable {
        let userId: String
        let email: String
        let displayName: String?
        let accessToken: String
        let refreshToken: String?
        let expiresAt: Date

        init(_ s: GoogleAuthSession) {
            userId = s.userId
            email = s.email
            displayName = s.displayName
            accessToken = s.accessToken
            refreshToken = s.refreshToken
            expiresAt = s.expiresAt
        }

        func toDomain() -> GoogleAuthSession {
            GoogleAuthSession(
                userId: userId,
                email: email,
                displayName: displayName,
                accessToken: accessToken,
                refreshToken: refreshToken,
                expiresAt: expiresAt
            )
        }
    }
}
