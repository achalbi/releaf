/*
 * AnalyticsRepository.swift
 *
 * The single point through which the rest of the app interacts
 * with the analytics outbox. Two write entry points
 * (enqueueCapture, enqueueIdentify); three flush-side methods
 * the BGAppRefreshTask uses (nextBatch, acknowledge,
 * markFailure); one GC sweep called per flush.
 *
 * NOTHING here knows about HTTP — `AnalyticsApiClient` does the
 * POST. Keeping persistence isolated means tests can drive
 * enqueue / drain / ack semantics against an in-memory GRDB
 * without spinning up a fake server.
 *
 * Mirror of Android `AnalyticsRepository.kt`.
 */

import Foundation
import GRDB
import ReleafCoreData

struct AnalyticsRepository {

    // ── Wire payloads (match the Rails backend's JSON shape) ──

    /// One body for /v1/identify. The verifier on the server pulls
    /// email / sub / name / picture from the JWT claims, so this
    /// only carries the two mobile-only fields.
    struct IdentifyPayload: Codable {
        let device_os:   String
        let app_version: String
    }

    /// One element in /v1/events/capture/batch's `events` array.
    /// Field names match the Rails controller's expected JSON
    /// keys exactly — keep aligned with Android + Rails on rename.
    struct CapturePayload: Codable {
        let capture_id:  String
        let source:      String
        let page_count:  Int
        let category:    String?
        let has_ocr:     Bool
        let ocr_chars:   Int
        let captured_at: String
    }

    /// Snapshot the flush task pulls per batch. Carries the per-
    /// row `kind` together with the parsed payload so the HTTP
    /// client can route each row to the right endpoint.
    struct PendingRow: Equatable {
        let id:           String
        let kind:         String
        let payloadJson:  String
        let attempts:     Int
    }

    let dbQueue: DatabaseQueue

    init(dbQueue: DatabaseQueue) {
        self.dbQueue = dbQueue
    }

    // ── Enqueue paths ───────────────────────────────────────────

    /// Append a fresh capture event. Called from
    /// `ScanFlowController.onPassComplete` for both scan and
    /// import. The id is the same UUID stamped on the local
    /// capture row — that becomes capture_events.id on the
    /// backend, so a retry safely lands as a no-op via
    /// ON CONFLICT DO NOTHING.
    ///
    /// Idempotent: re-enqueuing the same captureId overwrites the
    /// outbox row in place rather than inserting a duplicate.
    func enqueueCapture(
        captureId: String,
        source: String,
        pageCount: Int,
        category: String?,
        hasOcr: Bool,
        ocrChars: Int,
        capturedAt: String
    ) async throws {
        let payload = CapturePayload(
            capture_id:  captureId,
            source:      source,
            page_count:  pageCount,
            category:    category,
            has_ocr:     hasOcr,
            ocr_chars:   ocrChars,
            captured_at: capturedAt
        )
        let now = Self.isoNow()
        let row = AnalyticsOutboxRow(
            id:            captureId,
            kind:          "capture",
            payloadJson:   try Self.jsonString(payload),
            createdAt:     now,
            attempts:      0,
            nextAttemptAt: now,                    // flush ASAP
            lastError:     nil
        )
        try await dbQueue.write { db in
            // INSERT … ON CONFLICT(id) DO REPLACE — idempotent
            // re-enqueue in case process death lands between an
            // earlier insert and the worker firing.
            try row.upsert(db)
        }
    }

    /// Enqueue an identify event for the current session. Fired
    /// once on app cold-start AND once on sign-in. Each call
    /// generates a fresh outbox row id so we don't collapse
    /// separate sessions. The server upserts the user row keyed
    /// on the JWT's `sub`, so multiple identify rows in flight
    /// are safe.
    func enqueueIdentify(deviceOs: String, appVersion: String) async throws {
        let payload = IdentifyPayload(device_os: deviceOs, app_version: appVersion)
        let now = Self.isoNow()
        let row = AnalyticsOutboxRow(
            id:            Uuidv7.generate(),
            kind:          "identify",
            payloadJson:   try Self.jsonString(payload),
            createdAt:     now,
            attempts:      0,
            nextAttemptAt: now,
            lastError:     nil
        )
        try await dbQueue.write { db in
            try row.insert(db)
        }
    }

