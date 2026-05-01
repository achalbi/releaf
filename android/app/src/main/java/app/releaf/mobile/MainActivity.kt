/*
 * MainActivity.kt
 * Top-level dispatch — sign-in screen or the signed-in shell with a
 * persistent BottomNav and Scaffold-hosted NavHost.
 *
 * Top-level routes (BottomNav visible):
 *   home, notebooks, capture, notepad, settings
 * Drill-in routes (BottomNav hidden):
 *   notebook/{notebookId}, page/{pageId}                       — Drive-fake surfaces (Home tab).
 *   notebook/local/{notebookId}, page/local/{pageId}           — Room-backed surfaces (Notebooks tab).
 *   notepad/edit/{entryId}
 *
 * The two notebook surfaces will reconverge in phase 3 when the sync worker
 * makes the Drive-fake path unnecessary; until then they coexist to avoid
 * breaking the Home cards while the Room stack stabilises.
 *
 * The center "Leaf" tab is now a real top-level destination — tapping the
 * lifted FAB navigates to `capture`. Was a ModalBottomSheet pre-CAPTURE_TAB_PLAN.md.
 */

package app.releaf.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.auth.rememberGoogleSignInAction
import app.releaf.mobile.features.auth.SignInScreen
import app.releaf.mobile.features.calendar.CalendarScreen
import app.releaf.mobile.features.callhistory.CallHistoryScreen
import app.releaf.mobile.features.contacts.ContactsScreen
import app.releaf.mobile.features.home.HomeDrawerContent
import app.releaf.mobile.features.home.HomeScreen
import app.releaf.mobile.features.home.HomeScreenVariant1
import app.releaf.mobile.features.splash.SplashScreen
import app.releaf.mobile.features.notebook.ChapterLocalDetailScreen
import app.releaf.mobile.features.notebook.ChapterLocalDetailViewModel
import app.releaf.mobile.features.notebook.NotebookDetailScreen
import app.releaf.mobile.features.notebook.NotebookDetailScreenVariant1
import app.releaf.mobile.features.notebook.NotebookDetailViewModel
import app.releaf.mobile.features.notebook.NotebookLocalDetailScreen
import app.releaf.mobile.features.notebook.NotebookLocalDetailViewModel
import app.releaf.mobile.features.notebook.NotebookTabScreen
import app.releaf.mobile.features.notebook.PageLocalEditorScreen
import app.releaf.mobile.features.notebook.PageLocalEditorViewModel
import app.releaf.mobile.features.notepad.NotepadEditorScreen
import app.releaf.mobile.features.notepad.NotepadEditorViewModel
import app.releaf.mobile.features.notepad.NotepadScreen
import app.releaf.mobile.features.onboarding.OnboardingCta
import app.releaf.mobile.features.onboarding.OnboardingPreferences
import app.releaf.mobile.features.onboarding.OnboardingWizard
import app.releaf.mobile.features.page.PageDetailScreen
import app.releaf.mobile.features.page.PageDetailScreenVariant1
import app.releaf.mobile.features.page.PageDetailViewModel
import app.releaf.mobile.features.reminder.ReminderEditorScreen
import app.releaf.mobile.features.reminder.ReminderEditorViewModel
import app.releaf.mobile.features.reminder.RemindersScreen
import app.releaf.mobile.features.settings.SettingsScreen
import app.releaf.mobile.features.tasks.TasksScreen
import app.releaf.mobile.features.capture.CaptureScreen
import app.releaf.mobile.features.capture.CaptureTile
import app.releaf.mobile.features.capture.toCaptureMode
import app.releaf.mobile.ui.components.BottomNav
import app.releaf.mobile.ui.components.BottomNavItem
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.theme.NotebookListVariant
import app.releaf.mobile.ui.theme.ReleafCanvas
import app.releaf.mobile.ui.theme.ReleafTheme
import app.releaf.mobile.ui.theme.UiPreferences
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen must be called BEFORE super.onCreate so the
        // system plays the Theme.Releaf.Splash icon animation into this
        // activity's first frame. The `setKeepOnScreenCondition` gate
        // sustains the branded splash for ~600ms so returning users (who
        // skip AuthState.SigningIn entirely) still see the leaf.
        val splash = installSplashScreen()
        val startedAt = System.currentTimeMillis()
        splash.setKeepOnScreenCondition {
            System.currentTimeMillis() - startedAt < SPLASH_MIN_MS
        }

        super.onCreate(savedInstanceState)
        val authStore = (application as ReleafApp).authStore

        setContent {
            ReleafTheme {
                RootScreen(authStore = authStore)
            }
        }
    }

    private companion object {
        const val SPLASH_MIN_MS = 600L
    }
}

