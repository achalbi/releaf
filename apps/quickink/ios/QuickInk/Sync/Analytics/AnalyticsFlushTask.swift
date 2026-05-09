/*
 * AnalyticsFlushTask.swift
 *
 * BGAppRefreshTask wrapper that drains the on-device analytics
 * outbox to the QuickInk backend. Mirror of Android's
 * `AnalyticsFlushWorker.kt`.
 *
 * Invocation modes:
 *   - Periodic (background): the system schedules the registered
 *     `app.quickink.analytics.flush` task every ~30 min when the
 *     app is backgrounded and the network is available.
 *   - Opportunistic (foreground): `requestImmediate()` runs the
 *     flush in a Task off the main actor right after a scan
 *     completes or a sign-in lands, so the user's events show
 *     up on the dashboard within seconds rather than waiting
 *     for the next BG window.
 *
 * Coordination with the existing Drive sync task: both fire from
 * the same `onPassComplete` hook but live in PARALLEL. Don't
 * merge them — different cadences, different failure modes, an
 * analytics outage must not affect Drive sync and vice versa.
 *
 * Feature flag: gated by Info.plist's `AnalyticsEnabled` (Bool).
 * When the flag is missing or false, register and requestImmediate
 * become no-ops. The on-disk outbox is left intact — flipping the
 * flag on later picks up wherever it stopped.
 */

import Foundation
import GRDB
import ReleafCoreAuth
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

public enum AnalyticsFlushTask {

    /// Registered in Info.plist's BGTaskSchedulerPermittedIdentifiers.
    /// Match the value exactly when registering the system handler.
    public static let identifier = "app.quickink.analytics.flush"

    /// In-memory snapshot of the wiring needed to run a flush.
    /// Set by `install` once at app start so the BG task handler
    /// — which has no SwiftUI context — can reach the AuthStore
    /// + DB queue.
    private static var environment: Environment?

    private struct Environment {
        let dbQueue:   DatabaseQueue
        let authStore: AuthStore
        let baseUrl:   URL
    }

    // ── Setup ───────────────────────────────────────────────────

    /// Wire the BG task handler. Call from `@main App.init()`
    /// before the App's body resolves so
    /// `BGTaskScheduler.register(...)` runs before the system
    /// finishes app launch (a hard requirement of the BG framework).
    ///
    /// Idempotent. No-op when Info.plist `AnalyticsEnabled` is
    /// false — the on-disk outbox is left untouched so flipping
    /// the flag on later picks up wherever it stopped.
    public static func install(authStore: AuthStore) {
        guard isEnabled else { return }
        let url = analyticsBaseURL() ?? URL(string: "https://api-quickink.thoughtbasics.com")!
        environment = Environment(
            dbQueue:   QuickInkDatabase.shared.dbQueue,
            authStore: authStore,
            baseUrl:   url
        )

        #if canImport(BackgroundTasks)
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: identifier,
            using: nil
        ) { task in
            handleBgRefresh(task as! BGAppRefreshTask)
        }
        #endif

