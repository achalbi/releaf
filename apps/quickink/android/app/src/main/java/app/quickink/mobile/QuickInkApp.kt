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
 *                                schedules periodic on SignedIn,
 *                                cancels otherwise)
 *
 * Mirror of the wiring already present in `ReleafApp.kt`.
 */

package app.quickink.mobile

import android.app.Application
import app.quickink.mobile.data.db.QuickInkDatabase
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.InMemoryDriveClient
import app.releaf.mobile.data.drive.OkHttpDriveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

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
        authStore = AuthStore.get(this)

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
        val webClientId = getString(R.string.google_web_client_id)
        driveClient = if (webClientId == "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID") {
            InMemoryDriveClient()
        } else {
            OkHttpDriveClient()
        }

        observeAuthForSyncLifecycle()
    }

    /**
     * Watch the auth state and install/cancel the periodic sync
     * worker accordingly. [distinctUntilChanged] keeps us from
     * re-enqueueing on every in-flight state echo (e.g. SigningIn
     * → SignedIn → session restored); WorkManager itself de-dupes
     * via the unique-work name but this keeps logs cleaner. The
     * worker's per-pass Drive-backup-toggle gate handles the
     * runtime "user flipped the switch off" case — see
     * `QuickInkSyncWorker.doWork`.
     */
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

    private fun observeAuthForSyncLifecycle() {
        authStore.state
            .distinctUntilChanged { old, new ->
                // Equal when both are SignedIn (don't reschedule on
                // token refresh) or both non-SignedIn (no need to
                // re-cancel).
                (old is AuthState.SignedIn && new is AuthState.SignedIn) ||
                (old !is AuthState.SignedIn && new !is AuthState.SignedIn)
            }
            .onEach { state ->
                when (state) {
                    is AuthState.SignedIn -> {
                        // Install the recurring 15-minute job AND
                        // kick an immediate one-shot pass so the
                        // user's first sync lands in seconds, not
                        // 15 minutes after sign-in. iOS does the
                        // same thing in
                        // `QuickInkSyncEnvironment.install`'s auth
                        // observer (`scheduleBackgroundRefresh()` +
                        // `requestImmediate()`); Android was missing
                        // the immediate kick, so a fresh sign-in
                        // looked like sync was broken until the next
                        // periodic tick (or the user manually tapped
                        // "Sync now" in Settings). The worker's
                        // per-pass Drive-backup gate still applies —
                        // if the user has the toggle off, this
                        // one-shot no-ops cleanly.
                        QuickInkSyncScheduler.schedulePeriodic(this)
                        QuickInkSyncScheduler.requestImmediate(this)
                    }
                    else -> QuickInkSyncScheduler.cancelAll(this)
                }
            }
            .launchIn(appScope)
    }
}