private object Routes {
    const val HOME                  = "home"
    const val NOTEBOOKS             = "notebooks"
    const val NOTEPAD               = "notepad"
    const val SETTINGS              = "settings"
    /**
     * Capture is a top-level destination owned by the center "Leaf"
     * slot in the BottomNav. Promoted from a ModalBottomSheet to a
     * real route per docs/CAPTURE_TAB_PLAN.md. BottomNav stays
     * visible (CAPTURE is in [topLevel]).
     */
    const val CAPTURE               = "capture"
    const val NOTEBOOK_DETAIL       = "notebook/{notebookId}"
    const val PAGE                  = "page/{pageId}"
    const val NOTEBOOK_LOCAL_DETAIL = "notebook/local/{notebookId}"
    const val CHAPTER_LOCAL_DETAIL  = "chapter/local/{chapterId}"
    /**
     * `mode` is an optional query arg carrying a [CaptureMode] name — Quick
     * Capture sets it so the page editor lands on the right feature tab.
     */
    const val PAGE_LOCAL            = "page/local/{pageId}?mode={mode}"
    const val ARG_CAPTURE_MODE      = "mode"
    const val NOTEPAD_EDIT          = "notepad/edit/{entryId}?mode={mode}&autoLaunch={autoLaunch}"
    /**
     * Optional `autoLaunch` query carries a [CaptureMode] name. When
     * present, the editor's matching section auto-fires its primary
     * action on first composition (e.g. `autoLaunch=Scans` boots the
     * ML Kit scanner; `autoLaunch=Voice` opens the recording sheet).
     * Set by the Capture page tiles so a tap goes straight from the
     * Capture surface into the chosen capture flow without an
     * intermediate "tap inside the section" step.
     */
    const val ARG_AUTO_LAUNCH       = "autoLaunch"
    const val TASKS                 = "tasks"
    const val REMINDERS             = "reminders"
    const val CONTACTS              = "contacts"
    /** Phase-1 activity feed — full-screen list reachable from the
     *  Home timeline card's "See full timeline" link. */
    const val ACTIVITY              = "activity"
    /** Full-screen panchanga calendar — reachable from the drawer
     *  ("Calendar" leaf) and the QuickCaptureSheet footer. Bottom nav
     *  hidden via absence from `topLevel`. */
    const val CALENDAR              = "calendar"
    const val CALL_HISTORY          = "call-history"
    const val REMINDER_EDIT         = "reminders/edit/{reminderId}"

    fun notebookDetail(id: String)      = "notebook/$id"
    fun page(id: String)                = "page/$id"
    fun notebookLocalDetail(id: String) = "notebook/local/$id"
    fun chapterLocalDetail(id: String)  = "chapter/local/$id"
    /** Pass a non-null [mode] to preselect a CaptureTabBar tab in the editor. */
    fun pageLocal(id: String, mode: CaptureMode? = null): String {
        val base = "page/local/$id"
        return if (mode != null) "$base?mode=${mode.name}" else base
    }
    /** Pass `NotepadEditorViewModel.NEW_ENTRY_ID` to compose a fresh entry. */
    fun notepadEdit(id: String): String = "notepad/edit/$id"
    /** Same target with a [CaptureMode] hint so the editor opens
     *  scrolled to the matching feature section. Used by the Recents
     *  new-entry picker. */
    fun notepadEditWithMode(
        id: String,
        mode: app.releaf.mobile.ui.components.CaptureMode,
        autoLaunch: app.releaf.mobile.ui.components.CaptureMode? = null,
    ): String = buildString {
        append("notepad/edit/$id?mode=${mode.name}")
        if (autoLaunch != null) append("&autoLaunch=${autoLaunch.name}")
    }

