/*
 * QuickInkRestoreWorker.kt
 *
 * One-shot worker that performs a PULL-ONLY pass against Drive via
 * [SyncRepository.restore], followed by a [QuickInkBinarySync.restorePending]
 * pass to download PDFs + previews referenced by the just-pulled
 * captures rows. Distinct from [QuickInkSyncWorker] — the latter does
 * the bidirectional periodic sync; this one is the user-initiated
 * "Restore from Drive" CTA in Settings.
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
 * Two-phase pull:
 *   1. JSON metadata via SyncRepository.restore — populates rows
 *      in notepad_entries, captures, ocr_results, categories.
 *      Captures land with `pdf_drive_file_id` set but `pdf_uri`
 *      blanked (the source device's local path is meaningless here;
 *      see QuickInkSyncDataSource.applyRemoteUpsert KIND_CAPTURE).
 *   2. QuickInkBinarySync.restorePending — for every capture whose
 *      row has a Drive file id but no readable local file, downloads
 *      the bytes into AttachmentStorage and rewrites pdf_uri /
 *      preview_uri. Without this phase, the user opens a freshly-
 *      restored capture and gets "open failed: ENOENT" until the
 *      next periodic sync (15+ minutes later) does the same work.
 *
 * Token-freshness posture: same as QuickInkSyncWorker — proceed even
 * when the local TTL stamp is past. The wire token is the source of
 * truth; if it's also dead, the catch block below picks up the 401
 * and the AUTH_REJECTED banner takes over. With the foreground token-
 * refresh hook in QuickInkApp, tokens are typically rotated by the
 * time this worker runs, so the stale-stamp branch is mostly
 * defensive.
 *
 * Not signed in: returns [Result.success] with no-op (defended by
 * the Settings button's `isSignedIn` check, but belt-and-braces).
 *
 * Mirror of iOS `QuickInkSyncEnvironment.requestRestore()`.
 */

package app.quickink.mobile.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.quickink.mobile.QUICKINK_APP_VERSION
import app.quickink.mobile.QuickInkApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.sync.DeviceIdentity
import app.releaf.mobile.data.sync.SyncErrorCodes
import app.releaf.mobile.data.sync.SyncRepository
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.sync.SyncStateKeys
import java.time.Instant

class QuickInkRestoreWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as QuickInkApp
        Log.i(TAG, "restore.doWork: starting restore pass")

        // ---- Gate 1: signed-in? ----
        val authState = app.authStore.state.value
        if (authState !is AuthState.SignedIn) {
            Log.i(TAG, "restore.gate-1 (signed-in): user is signed out — skipping")
            return Result.success()
        }
        val session = authState.session
        Log.i(TAG, "restore.gate-1 (signed-in): ok (user=${session.userId.take(8)}…)")

        // ---- Gate 2: token freshness ----
        // Previously this returned Result.retry() when the local TTL
        // stamp was past expiry — same anti-pattern QuickInkSyncWorker
        // had and removed. The local stamp is conservative (55min vs
        // Google's ~60min wire TTL), and a Restore tap from a fresh-
        // launched app may run before the foreground-refresh hook in
        // QuickInkApp has fired. Returning retry there made
        // "Restore from Drive" feel silently broken: the user taps,
        // nothing visible happens, the worker quietly backs off and
        // retries. Better posture: try the pull anyway and let the
        // actual Drive 401 (caught below) be the source of truth —
        // AUTH_REJECTED then surfaces in Settings with a recovery
        // path the user can act on.
        if (!session.expiresAt.isAfter(Instant.now())) {
            Log.i(TAG, "restore.gate-2 (token-fresh): local TTL stamp is stale " +
                "(expiresAt=${session.expiresAt}) — proceeding anyway, " +
                "Drive will reject if the wire token is also dead.")
        } else {
            Log.i(TAG, "restore.gate-2 (token-fresh): ok (expiresAt=${session.expiresAt})")
        }

        val dataSource = QuickInkSyncDataSource(
            notepadDao         = app.database.notepadDao(),
            captureDao         = app.database.captureDao(),
            ocrResultDao       = app.database.ocrResultDao(),
            categoryDao        = app.database.categoryDao(),
            profileSettingsDao = app.database.profileSettingsDao(),
            userId             = session.userId,
        )
        val syncRepository = SyncRepository(
            dataSource   = dataSource,
            driveClient  = app.driveClient,
            syncStateDao = app.database.syncStateDao(),
            appVersion   = QUICKINK_APP_VERSION,
        )

        return try {
            // ---- Phase 1: JSON metadata pull ----
            Log.i(TAG, "restore: starting metadata pass")
            val result = syncRepository.restore(
                deviceId    = DeviceIdentity.get(applicationContext),
                accessToken = session.accessToken,
            )
            Log.i(TAG, "restore: metadata pass done — " +
                "downloaded=${result.downloaded} " +
                "applyFailed=${result.applyFailed} " +
                "versionBlocked=${result.versionBlocked}")

            if (result.versionBlocked) {
                // Remote manifest is on a newer major schema than this
                // build. Retrying won't help — the user has to update
                // the app. Stop WorkManager retries.
                Log.w(TAG, "restore: result=FAILURE (version-blocked by future schema)")
                writeRestoreOutcome(
                    app          = app,
                    status       = RESTORE_STATUS_VERSION_BLOCKED,
                    downloaded   = result.downloaded,
                    applyFailed  = result.applyFailed,
                    orphanFound  = 0,
                    orphanCleaned = 0,
                )
                return Result.failure()
            }

            // Surface partial-restore signal. The metadata that DID
            // land is durable, so we still consider this a SUCCESS
            // overall — the alternative (Result.retry) would loop on
            // genuinely broken rows (parse error in a single payload,
            // malformed manifest entry pointing at a deleted file)
            // without ever making progress. Logging at WARN so the
            // user can spot a partial restore in `adb logcat -s
            // QuickInkSync` even when the worker exits successfully.
            if (result.applyFailed > 0) {
                Log.w(TAG, "restore: ${result.applyFailed} row(s) failed to apply — " +
                    "see preceding 'pullDelta:' lines for kind/id/exception detail. " +
                    "Restore is otherwise complete; failed rows can be re-pulled by " +
                    "tapping Restore from Drive again.")
            }

            // ---- Phase 2: binary download ----
            // For every active capture whose row has a Drive file id
            // but no readable local file, pull the bytes into
            // AttachmentStorage and rewrite pdf_uri / preview_uri.
            // Best-effort: per-row failures are swallowed inside
            // restorePending so a single bad download doesn't block
            // the rest. Any outer-level throw (network down, Drive
            // 401) is caught here and the worker reports retry —
            // the metadata phase already succeeded so the rows are
            // durable; the next pass picks up the missing binaries.
            //
            // Without this phase, fresh-device restores leave the
            // user with a Library full of captures whose PDFs aren't
            // on disk yet. Tapping any capture lands on
            // "open failed: ENOENT" until QuickInkSyncWorker's next
            // periodic tick (15+ minutes later) runs the same call.
            val binarySync = QuickInkBinarySync(
                context            = applicationContext,
                captureDao         = app.database.captureDao(),
                profileSettingsDao = app.database.profileSettingsDao(),
                driveClient        = app.driveClient,
            )
            Log.i(TAG, "restore: starting binary restore pass")
            runCatching {
                binarySync.restorePending(session.userId, session.accessToken)
            }.onSuccess {
                Log.i(TAG, "restore: binary restore pass done")
            }.onFailure { e ->
                // Swallow — the metadata is already on disk and the
                // worker's overall outcome is still "we got most of
                // the way". The next periodic sync will retry the
                // missing binaries.
                Log.w(TAG, "restore: binary restore pass failed (continuing): $e")
            }

            // ---- Phase 3: Drive-side orphan cleanup ----
            // Tombstone manifest entries whose parent isn't in the
            // same manifest — typically ocr_result rows whose parent
            // capture was tombstoned by an old build that didn't
            // cascade (PR-E parts 1+2 prevent NEW orphans; this
            // retires the historical ones). Best-effort: the call
            // logs per-orphan upload failures and continues, so a
            // single bad orphan doesn't block the rest of the restore.
            // Failures here don't change the overall result — the
            // user's local data is already restored from phase 1+2;
            // cleanup is purely a Drive-housekeeping side-effect.
            Log.i(TAG, "restore: starting Drive cleanup pass")
            val cleanup = runCatching {
                syncRepository.cleanupOrphans(
                    deviceId    = DeviceIdentity.get(applicationContext),
                    accessToken = session.accessToken,
                )
            }.getOrElse { e ->
                Log.w(TAG, "restore: cleanup pass failed (continuing): $e")
                null
            }
            if (cleanup != null) {
                Log.i(TAG, "restore: cleanup pass done — " +
                    "orphansFound=${cleanup.orphansFound} " +
                    "orphansTombstoned=${cleanup.orphansTombstoned} " +
                    "manifestRewritten=${cleanup.manifestRewritten}")
            }

            // ---- Phase 4: clear stale AUTH_REJECTED banner ----
            // Drive accepted our token end-to-end on this restore —
            // if the manifest pull or any non-trapped Drive call had
            // 401'd, we'd be in the catch block below. Clear the
            // error code so the Settings banner stops showing. Best-
            // effort: a write failure here just leaves the banner up
            // until the next successful sync clears it itself.
            runCatching {
                val nowIso = IsoClock.nowIso()
                app.database.syncStateDao().upsert(
                    SyncStateEntity(
                        key       = SyncStateKeys.LAST_SYNC_ERROR_CODE,
                        value     = "",
                        updatedAt = nowIso,
                    )
                )
            }.onFailure { Log.w(TAG, "restore: clearAuthRejected failed: $it") }

            // ---- Phase 5: write outcome for Settings banner ----
            // Settings observes LAST_RESTORE_OUTCOME via Flow and
            // surfaces a transient banner with the numbers from this
            // pass. Best-effort write — the banner is cosmetic; if
            // the upsert fails the user just doesn't get the toast.
            writeRestoreOutcome(
                app           = app,
                status        = RESTORE_STATUS_OK,
                downloaded    = result.downloaded,
                applyFailed   = result.applyFailed,
                orphanFound   = cleanup?.orphansFound ?: 0,
                orphanCleaned = cleanup?.orphansTombstoned ?: 0,
            )

            Log.i(TAG, "restore: result=SUCCESS")
            Result.success()
        } catch (e: DriveError.Unauthenticated) {
            // Drive rejected the token (401 or 403). Same conservative
            // posture as QuickInkSyncWorker — DELIBERATELY do NOT sign
            // the user out here. The Settings banner observes
            // LAST_SYNC_ERROR_CODE and surfaces the recovery path; the
            // worker writing AUTH_REJECTED here keeps that surface
            // honest even when the failing pass was a Restore.
            Log.w(TAG, "restore: result=FAILURE (Drive auth rejected — 401/403). " +
                "User can manually sign out + back in via Settings if " +
                "this persists. $e")
            runCatching {
                val nowIso = IsoClock.nowIso()
                app.database.syncStateDao().upsert(
                    SyncStateEntity(
                        key       = SyncStateKeys.LAST_SYNC_ERROR_CODE,
                        value     = SyncErrorCodes.AUTH_REJECTED,
                        updatedAt = nowIso,
                    )
                )
            }.onFailure { Log.w(TAG, "restore: writeAuthRejected failed: $it") }
            writeRestoreOutcome(
                app           = app,
                status        = RESTORE_STATUS_FAILED,
                downloaded    = 0,
                applyFailed   = 0,
                orphanFound   = 0,
                orphanCleaned = 0,
            )
            Result.failure()
        } catch (e: DriveError) {
            // Transient (network blip, 5xx). Back off and retry.
            Log.w(TAG, "restore: result=RETRY (Drive transient error): $e")
            // Don't write the outcome here — retry means "try again
            // soon"; surfacing a "Restore failed" banner pre-empts
            // the auto-retry's chance at success.
            Result.retry()
        } catch (e: Exception) {
            // Anything else (I/O, serialization). Retry — this surface
            // is rarer than auth failures, and the metadata pull is
            // safe to redo.
            Log.e(TAG, "restore: result=RETRY (unexpected exception): $e", e)
            Result.retry()
        }
    }

    /**
     * Persist the outcome of this restore pass to
     * `sync_state[LAST_RESTORE_OUTCOME]` so the Settings banner can
     * surface a transient summary ("Restored 73 items, 11 orphan
     * rows skipped"). Pipe-separated key=value pairs — keeps the
     * Compose-side parsing trivial without dragging
     * kotlinx.serialization into the UI module.
     *
     * Best-effort: a write failure here just means the user doesn't
     * get the cosmetic toast. The actual restore work has already
     * happened.
     */
    private suspend fun writeRestoreOutcome(
        app: QuickInkApp,
        status: String,
        downloaded: Int,
        applyFailed: Int,
        orphanFound: Int,
        orphanCleaned: Int,
    ) {
        runCatching {
            val nowIso = IsoClock.nowIso()
            val payload = buildString {
                append("downloaded=").append(downloaded).append('|')
                append("applyFailed=").append(applyFailed).append('|')
                append("orphanFound=").append(orphanFound).append('|')
                append("orphanCleaned=").append(orphanCleaned).append('|')
                append("completedAt=").append(nowIso).append('|')
                append("status=").append(status)
            }
            app.database.syncStateDao().upsert(
                SyncStateEntity(
                    key       = SyncStateKeys.LAST_RESTORE_OUTCOME,
                    value     = payload,
                    updatedAt = nowIso,
                )
            )
        }.onFailure { Log.w(TAG, "writeRestoreOutcome: $it") }
    }

    companion object {
        /** Unique name for the user-initiated restore one-shot. */
        const val ONESHOT_WORK_NAME = "quickink-restore-oneshot"

        /**
         * Logcat tag — shared with QuickInkSyncWorker so a single
         * `adb logcat -s QuickInkSync` filter shows both workers'
         * output. Lines from this worker are all prefixed
         * `restore.…` to keep the two streams visually distinct.
         */
        private const val TAG = "QuickInkSync"

        /** Status values written to `sync_state[LAST_RESTORE_OUTCOME].status`. */
        const val RESTORE_STATUS_OK              = "ok"
        const val RESTORE_STATUS_FAILED          = "failed"
        const val RESTORE_STATUS_VERSION_BLOCKED = "version_blocked"
    }
}
