/*
 * MainActivity.kt
 * Top-level dispatch — sign-in screen or the signed-in shell with a
 * persistent BottomNav and Scaffold-hosted NavHost.
 *
 * Top-level routes (BottomNav visible):
 *   home, notebooks, notepad, settings
 * Drill-in routes (BottomNav hidden):
 *   notebook/{notebookId}, page/{pageId}                       — Drive-fake surfaces (Home tab).
 *   notebook/local/{notebookId}, page/local/{pageId}           — Room-backed surfaces (Notebooks tab).
 *   notepad/edit/{entryId}
 *
 * The two notebook surfaces will reconverge in phase 3 when the sync worker
 * makes the Drive-fake path unnecessary; until then they coexist to avoid
 * breaking the Home cards while the Room stack stabilises.
 *
 * The center "Leaf" tab opens QuickCaptureSheet rather than navigating.
 */

package app.releaf.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.features.auth.SignInScreen
import app.releaf.mobile.features.home.HomeScreen
import app.releaf.mobile.features.splash.SplashScreen
import app.releaf.mobile.features.notebook.ChapterLocalDetailScreen
import app.releaf.mobile.features.notebook.ChapterLocalDetailViewModel
import app.releaf.mobile.features.notebook.NotebookDetailScreen
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
import app.releaf.mobile.features.page.PageDetailViewModel
import app.releaf.mobile.features.settings.SettingsScreen
import app.releaf.mobile.ui.components.BottomNav
import app.releaf.mobile.ui.components.BottomNavItem
import app.releaf.mobile.ui.components.CaptureMode
import app.releaf.mobile.ui.components.QuickCaptureSheet
import app.releaf.mobile.ui.theme.ReleafCanvas
import app.releaf.mobile.ui.theme.ReleafTheme
import kotlinx.coroutines.launch

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
    const val NOTEPAD_EDIT          = "notepad/edit/{entryId}"

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
    fun notepadEdit(id: String)         = "notepad/edit/$id"

    /** Top-level destinations that should show the BottomNav. */
    val topLevel = setOf(HOME, NOTEBOOKS, NOTEPAD, SETTINGS)

    /** Map a tab id from BottomNavItem to its NavHost route. */
    fun routeForTab(tabId: String): String? = when (tabId) {
        "home"     -> HOME
        "notebook" -> NOTEBOOKS
        "notepad"  -> NOTEPAD
        "settings" -> SETTINGS
        else       -> null
    }

    /** Inverse: map current route → BottomNav selected id. */
    fun selectedTabForRoute(route: String?): String = when (route) {
        HOME      -> "home"
        NOTEBOOKS -> "notebook"
        NOTEPAD   -> "notepad"
        SETTINGS  -> "settings"
        else      -> "home"
    }
}

@Composable
private fun RootScreen(authStore: AuthStore) {
    val state by authStore.state.collectAsState()

    ReleafCanvas {
        when (val s = state) {
            is AuthState.SignedOut, is AuthState.Failed ->
                SignInScreen(state = s, onSignIn = authStore::signIn)

            AuthState.SigningIn -> SplashScreen()

            is AuthState.SignedIn ->
                SignedInShell(session = s.session, onSignOut = authStore::signOut)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInShell(session: GoogleAuthSession, onSignOut: () -> Unit) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in Routes.topLevel
    var showCapture by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val releafApp = context.applicationContext as ReleafApp
    val scope = rememberCoroutineScope()

    // First-run onboarding: auto-show when the user hasn't completed
    // (or skipped) it yet. The widget on HomeScreen lets them re-open
    // the wizard later via `showOnboarding = true`.
    val onboardingPrefs = remember { OnboardingPreferences.get(context) }
    val completedAt by onboardingPrefs.completedAt.collectAsState()
    var showOnboarding by remember { mutableStateOf(completedAt == 0L) }

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
                    onBrandTap = { showCapture = true },
                )
            }
        },
    ) { innerPadding ->
        SignedInNavHost(
            nav = nav,
            session = session,
            onSignOut = onSignOut,
            onShowOnboarding = { showOnboarding = true },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
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

    if (showCapture) {
        QuickCaptureSheet(
            onDismiss = { showCapture = false },
            onSelect = { mode ->
                scope.launch {
                    val chapterId = releafApp.notebookRepository
                        .resolveQuickCaptureChapter()
                    val page = releafApp.pageRepository.createPage(
                        chapterId = chapterId,
                        title     = null,
                        notes     = "",
                    )
                    nav.navigate(Routes.pageLocal(page.id, mode))
                }
            },
        )
    }
}

@Composable
private fun SignedInNavHost(
    nav: NavHostController,
    session: GoogleAuthSession,
    onSignOut: () -> Unit,
    onShowOnboarding: () -> Unit,
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
                onSignOut = onSignOut,
                onShowOnboarding = onShowOnboarding,
            )
        }
        composable(Routes.NOTEBOOKS) {
            NotebookTabScreen(
                onOpenNotebook = { id -> nav.navigate(Routes.notebookLocalDetail(id)) },
                onOpenPage     = { id -> nav.navigate(Routes.pageLocal(id)) },
            )
        }

        // Jump to the Notebooks tab from a breadcrumb without re-adding it to
        // the back stack. popUpTo(NOTEBOOKS) handles the case where the user
        // entered the drill from the tab directly; the singleTop flag covers
        // deep-link cases (Home → drill) where the tab isn't yet on the stack.
        val navigateToNotebooksTab: () -> Unit = {
            nav.navigate(Routes.NOTEBOOKS) {
                popUpTo(Routes.NOTEBOOKS) { inclusive = false; saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        val navigateHome: () -> Unit = {
            nav.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false; saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        composable(Routes.NOTEPAD) {
            NotepadScreen(
                onOpenEntry  = { id -> nav.navigate(Routes.notepadEdit(id)) },
                onComposeNew = { nav.navigate(Routes.notepadEdit(NotepadEditorViewModel.NEW_ENTRY_ID)) },
                onOpenSettings = {
                    nav.navigate(Routes.SETTINGS) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSignOut = onSignOut,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onSignOut = onSignOut)
        }

        // ---------- Drill-in routes (bottom nav hidden) ----------

        composable(
            route = Routes.NOTEBOOK_DETAIL,
            arguments = listOf(navArgument(NotebookDetailViewModel.ARG_NOTEBOOK_ID) {
                type = NavType.StringType
            }),
        ) {
            NotebookDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenPage = { id -> nav.navigate(Routes.page(id)) },
            )
        }
        composable(
            route = Routes.PAGE,
            arguments = listOf(navArgument(PageDetailViewModel.ARG_PAGE_ID) {
                type = NavType.StringType
            }),
        ) {
            PageDetailScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = Routes.NOTEPAD_EDIT,
            arguments = listOf(navArgument(NotepadEditorViewModel.ARG_ENTRY_ID) {
                type = NavType.StringType
            }),
        ) {
            NotepadEditorScreen(onBack = { nav.popBackStack() })
        }

        // Room-backed notebook surfaces (Notebooks tab).
        composable(
            route = Routes.NOTEBOOK_LOCAL_DETAIL,
            arguments = listOf(navArgument(NotebookLocalDetailViewModel.ARG_NOTEBOOK_ID) {
                type = NavType.StringType
            }),
        ) {
            NotebookLocalDetailScreen(
                onBack = navigateToNotebooksTab,
                onHome = navigateHome,
                onOpenChapter = { id -> nav.navigate(Routes.chapterLocalDetail(id)) },
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
            )
        }
    }
}