        scheduleNext()
    }

    private static func analyticsBaseURL() -> URL? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "AnalyticsBaseURL") as? String,
              !raw.isEmpty else { return nil }
        return URL(string: raw)
    }

    /// Schedule the next ~30-min wake-up. Called once at install
    /// time and again at the end of each BG run so the cadence
    /// keeps repeating. iOS may not honor it exactly — the system
    /// budgets BG refresh based on usage patterns, but this is
    /// the right ask.
    public static func scheduleNext() {
        guard isEnabled else { return }

        #if canImport(BackgroundTasks)
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("[analytics] scheduleNext failed: %@", "\(error)")
        }
        #endif
    }

    /// Run a flush right now in foreground context. Called from
    /// ScanFlowController.onPassComplete + auth-state observer
    /// so the user sees their event arrive within seconds rather
    /// than on the next BG tick.
    ///
    /// We don't deduplicate explicitly — concurrent calls would
    /// each pick a different batch from the outbox (FIFO order +
    /// next_attempt_at filtering); the worst case is two
    /// overlapping POSTs, neither of which corrupts state thanks
    /// to ON CONFLICT DO NOTHING server-side.
    public static func requestImmediate() {
        guard isEnabled, let env = environment else { return }
        Task.detached(priority: .background) {
            do {
                try await flushAll(env: env)
            } catch {
                NSLog("[analytics] immediate flush failed: %@", "\(error)")
            }
        }
    }

    // ── BG handler ──────────────────────────────────────────────

    #if canImport(BackgroundTasks)
    private static func handleBgRefresh(_ task: BGAppRefreshTask) {
        // Always re-arm even on early exit — otherwise a single
        // failed run breaks the cadence permanently.
        scheduleNext()

        guard let env = environment else { task.setTaskCompleted(success: true); return }

        let work = Task {
            do {
                try await flushAll(env: env)
                task.setTaskCompleted(success: true)
            } catch {
                NSLog("[analytics] BG flush failed: %@", "\(error)")
                task.setTaskCompleted(success: false)
            }
        }

        // Honor system expiration — let the in-flight task wind
        // down rather than getting force-killed mid-write.
        task.expirationHandler = { work.cancel() }
    }
    #endif

    // ── Flush loop ──────────────────────────────────────────────

    private static func flushAll(env: Environment) async throws {
        let repo   = AnalyticsRepository(dbQueue: env.dbQueue)
        let client = AnalyticsApiClient(
            authStore: env.authStore,
            baseUrl:   env.baseUrl
        )

        // Drain in waves of up to 200. A successful batch loops
        // immediately to the next; any non-success ends THIS run
        // and the next wake-up picks up where we left off.
        var pulled = 0
        while true {
            let batch = try await repo.nextBatch()
            if batch.isEmpty { break }

            let keepGoing = await flushBatch(repo: repo, client: client, batch: batch)
            pulled += batch.count
            if !keepGoing { break }
        }

        // GC sweep — drop rows older than 30 days regardless of
        // status. Bounds the table size in pathological cases.
        try? await repo.gc()

        if pulled > 0 {
            let remaining = (try? await repo.pendingCount()) ?? -1
            NSLog("[analytics] flush drained %d rows; remaining=%d", pulled, remaining)
        }
    }

    /// Returns true when the flush loop can immediately try the
    /// next batch; false on any non-success result (rate limit,
    /// server down, network drop) so the worker stops and lets
    /// the next wake-up retry.
    private static func flushBatch(
        repo: AnalyticsRepository,
        client: AnalyticsApiClient,
        batch: [AnalyticsRepository.PendingRow]
    ) async -> Bool {
        // Identify rows go one at a time. Captures bundle into
        // one batch POST. Identifies first since the server's
        // User row needs to exist before capture_events FKs.
        let identifies = batch.filter { $0.kind == "identify" }
        let captures   = batch.filter { $0.kind == "capture" }

        for row in identifies {
            switch await client.postIdentify(payloadJson: row.payloadJson) {
            case .success:
                try? await repo.acknowledge(ids: [row.id])
            case .rateLimited(let retryAfter):
                try? await repo.markFailure(
                    ids:          [row.id],
                    attemptCount: row.attempts,
                    error:        "rate_limited",
                    retryAfter:   retryAfter
                )
                return false
            case .unauthorized:
                try? await repo.markFailure(ids: [row.id], attemptCount: row.attempts, error: "unauthorized")
                return false
            case .clientError(let code, let body):
                NSLog("[analytics] dropping identify %@: %d %@", row.id, code, body ?? "")
                try? await repo.acknowledge(ids: [row.id])
            case .serverError(let code, _):
                try? await repo.markFailure(ids: [row.id], attemptCount: row.attempts, error: "5xx \(code)")
                return false
            case .networkError(let msg):
                try? await repo.markFailure(ids: [row.id], attemptCount: row.attempts, error: msg)
                return false
            }
        }

        if !captures.isEmpty {
            let pairs = captures.map { (id: $0.id, payloadJson: $0.payloadJson) }
            let ids = captures.map { $0.id }
            switch await client.postCaptureBatch(rows: pairs) {
            case .success(let acceptedIds):
                let acceptedSet = Set(acceptedIds)
                let toAck = ids.filter { acceptedSet.contains($0) }
                try? await repo.acknowledge(ids: toAck)
                let dropped = ids.filter { !acceptedSet.contains($0) }
                if !dropped.isEmpty {
                    NSLog("[analytics] server didn't accept %d ids; leaving queued", dropped.count)
                }
            case .rateLimited(let retryAfter):
                try? await repo.markFailure(
                    ids:          ids,
                    attemptCount: captures.first?.attempts ?? 0,
                    error:        "rate_limited",
                    retryAfter:   retryAfter
                )
                return false
            case .unauthorized:
                try? await repo.markFailure(
                    ids:          ids,
                    attemptCount: captures.first?.attempts ?? 0,
                    error:        "unauthorized"
                )
                return false
            case .clientError(let code, let body):
                NSLog("[analytics] dropping %d captures: %d %@", ids.count, code, body ?? "")
                try? await repo.acknowledge(ids: ids)
            case .serverError(let code, _):
                try? await repo.markFailure(
                    ids:          ids,
                    attemptCount: captures.first?.attempts ?? 0,
                    error:        "5xx \(code)"
                )
                return false
            case .networkError(let msg):
                try? await repo.markFailure(
                    ids:          ids,
                    attemptCount: captures.first?.attempts ?? 0,
                    error:        msg
                )
                return false
            }
        }
        return true
    }

    // ── Feature flag ────────────────────────────────────────────

    /// True when Info.plist `AnalyticsEnabled` (Bool) is YES.
    /// Defaults to false when the key is missing, so a fresh
    /// build doesn't send events until we explicitly turn it on.
    public static var isEnabled: Bool {
        Bundle.main.object(forInfoDictionaryKey: "AnalyticsEnabled") as? Bool ?? false
    }
}
