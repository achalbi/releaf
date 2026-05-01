/*
 * QuickInkSyncEnvironment.swift
 *
 * Process-wide wiring for QuickInk's sync stack. Mirror of Releaf's
 * `apps/releaf/ios/Releaf/Data/Sync/SyncEnvironment.swift`. Builds a
 * `SyncRepository` (in `ReleafCoreSync`) wrapped around a
 * `QuickInkSyncDataSource` per pass, hooks it into a
 * QuickInk-specific `SyncScheduler` instance, and watches the
 * `AuthStore` so sign-in / sign-out toggles background refresh.
 *
 * Call `QuickInkSyncEnvironment.shared.install(authStore:)` once
 * from the app entry point (today: `QuickInkRoot.init()`; eventually,
 * the Xcode app target's `@main App.init()`). Idempotent.
 *
 * Drive-toggle gate (per QUICKINK_PROPOSAL.md Phase 4 Q3): the
 * scheduler always runs on its 15-minute cadence — whether a pass
 * actually talks to Drive is decided per-pass inside the closure
 * by reading `SettingsState.driveBackupEnabled` from UserDefaults.
 * When the user has Drive backup off, the closure short-circuits
 * before constructing any data-source / repository. Same posture
 * Android's `QuickInkSyncWorker` takes — see its header.
 *
 * Background-task identifier: QuickInk uses
 * `app.quickink.mobile.sync`, distinct from Releaf's
 * `app.releaf.mobile.sync`. The two apps' BGTaskScheduler
 * registrations don't collide because each owns its own scheduler
 * instance constructed via `SyncScheduler(backgroundTaskId:)` (not
 * the `.shared` singleton, which Releaf keeps).
 */

import Foundation
import Combine
import ReleafCoreAuth
import ReleafCoreDrive
import ReleafCoreSync

@MainActor
public final class QuickInkSyncEnvironment {

    public static let shared = QuickInkSyncEnvironment()

    /// Background task identifier QuickInk's Xcode app target must
    /// list under `BGTaskSchedulerPermittedIdentifiers` in Info.plist
    /// (when that target lands).
    public static let backgroundTaskId = "app.quickink.mobile.sync"

    /// UserDefaults key that mirrors `SettingsState.driveBackupEnabled`.
    /// Re-read per pass so toggling the switch in Settings takes
    /// effect on the next 15-min tick without touching the scheduler.
    /// Source of truth lives in `apps/quickink/ios/QuickInk/Settings/SettingsState.swift`.
    private static let driveBackupKey = "quickink.settings.drive_backup_enabled"

    /// Drive client used for sync. Real REST when QuickInk's Google
    /// Sign-In client ID is configured (Info.plist `GIDClientID`);
    /// in-memory stub otherwise so the app stays runnable end-to-end
    /// without Google credentials checked in. Same posture Releaf
    /// takes in `SyncEnvironment`.
    public var driveClient: DriveClient = {
        let configured = Bundle.main.object(forInfoDictionaryKey: GoogleSignInBinding.infoPlistKey) as? String
        return (configured?.isEmpty == false)
            ? URLSessionDriveClient()
            : InMemoryDriveClient()
    }()

    /// QuickInk's own UserDefaults-backed sync state cache. The
    /// shared `SyncStateStore` uses `UserDefaults.standard` and
    /// "releaf.sync.*" keys; in QuickInk's process those keys live
    /// in QuickInk's own sandboxed defaults, so values don't bleed
    /// between apps. (Renaming the prefix is a follow-up — see the
    /// note on `SyncStateStore.swift`.)
    public let stateStore = SyncStateStore.shared

    /// Per-app scheduler instance. Distinct from
    /// `SyncScheduler.shared` (which Releaf owns) so the two apps'
    /// BGTaskScheduler registrations don't collide.
    public let scheduler = SyncScheduler(backgroundTaskId: backgroundTaskId)

    private var authObserver: AnyCancellable?
    private var installed = false

    private init() {}

    /// Idempotent — call from the app entry point. Wires the
    /// scheduler's `runOnce` closure, registers the background
    /// refresh handler, and observes auth for sign-in/sign-out
    /// toggles. The actual `SyncRepository` + `QuickInkSyncDataSource`
    /// pair is constructed lazily inside the closure so the data
    /// source always picks up the currently-signed-in user's id.
    public func install(authStore: AuthStore) {
        if installed { return }
        installed = true

        scheduler.runOnce = { [weak self] in
            guard let self else { return }

            // Drive-backup gate — read fresh per pass so toggling
            // in Settings takes effect on the next tick. UserDefaults
            // read is cheap (~µs) so doing this every pass is fine.
            // Default is true for parity with `SettingsState`.
            let defaults = UserDefaults.standard
            let driveBackupEnabled: Bool = {
                guard defaults.object(forKey: Self.driveBackupKey) != nil else { return true }
                return defaults.bool(forKey: Self.driveBackupKey)
            }()
            guard driveBackupEnabled else { return }

            guard let session = await self.currentSession(authStore: authStore) else { return }
            let dataSource = QuickInkSyncDataSource(userId: session.userId)
            let repo = SyncRepository(
                dataSource:  dataSource,
                driveClient: self.driveClient,
                stateStore:  self.stateStore
            )
            _ = try? await repo.sync(
                deviceId:    DeviceIdentity.get(),
                accessToken: session.accessToken
            )
        }

        scheduler.registerBackgroundRefreshHandler()

        // Toggle background refresh + kick off a sync on sign-in.
        // Same .signedIn / else split Releaf uses; the per-pass
        // gate handles the "user has Drive backup off" case.
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
