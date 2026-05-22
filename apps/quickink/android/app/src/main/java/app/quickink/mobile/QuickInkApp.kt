/*
 * QuickInkApp.kt
 *
 * Application-level singleton for QuickInk. Counterpart to Releaf's
 * `ReleafApp.kt`; the per-feature repositories Releaf carries
 * (panchanga, contacts, call history, reminders, shelf, notebook,
 * etc.) don't apply here — QuickInk's surface is notepad + scan +
 * OCR.
 *
 * What lives here (Slice 4.2b):
 *   - QuickInkDatabase          (Room, FTS5, set up in `:apps:quickink`)
 *   - AuthStore                 (singleton from `:shared:auth`; real
 *                                Google Sign-In runs through
 *                                `rememberQuickInkSignInAction` —
 *                                this just holds state)
 *   - DriveClient               (OkHttpDriveClient when web client ID is
 *                                populated, InMemoryDriveClient otherwise)
 *   - Sync lifecycle            ([observeAuthForSyncLifecycle]
 *                                clears legacy periodic work,
 *                                cancels on sign-out)
 *
 * Mirror of the wiring already present in `ReleafApp.kt`.
 */

package app.quickink.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import app.quickink.mobile.data.analytics.AnalyticsFlushWorker
import app.quickink.mobile.data.analytics.AnalyticsRepository
import app.quickink.mobile.data.db.QuickInkDatabase
import app.quickink.mobile.data.panchanga.PanchangaRepository
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.data.sync.QuickInkSyncWorker
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.auth.GoogleAuthClient
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.auth.RealGoogleAuthClient
import app.releaf.mobile.auth.StubGoogleAuthClient
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.InMemoryDriveClient
import app.releaf.mobile.data.drive.OkHttpDriveClient
import app.releaf.mobile.data.sync.SyncErrorCodes
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.sync.SyncStateKeys
import app.quickink.mobile.features.settings.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App version string stamped into the Drive manifest.
 * Informational only per `docs/DRIVE_SCHEMA.md` §"Field reference";
 * not used for compatibility gating. Keep roughly in sync with
 * `versionName` in `app/build.gradle.kts`.
 *
 * `internal` rather than `private` so [QuickInkSyncWorker] (in another
 * package of the same `:apps:quickink` module) can read it. Same
 * pattern Releaf uses (`ReleafApp.APP_VERSION`).
 */
internal const val QUICKINK_APP_VERSION = "0.1.0"

class QuickInkApp : Application() {

    /**
     * Process-scoped Room database. Eager-initialized in `onCreate`
     * so first-screen recompositions don't hit a synchronous
     * Room.databaseBuilder() call on the main thread.
     */
    lateinit var database: QuickInkDatabase
        private set

    /**
     * Process-scoped `AuthStore` singleton. Constructed with the
     * default `StubGoogleAuthClient` here so the app has a
     * persisted-state holder available from process start; the
     * REAL auth flow runs through `rememberQuickInkSignInAction`
     * (in `features/auth/QuickInkAuthBinding.kt`), which calls
     * `RealGoogleAuthClient.signIn()` directly and dispatches
     * results back via `AuthStore.adoptSession` /
     * `AuthStore.failSignIn`.
     */
    lateinit var authStore: AuthStore
        private set

    /**
     * Drive transport. Real REST when the OAuth Web Client ID has
     * been populated in `strings.xml`, in-memory stub otherwise.
     * Same pattern Releaf uses — keeps preview / unconfigured dev
     * builds working without hitting the network.
     */
    lateinit var driveClient: DriveClient
        private set

    /**
     * Read-mostly repository over the bundled Vontikoppal Panchanga
     * dataset. Backs the standalone Calendar screen
     * (`features/calendar`). Seeded on first launch from
     * `assets/panchanga_2026_27.csv`; subsequent launches no-op the
     * `ensureLoaded` call via the row-count guard inside the repo.
     * Mirror of Releaf's `app.panchangaRepository` pattern.
     */
    lateinit var panchangaRepository: PanchangaRepository
        private set

