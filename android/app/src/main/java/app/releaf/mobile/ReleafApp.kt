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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.db.ReleafDatabase
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveRepository
import app.releaf.mobile.data.drive.FakeDriveRepository
import app.releaf.mobile.data.drive.InMemoryDriveClient
import app.releaf.mobile.data.drive.OkHttpDriveClient
import app.releaf.mobile.data.contact.ContactDirectoryRepository
import app.releaf.mobile.data.contact.DeviceContactsProvider
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.shelf.ShelfRepository
import app.releaf.mobile.data.notepad.NotepadRepository
import app.releaf.mobile.data.perspective.PerspectiveRepository
import app.releaf.mobile.data.reminder.ReminderAlarmReceiver
import app.releaf.mobile.data.reminder.ReminderRepository
import app.releaf.mobile.data.sync.SyncRepository
import app.releaf.mobile.data.sync.SyncScheduler
import app.releaf.mobile.data.task.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** App version string stamped into the Drive manifest. Informational
 *  only per `docs/DRIVE_SCHEMA.md` §"Field reference"; not used for
 *  compatibility gating. Keep roughly in sync with versionName in
 *  app/build.gradle.kts. */
private const val APP_VERSION = "0.1.0"

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

    lateinit var shelfRepository: ShelfRepository
        private set

    lateinit var contactDirectoryRepository: ContactDirectoryRepository
        private set

    lateinit var deviceContactsProvider: DeviceContactsProvider
        private set

    lateinit var taskRepository: TaskRepository
        private set

    lateinit var perspectiveRepository: PerspectiveRepository
        private set

    lateinit var reminderRepository: ReminderRepository
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
            notebookDao   = database.notebookDao(),
            chapterDao    = database.chapterDao(),
            pageDao       = database.pageDao(),
            bookSeriesDao = database.bookSeriesDao(),
        )
        chapterRepository = ChapterRepository(
            chapterDao = database.chapterDao(),
            pageDao    = database.pageDao(),
        )
        pageRepository = PageRepository(database.pageDao())
        shelfRepository = ShelfRepository(database.shelfDao())
        contactDirectoryRepository = ContactDirectoryRepository(
            notepadDao = database.notepadDao(),
            pageDao    = database.pageDao(),
        )
        deviceContactsProvider = DeviceContactsProvider(context = this)
        appScope.launch {
            // Fresh installs land here before the migration seed has
            // anything to backfill; ensure the General shelf always
            // exists so the book-creation flow has a landing target.
            shelfRepository.ensureDefaultShelf()
        }
        taskRepository = TaskRepository(database.taskDao())
        perspectiveRepository = PerspectiveRepository(database.perspectiveDao())
        reminderRepository = ReminderRepository(
            context = this,
            dao     = database.reminderDao(),
        )

        createReminderNotificationChannel()
        appScope.launch {
            // Defensive reschedule on every process start — covers the
            // edge case where a reminder was created right before the
            // process died and the alarm never made it into the OS
            // queue. BootReceiver handles the post-reboot case.
            reminderRepository.rescheduleAll()
        }

        // Pick the Drive client at runtime: real REST when the OAuth
        // Web Client ID has been populated, in-memory stub otherwise.
        // Keeps preview / unconfigured dev builds working without
        // hitting the network, and flips automatically to real sync
        // once strings.xml is filled in.
        val webClientId = getString(R.string.google_web_client_id)
        driveClient = if (webClientId == "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID") {
            InMemoryDriveClient()
        } else {
            OkHttpDriveClient()
        }
        syncRepository = SyncRepository(
            notepadDao   = database.notepadDao(),
            notebookDao  = database.notebookDao(),
            chapterDao   = database.chapterDao(),
            pageDao      = database.pageDao(),
            taskDao      = database.taskDao(),
            syncStateDao = database.syncStateDao(),
            driveClient  = driveClient,
            appVersion   = APP_VERSION,
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
    /**
     * One-time registration of the `releaf.reminders` notification
     * channel. Must run before any reminder alarm fires, otherwise
     * `notify()` silently no-ops on API 26+. Creating an existing
     * channel is a no-op, so calling this on every launch is fine.
     */
    private fun createReminderNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ReminderAlarmReceiver.CHANNEL_ID,
            getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.reminders_channel_description)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

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
