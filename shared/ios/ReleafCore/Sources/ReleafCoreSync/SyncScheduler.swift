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

import Foundation
#if os(iOS)
import BackgroundTasks
#endif

public final class SyncScheduler: @unchecked Sendable {

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
    public func requestImmediate() {
        queue.async { [weak self] in
            guard let self else { return }
            if self.currentTask != nil { return }
            self.currentTask = Task.detached(priority: .utility) { [weak self] in
                await self?.runOnce?()
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
        let sync = Task.detached(priority: .utility) { [weak self] in
            await self?.runOnce?()
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
