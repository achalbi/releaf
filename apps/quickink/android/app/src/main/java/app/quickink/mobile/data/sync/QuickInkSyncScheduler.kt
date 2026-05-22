/*
 * QuickInkSyncScheduler.kt
 *
 * WorkManager façade for QuickInk's sync-worker lifecycle. Mirror of
 * Releaf's `SyncScheduler.kt` with QuickInk-specific unique-work
 * names so the two apps don't share a queue if both are installed
 * (separate processes wouldn't share anyway, but the explicit names
 * keep things grep-able).
 *
 * Four things live here:
 *   1. [schedulePeriodic] — legacy 15-minute recurring job helper.
 *      Current QuickInk auth wiring clears this work on sign-in
 *      because Drive backup is no longer periodic.
 *
 *   2. [requestAutoSyncIfDue] — app-initiated one-shot. It checks
 *      dirty rows and caps automatic backup to once per 24 hours.
 *
 *   3. [requestUserSync] — manual Sync now. This bypasses the daily
 *      auto-sync throttle and uses REPLACE so a fresh tap wins.
 *
 *   4. [cancelAll] — tear everything down on sign-out.
 *
 * Note on the Drive-backup toggle: [QuickInkSyncWorker] reads
 * `SettingsPreferences.driveBackupEnabled` per pass and no-ops
 * when off. We deliberately don't gate at scheduler time so a
 * toggle-off → toggle-on round trip doesn't have to re-register
 * the worker. See QuickInkSyncWorker's header for the rationale.
 */

package app.quickink.mobile.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.quickink.mobile.QuickInkApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.sync.SyncStateKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object QuickInkSyncScheduler {

    // 15-minute floor for periodic work — anything less gets rounded
    // up by WorkManager. 5-minute flex window gives WM slack to batch.
    private const val PERIODIC_INTERVAL_MIN = 15L
    private const val PERIODIC_FLEX_MIN     =  5L

    private const val PERIODIC_BACKOFF_SEC  = 60L
    private const val ONESHOT_BACKOFF_SEC   = 30L
    private const val AUTO_SYNC_MIN_INTERVAL_HOURS = 24L
    private const val TAG = "QuickInkSync"

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
     * Raw app-initiated enqueue with no daily throttle. Prefer
     * [requestAutoSyncIfDue] from feature code; this remains for
     * narrow internal recovery paths that have already made their
     * own scheduling decision.
     */
    fun requestImmediate(context: Context) {
        val request = oneShotRequest(appInitiated = true)

        WorkManager.getInstance(context).enqueueUniqueWork(
            QuickInkSyncWorker.ONESHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * App-initiated Drive backup for dirty local records. This is
     * intentionally throttled to once per 24 hours; manual Sync now
     * remains immediate via [requestUserSync].
     *
     * Returns true when a worker was enqueued.
     */
    suspend fun requestAutoSyncIfDue(
        context: Context,
        dirtyCount: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? QuickInkApp ?: return@withContext false
        val authState = app.authStore.state.value as? AuthState.SignedIn
            ?: return@withContext false

        val dirty = dirtyCount ?: app.countDirtyRowsForSync(authState.session.userId)
        if (dirty <= 0) {
            Log.i(TAG, "auto-sync: skip (no dirty records)")
            return@withContext false
        }

        val syncStateDao = app.database.syncStateDao()
        val now = Instant.now()
        val lastAuto = syncStateDao
            .get(SyncStateKeys.LAST_AUTO_SYNC_REQUEST_AT)
            ?.value
            ?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }
        if (lastAuto != null &&
            Duration.between(lastAuto, now) < Duration.ofHours(AUTO_SYNC_MIN_INTERVAL_HOURS)
        ) {
            Log.i(TAG, "auto-sync: skip ($dirty dirty records, already requested today)")
            return@withContext false
        }

        val nowIso = IsoClock.nowIso()
        syncStateDao.upsert(
            SyncStateEntity(
                key       = SyncStateKeys.LAST_AUTO_SYNC_REQUEST_AT,
                value     = nowIso,
                updatedAt = nowIso,
            )
        )

        val request = oneShotRequest(appInitiated = true)
        WorkManager.getInstance(context).enqueueUniqueWork(
            QuickInkSyncWorker.ONESHOT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        Log.i(TAG, "auto-sync: enqueued ($dirty dirty records)")
        true
    }

    private fun oneShotRequest(appInitiated: Boolean) =
        OneTimeWorkRequestBuilder<QuickInkSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.LINEAR, ONESHOT_BACKOFF_SEC, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(QuickInkSyncWorker.INPUT_APP_INITIATED to appInitiated)
            )
            .build()

    /**
     * Enqueue an immediate sync triggered by an explicit user
     * action (Settings → "Sync now"). Uses [ExistingWorkPolicy.REPLACE]
     * so a fresh tap *cancels any pending retry from a previous
     * failed run* and starts clean — KEEP would have silently
     * dropped the request, leaving the user stuck behind the old
     * retry's backoff window.
     *
     * NOT marked expedited. Earlier code added
     * `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` to bypass
     * Android 12+ scheduling delay, but expedited work requires
     * `CoroutineWorker.getForegroundInfo()` to be overridden — and
     * we don't have a foreground notification wired. Without the
     * override, expedited requests are silently demoted /
     * cancelled mid-run, which produced runaway
     * `JobCancellationException` loops (workers cancelled every
     * 3-5 seconds, retried, cancelled again). Plain non-expedited
     * one-time work runs within ~5-15s for a foreground app, which
     * is plenty given the UI's tap-ack window covers the gap.
     *
     * Same "fresh tap always wins" posture [requestRestore] takes
     * for the Restore button.
     */
    fun requestUserSync(context: Context) {
        val request = oneShotRequest(appInitiated = false)

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