    // ── Flush-side reads / mutations ────────────────────────────

    /// Pull the next slice of due events. Filters on
    /// next_attempt_at <= now() so backed-off rows aren't
    /// picked, and ORDER BY created_at so the oldest events
    /// flush first (FIFO).
    func nextBatch(limit: Int = 200) async throws -> [PendingRow] {
        let now = Self.isoNow()
        return try await dbQueue.read { db in
            try AnalyticsOutboxRow
                .filter(Column("next_attempt_at") <= now)
                .order(Column("created_at").asc)
                .limit(limit)
                .fetchAll(db)
                .map {
                    PendingRow(
                        id:          $0.id,
                        kind:        $0.kind,
                        payloadJson: $0.payloadJson,
                        attempts:    $0.attempts
                    )
                }
        }
    }

    /// Drop rows the server confirmed it has.
    func acknowledge(ids: [String]) async throws {
        guard !ids.isEmpty else { return }
        try await dbQueue.write { db in
            _ = try AnalyticsOutboxRow
                .filter(ids.contains(Column("id")))
                .deleteAll(db)
        }
    }

    /// Bump attempts + reschedule for the listed rows. The new
    /// next_attempt_at uses the capped exponential backoff
    /// schedule (or the server's Retry-After when one was
    /// supplied via [retryAfter]).
    func markFailure(
        ids: [String],
        attemptCount: Int,
        error: String,
        retryAfter: TimeInterval? = nil
    ) async throws {
        guard !ids.isEmpty else { return }
        let backoff = retryAfter ?? Self.backoffForAttempt(attemptCount)
        let nextAt = Self.iso(at: Date().addingTimeInterval(backoff))
        let truncated = String(error.prefix(maxErrorLen))

        try await dbQueue.write { db in
            try AnalyticsOutboxRow
                .filter(ids.contains(Column("id")))
                .updateAll(
                    db,
                    Column("attempts").set(to: Column("attempts") + 1),
                    Column("next_attempt_at").set(to: nextAt),
                    Column("last_error").set(to: truncated)
                )
        }
    }

    /// GC sweep — drop outbox rows older than 30 days regardless
    /// of attempts. Bounds the impact of "auth broken forever"
    /// or "user signed out for a month" cases.
    func gc() async throws {
        let cutoff = Self.iso(at: Date().addingTimeInterval(-30 * 24 * 3600))
        try await dbQueue.write { db in
            _ = try AnalyticsOutboxRow
                .filter(Column("created_at") < cutoff)
                .deleteAll(db)
        }
    }

    func pendingCount() async throws -> Int {
        try await dbQueue.read { db in
            try AnalyticsOutboxRow.fetchCount(db)
        }
    }

    // ── Backoff schedule ─────────────────────────────────────────
    // Capped exponential. Steps in seconds (matches Android):
    //    attempt 0 → 30s
    //    attempt 1 → 1m
    //    attempt 2 → 2m
    //    attempt 3 → 5m
    //    attempt 4 → 15m
    //    attempt 5+ → 30m (cap)
    private static func backoffForAttempt(_ attempts: Int) -> TimeInterval {
        let ladder: [TimeInterval] = [30, 60, 120, 300, 900, 1_800]
        let idx = min(max(0, attempts), ladder.count - 1)
        return ladder[idx]
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static func jsonString<T: Encodable>(_ value: T) throws -> String {
        let data = try JSONEncoder().encode(value)
        return String(data: data, encoding: .utf8) ?? ""
    }

    private static func isoNow() -> String { iso(at: Date()) }

    private static func iso(at date: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: date)
    }

    private let maxErrorLen = 256
}
