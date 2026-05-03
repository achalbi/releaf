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
        // Trim before checking — a copy/paste mishap could leave the
        // GIDClientID Info.plist value as `"   "` or `"\n"`. Without
        // this trim the gate would silently fall through to the real
        // client, which would then fail OAuth on every call and look
        // exactly like "sync isn't working" with no error trail. The
        // empty-after-trim case correctly drops to the in-memory stub.
        let raw = Bundle.main.object(forInfoDictionaryKey: GoogleSignInBinding.infoPlistKey) as? String
        let configured = raw?.trimmingCharacters(in: .whitespacesAndNewlines)
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
        // Cache so `requestRestore()` can resolve the active session
        // without needing the authStore threaded through every call
        // site. Held weakly; the AuthStore singleton outlives the
        // process anyway.
        installedAuthStore = authStore

        scheduler.runOnce = { [weak self] in
            guard let self else { return }
            print("[QuickInkSync] runOnce: starting sync pass")

            // Drive-backup gate — read fresh per pass so toggling
            // in Settings takes effect on the next tick. UserDefaults
            // read is cheap (~µs) so doing this every pass is fine.
            // Default is true for parity with `SettingsState`.
            let defaults = UserDefaults.standard
            let driveBackupEnabled: Bool = {
                guard defaults.object(forKey: Self.driveBackupKey) != nil else { return true }
                return defaults.bool(forKey: Self.driveBackupKey)
            }()
            guard driveBackupEnabled else {
                print("[QuickInkSync] gate (drive-backup): off in Settings — skipping")
                return
            }

            guard let session = await self.currentSession(authStore: authStore) else {
                print("[QuickInkSync] gate (signed-in): user is signed out — skipping")
                return
            }
            print("[QuickInkSync] gates ok — userId=\(session.userId.prefix(8))…")

            // Refresh the access token before every pass. The local
            // TTL stamp on `session.expiresAt` was 55 min, but real
            // Google access tokens expire at ~60 min and the device
            // may have slept for hours since the last sync. The
            // GoogleSignIn SDK's `refreshTokensIfNeeded` is a no-op
            // when the token is still fresh and a silent network
            // round-trip when it isn't, so this is cheap to do
            // unconditionally. Without this step, every Sync now tap
            // after the 60-min mark used to silently 401 and leave
            // "Last synced" at "Never".
            let activeSession: GoogleAuthSession = await self.refreshOrAdopt(
                session: session,
                authStore: authStore
            ) ?? session

            let dataSource = QuickInkSyncDataSource(userId: activeSession.userId)
            let repo = SyncRepository(
                dataSource:  dataSource,
                driveClient: self.driveClient,
                stateStore:  self.stateStore
            )
            // Don't `try?` — a swallow here was masking real failures
            // (Drive 401, manifest-upload throws, decode errors). On
            // throw, `SyncRepository.sync` already records pending-
            // count via `recordSyncFailed` without falsely stamping
            // `lastFullSyncAt`, so the UI's "Last synced" pill stays
            // honest. We log to Console so dev / TestFlight builds
            // surface what went wrong instead of silently degrading.
            do {
                let result = try await repo.sync(
                    deviceId:    DeviceIdentity.get(),
                    accessToken: activeSession.accessToken
                )
                print("[QuickInkSync] metadata pass done — uploaded=\(result.uploaded) " +
                      "tombstoned=\(result.tombstoned) downloaded=\(result.downloaded) " +
                      "failed=\(result.failed) versionBlocked=\(result.versionBlocked)")
            } catch DriveError.unauthenticated {
                // Drive rejected the (just-refreshed) token. The
                // refresh token itself is dead — typically because
                // the user revoked the app's Drive grant in their
                // Google account, or signed out everywhere. Sign
                // them out locally so QuickInkRoot's `ReSignInGate`
                // takes over and prompts a fresh consent + token.
                // The next sign-in will overwrite this session.
                print("[QuickInkSync] Drive 401 even after refresh — signing user out so re-sign-in re-issues credentials")
                await MainActor.run { Task { await authStore.signOut() } }
                return
            } catch {
                print("[QuickInkSync] periodic sync failed: \(error). " +
                      "UI's pendingCount will reflect the failure; lastFullSyncAt will not advance.")
                // Don't return — still try the binary phase below.
                // The metadata pass may have partially succeeded; a
                // PDF that's already locally tagged with its remote
                // file id can keep flowing.
            }

            // Phase 6 — back up the actual scanned PDFs + preview
            // JPEGs to Drive. Runs after the JSON metadata pass so
            // the captures rows already exist in the manifest, and
            // a fresh-device restore can find them via the row's
            // `pdf_drive_file_id`. Best-effort: errors per row are
            // swallowed inside the helper so a single bad upload
            // doesn't block the rest. We still log the outer-level
            // throw (e.g. an unrecoverable network failure that took
            // out the whole pass) for visibility. Mirror
            // `QuickInkBinarySync.kt` on Android.
            let binarySync = QuickInkBinarySync(driveClient: self.driveClient)
            do {
                try await binarySync.uploadAndCascade(
                    userId:      activeSession.userId,
                    accessToken: activeSession.accessToken
                )
            } catch {
                print("[QuickInkSync] binary upload phase failed: \(error)")
            }
            do {
                try await binarySync.restorePending(
                    userId:      activeSession.userId,
                    accessToken: activeSession.accessToken
                )
            } catch {
                print("[QuickInkSync] binary restore phase failed: \(error)")
            }
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

    /// Kick a one-shot PULL-ONLY pass against Drive — the iOS
    /// counterpart to Android's `QuickInkRestoreWorker`. Hits
    /// `SyncRepository.restore(...)` so local rows get rehydrated
    /// from the cloud copy without the bidirectional reconciliation
    /// the periodic worker does. Used by Settings → "Restore from
    /// Drive". No-ops gracefully when signed out.
    ///
    /// Drive-backup toggle is intentionally NOT consulted here:
    /// restore is an explicit user action, distinct from the
    /// background sync the toggle gates.
    public func requestRestore() {
        // `install(...)` may not have been called yet (e.g. preview /
        // test path) — `currentSession` would still resolve, so we
        // can run standalone. Fire-and-forget; errors swallowed
        // because the UI doesn't surface restore-specific failures
        // yet (Settings just shows the Last-synced row updating).
        Task { [weak self] in
            guard let self else { return }
            // Pulling the AuthStore through the install hook would
            // require an extra @Stored ref. Read fresh from the
            // shared store via the same KeychainTokenStore the auth
            // layer uses; SyncRepository.sync goes through the same
            // path, so the call site doesn't need an authStore arg.
            // For now, require `install(...)` to have been called so
            // we can reuse the cached AuthStore via the `authObserver`
            // closure context. If not installed, no-op cleanly.
            guard self.installed,
                  let store = self.installedAuthStore,
                  let session = await self.currentSession(authStore: store)
            else { return }

            // Refresh before restore for the same reason the
            // periodic pass does — a stale token is the most common
            // reason "Restore from Drive" silently does nothing.
            let activeSession = await self.refreshOrAdopt(
                session: session,
                authStore: store
            ) ?? session

            let dataSource = QuickInkSyncDataSource(userId: activeSession.userId)
            let repo = SyncRepository(
                dataSource:  dataSource,
                driveClient: self.driveClient,
                stateStore:  self.stateStore
            )
            do {
                _ = try await repo.restore(
                    deviceId:    DeviceIdentity.get(),
                    accessToken: activeSession.accessToken
                )
            } catch DriveError.unauthenticated {
                print("[QuickInkSync] restore: Drive 401 — signing user out")
                await MainActor.run { Task { await store.signOut() } }
            } catch {
                // Settings → "Restore from Drive" doesn't have a
                // dedicated error surface yet; logging keeps the
                // failure recoverable from Console without crashing
                // the user out of the screen.
                print("[QuickInkSync] restore failed: \(error)")
            }
        }
    }

    private func currentSession(authStore: AuthStore) async -> GoogleAuthSession? {
        await MainActor.run { authStore.session }
    }

    /// Cached AuthStore reference established by `install(...)` so
    /// `requestRestore()` doesn't need an authStore arg from each
    /// call site. Set inside `install` and read here.
    private weak var installedAuthStore: AuthStore?

    /// Refresh `session`'s access token via `RealGoogleAuthClient`
    /// and adopt the result onto the AuthStore. Returns the
    /// refreshed session on success, `nil` when no real client is
    /// configured (Info.plist's `GIDClientID` empty — InMemory
    /// driveClient path) or when the underlying refresh threw. The
    /// caller falls back to the input session in those cases.
    ///
    /// Why call this on every pass: the GoogleSignIn SDK's
    /// `refreshTokensIfNeeded` is a no-op when the access token is
    /// still fresh, and a silent network round-trip when it isn't.
    /// Without this hook every Sync now after the ~60-min token
    /// boundary used to silently 401 and leave "Last synced" at
    /// "Never" — see the QuickInkSyncWorker.kt parallel.
    private func refreshOrAdopt(
        session: GoogleAuthSession,
        authStore: AuthStore
    ) async -> GoogleAuthSession? {
        let raw = Bundle.main.object(forInfoDictionaryKey: GoogleSignInBinding.infoPlistKey) as? String
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return nil
        }
        let client = RealGoogleAuthClient(iosClientId: trimmed)
        do {
            let fresh = try await client.refresh(session)
            await MainActor.run { authStore.adoptSession(fresh) }
            print("[QuickInkSync] token refreshed (expiresAt=\(fresh.expiresAt))")
            return fresh
        } catch {
            // Refresh itself failed — typically the GIDSignIn SDK
            // can't restore `currentUser` (cold launch with no
            // keychain entry). Don't sign out here: the caller's
            // sync attempt will hit Drive with the (possibly stale)
            // existing token and either succeed (if the local TTL
            // was over-conservative) or 401, which the caller
            // handles by signing the user out for re-consent.
            print("[QuickInkSync] token refresh failed (\(error)) — falling through with existing token")
            return nil
        }
    }
}
