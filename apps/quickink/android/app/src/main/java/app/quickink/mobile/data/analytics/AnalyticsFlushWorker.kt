/*
 * AnalyticsFlushWorker.kt
 *
 * WorkManager worker that drains the on-device analytics outbox
 * to the QuickInk backend. Mirror of iOS `AnalyticsFlushTask.swift`.
 *
 * Invocation modes:
 *   - Periodic: every 30 min when network is available. Scheduled
 *     once on QuickInkApp init (see [scheduleAll]).
 *   - Opportunistic: [requestImmediate] enqueues a one-shot worker
 *     called from ScanFlowController.onPassComplete (right after a
 *     scan/import) and from the auth observer (right after sign-in).
 *
 * Both paths use ExistingWorkPolicy.KEEP semantics — the
 * one-shot is identified by [WORK_NAME_IMMEDIATE] so back-to-back
 * captures coalesce into one flush rather than 5 racing workers.
 *
 * Coordination with the existing Drive sync worker
 * ([QuickInkSyncWorker]): they're parallel pipelines, both keyed
 * off onPassComplete. An analytics outage doesn't affect Drive
 * sync, and vice versa. Don't merge them — different cadences,
 * different failure modes.
 *
 * Feature flag: gated by `BuildConfig.ANALYTICS_ENABLED`. When
 * off, schedule + requestImmediate are no-ops and any rows that
 * already exist in the outbox stay there, untouched, until a
 * future build flips the flag on. (This is for v1 dogfood —
 * default false in builds we ship until the backend is verified.)
 */

package app.quickink.mobile.data.analytics

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.quickink.mobile.BuildConfig
import app.quickink.mobile.QuickInkApp
import app.releaf.mobile.auth.AuthStore
import java.util.concurrent.TimeUnit

class AnalyticsFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!BuildConfig.ANALYTICS_ENABLED) {
            return Result.success()                  // flag off → no-op
        }

        val app = applicationContext as QuickInkApp
        val repo = AnalyticsRepository(
            app.database.analyticsOutboxDao(),
        )
        val client = AnalyticsApiClient(
            authStore = app.authStore,
            baseUrl   = BuildConfig.ANALYTICS_BASE_URL,
        )

        // Drain in waves of up to 200 rows. If a batch comes back
        // entirely successful, immediately try the next batch — the
        // outbox might have a multi-batch tail (e.g. 30 days of
        // queued events because the backend was offline).
        // Any non-success result ends THIS run; the next periodic
        // wake-up or opportunistic call picks up where we left off.
        var pulled = 0
        while (true) {
            val batch = repo.nextBatch()
            if (batch.isEmpty()) break

            val outcome = flushBatch(repo, client, batch)
            pulled += batch.size

            // Stop after any non-Success result — the worker's done
            // for this run, the outbox handles backoff via
            // markFailure's nextAttemptAt updates.
            if (!outcome) break
        }

        // GC sweep — drops rows older than 30 days, regardless of
        // attempts. Stops the table from growing forever in the
        // pathological case (auth broken, backend gone, etc.).
        runCatching { repo.gc() }

        if (pulled > 0) {
            Log.i(TAG, "[analytics] flush drained $pulled rows; " +
                "remaining=${runCatching { repo.pendingCount() }.getOrDefault(-1)}")
        }
        return Result.success()
    }

    /**
     * Flush a single batch. Returns true when the run can continue
     * to the next batch immediately, false when the worker should
     * stop (transient failure, rate limit, etc).
     */
    private suspend fun flushBatch(
        repo: AnalyticsRepository,
        client: AnalyticsApiClient,
        batch: List<AnalyticsRepository.PendingRow>,
    ): Boolean {
        // Identify rows go one at a time (the endpoint is /v1/identify
        // with a single body). Capture rows are bundled into one
        // batch POST. Worker sends identify(s) first since the
        // server's User row needs to exist before we can attach
        // capture_events (FK constraint).
        val (identifies, captures) = batch.partition { it.kind == "identify" }

        for (row in identifies) {
            when (val r = client.postIdentify(row.payloadJson)) {
                is AnalyticsApiClient.ApiResult.Success -> {
                    repo.acknowledge(listOf(row.id))
                }
                is AnalyticsApiClient.ApiResult.RateLimited -> {
                    repo.markFailure(
                        ids               = listOf(row.id),
                        attemptCount      = row.attempts,
                        error             = "rate_limited",
                        retryAfterSeconds = r.retryAfterSeconds,
                    )
                    return false
                }
                is AnalyticsApiClient.ApiResult.Unauthorized -> {
                    repo.markFailure(listOf(row.id), row.attempts, "unauthorized")
                    return false
                }
                is AnalyticsApiClient.ApiResult.ClientError -> {
                    // Drop — retrying won't help (e.g. malformed
                    // device_os, expired in-flight session).
                    Log.w(TAG, "[analytics] dropping identify ${row.id}: ${r.code} ${r.body}")
                    repo.acknowledge(listOf(row.id))
                }
                is AnalyticsApiClient.ApiResult.ServerError -> {
                    repo.markFailure(listOf(row.id), row.attempts, "5xx ${r.code}")
                    return false
                }
                is AnalyticsApiClient.ApiResult.NetworkError -> {
                    repo.markFailure(listOf(row.id), row.attempts, r.message)
                    return false
                }
            }
        }

        if (captures.isNotEmpty()) {
            val pairs = captures.map { it.id to it.payloadJson }
            val outcome = client.postCaptureBatch(pairs)
            val ids = captures.map { it.id }
            when (outcome) {
                is AnalyticsApiClient.ApiResult.Success -> {
                    // Server returns the IDs it durably persisted.
                    // Drop those from the outbox. Any row in the
                    // batch NOT in `accepted` we leave queued — the
                    // server didn't see it (could be the result of a
                    // partial commit, very unlikely but possible).
                    val accepted = outcome.acceptedIds.toSet()
                    val toAck = ids.filter { it in accepted }
                    repo.acknowledge(toAck)
                    val dropped = ids - accepted
                    if (dropped.isNotEmpty()) {
                        Log.w(TAG, "[analytics] server didn't accept ${dropped.size} ids; leaving queued")
                    }
                }
                is AnalyticsApiClient.ApiResult.RateLimited -> {
                    repo.markFailure(ids, captures.first().attempts, "rate_limited", outcome.retryAfterSeconds)
                    return false
                }
                is AnalyticsApiClient.ApiResult.Unauthorized -> {
                    repo.markFailure(ids, captures.first().attempts, "unauthorized")
                    return false
                }
                is AnalyticsApiClient.ApiResult.ClientError -> {
                    Log.w(TAG, "[analytics] dropping ${ids.size} captures: ${outcome.code} ${outcome.body}")
                    repo.acknowledge(ids)
                }
                is AnalyticsApiClient.ApiResult.ServerError -> {
                    repo.markFailure(ids, captures.first().attempts, "5xx ${outcome.code}")
                    return false
                }
                is AnalyticsApiClient.ApiResult.NetworkError -> {
                    repo.markFailure(ids, captures.first().attempts, outcome.message)
                    return false
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "QuickInkAnalytics"

        /** Periodic-job name; KEEP semantics dedupe re-registration. */
        const val WORK_NAME_PERIODIC  = "analytics_flush_periodic"

        /** One-shot opportunistic name; KEEP collapses bursts. */
        const val WORK_NAME_IMMEDIATE = "analytics_flush_immediate"

        /**
         * Register the 30-min periodic worker. Idempotent —
         * KEEP policy means re-registering is a no-op as long as
         * a periodic with the same name is already enqueued.
         * Called from QuickInkApp.onCreate() once per process.
         */
        fun scheduleAll(context: Context) {
            if (!BuildConfig.ANALYTICS_ENABLED) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodic = PeriodicWorkRequestBuilder<AnalyticsFlushWorker>(
                30, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        /**
         * Fire a one-shot flush right now. Called from
         * ScanFlowController.onPassComplete and the auth-state
         * observer so the user sees their event arrive within
         * seconds, not on the next 30-min periodic tick.
         *
         * KEEP policy means a burst of N captures coalesces into
         * one in-flight worker — no thrash if the user fires off
         * 5 scans in quick succession.
         */
        fun requestImmediate(context: Context) {
            if (!BuildConfig.ANALYTICS_ENABLED) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val one = OneTimeWorkRequestBuilder<AnalyticsFlushWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.KEEP,
                one,
            )
        }
    }
}
