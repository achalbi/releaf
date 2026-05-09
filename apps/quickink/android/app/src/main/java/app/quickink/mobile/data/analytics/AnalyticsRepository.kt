/*
 * AnalyticsRepository.kt
 *
 * Single point through which the rest of the app interacts with
 * the analytics outbox. Two write entry points (enqueueCapture,
 * enqueueIdentify); three flush-side entry points the worker uses
 * (nextBatch, acknowledge, markFailure); plus a GC sweep called
 * once per flush.
 *
 * NOTHING in this file knows about HTTP — the worker is what
 * actually POSTs to the backend. Keeping persistence isolated
 * means tests can exercise enqueue / drain / ack semantics
 * against in-memory Room without spinning up a fake server.
 *
 * Mirror of iOS `AnalyticsRepository.swift`.
 */

package app.quickink.mobile.data.analytics

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

/** ISO-8601 string for [millis] (epoch ms), in UTC. Mirrors IsoClock.nowIso(). */
private fun isoFromMillis(millis: Long): String =
    ISO_FORMATTER.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

class AnalyticsRepository(private val dao: AnalyticsOutboxDao) {

    // ── Wire payloads (match the Rails backend's JSON shape) ──

    /**
     * One-row body for /v1/identify. The verifier on the server
     * pulls email / sub / name / picture from the JWT itself, so
     * this body only carries the two mobile-only fields.
     */
    @Serializable
    data class IdentifyPayload(
        val device_os: String,
        val app_version: String,
    )

    /**
     * One element in /v1/events/capture/batch's `events` array.
     * Field names match the Rails controller's expected JSON
     * keys exactly — DO NOT rename without updating
     * api/v1/events_controller.rb in lockstep.
     */
    @Serializable
    data class CapturePayload(
        val capture_id: String,
        val source: String,
        val page_count: Int,
        val category: String? = null,
        val has_ocr: Boolean,
        val ocr_chars: Int,
        val captured_at: String,
    )

    /**
     * Snapshot the worker pulls per flush. Keeps the per-row
     * `kind` together with the parsed payload so the HTTP client
     * can route each row to the right endpoint.
     */
    data class PendingRow(
        val id: String,
        val kind: String,
        val payloadJson: String,
        val attempts: Int,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ── Enqueue paths ───────────────────────────────────────────────

    /**
     * Append a fresh capture event to the outbox. Called from
     * `ScanFlowController.onPassComplete` for both scan and import
     * paths. The id is the same UUID stamped on the local capture
     * row — that becomes capture_events.id on the backend, so a
     * retry safely lands as a no-op via ON CONFLICT DO NOTHING.
     *
     * Idempotent: re-enqueuing the same captureId overwrites the
     * outbox row in place rather than inserting a duplicate. This
     * matters when process death lands between enqueue and flush —
     * on relaunch the controller might re-fire onPassComplete for
     * an in-flight capture.
     */
    suspend fun enqueueCapture(
        captureId: String,
        source: String,
        pageCount: Int,
        category: String?,
        hasOcr: Boolean,
        ocrChars: Int,
        capturedAt: String,
    ) {
        val payload = CapturePayload(
            capture_id  = captureId,
            source      = source,
            page_count  = pageCount,
            category    = category,
            has_ocr     = hasOcr,
            ocr_chars   = ocrChars,
            captured_at = capturedAt,
        )
        val now = IsoClock.nowIso()
        dao.upsert(
            AnalyticsOutboxEntity(
                id              = captureId,
                kind            = "capture",
                payloadJson     = json.encodeToString(payload),
                createdAt       = now,
                attempts        = 0,
                nextAttemptAt   = now,           // flush ASAP
                lastError       = null,
            ),
        )
    }

    /**
     * Enqueue an identify event for the current session. Fired
     * once on app cold-start AND once on sign-in. Each call
     * generates a fresh outbox row id so we don't collapse
     * separate sessions. The server upserts the user row keyed
     * on the JWT's `sub`, so multiple identify rows in flight
     * are safe.
     */
    suspend fun enqueueIdentify(
        deviceOs: String,
        appVersion: String,
    ) {
        val payload = IdentifyPayload(deviceOs, appVersion)
        val now = IsoClock.nowIso()
        dao.upsert(
            AnalyticsOutboxEntity(
                id              = Uuidv7.generate(),
                kind            = "identify",
                payloadJson     = json.encodeToString(payload),
                createdAt       = now,
                attempts        = 0,
                nextAttemptAt   = now,
                lastError       = null,
            ),
        )
    }

    // ── Flush-side reads / mutations ────────────────────────────────

    /**
     * Pull the next slice of due events for the worker. Filters
     * on next_attempt_at <= now() so a row that's serving a
     * backoff isn't picked. Caller's responsibility to call
     * acknowledge / markFailure based on the server's response.
     */
    suspend fun nextBatch(limit: Int = 200): List<PendingRow> =
        dao.nextBatch(IsoClock.nowIso(), limit)
            .map { PendingRow(it.id, it.kind, it.payloadJson, it.attempts) }

    /** Drop rows the server confirmed it has. */
    suspend fun acknowledge(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.deleteByIds(ids)
    }

    /**
     * Bump attempts + reschedule for the listed rows. The new
     * next_attempt_at is computed from the current attempts via
     * a capped exponential backoff so a flapping backend doesn't
     * burn battery on tight retries.
     *
     * If [retryAfterSeconds] is non-null (i.e. the server sent a
     * 429 with Retry-After), use it directly — overrides the
     * capped backoff for this round.
     */
    suspend fun markFailure(
        ids: List<String>,
        attemptCount: Int,
        error: String,
        retryAfterSeconds: Long? = null,
    ) {
        if (ids.isEmpty()) return
        val nowMillis = System.currentTimeMillis()
        val backoff = retryAfterSeconds?.let { Duration.ofSeconds(it) }
            ?: backoffForAttempt(attemptCount)
        val nextAt = isoFromMillis(nowMillis + backoff.toMillis())
        dao.bumpFailure(ids, nextAt, error.take(MAX_ERROR_LEN))
    }

    /**
     * GC sweep — drop outbox rows older than 30 days. Bounds the
     * impact of "user has been signed out for a month" or "auth
     * is broken forever and the rows just keep retrying" cases.
     * Called by the worker once per flush after acknowledging.
     */
    suspend fun gc() {
        val cutoffMillis = System.currentTimeMillis() - Duration.ofDays(30).toMillis()
        dao.dropOlderThan(isoFromMillis(cutoffMillis))
    }

    suspend fun pendingCount(): Int = dao.count()

    // ── Backoff schedule ─────────────────────────────────────────────
    // Capped exponential. Steps in seconds:
    //    attempt 0 → 30s
    //    attempt 1 → 1m
    //    attempt 2 → 2m
    //    attempt 3 → 5m
    //    attempt 4 → 15m
    //    attempt 5+ → 30m (cap)
    // Steps were picked so that:
    //   - early retries still happen on the user's session (≤2 min)
    //   - later retries don't pile up faster than the 30-min
    //     periodic worker cadence
    //   - the 30-day GC kicks in long before the schedule drifts
    //     into being effectively dead
    private fun backoffForAttempt(attemptCount: Int): Duration {
        val ladder = listOf<Long>(30, 60, 120, 300, 900, 1_800)
        val seconds = ladder[attemptCount.coerceIn(0, ladder.lastIndex)]
        return Duration.ofSeconds(seconds)
    }

    companion object {
        // Truncate the lastError column so a verbose stack trace
        // doesn't blow out the row size.
        private const val MAX_ERROR_LEN = 256
    }
}
