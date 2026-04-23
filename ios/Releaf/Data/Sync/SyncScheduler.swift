/*
 * SyncScheduler.swift
 *
 * iOS sync scheduling façade. Mirror of Android's `SyncScheduler.kt`.
 *
 *   1. scheduleBackgroundRefresh — register a `BGAppRefreshTask` so iOS
 *      fires the sync pass opportunistically in the background. Must be
 *      paired with an Info.plist entry
 *      (`BGTaskSchedulerPermittedIdentifiers` → `app.releaf.mobile.sync`)
 *      and a matching `register(forTaskWithIdentifier:...)` call in the
 *      app's `AppDelegate` or `@main App` at launch.
 *
 *   2. requestImmediate — fire-and-forget one-shot. Called from
 *      repositories right after a mutation so user edits show up on
 *      Drive without waiting for the next refresh window.
 *
 *   3. cancel — tear everything down on sign-out.
 *
 * BGTaskScheduler registration is only a stub for now — wiring it up
 * needs an Xcode app target with the BGTaskSchedulerPermittedIdentifiers
 * Info.plist key. The `requestImmediate` path works without any
 * platform setup because it just spawns a `Task { }`; that's what the
 * Settings "Sync now" button + every mutation site will call.
 */

import Foundation
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

public final class SyncScheduler: @unchecked Sendable {

    /// Matches the value the app target must list under
    /// `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
    public static let backgroundTaskId = "app.releaf.mobile.sync"

    public static let shared = SyncScheduler()

    private let queue = DispatchQueue(label: "releaf.sync.scheduler")
    private var currentTask: Task<Void, Never>?

    /// Repositories supply a closure that performs one pass. We keep the
    /// scheduler unaware of auth / DB wiring so it stays easy to swap
    /// for previews + tests.
    public var runOnce: (() async -> Void)?

    private init() {}

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
    #if canImport(BackgroundTasks)
    public func registerBackgroundRefreshHandler() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.backgroundTaskId,
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
        let request = BGAppRefreshTaskRequest(identifier: Self.backgroundTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    public func cancelAll() {
        queue.async { [weak self] in
            self?.currentTask?.cancel()
            self?.currentTask = nil
        }
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.backgroundTaskId)
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
