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
import androidx.work.workDataOf
import app.quickink.mobile.QUICKINK_APP_VERSION
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.features.settings.SettingsPreferences
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.sync.DeviceIdentity
import app.releaf.mobile.data.sync.SyncErrorCodes
import app.releaf.mobile.data.sync.SyncRepository
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.sync.SyncStateKeys
import app.releaf.mobile.data.common.IsoClock
import kotlinx.coroutines.CancellationException
import java.time.Instant

/**
 * Diagnostic: call Google's `oauth2.googleapis.com/tokeninfo` with
 * the current access token, parse the response, and log the granted
 * scopes + audience. Intended to be called from a sync/restore
 * worker's auth-rejection catch path so the user (and any future
 * dev investigating "sync stuck on AUTH_REJECTED") sees definitively
 * whether the token has `drive.file` granted — separating "Drive
 * API disabled in Cloud project" from "Drive scope not granted at
 * consent".
 *
 * Logs under tag `QuickInkSync` so the existing
 * `adb logcat -s QuickInkSync` filter picks it up.
 *
 * `internal` so it's reachable from sibling workers (e.g.
 * [QuickInkRestoreWorker]) without exposing it across modules.
 */
internal suspend fun logDriveTokenInfo(accessToken: String) {
    val tag = "QuickInkSync"
    Log.w(tag, "tokeninfo: about to call — " +
        "tokenLen=${accessToken.length} " +
        "looksGoogleish=${accessToken.startsWith("ya29.")}")
    val client = okhttp3.OkHttpClient.Builder()
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    // URL-encode the access token. Google access tokens are
    // typically base64url (no `+/=`) so this rarely matters, but
    // any whitespace/control char in a corrupted token would
    // otherwise break the URL parser before the token even
    // reaches Google's validator.
    val encoded = java.net.URLEncoder.encode(accessToken, "UTF-8")
    val req = okhttp3.Request.Builder()
        .url("https://oauth2.googleapis.com/tokeninfo?access_token=$encoded")
        .get()
        .build()
    client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            Log.w(tag, "tokeninfo: HTTP ${resp.code} body=$body. " +
                "Token likely invalid/expired beyond what local TTL says.")
            return
        }
        // Body is JSON like:
        // {"azp":"...","aud":"...","scope":"...drive.file...","exp":"...","email":"..."}
        // Avoid logging the raw body because it can include the
        // account email address.
        val scopeMatch = Regex("\"scope\"\\s*:\\s*\"([^\"]+)\"").find(body)
        val scopes = scopeMatch?.groupValues?.get(1)?.split(' ').orEmpty()
        val aud = Regex("\"aud\"\\s*:\\s*\"([^\"]+)\"").find(body)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val exp = Regex("\"exp\"\\s*:\\s*\"?([^\",}]+)\"?").find(body)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        Log.w(tag, "tokeninfo: HTTP 200 aud=$aud exp=$exp scopes=$scopes")
        val hasDrive = scopes.any {
            it == "https://www.googleapis.com/auth/drive.file" ||
            it == "https://www.googleapis.com/auth/drive"
        }
        if (hasDrive) {
            Log.w(tag, "tokeninfo: drive.file IS in granted scopes — " +
                "401/403 means Drive API is likely DISABLED in the " +
                "Google Cloud project. Enable at " +
                "console.cloud.google.com/apis/api/drive.googleapis.com")
        } else {
            Log.w(tag, "tokeninfo: drive.file is NOT in granted scopes (got=$scopes). " +
                "User didn't tick the Drive checkbox at consent, OR the OAuth " +
                "consent screen doesn't list drive.file as an authorized scope. " +
                "Sign out, revoke at myaccount.google.com/permissions, sign in fresh.")
        }
    }
}

class QuickInkSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val syncLogLines = mutableListOf<String>()

    override suspend fun doWork(): Result {
        val app = applicationContext as QuickInkApp
        Log.i(TAG, "doWork: starting sync pass")
        writeSyncProgress(
            phase = SYNC_PROGRESS_PHASE_PREPARING,
            label = "Preparing Drive backup…",
            percent = 3,
        )

        // ---- Gate 1: signed-in? ----
        val initialAuthState = app.authStore.state.value
        if (initialAuthState !is AuthState.SignedIn) {
            Log.i(TAG, "gate-1 (signed-in): user is signed out — skipping")
            return Result.success()
        }
        Log.i(TAG, "gate-1 (signed-in): ok (user=${initialAuthState.session.userId.take(8)}…)")

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

        // ---- Pre-flight token refresh ----
        // Closes the "user keeps app open past 55 min, sync 401s,
        // AUTH_REJECTED banner shows" gap. Refresh fires only when
        // the token is genuinely about to expire AND a foreground
        // Activity is available — see
        // [QuickInkApp.ensureFreshSessionForSyncIfPossible] for the
        // full predicate. Background runs (no Activity) skip the
        // refresh entirely so we don't surface the GMS "Request
        // cancelled by quickink" toast; the existing 401 → AUTH_-
        // REJECTED banner path takes over for them.
        app.ensureFreshSessionForSyncIfPossible()

        // Re-read auth state — the pre-flight may have rotated the
        // session under us. Skip cleanly if the user signed out
        // during the refresh attempt.
        val authStateAfterRefresh = app.authStore.state.value
        if (authStateAfterRefresh !is AuthState.SignedIn) {
            Log.i(TAG, "post-refresh: signed out, skipping")
            return Result.success()
        }
        val session = authStateAfterRefresh.session

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
            notepadDao         = app.database.notepadDao(),
            captureDao         = app.database.captureDao(),
            ocrResultDao       = app.database.ocrResultDao(),
            tagDao             = app.database.tagDao(),
            profileSettingsDao = app.database.profileSettingsDao(),
            folderDao          = app.database.folderDao(),
            captureTagDao      = app.database.captureTagDao(),
            smartCollectionDao = app.database.smartCollectionDao(),
            userId             = session.userId,
        )
        val syncRepository = SyncRepository(
            dataSource   = dataSource,
            driveClient  = app.driveClient,
            syncStateDao = app.database.syncStateDao(),
            appVersion   = QUICKINK_APP_VERSION,
        )

        return try {
            // Upload scanned PDFs / previews BEFORE metadata so the
            // payload written in this same Sync-now tap carries fresh
            // `pdf_drive_file_id` / `preview_drive_file_id` values.
            // Previous ordering wrote JSON first and binaries second;
            // a one-tap backup could therefore leave Drive metadata
            // pointing at null binary ids until the next sync pass.
            val binarySync = QuickInkBinarySync(
                context            = applicationContext,
                captureDao         = app.database.captureDao(),
                profileSettingsDao = app.database.profileSettingsDao(),
                driveClient        = app.driveClient,
            )
            Log.i(TAG, "sync: starting binary upload pass")
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_BINARIES,
                label = "Uploading PDFs and previews…",
                percent = 20,
            )
            val binaryResult = binarySync.uploadAndCascade(session.userId, session.accessToken)
            Log.i(TAG, "sync: binary upload pass done — " +
                "completed=${binaryResult.completed}/${binaryResult.attempted} " +
                "failed=${binaryResult.failed}")
            val binaryLabel = if (binaryResult.attempted > 0) {
                "Uploaded ${binaryResult.completed} of ${binaryResult.attempted} files."
            } else {
                "No new files to upload."
            }
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_BINARIES,
                label = binaryLabel,
                percent = 55,
            )

            // Upload-only sync. QuickInk treats Drive as a one-way
            // backup of local-first data — pull-down is only needed
            // when the user explicitly taps "Restore from Drive"
            // (handled by QuickInkRestoreWorker, not this path). The
            // earlier bidirectional behaviour was both slow (every
            // pass downloaded remote changes even when nothing
            // changed) and surprising (cross-device data appeared
            // automatically in the background). Sync-now now means
            // "push my pending changes to Drive."
            Log.i(TAG, "sync: starting metadata pass (push-only)")
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_METADATA,
                label = "Updating Drive records…",
                percent = 70,
            )
            val result = syncRepository.sync(
                deviceId    = DeviceIdentity.get(applicationContext),
                accessToken = session.accessToken,
                pullRemote  = false,
            )
            Log.i(TAG, "sync: metadata pass done — " +
                "uploaded=${result.uploaded} tombstoned=${result.tombstoned} " +
                "downloaded=${result.downloaded} failed=${result.failed} " +
                "versionBlocked=${result.versionBlocked}")
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_METADATA,
                label = "Updated ${result.uploaded + result.tombstoned} Drive record${if (result.uploaded + result.tombstoned == 1) "" else "s"}.",
                percent = 92,
            )

            when {
                // Remote manifest is on a newer major schema than
                // this build. Retrying won't help — the user has to
                // update. Failure stops WorkManager retries; the
                // (future) Settings block banner will surface it.
                result.versionBlocked -> {
                    Log.w(TAG, "sync: result=FAILURE (version-blocked by future schema)")
                    writeSyncErrorCode(app, SyncErrorCodes.UNKNOWN)
                    Result.failure()
                }
                result.failed > 0 || binaryResult.failed > 0 -> {
                    Log.w(TAG, "sync: result=RETRY " +
                        "(metadataFailed=${result.failed}, binaryFailed=${binaryResult.failed})")
                    writeSyncErrorCode(app, SyncErrorCodes.TRANSIENT)
                    Result.retry()
                }
                else -> {
                    Log.i(TAG, "sync: result=SUCCESS")
                    // Clear any previous error code on success so the
                    // Settings re-auth banner stops showing once the
                    // user fixes the underlying issue.
                    writeSyncErrorCode(app, "")
                    // Zero the local-dirty counter immediately so
                    // the Home pill clears the same instant the
                    // worker finishes — without this, the pill would
                    // wait up to 60 seconds for the foreground
                    // pending-push tick to refresh it.
                    writeLocalDirtyCount(app, 0)
                    writeSyncProgress(
                        phase = SYNC_PROGRESS_PHASE_DONE,
                        label = "Backup complete.",
                        percent = 100,
                    )
                    Result.success()
                }
            }
        } catch (e: DriveError.RateLimited) {
            Log.w(TAG, "sync: result=RETRY (Drive rate limited): $e")
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_QUEUED,
                label = "Drive is rate-limiting backup. Retrying soon…",
                percent = 0,
                logLine = "Drive rate limit hit; backup will retry with backoff.",
            )
            recordPendingFromError(app)
            writeSyncErrorCode(app, SyncErrorCodes.TRANSIENT)
            Result.retry()
        } catch (e: DriveError.Unauthenticated) {
            // Drive rejected the token (401, or a 403 that was not a
            // rate/quota response). DELIBERATELY do NOT sign the user
            // out here — a single bad response should not bounce the
            // user to the SignIn screen and risk another loop. The
            // Settings banner gives them an explicit recovery path.
            //
            // Conservative posture: log loudly, mark the pass as
            // permanently-failed (no retry — repeating won't help
            // if the auth is actually dead), and let the user
            // manually re-authenticate via Settings → Account if
            // they notice "Last synced" not updating. The pending-
            // count surfaces the issue without destroying their
            // session.
            Log.w(TAG, "sync: result=FAILURE (Drive auth rejected — 401/403). " +
                "User can manually sign out + back in via Settings if " +
                "this persists. $e")
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_DONE,
                label = "Backup stopped: Drive needs re-authentication.",
                percent = 100,
                logLine = "Backup stopped because Drive rejected the token.",
            )
            // Definitive diagnostic — call Google's tokeninfo endpoint
            // and log the actual scopes attached to this access token.
            // This tells us apart:
            //   - "drive.file" missing       → user didn't grant Drive
            //                                  consent OR Cloud project
            //                                  doesn't authorize the
            //                                  scope. Sign-out + fresh
            //                                  consent fixes the first;
            //                                  Cloud Console fixes the
            //                                  second.
            //   - "drive.file" present       → Drive API is disabled in
            //                                  the Cloud project
            //                                  (returns 403 for any call
            //                                  with a properly-scoped
            //                                  token). Enable at
            //                                  console.cloud.google.com.
            //   - tokeninfo itself 400s      → token is genuinely
            //                                  invalid / expired beyond
            //                                  the local TTL.
            // Best-effort — silent failure here doesn't change the
            // user-facing outcome.
            runCatching {
                logDriveTokenInfo(session.accessToken)
            }.onFailure { Log.w(TAG, "tokeninfo diagnostic failed: $it") }
            recordPendingFromError(app)
            writeSyncErrorCode(app, SyncErrorCodes.AUTH_REJECTED)
            Result.failure()
        } catch (e: DriveError) {
            // Transient (network, 5xx). Back off and retry.
            Log.w(TAG, "sync: result=RETRY (Drive transient error): $e")
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_QUEUED,
                label = "Backup paused. Retrying soon…",
                percent = 0,
                logLine = "Transient Drive error; backup will retry with backoff.",
            )
            recordPendingFromError(app)
            writeSyncErrorCode(app, SyncErrorCodes.TRANSIENT)
            Result.retry()
        } catch (e: CancellationException) {
            // Cancellation = caller asked us to stop, OR the OS tore
            // the worker down (BACKGROUND_RESTRICTION, PREEMPT,
            // TIMEOUT, CONSTRAINT_CONNECTIVITY dropping, etc.). The
            // app cancels its own ONESHOT_WORK on background, so most
            // cancellations land here from there.
            //
            // Return Result.failure() (not retry) so WorkManager
            // doesn't auto-reschedule with backoff — the next time
            // the user opens the app, the foreground pending-push
            // tick fires immediately, sees the still-dirty rows, and
            // re-kicks `requestImmediate`. This breaks the prior
            // cancel/retry loop on ROMs that kill backgrounded work.
            //
            // Still log at INFO (not ERROR) and skip writing the
            // UNKNOWN error code — cancellation isn't a sync fault.
            val stopReason = runCatching { stopReason }.getOrDefault(-999)
            val stopReasonName = stopReasonName(stopReason)
            Log.i(TAG,
                "sync: result=FAILURE (worker cancelled, will retry on foreground): " +
                "stopReason=$stopReason ($stopReasonName)"
            )
            recordPendingFromError(app)
            // Intentionally NOT writing an error code — cancellation
            // isn't a sync failure; the previous code (if any) stays.
            Result.failure()
        } catch (e: Exception) {
            // Anything else (I/O, serialization) — retry.
            // Stop-reason gives the OS-level reason WorkManager tore
            // the worker down: PREEMPT (replaced by REPLACE), TIMEOUT,
            // CONSTRAINT_CONNECTIVITY (network dropped), QUOTA,
            // ESTIMATED_APP_LAUNCH_TIME_CHANGED, etc. Cancellation is
            // handled in the catch block above; this branch only sees
            // genuine failures.
            val stopReason = runCatching { stopReason }.getOrDefault(-999)
            val stopReasonName = stopReasonName(stopReason)
            Log.e(TAG,
                "sync: result=RETRY (unexpected exception): " +
                "exception=$e " +
                "stopReason=$stopReason ($stopReasonName)",
                e,
            )
            writeSyncProgress(
                phase = SYNC_PROGRESS_PHASE_QUEUED,
                label = "Backup paused. Retrying soon…",
                percent = 0,
                logLine = "Unexpected backup error; backup will retry with backoff.",
            )
            recordPendingFromError(app)
            writeSyncErrorCode(app, SyncErrorCodes.UNKNOWN)
            Result.retry()
        }
    }

    private suspend fun writeSyncProgress(
        phase: String,
        label: String,
        percent: Int,
        logLine: String? = label,
    ) {
        val cleanLogLine = logLine
            ?.replace('…', '.')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (cleanLogLine != null && syncLogLines.lastOrNull() != cleanLogLine) {
            syncLogLines += cleanLogLine
            if (syncLogLines.size > SYNC_PROGRESS_MAX_LOG_LINES) {
                syncLogLines.removeAt(0)
            }
        }
        setProgress(
            workDataOf(
                SYNC_PROGRESS_PHASE_KEY to phase,
                SYNC_PROGRESS_LABEL_KEY to label,
                SYNC_PROGRESS_PERCENT_KEY to percent.coerceIn(0, 100),
                SYNC_PROGRESS_LOG_KEY to syncLogLines.joinToString("\n"),
            )
        )
    }

    /**
     * Translate WorkManager's `getStopReason()` int into a readable
     * label so the worker's catch-block log tells us at a glance why
     * the OS / WorkManager pulled the rug out. Constants from
     * `androidx.work.WorkInfo.STOP_REASON_*` (added in WorkManager
     * 2.9). When the int isn't one of the documented values we just
     * print it raw — better than dropping the signal entirely.
     */
    private fun stopReasonName(reason: Int): String = when (reason) {
        -256 -> "NOT_STOPPED"   // androidx.work.WorkInfo.STOP_REASON_NOT_STOPPED
        -1   -> "UNKNOWN"
        1    -> "CANCELLED_BY_APP"   // someone called cancelUniqueWork / REPLACE
        2    -> "PREEMPT"            // a higher-priority job displaced this one
        3    -> "TIMEOUT"            // exceeded execution-time budget
        4    -> "DEVICE_STATE"       // battery / charging / idle constraint flipped
        5    -> "CONSTRAINT_BATTERY_NOT_LOW"
        6    -> "CONSTRAINT_CHARGING"
        7    -> "CONSTRAINT_CONNECTIVITY"   // network requirement broken
        8    -> "CONSTRAINT_DEVICE_IDLE"
        9    -> "CONSTRAINT_STORAGE_NOT_LOW"
        10   -> "QUOTA"
        11   -> "BACKGROUND_RESTRICTION"
        12   -> "APP_STANDBY"
        13   -> "USER"
        14   -> "SYSTEM_PROCESSING"
        15   -> "ESTIMATED_APP_LAUNCH_TIME_CHANGED"
        16   -> "FOREGROUND_SERVICE_LAUNCH"
        -999 -> "stopReason API unavailable"
        else -> "raw=$reason"
    }

    /**
     * Persist the latest sync outcome's error code (or empty string
     * to clear) to `sync_state[LAST_SYNC_ERROR_CODE]`. The Settings
     * screen observes this key and surfaces a re-auth banner when
     * the value is [SyncErrorCodes.AUTH_REJECTED]. Best-effort —
     * silent failure leaves the previous code in place.
     */
    private suspend fun writeSyncErrorCode(app: QuickInkApp, code: String) {
        runCatching {
            val nowIso = IsoClock.nowIso()
            app.database.syncStateDao().upsert(
                SyncStateEntity(
                    key       = SyncStateKeys.LAST_SYNC_ERROR_CODE,
                    value     = code,
                    updatedAt = nowIso,
                )
            )
        }.onFailure { Log.w(TAG, "writeSyncErrorCode($code): $it") }
    }

    /**
     * Persist [count] to `sync_state[LOCAL_DIRTY_COUNT]`. The Home
     * screen "N pending" pill observes this key via Room Flow.
     * Worker calls this with `0` on a successful push so the pill
     * clears instantly instead of waiting for QuickInkApp's 60s
     * foreground ticker to re-poll. Best-effort.
     */
    private suspend fun writeLocalDirtyCount(app: QuickInkApp, count: Int) {
        runCatching {
            val nowIso = IsoClock.nowIso()
            app.database.syncStateDao().upsert(
                SyncStateEntity(
                    key       = SyncStateKeys.LOCAL_DIRTY_COUNT,
                    value     = count.toString(),
                    updatedAt = nowIso,
                )
            )
        }.onFailure { Log.w(TAG, "writeLocalDirtyCount($count): $it") }
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

        const val SYNC_PROGRESS_PHASE_KEY   = "phase"
        const val SYNC_PROGRESS_LABEL_KEY   = "label"
        const val SYNC_PROGRESS_PERCENT_KEY = "percent"
        const val SYNC_PROGRESS_LOG_KEY     = "log"

        const val SYNC_PROGRESS_PHASE_QUEUED    = "queued"
        const val SYNC_PROGRESS_PHASE_PREPARING = "preparing"
        const val SYNC_PROGRESS_PHASE_BINARIES  = "binaries"
        const val SYNC_PROGRESS_PHASE_METADATA  = "metadata"
        const val SYNC_PROGRESS_PHASE_DONE      = "done"

        private const val SYNC_PROGRESS_MAX_LOG_LINES = 40
    }
}
