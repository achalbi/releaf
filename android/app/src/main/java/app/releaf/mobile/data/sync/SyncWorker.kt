/*
 * SyncWorker.kt
 *
 * Background worker that drains every dirty local row to Drive via
 * [SyncRepository.pushDirty]. Enqueued by [SyncScheduler] — both on a
 * 15-minute periodic cadence and immediately after a user mutation via
 * a one-shot trigger.
 *
 * Not signed in: the worker returns success with no-op. The scheduler
 * also calls `cancelAll` on sign-out, but this extra check keeps us
 * defensive in case an enqueued job runs between sign-out events.
 *
 * Access-token freshness: we don't try to refresh here. If the stored
 * token is past its expiry, we return [Result.retry] — the WorkManager
 * backoff will come back later, by which time the UI (or a future
 * background refresher) should have rotated the token. Trying to refresh
 * from the worker would race with any refresh the UI layer is doing.
 *
 * Partial pass (PushResult.failed > 0): we return [Result.retry] so
 * WorkManager schedules an exponential backoff to try the remaining
 * rows again. The successful rows are already dirty=0 and won't be
 * re-uploaded.
 */

package app.releaf.mobile.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.drive.DriveError
import java.time.Instant

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ReleafApp

        val authState = app.authStore.state.value
        if (authState !is AuthState.SignedIn) {
            // Signed-out: nothing to push. Success (not failure/retry) so
            // WorkManager doesn't back off; the scheduler also cancels the
            // periodic work on sign-out, but belt-and-braces.
            return Result.success()
        }
        val session = authState.session

        // Don't push with a stale token. The UI layer owns refresh; we
        // just retry later once it's rotated.
        if (!session.expiresAt.isAfter(Instant.now())) {
            return Result.retry()
        }

        return try {
            val result = app.syncRepository.pushDirty(
                userId      = session.userId,
                deviceId    = DeviceIdentity.get(applicationContext),
                accessToken = session.accessToken,
            )
            // Per-row failures are caught inside SyncRepository and
            // counted on `failed`; dirty rows survive so a retry can pick
            // them up with backoff.
            if (result.failed > 0) Result.retry() else Result.success()
        } catch (_: DriveError.Unauthenticated) {
            // Token was rejected server-side (revoked, scope removed).
            // Don't retry — the user needs to sign in again; the UI will
            // surface a nudge when authStore flips to SignedOut.
            Result.failure()
        } catch (_: DriveError) {
            // Transient Drive error (network, 5xx). Back off and retry.
            Result.retry()
        } catch (_: Exception) {
            // Anything else (I/O, serialization) — retry with backoff.
            Result.retry()
        }
    }

    companion object {
        /** Unique name for the 15-minute periodic job. */
        const val PERIODIC_WORK_NAME = "releaf-sync-periodic"

        /** Unique name for immediate, mutation-triggered jobs. */
        const val ONESHOT_WORK_NAME  = "releaf-sync-oneshot"
    }
}
