/*
 * AnalyticsOutboxEntity.kt
 *
 * On-device queue of analytics events waiting for the QuickInk
 * backend (api-quickink.thoughtbasics.com). Mirror of iOS
 * `AnalyticsOutboxRow.swift`.
 *
 * Lifecycle:
 *   1. ScanFlowController.onPassComplete fires after a scan/import
 *      → AnalyticsRepository.enqueueCapture inserts a row here.
 *   2. AnalyticsFlushWorker picks the next batch (≤200), POSTs to
 *      /v1/events/capture/batch.
 *   3. Server returns `accepted: [...ids...]` — worker calls
 *      acknowledge() which deletes those rows.
 *   4. Failures (5xx, network) leave rows; markFailure() bumps
 *      attempts + nextAttemptAt with exponential backoff.
 *
 * Schema lives parallel to the existing analytics tables (captures,
 * ocr_results) but doesn't reference them — the row carries every
 * field the backend needs as a serialized JSON blob in
 * payloadJson. Keeps the outbox decoupled from app schema changes.
 *
 * NOTE on `kind`:
 *   "capture"  → POST /v1/events/capture/batch  body shape per row
 *   "identify" → POST /v1/identify              one-off per session
 * Row id semantics:
 *   - capture rows: id = capture_event UUID (also the server's
 *     capture_events.id PK — idempotent retry safe).
 *   - identify rows: id = a fresh UUIDv7 just for this row.
 */

package app.quickink.mobile.data.analytics

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analytics_outbox")
data class AnalyticsOutboxEntity(
    @PrimaryKey
    val id: String,                                 // UUIDv7

    @ColumnInfo(name = "kind")
    val kind: String,                               // "capture" | "identify"

    @ColumnInfo(name = "payload_json")
    val payloadJson: String,                        // serialized POST body

    @ColumnInfo(name = "created_at")
    val createdAt: String,                          // ISO-8601

    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: String,                      // ISO-8601 — flush only when now ≥ this

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)
