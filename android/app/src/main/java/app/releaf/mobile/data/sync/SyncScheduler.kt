/*
 * SyncScheduler.kt
 *
 * WorkManager façade for sync-worker lifecycle. Three things live here:
 *
 *   1. [schedulePeriodic] — install the 15-minute recurring job. Called
 *      from [ReleafApp.onCreate] when we observe a signed-in state.
 *      KEEP policy means re-invoking this on every app start is safe;
 *      WorkManager de-dupes by [SyncWorker.PERIODIC_WORK_NAME].
 *
 *   2. [requestImmediate] — fire-and-forget one-shot. Called from
 *      repositories right after a mutation so the user's edit shows up
 *      on Drive without waiting 15 minutes. KEEP policy coalesces bursts
 *      (typing quickly into the editor won't enqueue one per keystroke).
 *
 *   3. [cancelAll] — tear everything down on sign-out so we don't retain
 *      any previous-user work in the queue.
 *
 * Both jobs require a connected network. Periodic uses exponential
 * backoff anchored at 60s; one-shot defaults to LINEAR 30s — the
 * one-shot is user-triggered, so a shorter first retry keeps latency
 * low for the common "flaky wifi" case.
 */

package app.releaf.mobile.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    // Periodic cadence. 15 minutes is WorkManager's floor for periodic
    // work; anything shorter gets rounded up. The 5-minute flex window
    // gives WorkManager slack to batch with other jobs.
    private const val PERIODIC_INTERVAL_MIN = 15L
    private const val PERIODIC_FLEX_MIN     =  5L

    private const val PERIODIC_BACKOFF_SEC  = 60L
    private const val ONESHOT_BACKOFF_SEC   = 30L

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Install or refresh the 15-minute periodic job. Idempotent. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_MIN, TimeUnit.MINUTES,
            PERIODIC_FLEX_MIN,     TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PERIODIC_BACKOFF_SEC, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueue an immediate single-flight push. Safe to call from any
     * repository after a row is marked dirty — KEEP coalesces a burst
     * of calls into one run.
     */
    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, ONESHOT_BACKOFF_SEC, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.ONESHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel every queued + running sync job. Called on sign-out. */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(SyncWorker.ONESHOT_WORK_NAME)
    }
}