    /** Convenience for the Capture page tiles — opens the notepad
     *  editor on today's latest entry, lands on [mode]'s tab, and
     *  auto-fires that section's primary action so the user goes
     *  straight from "tap tile" to "do the thing" (frame document,
     *  start recording, request GPS, …) without an intermediate
     *  tap inside the section. */
    fun notepadEditAutoLaunch(
        id: String,
        mode: app.releaf.mobile.ui.components.CaptureMode,
    ): String = notepadEditWithMode(id = id, mode = mode, autoLaunch = mode)
    /** Pass `ReminderEditorViewModel.NEW_REMINDER_ID` to compose a fresh reminder. */
    fun reminderEdit(id: String)        = "reminders/edit/$id"

    /** Top-level destinations that should show the BottomNav. */
    val topLevel = setOf(HOME, NOTEBOOKS, NOTEPAD, SETTINGS, CAPTURE)

    /** Map a tab id from BottomNavItem to its NavHost route. */
    fun routeForTab(tabId: String): String? = when (tabId) {
        "home"     -> HOME
        "notebook" -> NOTEBOOKS
        "leaf"     -> CAPTURE
        "notepad"  -> NOTEPAD
        "settings" -> SETTINGS
        else       -> null
    }

    /** Inverse: map current route → BottomNav selected id. */
    fun selectedTabForRoute(route: String?): String = when (route) {
        HOME      -> "home"
        NOTEBOOKS -> "notebook"
        CAPTURE   -> "leaf"
        NOTEPAD   -> "notepad"
        SETTINGS  -> "settings"
        else      -> "home"
    }
}

@Composable
private fun RootScreen(authStore: AuthStore) {
    val state by authStore.state.collectAsState()
    // Composable wiring for the real Google Sign-In flow. Falls back
    // to the stub client when `google_web_client_id` is still the
    // default placeholder string (see strings.xml + GoogleSignInBinding).
    val onSignIn = rememberGoogleSignInAction(authStore)

    // Branded splash hold: the system SplashScreen (leaf + wordmark
    // drawable) animates into this activity's first frame; we then keep
    // the full Compose SplashScreen — leaf + wordmark + WRITE. ERASE.
    // REPEAT. tagline + subtitle + animated loading dots — on screen for
    // BRANDED_SPLASH_MS so cold-launches show the full marketing splash
    // before falling through to the auth-state-driven UI.
    var brandedSplashVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(BRANDED_SPLASH_MS)
        brandedSplashVisible = false
    }

    ReleafCanvas {
        when {
            brandedSplashVisible -> SplashScreen()

            else -> when (val s = state) {
                is AuthState.SignedOut, is AuthState.Failed ->
                    SignInScreen(state = s, onSignIn = onSignIn)

                AuthState.SigningIn -> SplashScreen()

                is AuthState.SignedIn ->
                    SignedInShell(session = s.session, onSignOut = authStore::signOut)
            }
        }
    }
}

