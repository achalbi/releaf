/*
 * GoogleSignInBinding.swift
 *
 * SwiftUI glue that drives the real Google Sign-In flow from the
 * Sign In screen. Mirror of Android's `GoogleSignInBinding.kt`.
 *
 * Usage from a view:
 *
 *     let signIn = GoogleSignInBinding.signInAction(authStore: authStore)
 *     AppButton("Sign in with Google") { Task { await signIn() } }
 *
 * The binding checks the main bundle's Info.plist for a `GIDClientID`
 * entry (the iOS OAuth client ID from Google Cloud Console). When
 * unset, it falls through to `AuthStore.signIn()` which runs the stub.
 * This keeps previews + unconfigured dev builds working while letting
 * a production build auto-promote to the real flow.
 */

import Foundation

public enum GoogleSignInBinding {

    /// Info.plist key the iOS Google Sign-In SDK reads by convention.
    /// Production builds should set it to the iOS OAuth client id
    /// (e.g. `1029384.apps.googleusercontent.com`).
    public static let infoPlistKey = "GIDClientID"

    /// Create a callback that drives the real sign-in flow. When no
    /// `GIDClientID` is configured in Info.plist, the returned
    /// closure falls back to [AuthStore.signIn] (stub).
    @MainActor
    public static func signInAction(authStore: AuthStore) -> @MainActor () async -> Void {
        let clientId = Bundle.main.object(forInfoDictionaryKey: infoPlistKey) as? String
        guard let clientId, !clientId.isEmpty else {
            return {
                await authStore.signIn()
            }
        }
        let client = RealGoogleAuthClient(iosClientId: clientId)
        return {
            // The returned closure is `@MainActor`, so synchronous
            // @MainActor methods on `authStore` (beginExternalSignIn,
            // adoptSession, cancelSignIn, failSignIn) call directly
            // without `await`. Only the actually-async
            // `client.signIn()` keeps `try await`.
            authStore.beginExternalSignIn()
            do {
                let session = try await client.signIn()
                authStore.adoptSession(session)
            } catch GoogleAuthError.cancelled {
                authStore.cancelSignIn()
            } catch {
                authStore.failSignIn((error as? LocalizedError)?.errorDescription
                                            ?? "\(error)")
            }
        }
    }

    /// Attempt a silent restore at app-launch. Returns `true` when a
    /// prior session was adopted.
    @MainActor
    @discardableResult
    public static func restorePreviousSignIn(authStore: AuthStore) async -> Bool {
        let clientId = Bundle.main.object(forInfoDictionaryKey: infoPlistKey) as? String
        guard let clientId, !clientId.isEmpty else { return false }
        let client = RealGoogleAuthClient(iosClientId: clientId)
        do {
            let session = try await client.restorePreviousSignIn()
            // We're already on @MainActor (function attribute), so the
            // sync `adoptSession` call is direct — no `await` needed.
            authStore.adoptSession(session)
            return true
        } catch {
            return false
        }
    }
}
