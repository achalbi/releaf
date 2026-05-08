/*
 * SyncScheduler.swift
 *
 * iOS sync scheduling façade. Mirror of Android's `SyncScheduler.kt`.
 *
 *   1. scheduleBackgroundRefresh — register a `BGAppRefreshTask` so iOS
 *      fires the sync pass opportunistically in the background. Must be
 *      paired with an Info.plist entry
 *      (`BGTaskSchedulerPermittedIdentifiers` → the `backgroundTaskId`
 *      this scheduler instance was constructed with) and a matching
 *      `register(forTaskWithIdentifier:...)` call in the app's
 *      `AppDelegate` or `@main App` at launch.
 *
 *   2. requestImmediate — fire-and-forget one-shot. Called from
 *      repositories right after a mutation so user edits show up on
 *      Drive without waiting for the next refresh window.
 *
 *   3. cancel — tear everything down on sign-out.
 *
 * Cross-app reuse: each consumer (Releaf, QuickInk) owns its own
 * scheduler instance constructed with its own background task
 * identifier. Releaf keeps `SyncScheduler.shared` (defaulting to
 * `app.releaf.mobile.sync`); QuickInk's `QuickInkSyncEnvironment`
 * builds a separate instance with `app.quickink.mobile.sync` so the
 * two apps' BGTaskScheduler registrations don't collide. Per-app
 * `.shared`-equivalents live in each app's environment file.
 *
 * BGTaskScheduler registration is only a stub for now — wiring it up
 * needs an Xcode app target with the BGTaskSchedulerPermittedIdentifiers
 * Info.plist key. The `requestImmediate` path works without any
 * platform setup because it just spawns a `Task { }`; that's what the
 * Settings "Sync now" button + every mutation site will call.
 */

import Combine
import Foundation
#if os(iOS)
import BackgroundTasks
#endif

public final class SyncScheduler: ObservableObject, @unchecked Sendable {

    /// Whether a sync pass is currently in flight. Published so SwiftUI
    /// surfaces (Home pending pill, Settings status row) can flip into
    /// a "Backing up…" state without polling. Mirror of how the Android
    /// home screen reads `WorkManager.getWorkInfosForUniqueWorkFlow(...)`
    /// to know when its `ONESHOT_WORK_NAME` job is RUNNING.
    ///
    /// Always toggled from the main actor so the `@Published` write is
    /// safe for SwiftUI consumers — the actual sync work still runs on
    /// a detached background Task; only the bool flip hops back to main.
    /// Set via `private(set)` so external callers can subscribe but only
    /// the scheduler itself drives the value.
    @Published public private(set) var isRunning: Bool = false

    /// Default identifier used by Releaf's `SyncEnvironment.shared`
    /// — the one-arg `init()` falls back to this so Releaf wiring
    /// doesn't change. QuickInk passes its own identifier to the
    /// two-arg `init(backgroundTaskId:)`.
    public static let defaultBackgroundTaskId = "app.releaf.mobile.sync"

    /// Matches the value this scheduler's app target must list
    /// under `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
    /// Per-instance so Releaf and QuickInk can register distinct
    /// identifiers from the same shared module.
    public let backgroundTaskId: String

    /// Releaf keeps `SyncScheduler.shared` for backwards compat;
    /// callers that still reach for the singleton get the
    /// Releaf-flavored instance. QuickInk builds its own through
    /// `init(backgroundTaskId:)` and stashes it in
    /// `QuickInkSyncEnvironment`.
    public static let shared = SyncScheduler()

    private let queue = DispatchQueue(label: "releaf.sync.scheduler")
    private var currentTask: Task<Void, Never>?

    /// Repositories supply a closure that performs one pass. We keep the
    /// scheduler unaware of auth / DB wiring so it stays easy to swap
    /// for previews + tests.
    public var runOnce: (() async -> Void)?

    /// Public so per-app environments (Releaf's `SyncEnvironment`,
    /// QuickInk's `QuickInkSyncEnvironment`) can construct their own
    /// scheduler with the right background task identifier.
    public init(backgroundTaskId: String = SyncScheduler.defaultBackgroundTaskId) {
        self.backgroundTaskId = backgroundTaskId
    }

    // MARK: - One-shot

    /// Fire a single sync pass on a background Task. Coalesces bursts
    /// into one run via a simple in-flight check.
    ///
    /// The `isRunning` flip happens on the main actor (so SwiftUI views
    /// observing it via `@ObservedObject` recompose) but brackets the
    /// detached `runOnce` call, not the queue.async hop — the brief
    /// dispatch-queue serialisation isn't user-visible work. If a
    /// second `requestImmediate` lands while the first is in flight
    /// the in-flight check drops it; `isRunning` stays true for the
    /// duration of the actual pass.
    public func requestImmediate() {
        queue.async { [weak self] in
            guard let self else { return }
            if self.currentTask != nil { return }
            self.currentTask = Task.detached(priority: .utility) { [weak self] in
                await MainActor.run { self?.isRunning = true }
                await self?.runOnce?()
                await MainActor.run { self?.isRunning = false }
                self?.queue.async { self?.currentTask = nil }
            }
        }
    }

    // MARK: - Background refresh

    /// Register the BGAppRefreshTask handler. Call from the app's
    /// `@main App.init()` (or `AppDelegate.didFinishLaunching`) so iOS
    /// knows which identifier we own.
    #if os(iOS)
    public func registerBackgroundRefreshHandler() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: backgroundTaskId,
            using: nil
        ) { [weak self] task in
            guard let appRefresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self?.handleBackgroundRefresh(appRefresh)
        }
    }

    private func handleBackgroundRefresh(_ task: BGAppRefreshTask) {
        scheduleBackgroundRefresh() // reschedule for next window
        // Same isRunning bracketing as `requestImmediate` — observers
        // get a "Backing up…" state for the duration of the BG pass
        // (it's brief and rarely seen in foreground, but keeps the
        // signal honest if the user happens to bring the app forward
        // mid-refresh).
        let sync = Task.detached(priority: .utility) { [weak self] in
            await MainActor.run { self?.isRunning = true }
            await self?.runOnce?()
            await MainActor.run { self?.isRunning = false }
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = { sync.cancel() }
    }

    /// Ask iOS to fire a background refresh at its discretion, no
    /// sooner than ~15 minutes from now.
    public func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: backgroundTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    public func cancelAll() {
        queue.async { [weak self] in
            self?.currentTask?.cancel()
            self?.currentTask = nil
        }
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: backgroundTaskId)
    }
    #else
    public func registerBackgroundRefreshHandler() {}
    public func scheduleBackgroundRefresh() {}
    public func cancelAll() {
        queue.async { [weak self] in
            self?.currentTask?.cancel()
            self?.currentTask = nil
        }
    }
    #endif
}
