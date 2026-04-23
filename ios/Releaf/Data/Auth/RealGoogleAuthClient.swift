/*
 * RealGoogleAuthClient.swift
 *
 * Production Google Sign-In wrapper. Wraps the GoogleSignIn-iOS SDK
 * (`GIDSignIn`) and exposes the same `GoogleAuthClient` protocol the
 * stub implements.
 *
 * Scope: requests `drive.file` on sign-in. The SDK handles the consent
 * UX internally via `signIn(withPresenting:additionalScopes:)`, so we
 * don't need the two-phase consent-intent dance Android does — iOS
 * presents the consent sheet as part of the same flow.
 *
 * Token refresh: `GIDSignIn.restorePreviousSignIn` on cold launch, and
 * `currentUser?.refreshTokensIfNeeded()` before any Drive call via
 * [refresh]. The SDK owns the refresh token behind `GIDGoogleUser`.
 *
 * Caller requirements:
 *   • Info.plist: add a URL scheme matching the iOS client ID's
 *     reversed form (e.g. `com.googleusercontent.apps.<id>`).
 *   • In the SwiftUI app, pass `GIDConfiguration(clientID:)` once via
 *     `GIDSignIn.sharedInstance.configuration = ...`. The iOS client ID
 *     comes from Google Cloud Console → OAuth 2.0 → iOS client.
 *   • For `signIn`, we need a presenting `UIViewController`. The SDK
 *     walks up the key window's root VC chain, so callers just ensure
 *     there IS a key window (there always is once the app is
 *     foregrounded).
 *
 * Until the caller sets the iOS client ID, this class falls back to
 * throwing `.underlying("Google Sign-In not configured")` so callers
 * can surface a friendly error instead of crashing.
 */

import Foundation
import UIKit
import GoogleSignIn

public final class RealGoogleAuthClient: GoogleAuthClient, @unchecked Sendable {

    /// Scope granted on every sign-in. See `PROMPT.md` §Hard constraints #5.
    public static let driveFileScope = "https://www.googleapis.com/auth/drive.file"

    /// Initialize with an iOS OAuth client ID from Google Cloud Console.
    /// Pass `nil` to let the SDK pick up its own configuration if
    /// `GIDSignIn.sharedInstance.configuration` is set elsewhere.
    public init(iosClientId: String? = nil) {
        if let id = iosClientId, !id.isEmpty {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: id)
        }
    }

    // MARK: - GoogleAuthClient

    public func signIn() async throws -> GoogleAuthSession {
        guard GIDSignIn.sharedInstance.configuration != nil else {
            throw GoogleAuthError.underlying("Google Sign-In not configured")
        }
        let presenter = try await Self.resolvePresenter()
        let result = try await MainActor.run {
            // SDK API is MainActor-only on iOS 16+; hop over.
            GIDSignIn.sharedInstance
        }

        let gidResult: GIDSignInResult = try await withCheckedThrowingContinuation { cont in
            Task { @MainActor in
                GIDSignIn.sharedInstance.signIn(
                    withPresenting: presenter,
                    hint: nil,
                    additionalScopes: [Self.driveFileScope]
                ) { r, err in
                    if let err = err as NSError? {
                        if err.code == GIDSignInError.canceled.rawValue {
                            cont.resume(throwing: GoogleAuthError.cancelled)
                        } else {
                            cont.resume(throwing: GoogleAuthError.underlying(err.localizedDescription))
                        }
                        return
                    }
                    guard let r = r else {
                        cont.resume(throwing: GoogleAuthError.underlying("Empty sign-in result"))
                        return
                    }
                    cont.resume(returning: r)
                }
            }
        }
        return try mapToSession(user: gidResult.user)
    }

    public func refresh(_ session: GoogleAuthSession) async throws -> GoogleAuthSession {
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw GoogleAuthError.underlying("No current Google user — sign in required")
        }
        let refreshed: GIDGoogleUser = try await withCheckedThrowingContinuation { cont in
            user.refreshTokensIfNeeded { u, err in
                if let err = err {
                    cont.resume(throwing: GoogleAuthError.underlying(err.localizedDescription))
                } else if let u = u {
                    cont.resume(returning: u)
                } else {
                    cont.resume(throwing: GoogleAuthError.underlying("Refresh returned nil"))
                }
            }
        }
        return try mapToSession(user: refreshed)
    }

    public func signOut() async {
        GIDSignIn.sharedInstance.signOut()
    }

    /// Attempt to restore a prior session silently — call from app
    /// launch to avoid an extra tap on warm start. Throws `cancelled`
    /// if no prior session exists.
    public func restorePreviousSignIn() async throws -> GoogleAuthSession {
        try await withCheckedThrowingContinuation { cont in
            GIDSignIn.sharedInstance.restorePreviousSignIn { user, err in
                if let err = err {
                    cont.resume(throwing: GoogleAuthError.underlying(err.localizedDescription))
                    return
                }
                guard let user = user else {
                    cont.resume(throwing: GoogleAuthError.cancelled)
                    return
                }
                do {
                    cont.resume(returning: try self.mapToSession(user: user))
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    // MARK: - Mapping

    private func mapToSession(user: GIDGoogleUser) throws -> GoogleAuthSession {
        let profile = user.profile
        let token   = user.accessToken
        return GoogleAuthSession(
            userId: user.userID ?? profile?.email ?? "",
            email: profile?.email ?? "",
            displayName: profile?.name,
            accessToken: token.tokenString,
            refreshToken: user.refreshToken.tokenString,
            expiresAt: token.expirationDate ?? Date().addingTimeInterval(3_300)
        )
    }

    // MARK: - Presenter resolution

    /// Find a presenting view controller to hand to the SDK's consent
    /// sheet. Walks the active window scene's key window's root chain.
    @MainActor
    private static func resolvePresenter() async throws -> UIViewController {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }
        guard let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow),
              var vc = window.rootViewController
        else {
            throw GoogleAuthError.underlying("No presenting view controller available")
        }
        while let presented = vc.presentedViewController {
            vc = presented
        }
        return vc
    }
}
