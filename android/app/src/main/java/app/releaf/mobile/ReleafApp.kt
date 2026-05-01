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
import app.releaf.mobile.data.drive.LocalDriveRepository
import app.releaf.mobile.data.drive.OkHttpDriveClient
import app.releaf.mobile.data.callhistory.CallHistoryRepository
import app.releaf.mobile.data.callhistory.CallObserver
import app.releaf.mobile.data.contact.ContactDirectoryRepository
import app.releaf.mobile.data.contact.DeviceContactsProvider
import app.releaf.mobile.data.notebook.ChapterRepository
import app.releaf.mobile.data.notebook.NotebookRepository
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.shelf.ShelfRepository
import app.releaf.mobile.data.notepad.NotepadRepository
import app.releaf.mobile.data.panchanga.PanchangaRepository
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

    lateinit var callHistoryRepository: CallHistoryRepository
        private set

    lateinit var callObserver: CallObserver
        private set

    lateinit var taskRepository: TaskRepository
        private set

    lateinit var perspectiveRepository: PerspectiveRepository
        private set

    lateinit var reminderRepository: ReminderRepository
        private set

    /**
     * Bundled Vontikoppal / Mysore Panchanga dataset (Sri Parabhava
     * year, 2026-03-19 → 2027-04-06). Parsed once on first launch
     * from `assets/panchanga_2026_27.csv` and cached in Room; the
     * full-screen calendar (`features/calendar`) reads from this
     * repo. Source-of-truth dataset is community / OCR-derived;
     * see `PanchangaEntity` for the upstream link.
     */
    lateinit var panchangaRepository: PanchangaRepository
        private set

    /**
     * Phase-1 activity feed. Reads `updated_at` from existing tables;
     * no schema, no decorators. Phase 2 will swap the source for an
     * `audit_events` table without breaking call sites.
     */
    lateinit var recentActivityRepository: app.releaf.mobile.data.activity.RecentActivityRepository
        private set

    /**
     * Phase-3.5 sub-event capture. Editor VMs (notepad / page) call
     * this directly when the user adds a single piece of content
     * (photo, voice note, todo, contact, etc.) so the timeline can
     * render granular rows instead of rolling everything up into an
     * entity-level "Updated".
     */
    lateinit var auditLogger: app.releaf.mobile.data.activity.AuditLogger
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
        // Plant catalogue is parsed once from `assets/plants.json`
        // (symlinked back to the canonical `design-system/plants.json`).
        // Init here so every subsequent `DailyPlants.all` /
        // `DailyPlants.forToday(...)` access — including from the
        // notepad seeding flow that fires the moment the user opens
        // a fresh entry — hits a populated cache.
        app.releaf.mobile.ui.theme.DailyPlants.initialize(this)
        authStore = AuthStore.get(this)
        // Database has to be ready before LocalDriveRepository can
        // open its DAOs — flip the init order so the repo lands on
        // a live instance.
        database = ReleafDatabase.get(this)
        driveRepository = LocalDriveRepository(database)
        // Audit logger is constructed BEFORE the four repos that
        // consume it so we can pass it directly into their
        // constructors. Phase-2 of the activity tracker — every
        // mutation through the consuming repos appends one row.
        // Exposed publicly so editor VMs can also log the
        // sub-event captures (phase 3.5).
        auditLogger = app.releaf.mobile.data.activity.AuditLogger(
            dao       = database.auditDao(),
            authStore = authStore,
        )
        notepadRepository = NotepadRepository(
            dao          = database.notepadDao(),
            auditLogger  = auditLogger,
        )
        notebookRepository = NotebookRepository(
            notebookDao   = database.notebookDao(),
            chapterDao    = database.chapterDao(),
            pageDao       = database.pageDao(),
            bookSeriesDao = database.bookSeriesDao(),
            auditLogger   = auditLogger,
        )
        chapterRepository = ChapterRepository(
            chapterDao  = database.chapterDao(),
            pageDao     = database.pageDao(),
            auditLogger = auditLogger,
        )
        pageRepository = PageRepository(
            pageDao     = database.pageDao(),
            auditLogger = auditLogger,
        )
        shelfRepository = ShelfRepository(database.shelfDao())
        contactDirectoryRepository = ContactDirectoryRepository(
            notepadDao = database.notepadDao(),
            pageDao    = database.pageDao(),
        )
        deviceContactsProvider = DeviceContactsProvider(context = this)
        callHistoryRepository = CallHistoryRepository(dao = database.callHistoryDao())
        callObserver = CallObserver(context = this, repository = callHistoryRepository)
        appScope.launch {
            // Fresh installs land here before the migration seed has
            // anything to backfill; ensure the General shelf always
            // exists so the book-creation flow has a landing target.
            shelfRepository.ensureDefaultShelf()
        }
        taskRepository = TaskRepository(database.taskDao())
        perspectiveRepository = PerspectiveRepository(database.perspectiveDao())
        recentActivityRepository = app.releaf.mobile.data.activity.RecentActivityRepository(
            auditDao    = database.auditDao(),
            notepadDao  = database.notepadDao(),
            pageDao     = database.pageDao(),
            chapterDao  = database.chapterDao(),
            notebookDao = database.notebookDao(),
        )
        reminderRepository = ReminderRepository(
            context = this,
            dao     = database.reminderDao(),
        )
        panchangaRepository = PanchangaRepository(
            context = this,
            dao     = database.panchangaDao(),
        )
        appScope.launch {
            // First-launch bootstrap: parse the bundled CSV into Room
            // if the table is empty. Subsequent launches no-op on the
            // count check inside `ensureLoaded`.
            panchangaRepository.ensureLoaded()
        }

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
                    is AuthState.SignedIn -> {
                        SyncScheduler.schedulePeriodic(this)
                        // Phase-3 daily prune — honors the user's
                        // `ActivityRetention` setting, skipped when
                        // it's Forever.
                        app.releaf.mobile.data.activity.AuditScheduler
                            .schedulePeriodic(this)
                        // Phase-2 audit log seeds itself once per
                        // user — idempotent, so re-runs across
                        // process restarts are no-ops once the log
                        // has any rows.
                        appScope.launch {
                            recentActivityRepository.backfillIfEmpty(state.session.userId)
                        }
                    }
                    else -> {
                        SyncScheduler.cancelAll(this)
                        app.releaf.mobile.data.activity.AuditScheduler.cancelAll(this)
                    }
                }
            }
            .launchIn(appScope)
    }
}
