/*
 * SyncEnvironment.swift
 *
 * Process-wide wiring for Releaf's sync stack. Builds a `SyncRepository`
 * (in ReleafCoreSync) wrapped around a `ReleafSyncDataSource` (in this
 * module), hooks it into `SyncScheduler.runOnce`, and watches
 * `AuthStore` so sign-in / sign-out toggles background refresh.
 *
 * Call `SyncEnvironment.shared.install(authStore:)` once from the app
 * entry point (e.g. inside `ReleafApp.init()`). Idempotent.
 *
 * Phase 2 refactor (PR #3b): SyncRepository moved out of this module
 * into `ReleafCoreSync` so QuickInk can reuse it. The Releaf-specific
 * snapshot/apply/mark code that used to live in SyncRepository is now
 * in `ReleafSyncDataSource`. Per QUICKINK_DESIGN.md §1, both are
 * constructed lazily inside the scheduler closure so the dataSource
 * always picks up the current signed-in user — when the user signs
 * out and back in as a different account, the next sync pass uses
 * fresh objects.
 */

import Foundation
import Combine
import ReleafCoreDrive
import ReleafCoreSync

@MainActor
public final class SyncEnvironment {

    public static let shared = SyncEnvironment()

    /// Drive client used for sync. Swapped to `URLSessionDriveClient`
    /// (real REST) automatically when the iOS Google Sign-In client ID
    /// is configured in Info.plist under `GIDClientID`; falls back to
    /// the in-memory stub for previews / unconfigured dev builds so
    /// the app stays runnable end-to-end without Google credentials.
    public var driveClient: DriveClient = {
        let configured = Bundle.main.object(forInfoDictionaryKey: GoogleSignInBinding.infoPlistKey) as? String
        return (configured?.isEmpty == false)
            ? URLSessionDriveClient()
            : InMemoryDriveClient()
    }()

    public let stateStore = SyncStateStore.shared
    public let scheduler  = SyncScheduler.shared

    private var authObserver: AnyCancellable?
    private var installed = false

    private init() {}

    /// Idempotent — call from the app entry point. Registers the
    /// background refresh handler and observes auth for sign-in/sign-out
    /// toggles. The actual `SyncRepository` + `ReleafSyncDataSource`
    /// pair is constructed lazily inside the scheduler's `runOnce`
    /// closure so we always use the currently-signed-in user's id.
    ///
    /// `authStore` is not defaulted because `AuthStore` is
    /// `@MainActor`-isolated and Swift 6 rejects MainActor singleton
    /// access from nonisolated default-value evaluation. Callers pass
    /// `AuthStore.shared` from a @MainActor scope (the app's init).
    public func install(authStore: AuthStore) {
        if installed { return }
        installed = true

        // Wire the scheduler's runOnce to actually sync.
        scheduler.runOnce = { [weak self] in
            guard let self else { return }
            guard let session = await self.currentSession(authStore: authStore) else { return }
            let dataSource = ReleafSyncDataSource(userId: session.userId)
            let repo = SyncRepository(
                dataSource: dataSource,
                driveClient: self.driveClient,
                stateStore: self.stateStore
            )
            _ = try? await repo.sync(
                deviceId:    DeviceIdentity.get(),
                accessToken: session.accessToken
            )
        }

        scheduler.registerBackgroundRefreshHandler()

        // Toggle background refresh + kick off a sync on sign-in.
        authObserver = authStore.$state.sink { [weak self] state in
            guard let self else { return }
            switch state {
            case .signedIn:
                self.scheduler.scheduleBackgroundRefresh()
                self.scheduler.requestImmediate()
            case .signedOut, .signingIn, .failed:
                self.scheduler.cancelAll()
            }
        }
    }

    private func currentSession(authStore: AuthStore) async -> GoogleAuthSession? {
        await MainActor.run { authStore.session }
    }
}
