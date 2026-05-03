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
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.quickink.mobile.QUICKINK_APP_VERSION
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.features.settings.SettingsPreferences
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.sync.DeviceIdentity
import app.releaf.mobile.data.sync.SyncRepository
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.sync.SyncStateKeys
import app.releaf.mobile.data.common.IsoClock
import java.time.Instant

class QuickInkSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as QuickInkApp
        Log.i(TAG, "doWork: starting sync pass")

        // ---- Gate 1: signed-in? ----
        val authState = app.authStore.state.value
        if (authState !is AuthState.SignedIn) {
            Log.i(TAG, "gate-1 (signed-in): user is signed out — skipping")
            return Result.success()
        }
        val session = authState.session
        Log.i(TAG, "gate-1 (signed-in): ok (user=${session.userId.take(8)}…)")

        // ---- Gate 2: drive backup toggle on? ----
        // Read fresh per pass — the user can flip it from Settings
        // between ticks. SharedPreferences read is cheap (~µs) so
        // doing this every pass is fine.
        val prefs = SettingsPreferences(applicationContext)
        if (!prefs.driveBackupEnabled) {
            Log.i(TAG, "gate-2 (drive-backup): disabled in Settings — skipping")
            return Result.success()
        }
        Log.i(TAG, "gate-2 (drive-backup): on")

        // (Previously: a `gate-3` here returned Result.retry when
        // `session.expiresAt` was past now. That was the source of
        // the "Sync now silently does nothing" bug — the local TTL
        // stamp is conservative (55min vs Google's ~60min) and the
        // worker has no Activity context to refresh the token in
        // the background. Better posture: try the sync anyway and
        // let the actual Drive 401 be the source of truth — the
        // catch block below signs the user out so QuickInkRoot's
        // ReSignInGate prompts a fresh consent + token. No more
        // infinite-retry-loop on stale TTL stamps.)
        if (!session.expiresAt.isAfter(Instant.now())) {
            Log.i(TAG, "gate-3 (token-fresh): local TTL stamp is stale " +
                "(expiresAt=${session.expiresAt}) — proceeding anyway, " +
                "Drive will reject if the wire token is also dead and " +
                "the catch block will trigger sign-out.")
        } else {
            Log.i(TAG, "gate-3 (token-fresh): ok (expiresAt=${session.expiresAt})")
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
            Log.i(TAG, "sync: starting metadata pass")
            val result = syncRepository.sync(
                deviceId    = DeviceIdentity.get(applicationContext),
                accessToken = session.accessToken,
            )
            Log.i(TAG, "sync: metadata pass done — " +
                "uploaded=${result.uploaded} tombstoned=${result.tombstoned} " +
                "downloaded=${result.downloaded} failed=${result.failed} " +
                "versionBlocked=${result.versionBlocked}")

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
                Log.i(TAG, "sync: starting binary pass")
                binarySync.uploadAndCascade(session.userId, session.accessToken)
                binarySync.restorePending(session.userId, session.accessToken)
                Log.i(TAG, "sync: binary pass done")
            }.onFailure { e ->
                Log.w(TAG, "sync: binary pass failed (best-effort, continuing): $e")
            }

            when {
                // Remote manifest is on a newer major schema than
                // this build. Retrying won't help — the user has to
                // update. Failure stops WorkManager retries; the
                // (future) Settings block banner will surface it.
                result.versionBlocked -> {
                    Log.w(TAG, "sync: result=FAILURE (version-blocked by future schema)")
                    Result.failure()
                }
                result.failed > 0 -> {
                    Log.w(TAG, "sync: result=RETRY (${result.failed} rows failed)")
                    Result.retry()
                }
                else -> {
                    Log.i(TAG, "sync: result=SUCCESS")
                    Result.success()
                }
            }
        } catch (e: DriveError.Unauthenticated) {
            // Token rejected server-side (revoked, scope removed,
            // or genuinely expired with no in-process refresh path).
            // The Android Credential Manager flow doesn't surface a
            // refresh_token to the worker, so background refresh
            // isn't possible here — instead, sign the user out so
            // QuickInkRoot's ReSignInGate takes over and prompts a
            // fresh consent + new access token. The next periodic
            // tick (or the user re-tapping Sync now) then runs with
            // a healthy token. This breaks the silent-retry loop
            // that used to leave "Last synced" at "Never" forever
            // for users whose Drive grant had lapsed.
            Log.w(TAG, "sync: result=FAILURE (Drive 401 — token rejected). " +
                "Signing user out so re-sign-in re-issues credentials. $e")
            recordPendingFromError(app)
            // Fire-and-forget sign-out. Don't block on it — the
            // worker just needs to flag the AuthStore so the UI
            // observer flips to ReSignInGate; the actual Google
            // SDK signOut runs on AuthStore's own scope.
            app.authStore.signOut()
            Result.failure()
        } catch (e: DriveError) {
            // Transient (network, 5xx). Back off and retry.
            Log.w(TAG, "sync: result=RETRY (Drive transient error): $e")
            recordPendingFromError(app)
            Result.retry()
        } catch (e: Exception) {
            // Anything else (I/O, serialization) — retry.
            Log.e(TAG, "sync: result=RETRY (unexpected exception): $e", e)
            recordPendingFromError(app)
            Result.retry()
        }
    }

    /**
     * On a thrown sync attempt, bump `PENDING_COUNT` to a non-zero
     * sentinel so the UI's sync pill switches from "Synced" / "Never"
     * to a "pending" state. Best-effort — silent failure here just
     * leaves the pill unchanged. Does NOT touch `LAST_FULL_SYNC_AT`:
     * the pass didn't succeed, so claiming "synced just now" would
     * be a lie (matches iOS [SyncStateStore.recordSyncFailed]).
     */
    private suspend fun recordPendingFromError(app: QuickInkApp) {
        runCatching {
            val nowIso = IsoClock.nowIso()
            app.database.syncStateDao().upsert(
                SyncStateEntity(
                    key       = SyncStateKeys.PENDING_COUNT,
                    value     = "1",
                    updatedAt = nowIso,
                )
            )
        }.onFailure { Log.w(TAG, "recordPendingFromError: $it") }
    }

    companion object {
        /** Unique name for the 15-minute periodic job. */
        const val PERIODIC_WORK_NAME = "quickink-sync-periodic"

        /** Unique name for immediate, mutation-triggered jobs. */
        const val ONESHOT_WORK_NAME  = "quickink-sync-oneshot"

        /**
         * Logcat tag for every sync trace. Filter with
         * `adb logcat -s QuickInkSync` to see only sync output.
         * Logs are intentionally chatty so a user investigating
         * "sync stuck on Never" can see exactly which gate or step
         * is failing — pre-fix, the entire flow returned silently.
         */
        private const val TAG = "QuickInkSync"
    }
}
