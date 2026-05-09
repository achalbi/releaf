/*
 * GoogleAuthClient.swift
 * Protocol + in-memory stub for Google Sign-In.
 *
 * Real implementation: swap `StubGoogleAuthClient` for a wrapper around
 * `GoogleSignIn-iOS` (https://github.com/google/GoogleSignIn-iOS).
 *
 * Scope required for Drive writes: `https://www.googleapis.com/auth/drive.file`
 * (Releaf can only see files it created).
 */

import Foundation

public struct GoogleAuthSession: Equatable, Sendable {
    public let userId: String
    public let email: String
    public let displayName: String?
    public let accessToken: String
    public let refreshToken: String?
    public let expiresAt: Date

    public init(
        userId: String,
        email: String,
        displayName: String? = nil,
        accessToken: String,
        refreshToken: String? = nil,
        expiresAt: Date
    ) {
        self.userId = userId
        self.email = email
        self.displayName = displayName
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresAt = expiresAt
    }
}

public enum GoogleAuthError: Error, Equatable {
    case cancelled
    case notImplemented
    case underlying(String)
}

public protocol GoogleAuthClient: AnyObject, Sendable {
    /// Launches the Google Sign-In flow and returns a session with Drive scope.
    func signIn() async throws -> GoogleAuthSession

    /// Refreshes the access token using the stored refresh token.
    func refresh(_ session: GoogleAuthSession) async throws -> GoogleAuthSession

    /// Revokes the token locally + remotely.
    func signOut() async

    /// Return a Google ID token (RS256 JWT) for the currently
    /// signed-in user. Used by the QuickInk analytics backend to
    /// authenticate `/v1/identify` and `/v1/events/capture/batch`
    /// POSTs without forcing the user to re-authenticate — the SDK's
    /// `refreshTokensIfNeeded` returns a fresh ID token as long as
    /// the prior session is intact.
    ///
    /// Throws `.underlying(...)` if no signed-in user is available
    /// — the caller leaves outbox rows queued and the next sign-in
    /// produces a fresh token.
    func idToken() async throws -> String
}

/// Default stub — lets the skeleton build + preview without the real SDK.
/// Returns a fake session after a short delay. Replace before shipping.
public final class StubGoogleAuthClient: GoogleAuthClient, @unchecked Sendable {
    public init() {}

    public func signIn() async throws -> GoogleAuthSession {
        try await Task.sleep(nanoseconds: 400_000_000)
        return GoogleAuthSession(
            userId: "stub-user",
            email: "you@example.com",
            displayName: "Preview User",
            accessToken: "stub-access-token",
            refreshToken: "stub-refresh-token",
            expiresAt: Date().addingTimeInterval(3_600)
        )
    }

    public func refresh(_ session: GoogleAuthSession) async throws -> GoogleAuthSession {
        return GoogleAuthSession(
            userId: session.userId,
            email: session.email,
            displayName: session.displayName,
            accessToken: session.accessToken,
            refreshToken: session.refreshToken,
            expiresAt: Date().addingTimeInterval(3_600)
        )
    }

    public func signOut() async {}

    /// Sentinel that the QuickInk backend's verifier will reject as
    /// a malformed JWT (it checks for three base64url segments).
    /// Surfacing the specific string in logs makes "is the device on
    /// the stub or the real client?" trivial to answer when
    /// triaging an analytics 401.
    public func idToken() async throws -> String {
        return "stub-id-token-not-a-real-jwt"
    }
}