    /**
     * Process-scoped scope for the auth-state observer. Main
     * dispatcher because it only schedules / cancels WorkManager
     * jobs and that has to happen on the main thread anyway.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        // Set the attachments folder name BEFORE the database is
        // touched (and before any sync worker can reach into
        // AttachmentStorage). The shared default is "releaf"; QuickInk
        // overrides to keep its bytes under a sibling-app-distinct
        // folder. The migration on the next line moves any
        // pre-override files into the new location and rewrites the
        // URIs the captures table stores so they keep resolving.
        AttachmentStorage.appFolderName = "quickink"

        database  = QuickInkDatabase.get(this)

        // Resolve the OAuth Web Client ID once and use it for both
        // the AuthStore wrapped client AND the Drive transport
        // selection below. When the placeholder is still in
        // strings.xml, fall back to the in-memory stubs so the app
        // is still buildable/runnable without QuickInk's Cloud
        // credentials checked in.
        val webClientId = getString(R.string.google_web_client_id)
        val placeholderClientId = webClientId == "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID"

        // AuthStore's wrapped GoogleAuthClient — used by every call
        // to `authStore.idToken()` (the analytics worker's auth
        // path). The interactive sign-in flow runs through
        // `rememberQuickInkSignInAction` against an Activity-bound
        // RealGoogleAuthClient and returns its session via
        // `authStore.adoptSession`, so the wrapped client here only
        // needs the silent-Credential-Manager idToken path. Pass
        // `applicationContext` so the analytics worker (with no
        // Activity available) can call `idToken()` safely.
        val authClient: GoogleAuthClient = if (placeholderClientId) {
            StubGoogleAuthClient()
        } else {
            RealGoogleAuthClient(applicationContext, webClientId)
        }
        authStore = AuthStore.get(this, authClient)

        // Best-effort one-shot rename of the legacy attachments
        // folder. Idempotent — if `releaf/attachments/` is gone (fresh
        // install or already migrated) this no-ops. Runs on IO so app
        // start doesn't pay for it; the work is small (a directory
        // rename + one bulk SQL UPDATE) so the race window where a
        // sync worker reads a stale URI is sub-second in practice.
        appScope.launch(Dispatchers.IO) { migrateLegacyAttachmentsFolder() }

        // Pick the Drive client at runtime: real REST when the OAuth
        // Web Client ID has been populated, in-memory stub otherwise.
        // Mirror of ReleafApp.onCreate's logic — flips automatically
        // once `strings.xml`'s `google_web_client_id` is filled in.
        driveClient = if (placeholderClientId) {
            InMemoryDriveClient()
        } else {
            OkHttpDriveClient()
        }

        // Bundled panchanga dataset → SQLite. Fire-and-forget on the
        // app scope so screen mounts don't have to await CSV parsing;
        // the repo's row-count guard makes subsequent calls cheap.
        panchangaRepository = PanchangaRepository(
            context = this,
            dao     = database.panchangaDao(),
        )
        appScope.launch(Dispatchers.IO) {
            runCatching { panchangaRepository.ensureLoaded() }
                .onFailure { Log.w("QuickInkPanchanga", "ensureLoaded failed: $it") }
        }

        observeAuthForSyncLifecycle()
        observeAuthForAnalytics()

        // Schedule the 30-min periodic analytics flush. Idempotent
        // (KEEP semantics on the unique work name); gated by
        // `BuildConfig.ANALYTICS_ENABLED` so a kill-switch build
        // wires nothing.
        AnalyticsFlushWorker.scheduleAll(this)

        // Foreground tracking for analytics gating and the dirty-row
        // auto-sync safety net. Google auth itself does not refresh
        // from this callback; UI-bound AuthorizationClient calls on
        // resume can surface Android's "Request cancelled by
        // QuickInk" toast when the user backgrounds the app quickly.
        // Drive workers instead try a background-safe silent refresh
        // only after Drive rejects an access token.
        registerActivityLifecycleCallbacks(activityTracker)
    }

    // ------------------------------------------------------------------
    // Foreground tracking
    // ------------------------------------------------------------------

    /**
     * Tracks the currently-foregrounded Activity. We only need a weak
     * reference — the lifecycle callbacks fire synchronously and we
     * never hold the Activity across configuration changes (each
     * resume rebinds). When the app has no Activity in the foreground
     * (cold launch with worker running first, or backgrounded), the
     * ref is null and foreground-only work defers.
     */
    private val activityTracker = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            topActivityRef = WeakReference(activity)
            startPendingPushLoopIfNeeded()
        }

        override fun onActivityPaused(activity: Activity) {
            // Drop the ref only if it still points at this activity —
            // a fast resume → pause → resume race could otherwise
            // null-out a fresher reference written by the next resume.
            if (topActivityRef?.get() === activity) {
                topActivityRef = null
                pendingPushLoopJob?.cancel()
                pendingPushLoopJob = null
            }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private var topActivityRef: WeakReference<Activity>? = null
    private val refreshInFlight = AtomicBoolean(false)

    /**
     * True when an Activity is currently resumed (the user has the
     * app open, not just installed). Background callers — primarily
     * the analytics flush worker — gate any operation that would
     * route through `CredentialManager` / `AuthorizationClient` on
     * this. Without an Activity the silent identity flow surfaces
     * the system "Request cancelled by quickink" toast even when
     * the app process is otherwise quiet.
     */
    fun isInForeground(): Boolean = topActivityRef?.get() != null

    /**
     * Background-safe silent token refresh used by
     * `QuickInkSyncWorker` after a 401 from Drive. Wraps
     * `RealGoogleAuthClient.refreshSilentBackground` with a
     * single-flight guard; on success adopts the rotated session
     * through `authStore` so the next worker pass sees the new
     * access token.
     *
     * Returns the rotated session when GMS still has the user's
     * authorization cached; returns `null` when it would need a
     * UI prompt (revoked consent, cleared cache, etc.) — the
     * caller falls back to the existing AUTH_REJECTED banner path.
     */
    suspend fun attemptBackgroundSilentRefresh(
        session: GoogleAuthSession,
    ): GoogleAuthSession? {
        val webClientId = getString(R.string.google_web_client_id)
        if (webClientId == "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID") return null
        if (!refreshInFlight.compareAndSet(false, true)) {
            Log.i("QuickInkAuth", "background-silent-refresh: already in flight, skip")
            return null
        }
        return try {
            val client = RealGoogleAuthClient(this, webClientId)
            val fresh = client.refreshSilentBackground(session)
            if (fresh != null) {
                authStore.adoptSession(fresh)
            }
            fresh
        } finally {
            refreshInFlight.set(false)
        }
    }

    // ------------------------------------------------------------------
    // Pending-push safety net (foreground-only, 60s ticker)
    // ------------------------------------------------------------------

    /** Job for the foreground-only "check for pending pushes" loop. */
    private var pendingPushLoopJob: Job? = null

    /**
     * Start (or no-op if already running) a 60-second ticker that:
     *   1. Counts dirty captures for the UI and all dirty row types
     *      for the scheduling gate.
     *   2. Writes the count to `sync_state[LOCAL_DIRTY_COUNT]` so
     *      the Home screen pill can react reactively via Room Flow.
     *   3. Kicks an app-initiated backup when the count is > 0,
     *      Drive backup is enabled, and the once-per-day auto-sync
     *      throttle allows it. Manual Sync now bypasses that
     *      throttle.
     *
     * Foreground-scoped: started on `onActivityResumed`, cancelled
     * on the LAST `onActivityPaused` (when [topActivityRef] flips
     * to null). The worker itself is left alone and can continue in
     * the background after it has been enqueued.
     */
    private fun startPendingPushLoopIfNeeded() {
        if (pendingPushLoopJob?.isActive == true) return
        pendingPushLoopJob = appScope.launch {
            // Tick once immediately so the pill updates the moment
            // the user opens the app — no 60-second wait.
            try {
                while (true) {
                    runCatching { tickPendingPush() }
                        .onFailure { Log.w("QuickInkSync", "pending-push tick failed: $it") }

                    delay(60_000L)

                    // If the app went background while we were
                    // sleeping, exit the loop — `onActivityResumed`
                    // will start a fresh one on the next foreground.
                    if (topActivityRef?.get() == null) break
                }
            } finally {
                Log.i("QuickInkSync", "pending-push loop stopped (app backgrounded)")
            }
        }
        Log.i("QuickInkSync", "pending-push loop started")
    }

    /**
     * One iteration of the pending-push loop. Computes the dirty
     * count for the active user, writes the user-visible item count
     * to `sync_state`, and checks whether the daily auto-sync policy
     * should enqueue a worker.
     *
     * No-ops gracefully when:
     *   - the user is signed out (nothing to push),
     *   - Drive backup is off (user explicitly opted out),
     *   - or any DAO read throws (logged via runCatching at the
     *     caller, the loop continues on the next tick).
     */
    private suspend fun tickPendingPush() {
        refreshPendingPushState()
    }

    /**
     * Recompute the Home "items pending" count immediately after a
     * local mutation, then nudge the sync scheduler using the full
     * dirty-row count. The visible count intentionally excludes
     * tombstones because deleted rows are no longer user-visible
     * items; the scheduler count still includes tombstones so remote
     * deletes are backed up.
     */
    suspend fun refreshPendingPushState() {
        val authState = authStore.state.value
        if (authState !is AuthState.SignedIn) {
            writeLocalDirtyCount(0)
            return
        }
        val prefs = SettingsPreferences(this)
        if (!prefs.driveBackupEnabled) {
            writeLocalDirtyCount(0)
            return
        }

        // Token refresh is intentionally NOT piggybacked on this
        // tick. Earlier code re-checked the access token here every
        // 60s so a long foreground session (>55 min) wouldn't 401
        // on the next sync, but each call routes through
        // `AuthorizationClient.authorize()` — and if the user
        // happens to background the app while that call is in
        // flight, Android emits a system "Request cancelled by
        // quickink" toast. Now the worker tries the stored token
        // first and only attempts background-safe silent refresh
        // after Drive actually rejects it.

        val dirty = countLocalDirty(authState.session.userId)
        // Always write — including 0 — so the Home pill clears
        // promptly when the user signs out / pauses backup / a sync
        // run completes between ticks.
        writeLocalDirtyCount(dirty)

        val syncDirty = countDirtyRowsForSync(authState.session.userId)
        if (syncDirty <= 0) return

        // If the last sync ended with AUTH_REJECTED, the access token
        // needs an explicit reconnect. Re-firing the worker every
        // minute against a dead token just hammers Drive with 401s
        // and bounces between
        // doWork → fail → doWork (e.g. when WorkManager re-dispatches
        // a queued retry from before the AUTH_REJECTED was recorded).
        // Read fresh per tick so the moment the user signs back in
        // and the worker writes "" on first success, the safety net
        // resumes nudging.
        val lastErr = runCatching {
            database.syncStateDao().get(SyncStateKeys.LAST_SYNC_ERROR_CODE)?.value
        }.getOrNull()
        if (lastErr == SyncErrorCodes.AUTH_REJECTED) {
            Log.i(
                "QuickInkSync",
                "pending-push tick: $syncDirty dirty rows — skipping sync " +
                "(last error AUTH_REJECTED; user must re-auth via Settings)"
            )
            return
        }

        Log.i("QuickInkSync", "pending-push tick: $syncDirty dirty rows — checking daily auto-sync")
        QuickInkSyncScheduler.requestAutoSyncIfDue(this, dirtyCount = syncDirty)
    }

    private suspend fun writeLocalDirtyCount(count: Int) {
        runCatching {
            database.syncStateDao().upsert(
                SyncStateEntity(
                    key       = SyncStateKeys.LOCAL_DIRTY_COUNT,
                    value     = count.toString(),
                    updatedAt = IsoClock.nowIso(),
                )
            )
        }.onFailure { Log.w("QuickInkSync", "writeLocalDirtyCount($count) failed: $it") }
    }

    /**
     * Count of active, dirty user-visible items pending sync. Deleted
     * rows are excluded from this display count because they are no
     * longer visible items; [countDirtyRowsForSync] still includes
     * tombstones so remote deletes are pushed.
     *
     * Derived rows (OCR results, tags, join rows) are not counted here
     * because they can over-count one user action. The sync worker still
     * pushes them on every pass; this number drives the Home pending
     * count only.
     *
     * Returns 0 on any read failure — better than erroring out
     * the loop entirely.
     */
    private suspend fun countLocalDirty(userId: String): Int = try {
        database.notepadDao().dirtyRows().count {
            it.userId == userId && it.deletedAt == null
        } +
            database.captureDao().dirtyRows().count {
                it.userId == userId && it.deletedAt == null
            } +
            database.voiceNoteDao().countActiveDirtyForUser(userId)
    } catch (e: Exception) {
        Log.w("QuickInkSync", "countLocalDirty failed: $e")
        0
    }

    /**
     * Count every dirty row type that the Drive sync worker can push.
     * Used only for scheduling gates, not for the Home "N pending"
     * label, which intentionally counts user-visible captures.
     */
    suspend fun countDirtyRowsForSync(userId: String): Int = withContext(Dispatchers.IO) {
        try {
            database.notepadDao().dirtyRows().count { it.userId == userId } +
                database.captureDao().dirtyRows().count { it.userId == userId } +
                database.ocrResultDao().dirtyRows().size +
                database.tagDao().dirtyRows().count { it.userId == userId } +
                database.profileSettingsDao().dirtyRows().count { it.userId == userId } +
                database.folderDao().dirtyRows().count { it.userId == userId } +
                database.captureTagDao().dirtyRows().size +
                database.smartCollectionDao().dirtyRows().count { it.userId == userId } +
                database.voiceNoteDao().dirtyRows().size +
                database.locationDao().dirtyRows().count { it.userId == userId } +
                database.captureLocationDao().dirtyRows().size +
                database.personDao().dirtyRows().count { it.userId == userId } +
                database.capturePersonDao().dirtyRows().size +
                database.storyDao().dirtyRows().count { it.userId == userId } +
                database.storyItemDao().dirtyRows().size +
                database.storyVoiceClipDao().dirtyRows().size
        } catch (e: Exception) {
            Log.w("QuickInkSync", "countDirtyRowsForSync failed: $e")
            0
        }
    }

    /**
     * One-shot move of the historical `<filesDir>/releaf/attachments/`
     * directory (where attachments landed before
     * `AttachmentStorage.appFolderName` was overridden in this app)
     * into the new `<filesDir>/quickink/attachments/` location. Then
     * rewrite the `pdf_uri` / `preview_uri` columns on every capture
     * row that still references the old subpath so the on-disk move
     * stays consistent with the database.
     *
     * Idempotent: if the legacy directory is already absent (fresh
     * install or a previous run completed the move) this returns
     * cheaply. Failures are swallowed — a partial state still
     * eventually self-heals via the sync worker, which re-downloads
     * the bytes from Drive into the new location and rewrites the URI
     * itself.
     */
    private suspend fun migrateLegacyAttachmentsFolder() {
        val oldDir = File(filesDir, "releaf/attachments")
        if (!oldDir.exists()) return

        val newDir = File(filesDir, "quickink/attachments")
        runCatching {
            // Make sure the new parent dir exists so renameTo can
            // succeed (renameTo is atomic but only when the
            // destination's parent already exists on the same
            // filesystem — both paths are under filesDir, so that
            // condition holds).
            newDir.parentFile?.takeIf { !it.exists() }?.mkdirs()

            if (!newDir.exists()) {
                if (!oldDir.renameTo(newDir)) {
                    // Fallback for the rare case where renameTo fails
                    // (e.g. SELinux quirk on a specific OEM): copy
                    // each file in, then delete the old dir.
                    newDir.mkdirs()
                    oldDir.listFiles()?.forEach { src ->
                        val dst = File(newDir, src.name)
                        if (!dst.exists()) {
                            src.copyTo(dst, overwrite = false)
                        }
                    }
                    oldDir.deleteRecursively()
                }
            } else {
                // New dir already exists (e.g. a half-completed earlier
                // run, or app reinstall race). Move any leftover files
                // in, then drop the old dir.
                oldDir.listFiles()?.forEach { src ->
                    val dst = File(newDir, src.name)
                    if (!dst.exists()) src.renameTo(dst)
                }
                oldDir.deleteRecursively()
            }

            // Drop the now-empty `releaf/` parent if we own it; it's
            // an orphan once the attachments subdir is gone.
            File(filesDir, "releaf").takeIf {
                it.exists() && (it.list()?.isEmpty() == true)
            }?.delete()
        }

        // Rewrite DB URIs that still point at the legacy subpath.
        // Touch only matching rows (no `dirty` bump) — these are local
        // paths, and remote sync rewrites the URI on download anyway.
        runCatching {
            database.captureDao().rewriteAttachmentPaths(
                oldFrag = "/releaf/attachments/",
                newFrag = "/quickink/attachments/",
            )
        }
    }

    /**
     * One-shot latch for the "clear legacy auto-scheduled periodic
     * work" cleanup that runs the first time the auth observer sees
     * `SignedIn` in this process. Re-doing the cancel on every
     * `SignedIn` emission was wrong: background silent token refresh
     * calls `authStore.adoptSession(fresh)` after rotating the access
     * token, which makes the `AuthStore` StateFlow emit a NEW
     * `SignedIn` value. Pre-fix, that emission fired
     * `QuickInkSyncScheduler.cancelAll(this)` and killed in-flight
     * work.
     *
     * Latching with a Boolean means the cleanup only runs once per
     * process — enough to retire any leftover periodic schedule from
     * an upgrade-from-periodic-build, no more.
     */
    private var hasClearedLegacyPeriodicWork = false

    /**
     * Tracks the auth state observed on the previous emission so we
     * can detect "user just completed a fresh sign-in" — i.e. a
     * transition into [AuthState.SignedIn] from anything else, OR a
     * SignedIn→SignedIn switch with a different `userId` (account
     * change). Used to clear a stale `AUTH_REJECTED` banner whose
     * underlying token has just been replaced; without this the
     * banner can persist after Sign out + Sign in until the next
     * worker pass succeeds, which is confusing when the user has
     * "already done what the banner asked".
     *
     * `null` on the very first emission of this process so a
     * SignedIn restored from prefs at app start does NOT clear the
     * flag — that flag may be a legitimate auth issue from the
     * previous run that the user still needs to address.
     */
    private var previousAuthState: AuthState? = null

    /**
     * Watch the auth state for sign-in cleanup and sign-out teardown.
     * The worker's per-pass Drive-backup-toggle gate handles the
     * runtime "user flipped the switch off" case — see
     * `QuickInkSyncWorker.doWork`.
     */
    private fun observeAuthForSyncLifecycle() {
        // StateFlow already dedupes by equality — no
        // distinctUntilChanged needed (and the operator is
        // deprecated on StateFlow as a no-op).
        authStore.state
            .onEach { state ->
                val prev = previousAuthState
                previousAuthState = state
                when (state) {
                    is AuthState.SignedIn -> {
                        // Fresh sign-in detector — see `previousAuthState`
                        // doc. Clears any stale AUTH_REJECTED banner so the
                        // user doesn't see "needs re-auth" right after they
                        // re-authed. If Drive is still rejecting (real
                        // Cloud-side issue, not a stale token), the next
                        // worker pass writes AUTH_REJECTED again — same
                        // surface as before, just no longer a phantom
                        // carryover from the previous session.
                        val isFreshSignIn = when (prev) {
                            null                                              -> false
                            is AuthState.SignedOut, is AuthState.SigningIn,
                            is AuthState.Failed                               -> true
                            is AuthState.SignedIn                             ->
                                prev.session.userId != state.session.userId
                        }
                        if (isFreshSignIn) {
                            Log.i("QuickInkSync",
                                "auth: fresh SignedIn detected — clearing any " +
                                "stale AUTH_REJECTED banner")
                            appScope.launch {
                                runCatching {
                                    database.syncStateDao().upsert(
                                        SyncStateEntity(
                                            key       = SyncStateKeys.LAST_SYNC_ERROR_CODE,
                                            value     = "",
                                            updatedAt = IsoClock.nowIso(),
                                        )
                                    )
                                }.onFailure {
                                    Log.w("QuickInkSync",
                                        "auth: clearAuthRejected on fresh sign-in failed: $it")
                                }
                            }
                        }
                        // Sync is now USER-INITIATED ONLY. We
                        // intentionally don't schedule a periodic
                        // worker or kick an immediate sync on
                        // sign-in — Settings → "Sync now" is the
                        // single entry point. Rationale:
                        //
                        //   - Periodic 15-min syncs were burning
                        //     battery + cellular for a feature most
                        //     users only need on demand.
                        //   - Auto-kicks-on-sign-in caused an
                        //     immediate 30-second wait for the
                        //     foreground UI when the user just wants
                        //     to use the app.
                        //
                        // The legacy-periodic cleanup runs ONCE per
                        // process via [hasClearedLegacyPeriodicWork] —
                        // see that field's doc for why re-running on
                        // every `SignedIn` emission was racing the
                        // user's banner / Sync-now taps.
                        if (!hasClearedLegacyPeriodicWork) {
                            hasClearedLegacyPeriodicWork = true
                            android.util.Log.i("QuickInkSync",
                                "auth: SignedIn — clearing any legacy " +
                                "auto-scheduled periodic work (one-shot); " +
                                "sync is user-initiated only.")
                            // Narrowed from `cancelAll(...)` to just the
                            // periodic worker. Leaves ONESHOT_WORK_NAME
                            // and the restore worker alone so a token-
                            // refresh-driven re-emit of `SignedIn` doesn't
                            // nuke an in-flight user-initiated sync.
                            androidx.work.WorkManager.getInstance(this)
                                .cancelUniqueWork(QuickInkSyncWorker.PERIODIC_WORK_NAME)
                        }
                    }
                    is AuthState.SignedOut -> {
                        // ONLY tear down work on a real sign-out.
                        // The earlier `else` branch was a real bug:
                        // it also fired on the transient `SigningIn`
                        // state and `Failed`, cancelling in-flight
                        // sync work mid-execution. Logs showed
                        // workers being cancelled every 3-5 seconds
                        // with `JobCancellationException`, then
                        // immediately retrying — a runaway loop
                        // that produced the "Sync now never stops"
                        // symptom.
                        android.util.Log.i("QuickInkSync",
                            "auth: SignedOut — cancelAll")
                        QuickInkSyncScheduler.cancelAll(this)

                        // Mirror of iOS QuickInkRoot.swift
                        // `handleAuthStateForSettings`: drop every
                        // identity-leaking SettingsPreferences override
                        // so the next account on the same device
                        // doesn't inherit the previous user's display
                        // name / phone / photo / punchline / search
                        // MRU. Theme + Drive-backup + experimental-flag
                        // prefs are device-level and intentionally
                        // preserved.
                        runCatching {
                            SettingsPreferences(this).clearAllUserOverrides()
                        }
                    }
                    is AuthState.SigningIn,
                    is AuthState.Failed -> {
                        // Transient — leave existing work alone.
                        // Sign-in flow may briefly enter these
                        // states; cancelling work here used to kill
                        // perfectly healthy in-flight syncs.
                        android.util.Log.i("QuickInkSync",
                            "auth: $state (transient) — leaving work scheduled")
                    }
                }
            }
            .launchIn(appScope)
    }

    /**
     * Tracks the userId we've already enqueued an `/v1/identify`
     * row for in this process. A transient re-emission of
     * `SignedIn` (token refresh, observer rebind) won't re-enqueue
     * a duplicate identify; an actual account switch will because
     * the userId differs. Reset on `SignedOut` so the next sign-in
     * fires a fresh identify.
     */
    private var lastIdentifiedUserId: String? = null

    /**
     * Mirror of iOS QuickInkRoot.swift `handleAuthStateForAnalytics`.
     * Watches the auth StateFlow and enqueues a `/v1/identify`
     * outbox row per SignedIn → opportunistically flushes so the
     * backend's User row exists before the first capture event
     * arrives (FK constraint on `capture_events.user_uid`).
     *
     * Gated by `BuildConfig.ANALYTICS_ENABLED` — a build with the
     * flag off skips the observer entirely so no rows accumulate
     * in the outbox and no work fires.
     */
    private fun observeAuthForAnalytics() {
        if (!BuildConfig.ANALYTICS_ENABLED) return

        authStore.state
            .onEach { state ->
                when (state) {
                    is AuthState.SignedIn -> {
                        val userId = state.session.userId
                        if (lastIdentifiedUserId == userId) return@onEach
                        lastIdentifiedUserId = userId

                        appScope.launch(Dispatchers.IO) {
                            try {
                                AnalyticsRepository(database.analyticsOutboxDao())
                                    .enqueueIdentify(
                                        deviceOs   = "android",
                                        appVersion = QUICKINK_APP_VERSION,
                                    )
                                AnalyticsFlushWorker.requestImmediate(this@QuickInkApp)
                            } catch (e: Exception) {
                                Log.w(
                                    "QuickInkAnalytics",
                                    "[analytics] enqueueIdentify failed: $e"
                                )
                            }
                        }
                    }
                    is AuthState.SignedOut -> {
                        lastIdentifiedUserId = null
                    }
                    is AuthState.SigningIn,
                    is AuthState.Failed -> {
                        // Transient — leave the latch alone so a
                        // brief flicker through SigningIn doesn't
                        // re-emit identify on the next SignedIn.
                    }
                }
            }
            .launchIn(appScope)
    }
}
