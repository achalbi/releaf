/*
 * QuickInkSyncWorker.kt
 *
 * Background worker that drains every dirty local row to Drive via
 * [SyncRepository.sync]. Mirror of Releaf's `SyncWorker.kt`, scoped
 * to QuickInk's three entity kinds via [QuickInkSyncDataSource].
 *
 * Drive-toggle gate (per QUICKINK_PROPOSAL.md Phase 4 Q3): the
 * scheduler always registers the periodic job — whether to actually
 * sync is decided here, on each pass, by reading
 * `SettingsPreferences.driveBackupEnabled`. When the user has the
 * Drive backup toggle off, the worker returns [Result.success] with
 * no Drive interaction. This way:
 *   - Toggling the switch in Settings doesn't need to talk to
 *     WorkManager (no race with cancel/reschedule)
 *   - The user can flip it back on and the next 15-min tick picks
 *     up where it left off, no re-registration needed
 *   - Tests can isolate the worker from preferences trivially
 *
 * Not signed in: returns [Result.success] with no-op (also defended
 * by the lifecycle observer in [QuickInkApp.observeAuthForSyncLifecycle]).
 *
 * Access-token freshness: we don't try to refresh here. If the
 * stored token is past expiry, return [Result.retry] — the
 * WorkManager backoff comes back later, by which time the UI (or a
 * future background refresher) should have rotated it. Same posture
 * as Releaf's SyncWorker.
 *
 * Partial pass (`failed > 0`): return [Result.retry] so WorkManager
 * schedules an exponential backoff to retry the remaining rows. The
 * successful rows are already dirty=0 and won't be re-uploaded.
 */

package app.quickink.mobile.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.quickink.mobile.QUICKINK_APP_VERSION
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.features.settings.SettingsPreferences
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.sync.DeviceIdentity
import app.releaf.mobile.data.sync.SyncRepository
import java.time.Instant

class QuickInkSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as QuickInkApp

        // ---- Gate 1: signed-in? ----
        val authState = app.authStore.state.value
        if (authState !is AuthState.SignedIn) {
            // Signed-out: nothing to push. Lifecycle observer also
            // cancels the periodic on sign-out, but belt-and-braces.
            return Result.success()
        }
        val session = authState.session

        // ---- Gate 2: drive backup toggle on? ----
        // Read fresh per pass — the user can flip it from Settings
        // between ticks. SharedPreferences read is cheap (~µs) so
        // doing this every pass is fine.
        val prefs = SettingsPreferences(applicationContext)
        if (!prefs.driveBackupEnabled) {
            // No-op success: user has Drive backup off. Schedule
            // stays installed so flipping the toggle back on picks
            // up at the next 15-min tick without re-registration.
            return Result.success()
        }

        // ---- Gate 3: access token still fresh? ----
        if (!session.expiresAt.isAfter(Instant.now())) {
            return Result.retry()
        }

        // PR #3c (per Releaf's pattern): construct fresh per work
        // pass so the data source captures the active session's
        // userId — when the user signs out and back in as a
        // different account, the next worker run uses fresh objects.
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
            val result = syncRepository.sync(
                deviceId    = DeviceIdentity.get(applicationContext),
                accessToken = session.accessToken,
            )

            // Phase 6 — back up the actual scanned PDFs + preview
            // JPEGs to Drive. Runs after the JSON metadata pass so
            // the capture rows already exist in the manifest; a
            // fresh-device restore can then find binaries via the
            // row's `pdf_drive_file_id`. Best-effort: per-row errors
            // are swallowed inside the helper. Mirror
            // `QuickInkBinarySync.swift` on iOS.
            val binarySync = QuickInkBinarySync(
                context     = applicationContext,
                captureDao  = app.database.captureDao(),
                driveClient = app.driveClient,
            )
            runCatching {
                binarySync.uploadAndCascade(session.userId, session.accessToken)
                binarySync.restorePending(session.userId, session.accessToken)
            }

            when {
                // Remote manifest is on a newer major schema than
                // this build. Retrying won't help — the user has to
                // update. Failure stops WorkManager retries; the
                // (future) Settings block banner will surface it.
                result.versionBlocked -> Result.failure()
                result.failed > 0     -> Result.retry()
                else                  -> Result.success()
            }
        } catch (_: DriveError.Unauthenticated) {
            // Token rejected server-side (revoked, scope removed).
            // Don't retry — UI surfaces the nudge once authStore
            // flips to SignedOut.
            Result.failure()
        } catch (_: DriveError) {
            // Transient (network, 5xx). Back off and retry.
            Result.retry()
        } catch (_: Exception) {
            // Anything else (I/O, serialization) — retry.
            Result.retry()
        }
    }

    companion object {
        /** Unique name for the 15-minute periodic job. */
        const val PERIODIC_WORK_NAME = "quickink-sync-periodic"

        /** Unique name for immediate, mutation-triggered jobs. */
        const val ONESHOT_WORK_NAME  = "quickink-sync-oneshot"
    }
}