private const val BRANDED_SPLASH_MS = 2000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInShell(session: GoogleAuthSession, onSignOut: () -> Unit) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in Routes.topLevel

    val context = LocalContext.current
    val releafApp = context.applicationContext as ReleafApp
    val scope = rememberCoroutineScope()

    // First-run onboarding: auto-show when the user hasn't completed
    // (or skipped) it yet. The widget on HomeScreen lets them re-open
    // the wizard later via `showOnboarding = true`.
    val onboardingPrefs = remember { OnboardingPreferences.get(context) }
    val completedAt by onboardingPrefs.completedAt.collectAsState()
    var showOnboarding by remember { mutableStateOf(completedAt == 0L) }

    // Shared drawer state — hoisted here (above Scaffold) so the
    // drawer can slide over the bottom nav, covering the full screen
    // from status bar to home indicator.
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Drawer metrics — live counts from each feature's repository so
    // the drawer's metadata lines reflect real data instead of the
    // earlier placeholder strings.
    val notebookCount by releafApp.notebookRepository.observeActive()
        .map { it.size }
        .collectAsState(initial = 0)
    val shelfCount by releafApp.shelfRepository.observeActive()
        .map { it.size }
        .collectAsState(initial = 0)
    val notepadEntries by releafApp.notepadRepository
        .observeActive(session.userId)
        .collectAsState(initial = emptyList())
    val openTaskCount by releafApp.taskRepository
        .observeOpenCount(session.userId)
        .collectAsState(initial = 0)
    val reminderCount by releafApp.reminderRepository
        .observeActive(session.userId)
        .map { it.size }
        .collectAsState(initial = 0)
    val contactCount by releafApp.contactDirectoryRepository
        .observeAll(session.userId)
        .map { it.size }
        .collectAsState(initial = 0)

    val today               = LocalDate.now().toString()
    val todayNotepadCount   = notepadEntries.count { it.entryDate == today }
    val librarySubtitle     = "$notebookCount books · $shelfCount shelves"
    val notepadEntryLabel   = if (notepadEntries.size == 1) "entry" else "entries"
    val notepadSubtitle     = "${notepadEntries.size} $notepadEntryLabel · $todayNotepadCount today"
    val tasksSubtitle       = "$openTaskCount open"
    val reminderLabel       = if (reminderCount == 1) "reminder" else "reminders"
    val remindersSubtitle   = "$reminderCount $reminderLabel"
    val contactsSubtitle    = "$contactCount in your circle"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Helper closure: every drawer link should close the
            // drawer first and then navigate. Pulled out so each
            // wire-up is a one-line `closeAndGo { … }` instead of
            // duplicating the boilerplate.
            val closeAndGo: (() -> Unit) -> () -> Unit = { dest ->
                {
                    scope.launch { drawerState.close() }
                    dest()
                }
            }
            HomeDrawerContent(
                session           = session,
                librarySubtitle   = librarySubtitle,
                notepadSubtitle   = notepadSubtitle,
                tasksSubtitle     = tasksSubtitle,
                remindersSubtitle = remindersSubtitle,
                contactsSubtitle  = contactsSubtitle,
                onClose           = { scope.launch { drawerState.close() } },
                onOpenTimeline    = closeAndGo { nav.navigate(Routes.ACTIVITY) },
                // Tab destinations use the same popUpTo-HOME / restoreState
                // dance the bottom nav uses so back-stack state is preserved.
                onOpenLibrary     = closeAndGo {
                    nav.navigate(Routes.NOTEBOOKS) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenNotepad     = closeAndGo {
                    nav.navigate(Routes.NOTEPAD) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenSettings    = closeAndGo {
                    nav.navigate(Routes.SETTINGS) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                // Drill-in destinations — plain navigate is fine; back
                // arrow on the destination pops them off.
                onOpenTasks       = closeAndGo { nav.navigate(Routes.TASKS) },
                onOpenReminders   = closeAndGo { nav.navigate(Routes.REMINDERS) },
                onOpenContacts    = closeAndGo { nav.navigate(Routes.CONTACTS) },
                onOpenCalendar    = closeAndGo { nav.navigate(Routes.CALENDAR) },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    onSignOut()
                },
            )
        },
    ) {
        Scaffold(
            // Transparent so the canvas painted by ReleafCanvas shows through.
            containerColor = Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    BottomNav(
                        items = BottomNavItem.defaults,
                        selectedId = Routes.selectedTabForRoute(currentRoute),
                        onSelect = { tabId ->
                            Routes.routeForTab(tabId)?.let { route ->
                                if (route != currentRoute) {
                                    nav.navigate(route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        },
                        onBrandTap = {
                            // Tap the lifted Leaf FAB → navigate to
                            // the Capture top-level destination.
                            // Same popUpTo / restoreState dance the
                            // other tabs use so back-stack state is
                            // preserved when the user flips between
                            // Capture and Home.
                            if (Routes.CAPTURE != currentRoute) {
                                nav.navigate(Routes.CAPTURE) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            SignedInNavHost(
                nav = nav,
                session = session,
                onSignOut = onSignOut,
                onShowOnboarding = { showOnboarding = true },
                onOpenDrawer = { scope.launch { drawerState.open() } },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }

    if (showOnboarding) {
        OnboardingWizard(
            onDismiss = {
                onboardingPrefs.markComplete()
                showOnboarding = false
            },
            onCta = { cta ->
                onboardingPrefs.markComplete()
                when (cta) {
                    OnboardingCta.Notebook -> nav.navigate(Routes.NOTEBOOKS) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    OnboardingCta.Notepad  -> nav.navigate(Routes.NOTEPAD) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
        )
    }

    // The Capture flow used to be a ModalBottomSheet rendered here
    // when `showCapture` was true. Per docs/CAPTURE_TAB_PLAN.md Phase
    // 2, Capture is now a real top-level destination — `onBrandTap`
    // (on the BottomNav above) navigates to `Routes.CAPTURE` instead
    // of toggling a boolean. The sheet host (`QuickCaptureSheet.kt`)
    // is kept for one release cycle for any deep links that still
    // resolve through it; remove it in the next release.
}

@Composable
private fun SignedInNavHost(
    nav: NavHostController,
    session: GoogleAuthSession,
    onSignOut: () -> Unit,
    onShowOnboarding: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        // ---------- Top-level tabs ----------

        composable(Routes.HOME) {
            HomeScreen(
                session = session,
                onOpenNotebook = { id -> nav.navigate(Routes.notebookDetail(id)) },
                onOpenNotebooksTab = {
                    nav.navigate(Routes.NOTEBOOKS) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenNotepadTab = {
                    nav.navigate(Routes.NOTEPAD) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenNotepadEntry = { id -> nav.navigate(Routes.notepadEdit(id)) },
                onOpenTasks      = { nav.navigate(Routes.TASKS) },
                onOpenReminders  = { nav.navigate(Routes.REMINDERS) },
                onOpenContacts   = { nav.navigate(Routes.CONTACTS) },
                onOpenActivityLog = { nav.navigate(Routes.ACTIVITY) },
                onSignOut        = onSignOut,
                onShowOnboarding = onShowOnboarding,
                onOpenDrawer     = onOpenDrawer,
            )
        }
        composable(Routes.NOTEBOOKS) {
            val prefs   = UiPreferences.get(LocalContext.current)
            val uiState by prefs.state.collectAsState()
            when (uiState.notebookVariant) {
                NotebookListVariant.Classic -> NotebookTabScreen(
                    onOpenNotebook = { id -> nav.navigate(Routes.notebookLocalDetail(id)) },
                    onOpenPage     = { id -> nav.navigate(Routes.pageLocal(id)) },
                )
                // Variant-1 replaces the Room-backed notebooks list with
                // the editorial "Your shelves" screen; drill-in uses the
                // drive-fake routes that match the seeded volumes.
                NotebookListVariant.Variant1 -> HomeScreenVariant1(
                    session        = session,
                    // Route to Room-backed surfaces — `ShelvesViewModel`
                    // (which feeds HomeScreenVariant1) emits ids from
                    // the Room `notebookRepository`, so previously
                    // routing through `Routes.notebookDetail(id)`
                    // (Drive-fake) handed those ids to a screen that
                    // had no matching row, leaving the page editor
                    // at the bottom of this flow unreachable.
                    onOpenNotebook = { id -> nav.navigate(Routes.notebookLocalDetail(id)) },
                    onOpenPageTodo = { id ->
                        nav.navigate(Routes.pageLocal(id, CaptureMode.Todo))
                    },
                    onSignOut      = onSignOut,
                )
            }
        }

        // Jump to the Notebooks tab from a breadcrumb. The common case is
        // "I drilled in from the tab and want to pop back" — which is
        // exactly what popBackStack does when the tab is on the stack.
        // For deep links (Home → drill, with no tab in the stack) we
        // fall through to a fresh navigate.
        //
        // The previous implementation used `nav.navigate(NOTEBOOKS) {
        // popUpTo(NOTEBOOKS, inclusive=false) }` — that combo pops the
        // intermediate destinations but the navigate itself becomes a
        // launchSingleTop no-op (NOTEBOOKS is already on the stack
        // after the popUpTo). The result was a silent no-op breadcrumb
        // tap on some back-stack shapes. popBackStack is the
        // unambiguous expression of "rewind to this destination".
        val navigateToNotebooksTab: () -> Unit = {
            if (!nav.popBackStack(Routes.NOTEBOOKS, inclusive = false)) {
                nav.navigate(Routes.NOTEBOOKS) {
                    popUpTo(Routes.HOME) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        val navigateHome: () -> Unit = {
            if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        composable(Routes.NOTEPAD) {
            NotepadScreen(
                session      = session,
                onOpenEntry  = { id -> nav.navigate(Routes.notepadEdit(id)) },
                onComposeNew = { nav.navigate(Routes.notepadEdit(NotepadEditorViewModel.NEW_ENTRY_ID)) },
                // Recents new-entry picker → editor scrolled to the
                // matching feature section. The query-string variant
                // of the route carries the mode through.
                onOpenEntryWithMode = { id, mode ->
                    nav.navigate(Routes.notepadEditWithMode(id, mode))
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onSignOut = onSignOut)
        }

        // Capture is a top-level destination owned by the center
        // "Leaf" slot in BottomNav. Tapping a tile creates a fresh
        // page in the user's quick-capture chapter and navigates to
        // the page editor preset to the matching CaptureMode tab —
        // same flow that lived inside the old QuickCaptureSheet.
        composable(Routes.CAPTURE) {
            val captureScope = rememberCoroutineScope()
            val app = LocalContext.current.applicationContext as ReleafApp

            // Shared launcher: open today's latest notepad entry (or
            // create one when the day is empty) and land on the
            // editor preset to the given mode, auto-firing that
            // section's primary action (scan, record, GPS, …) when
            // [autoLaunch] is true. Used by every Capture-page tile
            // and the Scan-hero card.
            //
            // Quick Capture lands on the Notepad's daily page rather
            // than the Notebook quick-capture chapter so taps from the
            // center FAB always file into the same per-day surface the
            // user already sees on the Notepad tab. "Latest page" =
            // most-recently-updated entry filed under today.
            val launchCapture: (mode: CaptureMode?, autoLaunch: Boolean) -> Unit =
                { mode, autoLaunch ->
                    captureScope.launch {
                        val today = IsoClock.todayLocalDate()
                        val targetId = (
                            app.notepadRepository.findLatestForDate(session.userId, today)
                                ?: app.notepadRepository.create(
                                    userId    = session.userId,
                                    title     = null,
                                    notes     = "",
                                    entryDate = today,
                                )
                        ).id
                        val route = when {
                            mode != null && autoLaunch ->
                                Routes.notepadEditAutoLaunch(targetId, mode)
                            mode != null ->
                                Routes.notepadEditWithMode(targetId, mode)
                            else ->
                                Routes.notepadEdit(targetId)
                        }
                        nav.navigate(route)
                    }
                }

            CaptureScreen(
                // Every tile is a one-tap entry into its section's
                // primary action — Notes opens the writing sheet,
                // Photo opens the camera/gallery chooser, Voice
                // starts the recorder, Todo focuses the input,
                // Contact opens the add-contact sheet, Pin fires
                // GPS capture. Auto-launch is opt-in per call so
                // other entry points to this editor (e.g. Recents)
                // can keep landing on the section without firing
                // its action.
                onSelectTile  = { tile ->
                    launchCapture(tile.toCaptureMode(), /* autoLaunch = */ true)
                },
                // The Scan hero is the same flow as a Scans tile —
                // editor opens on the Scans tab and the ML Kit
                // scanner fires immediately.
                onScanNow     = { launchCapture(CaptureMode.Scans, true) },
                // Header calendar icon → existing panchanga calendar
                // route. Search is stubbed (no SearchScreen yet — see
                // CAPTURE_TAB_PLAN.md open question #1); falls through
                // to a no-op for now.
                onOpenSearch   = { /* TODO: route to releaf://search */ },
                onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                // Pretag chip selection is wired in a follow-up once
                // tagRepository exists — see CaptureScreen.kt header.
            )
        }

        // Tasks is a drill-in surface (not a top-level tab), so it's
        // absent from BottomNav. Entry lives on the Home screen's
        // Tasks card; the back arrow / breadcrumb returns there.
        composable(Routes.TASKS) {
            TasksScreen(onBack = { nav.popBackStack() })
        }

        // Activity log — drill-in from the Home timeline card's
        // "See full timeline" link. Bottom nav stays hidden via the
        // route's absence from `topLevel`.
        composable(Routes.ACTIVITY) {
            app.releaf.mobile.features.activity.ActivityScreen(
                onBack = { nav.popBackStack() },
            )
        }

        // Calendar — drill-in surface backed by the Vontikoppal
        // panchanga dataset. Reachable from the home drawer's
        // "Calendar" leaf and the QuickCaptureSheet's "Open full
        // calendar" footer link.
        composable(Routes.CALENDAR) {
            CalendarScreen(onBack = { nav.popBackStack() })
        }

        // Contacts — drill-in surface from the Home Contacts card.
        // No bottom-nav slot; back arrow returns to Home.
        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack        = { nav.popBackStack() },
                onOpenHistory = { nav.navigate(Routes.CALL_HISTORY) },
            )
        }

        // Call history — list of outbound calls placed from the
        // app, with duration captured via TelephonyCallback. Entry
        // is the phone icon on the Contacts screen header.
        composable(Routes.CALL_HISTORY) {
            CallHistoryScreen(onBack = { nav.popBackStack() })
        }

        // Reminders — same shape as Tasks. List view + per-reminder
        // editor route. Editor carries `reminderId` which the VM
        // factory reads from the SavedStateHandle.
        composable(Routes.REMINDERS) {
            RemindersScreen(
                onBack         = { nav.popBackStack() },
                onComposeNew   = { nav.navigate(Routes.reminderEdit(ReminderEditorViewModel.NEW_REMINDER_ID)) },
                onOpenReminder = { id -> nav.navigate(Routes.reminderEdit(id)) },
            )
        }
        composable(
            route     = Routes.REMINDER_EDIT,
            arguments = listOf(navArgument(ReminderEditorViewModel.ARG_REMINDER_ID) {
                type = NavType.StringType
            }),
        ) {
            ReminderEditorScreen(onBack = { nav.popBackStack() })
        }

        // ---------- Drill-in routes (bottom nav hidden) ----------

        composable(
            route = Routes.NOTEBOOK_DETAIL,
            arguments = listOf(navArgument(NotebookDetailViewModel.ARG_NOTEBOOK_ID) {
                type = NavType.StringType
            }),
        ) {
            val prefs   = UiPreferences.get(LocalContext.current)
            val uiState by prefs.state.collectAsState()
            when (uiState.notebookVariant) {
                NotebookListVariant.Classic -> NotebookDetailScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPage = { id -> nav.navigate(Routes.page(id)) },
                )
                NotebookListVariant.Variant1 -> NotebookDetailScreenVariant1(
                    onBack = { nav.popBackStack() },
                    onOpenPage = { id -> nav.navigate(Routes.page(id)) },
                )
            }
        }
        composable(
            route = Routes.PAGE,
            arguments = listOf(navArgument(PageDetailViewModel.ARG_PAGE_ID) {
                type = NavType.StringType
            }),
        ) {
            val prefs   = UiPreferences.get(LocalContext.current)
            val uiState by prefs.state.collectAsState()
            when (uiState.notebookVariant) {
                NotebookListVariant.Classic -> PageDetailScreen(onBack = { nav.popBackStack() })
                NotebookListVariant.Variant1 -> PageDetailScreenVariant1(onBack = { nav.popBackStack() })
            }
        }
        composable(
            route = Routes.NOTEPAD_EDIT,
            arguments = listOf(
                navArgument(NotepadEditorViewModel.ARG_ENTRY_ID) {
                    type = NavType.StringType
                },
                // Optional `mode` query param — drives the deep-link
                // from the Recents new-entry picker so the editor
                // opens scrolled to the matching feature section.
                navArgument("mode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                // Optional `autoLaunch` query carries a CaptureMode
                // name — set by the Capture page tiles so the
                // editor's matching section auto-fires its primary
                // action on first composition (scan, record, GPS,
                // open contact sheet, focus todo input, open notes
                // sheet). Skips the "tap inside the section" step.
                navArgument(Routes.ARG_AUTO_LAUNCH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val modeName = backStackEntry.arguments?.getString("mode")
            val initialMode = modeName?.let { name ->
                runCatching {
                    app.releaf.mobile.ui.components.CaptureMode.valueOf(name)
                }.getOrNull()
            }
            val autoLaunch = backStackEntry.arguments
                ?.getString(Routes.ARG_AUTO_LAUNCH)
                ?.let { name ->
                    runCatching {
                        app.releaf.mobile.ui.components.CaptureMode.valueOf(name)
                    }.getOrNull()
                }
            NotepadEditorScreen(
                onBack = { nav.popBackStack() },
                initialMode = initialMode,
                autoLaunch = autoLaunch,
            )
        }

        // Room-backed notebook surfaces (Notebooks tab).
        composable(
            route = Routes.NOTEBOOK_LOCAL_DETAIL,
            arguments = listOf(navArgument(NotebookLocalDetailViewModel.ARG_NOTEBOOK_ID) {
                type = NavType.StringType
            }),
        ) {
            NotebookLocalDetailScreen(
                onBack        = navigateToNotebooksTab,
                onHome        = navigateHome,
                onOpenChapter = { id -> nav.navigate(Routes.chapterLocalDetail(id)) },
                onOpenPage    = { id -> nav.navigate(Routes.pageLocal(id)) },
            )
        }
        composable(
            route = Routes.CHAPTER_LOCAL_DETAIL,
            arguments = listOf(navArgument(ChapterLocalDetailViewModel.ARG_CHAPTER_ID) {
                type = NavType.StringType
            }),
        ) { backStackEntry ->
            ChapterLocalDetailScreen(
                onBack = navigateToNotebooksTab,
                onHome = navigateHome,
                onOpenNotebook = { nav.popBackStack() },
                onOpenPage = { id -> nav.navigate(Routes.pageLocal(id)) },
            )
        }
        composable(
            route = Routes.PAGE_LOCAL,
            arguments = listOf(
                navArgument(PageLocalEditorViewModel.ARG_PAGE_ID) {
                    type = NavType.StringType
                },
                navArgument(Routes.ARG_CAPTURE_MODE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val modeName = backStackEntry.arguments?.getString(Routes.ARG_CAPTURE_MODE)
            val initialMode = modeName?.let {
                runCatching { CaptureMode.valueOf(it) }.getOrNull()
            }
            // Hero card chrome on the page editor follows the same
            // NotebookListVariant preference the rest of the notebook
            // surfaces use — Variant1 swaps the breadcrumb top bar
            // for a coloured band; Classic keeps the breadcrumb.
            val pagePrefs = UiPreferences.get(LocalContext.current)
            val pageUiState by pagePrefs.state.collectAsState()
            PageLocalEditorScreen(
                onBack = { nav.popBackStack() },
                onHome = navigateHome,
                onNotebooksTab = navigateToNotebooksTab,
                onOpenNotebook = { id ->
                    nav.navigate(Routes.notebookLocalDetail(id)) {
                        popUpTo(Routes.NOTEBOOK_LOCAL_DETAIL) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                initialCaptureMode = initialMode,
                useHeroHeader = pageUiState.notebookVariant == NotebookListVariant.Variant1,
            )
        }
    }
}
