/*
 * AnalyticsOutboxRow.swift
 *
 * GRDB row for the `analytics_outbox` table. Mirror of Android's
 * `AnalyticsOutboxEntity.kt`.
 *
 * Lifecycle:
 *   1. ScanFlowController.onPassComplete / auth-state observer
 *      enqueue rows via AnalyticsRepository.
 *   2. AnalyticsFlushTask drains in batches via the URLSession
 *      client and acknowledges accepted ids.
 *   3. Failures bump `attempts` + `next_attempt_at` per the
 *      capped exponential backoff in the repository.
 *
 * NOTE on `kind`:
 *   "capture"  → POST /v1/events/capture/batch
 *   "identify" → POST /v1/identify
 *
 * Row id semantics:
 *   - capture rows: id = capture_event UUID. Same as the local
 *     `captures.id` and the server's capture_events.id PK, so a
 *     server-side ON CONFLICT DO NOTHING makes retries safe.
 *   - identify rows: id = a fresh UUIDv7 just for this row.
 */

import Foundation
import GRDB

struct AnalyticsOutboxRow: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = "analytics_outbox"

    var id: String                                  // UUIDv7
    var kind: String                                // "capture" | "identify"
    var payloadJson: String                         // serialized POST body
    var createdAt: String                           // ISO-8601
    var attempts: Int
    var nextAttemptAt: String                       // ISO-8601
    var lastError: String?

    enum CodingKeys: String, CodingKey {
        case id
        case kind
        case payloadJson    = "payload_json"
        case createdAt      = "created_at"
        case attempts
        case nextAttemptAt  = "next_attempt_at"
        case lastError      = "last_error"
    }
}
