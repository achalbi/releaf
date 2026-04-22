/*
 * ReleafApp.kt
 *
 * Application-level singleton. Holds process-scoped dependencies
 * (AuthStore, DriveRepository, local DB, sync stack) so the UI can grab
 * them from context.
 *
 * Sync wiring (phase 3): on first onCreate the app builds a
 * [SyncRepository] and installs the periodic worker whenever the
 * [AuthStore] flips into a signed-in state. Sign-out cancels the jobs
 * so we don't carry previous-user work across accounts. The live-session
 * observer runs on the Main dispatcher via a process-scoped
 * [CoroutineScope].
 */

package app.releaf.mobile

import android.app.Application
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.db.ReleafDatabase
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveRepository
import app.releaf.mobile.data.drive.FakeDriveRepository
import app.releaf.mobile.data.drive.InMemoryDriveClient
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notepad.NotepadRepository
import app.releaf.mobile.data.sync.SyncRepository
import app.releaf.mobile.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ReleafApp : Application() {
    lateinit var authStore: AuthStore
        private set

    lateinit var driveRepository: DriveRepository
        private set

    lateinit var database: ReleafDatabase
        private set

    lateinit var notepadRepository: NotepadRepository
        private set

    lateinit var notebookRepository: NotebookRepository
        private set

    lateinit var chapterRepository: ChapterRepository
        private set

    lateinit var pageRepository: PageRepository
        private set

    /**
     * Façade over Google Drive. [InMemoryDriveClient] lets the skeleton
     * run end-to-end without hitting the network; swap in a real
     * Drive v3 client when the auth flow is real.
     */
    lateinit var driveClient: DriveClient
        private set

    /**
     * Push-only sync orchestrator. Consumed by [SyncWorker] — wire-up
     * goes through here so the worker has a single address for its
     * dependencies.
     */
    lateinit var syncRepository: SyncRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        authStore = AuthStore.get(this)
        driveRepository = FakeDriveRepository()
        database = ReleafDatabase.get(this)
        notepadRepository = NotepadRepository(database.notepadDao())
        notebookRepository = NotebookRepository(
            notebookDao = database.notebookDao(),
            chapterDao  = database.chapterDao(),
            pageDao     = database.pageDao(),
        )
        chapterRepository = ChapterRepository(
            chapterDao = database.chapterDao(),
            pageDao    = database.pageDao(),
        )
        pageRepository = PageRepository(database.pageDao())

        driveClient = InMemoryDriveClient()
        syncRepository = SyncRepository(
            notepadDao   = database.notepadDao(),
            notebookDao  = database.notebookDao(),
            chapterDao   = database.chapterDao(),
            pageDao      = database.pageDao(),
            syncStateDao = database.syncStateDao(),
            driveClient  = driveClient,
        )

        observeAuthForSyncLifecycle()
    }

    /**
     * Watch the auth state and install/cancel the periodic sync worker
     * accordingly. [distinctUntilChanged] keeps us from re-enqueueing on
     * every in-flight state echo (e.g. SigningIn → SignedIn → session
     * restored); WorkManager itself de-dupes via the unique-work name
     * but this keeps the logs cleaner.
     */
    private fun observeAuthForSyncLifecycle() {
        authStore.state
            .distinctUntilChanged { old, new ->
                // Consider transitions equal when they're both SignedIn (we
                // don't re-schedule on every token refresh) or both non-
                // SignedIn (no need to re-cancel).
                (old is AuthState.SignedIn && new is AuthState.SignedIn) ||
                (old !is AuthState.SignedIn && new !is AuthState.SignedIn)
            }
            .onEach { state ->
                when (state) {
                    is AuthState.SignedIn -> SyncScheduler.schedulePeriodic(this)
                    else                  -> SyncScheduler.cancelAll(this)
                }
            }
            .launchIn(appScope)
    }
}
