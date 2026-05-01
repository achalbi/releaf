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
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.InMemoryDriveClient
import app.releaf.mobile.data.drive.OkHttpDriveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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
        database  = QuickInkDatabase.get(this)
        authStore = AuthStore.get(this)

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
                    is AuthState.SignedIn -> QuickInkSyncScheduler.schedulePeriodic(this)
                    else                  -> QuickInkSyncScheduler.cancelAll(this)
                }
            }
            .launchIn(appScope)
    }
}
