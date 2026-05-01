/*
 * QuickInkSyncScheduler.kt
 *
 * WorkManager façade for QuickInk's sync-worker lifecycle. Mirror of
 * Releaf's `SyncScheduler.kt` with QuickInk-specific unique-work
 * names so the two apps don't share a queue if both are installed
 * (separate processes wouldn't share anyway, but the explicit names
 * keep things grep-able).
 *
 * Three things live here:
 *   1. [schedulePeriodic] — install the 15-minute recurring job.
 *      Called from [QuickInkApp]'s auth observer when state flips
 *      to SignedIn. KEEP policy means re-invoking on every app
 *      start is safe; WorkManager de-dupes by
 *      `QuickInkSyncWorker.PERIODIC_WORK_NAME`.
 *
 *   2. [requestImmediate] — fire-and-forget one-shot. Called from
 *      mutation sites (capture save, ocr-result save, notepad
 *      edit) so the user's edit shows up on Drive without waiting
 *      15 minutes. KEEP coalesces typing bursts into one run.
 *
 *   3. [cancelAll] — tear everything down on sign-out.
 *
 * Note on the Drive-backup toggle: [QuickInkSyncWorker] reads
 * `SettingsPreferences.driveBackupEnabled` per pass and no-ops
 * when off. We deliberately don't gate at scheduler time so a
 * toggle-off → toggle-on round trip doesn't have to re-register
 * the worker. See QuickInkSyncWorker's header for the rationale.
 */

package app.quickink.mobile.data.sync

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

object QuickInkSyncScheduler {

    // 15-minute floor for periodic work — anything less gets rounded
    // up by WorkManager. 5-minute flex window gives WM slack to batch.
    private const val PERIODIC_INTERVAL_MIN = 15L
    private const val PERIODIC_FLEX_MIN     =  5L

    private const val PERIODIC_BACKOFF_SEC  = 60L
    private const val ONESHOT_BACKOFF_SEC   = 30L

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Install or refresh the 15-minute periodic job. Idempotent. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<QuickInkSyncWorker>(
            PERIODIC_INTERVAL_MIN, TimeUnit.MINUTES,
            PERIODIC_FLEX_MIN,     TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PERIODIC_BACKOFF_SEC, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            QuickInkSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueue an immediate single-flight push. Safe to call from
     * any repository after a row is marked dirty — KEEP coalesces a
     * burst of calls into one run.
     */
    fun requestImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<QuickInkSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, ONESHOT_BACKOFF_SEC, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            QuickInkSyncWorker.ONESHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Cancel every queued + running QuickInk sync job. */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(QuickInkSyncWorker.PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(QuickInkSyncWorker.ONESHOT_WORK_NAME)
    }
}
