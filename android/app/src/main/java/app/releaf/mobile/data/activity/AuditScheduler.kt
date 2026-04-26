/*
 * AuditScheduler.kt
 *
 * WorkManager façade for the audit log's prune lifecycle. Mirror of
 * SyncScheduler — the same lifecycle hooks, same KEEP coalescing.
 *
 * Periodic cadence is 24h (with a 6h flex window). Pruning is cheap
 * — a single indexed `DELETE WHERE timestamp < cutoff` — so daily is
 * a wide enough margin that even active users don't notice latency
 * between an event aging out and being deleted.
 */

package app.releaf.mobile.data.activity

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AuditScheduler {

    private const val PERIODIC_INTERVAL_HOURS = 24L
    private const val PERIODIC_FLEX_HOURS     =  6L

    /** Install or refresh the daily prune job. Idempotent. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<PruneAuditWorker>(
            PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS,
            PERIODIC_FLEX_HOURS,     TimeUnit.HOURS,
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PruneAuditWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel the prune job — called on sign-out. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(PruneAuditWorker.PERIODIC_WORK_NAME)
    }
}
