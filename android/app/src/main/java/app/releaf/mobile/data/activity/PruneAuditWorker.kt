/*
 * PruneAuditWorker.kt
 *
 * Daily WorkManager job that drops audit events past the user's
 * chosen retention window. Reads the window from [UiPreferences]
 * at run-time so changing the setting in Settings ▸ Activity
 * takes effect on the next tick without rescheduling.
 *
 * Skipped entirely when retention = Forever or no signed-in user.
 * Errors aren't retried — the next tick will try again, and
 * losing one prune cycle is harmless.
 */

package app.releaf.mobile.data.activity

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.ui.theme.UiPreferences

class PruneAuditWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ReleafApp
        val prefs = UiPreferences.get(applicationContext)
        val retention = prefs.state.value.activityRetention
        val days = retention.days ?: return Result.success() // Forever — skip
        // No-op when signed out — the audit log is user-scoped, but the
        // prune query is timestamp-only, so we still let it run. The user
        // gate is just to avoid touching a DB tied to no logical session.
        val signedIn = app.authStore.state.value as? AuthState.SignedIn
            ?: return Result.success()
        // Currently unused — kept for symmetry with future per-user prune.
        @Suppress("unused") val userId = signedIn.session.userId
        runCatching { app.recentActivityRepository.prune(days) }
        return Result.success()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "audit_prune_periodic"
    }
}
