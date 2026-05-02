/*
 * QuickInkRestoreWorker.kt
 *
 * One-shot worker that performs a PULL-ONLY pass against Drive via
 * [SyncRepository.restore]. Distinct from [QuickInkSyncWorker] —
 * the latter does the bidirectional periodic sync; this one is the
 * user-initiated "Restore from Drive" CTA in Settings, which only
 * downloads remote rows and applies them locally.
 *
 * Why a separate worker class instead of a parameterised mode flag:
 *   - Different unique-work names (`quickink-sync-*` vs
 *     `quickink-restore-oneshot`) so a queued restore can't be
 *     coalesced with a queued sync.
 *   - Different success semantics — restore should always run when
 *     the user taps, even if a sync is already pending. The
 *     scheduler uses `ExistingWorkPolicy.REPLACE` here (vs KEEP for
 *     sync) so a fresh tap always wins.
 *   - Easier to reason about in tests + logs.
 *
 * Drive-toggle gate: NOT applied here. Restore is an explicit user
 * action; if they tap "Restore from Drive", they want to pull data
 * even if the recurring sync toggle is off. If they want neither,
 * they don't tap.
 *
 * Not signed in: returns [Result.success] with no-op (defended by
 * the Settings button's `isSignedIn` check, but belt-and-braces).
 */

package app.quickink.mobile.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.quickink.mobile.QUICKINK_APP_VERSION
import app.quickink.mobile.QuickInkApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.sync.DeviceIdentity
import app.releaf.mobile.data.sync.SyncRepository
import java.time.Instant

class QuickInkRestoreWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as QuickInkApp

        val authState = app.authStore.state.value
        if (authState !is AuthState.SignedIn) return Result.success()
        val session = authState.session

        // Token freshness check — same posture as the sync worker.
        if (!session.expiresAt.isAfter(Instant.now())) {
            return Result.retry()
        }

        val dataSource = QuickInkSyncDataSource(
            notepadDao    = app.database.notepadDao(),
            captureDao    = app.database.captureDao(),
            ocrResultDao  = app.database.ocrResultDao(),
            categoryDao   = app.database.categoryDao(),
            userId        = session.userId,
        )
        val syncRepository = SyncRepository(
            dataSource   = dataSource,
            driveClient  = app.driveClient,
            syncStateDao = app.database.syncStateDao(),
            appVersion   = QUICKINK_APP_VERSION,
        )

        return try {
            val result = syncRepository.restore(
                deviceId    = DeviceIdentity.get(applicationContext),
                accessToken = session.accessToken,
            )
            if (result.versionBlocked) Result.failure() else Result.success()
        } catch (_: DriveError.Unauthenticated) {
            Result.failure()
        } catch (_: DriveError) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        /** Unique name for the user-initiated restore one-shot. */
        const val ONESHOT_WORK_NAME = "quickink-restore-oneshot"
    }
}
