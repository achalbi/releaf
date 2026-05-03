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
import androidx.work.OutOfQuotaPolicy
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
     *
     * For *user-initiated* syncs (Settings → "Sync now"), call
     * [requestUserSync] instead. KEEP causes a serious dead-state
     * bug for that path: once a worker run fails and WorkManager
     * queues a backoff retry, every subsequent KEEP request is
     * silently dropped because the retry is still ENQUEUED. Result:
     * the user keeps tapping "Sync now" and sees nothing happen.
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

    /**
     * Enqueue an immediate sync triggered by an explicit user
     * action (Settings → "Sync now"). Uses [ExistingWorkPolicy.REPLACE]
     * so a fresh tap *cancels any pending retry from a previous
     * failed run* and starts clean — KEEP would have silently
     * dropped the request, leaving the user stuck behind the old
     * retry's backoff window.
     *
     * Marked **expedited** so WorkManager actually starts the
     * worker within ~10 seconds instead of the indefinite delay
     * non-expedited OneTimeWork can sit at on Android 12+. The
     * user has just tapped "Sync now"; deferring execution past
     * the optimistic UI flash is what produced the "Last synced
     * still shows Never" symptom users were reporting.
     * `RUN_AS_NON_EXPEDITED_WORK_REQUEST` is the safe fallback
     * when the per-app expedited quota is exhausted (instead of
     * throwing).
     *
     * Same "fresh tap always wins" posture [requestRestore] takes
     * for the Restore button. Mirrors the iOS
     * `SyncScheduler.requestImmediate(...)` semantics where each
     * call kicks a fresh in-process Task.
     */
    fun requestUserSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<QuickInkSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, ONESHOT_BACKOFF_SEC, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            QuickInkSyncWorker.ONESHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Enqueue a one-shot PULL-ONLY pass via [QuickInkRestoreWorker].
     * The Settings → "Restore from Drive" CTA calls this. Uses a
     * distinct unique-work name so a queued restore can't be
     * coalesced with a queued sync, and `ExistingWorkPolicy.REPLACE`
     * so a fresh tap always wins (vs sync's KEEP).
     */
    fun requestRestore(context: Context) {
        val request = OneTimeWorkRequestBuilder<QuickInkRestoreWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, ONESHOT_BACKOFF_SEC, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            QuickInkRestoreWorker.ONESHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Cancel every queued + running QuickInk sync job. */
    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(QuickInkSyncWorker.PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(QuickInkSyncWorker.ONESHOT_WORK_NAME)
        wm.cancelUniqueWork(QuickInkRestoreWorker.ONESHOT_WORK_NAME)
    }
}
