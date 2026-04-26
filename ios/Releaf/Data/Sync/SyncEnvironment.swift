/*
 * SyncEnvironment.swift
 *
 * Process-wide wiring for the sync stack. Builds the `SyncRepository`
 * singleton, hooks it into `SyncScheduler.runOnce`, and watches
 * `AuthStore` so sign-in / sign-out toggles background refresh.
 *
 * Call `SyncEnvironment.shared.install()` once from the app entry
 * point (e.g. inside `ReleafApp.init()`). The install is idempotent.
 *
 * Swap the `driveClient` property to a real REST implementation when
 * Phase 2 lands; previews / tests continue to use `InMemoryDriveClient`.
 */

import Foundation
import Combine

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

    public private(set) var repository: SyncRepository?

    private var authObserver: AnyCancellable?
    private var installed = false

    private init() {}

    /// Idempotent — call from the app entry point. Builds the sync
    /// repository, registers the background refresh handler, and
    /// observes auth for enable/cancel.
    ///
    /// `authStore` is no longer defaulted to `.shared` — `AuthStore`
    /// is `@MainActor`-isolated and Swift 6 rejects MainActor singleton
    /// access from the nonisolated default-value evaluation context.
    /// Callers always pass `AuthStore.shared` from a @MainActor scope
    /// (the app's `init`); this just makes that explicit.
    public func install(authStore: AuthStore) {
        if installed { return }
        installed = true

        let repo = SyncRepository(
            driveClient: driveClient,
            stateStore: stateStore
        )
        self.repository = repo

        // Wire the scheduler's runOnce to actually sync.
        scheduler.runOnce = { [weak repo, weak self] in
            guard let repo, let self else { return }
            guard let session = await self.currentSession(authStore: authStore) else { return }
            _ = try? await repo.sync(
                userId:      session.userId,
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
