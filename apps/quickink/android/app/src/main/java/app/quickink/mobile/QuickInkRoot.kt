/*
 * QuickInkRoot.kt
 *
 * QuickInk's top-level Compose entry point. Counterpart to iOS's
 * `QuickInkRoot.swift`. Body intentionally exercises shared design
 * tokens (`AppColors.Canvas`, `AppTypography.PageTitle`,
 * `AppSpacing.s4`) so a regression in the
 * `:shared:designsystem` ↔ QuickInk wiring shows up at first build
 * rather than at MVP-feature time.
 *
 * Real screens (per QUICKINK_PROPOSAL.md §6.4):
 *   - 3-screen onboarding (welcome / permissions / Google sign-in
 *     with Drive backup toggle on screen 3)
 *   - Camera-first Home opening directly to DocumentScannerLauncher
 *   - Scan + OCR result review surface
 *   - Notes list + editor (thin wrappers over :shared:notes VMs)
 *   - Settings
 */

package app.quickink.mobile

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.category.CategoryRepository
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.features.home.CategoryEntriesScreen
import app.quickink.mobile.features.home.HomeScreen
import app.quickink.mobile.features.notes.NoteEditorScreen
import app.quickink.mobile.features.notes.NotesListScreen
import app.quickink.mobile.features.onboarding.OnboardingFlow
import app.quickink.mobile.features.onboarding.OnboardingPreferences
import app.quickink.mobile.features.onboarding.OnboardingState
import app.quickink.mobile.features.onboarding.SignInScreen
import app.quickink.mobile.features.scan.ScanDetailScreen
import app.quickink.mobile.features.scan.ScanFlowController
import app.quickink.mobile.features.scan.ScanReviewScreen
import app.quickink.mobile.features.search.SearchScreen
import app.quickink.mobile.features.settings.CategoriesSettingsScreen
import app.quickink.mobile.features.settings.ProfileScreen
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.features.settings.SettingsScreen
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.shared.scan.MlKitTextRecognizer
import app.releaf.shared.scan.OcrPipeline

@Composable
fun QuickInkRoot() {
    val context = LocalContext.current
    val app     = context.applicationContext as QuickInkApp
    val preferences = remember { OnboardingPreferences(context) }

    var onboardingCompleted by remember { mutableStateOf(preferences.isCompleted) }
    val authState by app.authStore.state.collectAsState()

    when {
        // First-time users — full 3-screen onboarding.
        !onboardingCompleted -> OnboardingFlow(
            preferences = preferences,
            authStore   = app.authStore,
            onComplete  = { onboardingCompleted = true },
        )

        // Onboarding done but no active session — Option A: bounce
        // to the SignIn screen only (skip welcome + permissions).
        // Persisted Drive choice from earlier onboarding stays
        // valid; toggling it here overwrites Settings, same as the
        // first-run flow.
        authState !is AuthState.SignedIn -> ReSignInGate(authStore = app.authStore)

        // Signed in — the main shell takes over.
        else -> MainShell(userId = (authState as AuthState.SignedIn).session.userId)
    }
}

/**
 * Standalone re-sign-in surface — renders only the onboarding
 * `SignInScreen` (no welcome, no permissions). Shared with the
 * full onboarding flow's third step; on success, `authState`
 * transitions to `SignedIn` and `QuickInkRoot`'s `when` flips
 * automatically to `MainShell`.
 */
@Composable
private fun ReSignInGate(authStore: AuthStore) {
    // Slice 4.3 — seed the in-memory OnboardingState's Drive
    // toggle from the persisted Settings keyspace so the gate
    // reflects the user's current choice, not the always-true
    // default. SignInScreen's comment promised "toggling it here
    // overwrites Settings, same as the first-run flow" — we now
    // honor that by both seeding AND persisting on success.
    val context = LocalContext.current
    val settings = remember { app.quickink.mobile.features.settings.SettingsPreferences(context) }
    val state = remember {
        OnboardingState().apply {
            driveBackupEnabled = settings.driveBackupEnabled
        }
    }
    SignInScreen(
        state      = state,
        authStore  = authStore,
        onSignedIn = {
            // Persist any toggle change made in this gate. Mirror
            // of OnboardingFlow's first-run path. `QuickInkRoot`
            // reads AuthStore state directly and routes to
            // MainShell on its own — no other action needed.
            settings.driveBackupEnabled = state.driveBackupEnabled
        },
    )
}

/**
 * Compose Navigation route names. String-based for compatibility
 * with the navigation-compose 2.8.x in `libs.versions.toml`;
 * typed routes (the @Serializable variant) are a 2.8+ feature
 * but worth a follow-up sweep to type-check the entryId arg.
 */
private object Routes {
    const val HOME              = "home"
    const val NOTES_LIST        = "notes_list"
    const val SETTINGS          = "settings"
    const val PROFILE           = "profile"
    const val SEARCH            = "search"
    const val NOTE_EDITOR       = "note_editor/{entryId}"
    const val CATEGORIES        = "categories"
    const val SCAN_DETAIL       = "scan_detail/{captureId}"
    // Per-category browse — pushed from the Home category grid.
    // `name` is encoded so categories with spaces ("Manage Categories")
    // round-trip cleanly through the nav arg.
    const val CATEGORY_ENTRIES  = "category_entries/{name}"

    fun noteEditor(entryId: String): String =
        "note_editor/${Uri.encode(entryId)}"

    fun scanDetail(captureId: String): String =
        "scan_detail/${Uri.encode(captureId)}"

    fun categoryEntries(name: String): String =
        "category_entries/${Uri.encode(name)}"
}

/**
 * Camera-first main shell. Constructs a `ScanFlowController`
 * scoped to this Composable's lifetime, plus a `NavController`
 * for the Home / Notes / Editor / Settings nav graph. The scan
 * flow preempts the entire NavHost when its controller is non-
 * Idle — OCR is an interruption that should land the user on
 * the review surface; back-stack state is preserved across the
 * preemption since `NavController` is held in `rememberNavController`.
 *
 * `userId` is hardcoded for the scaffold; auth wiring replaces
 * this with the real auth-state-derived value in a follow-up.
 */
@Composable
private fun MainShell(userId: String) {
    val context = LocalContext.current
    val app     = context.applicationContext as QuickInkApp
    val scope   = rememberCoroutineScope()

    // Resolved Home greeting name (Settings override > Google
    // session displayName > null fallback). Held as a Compose state
    // so a Settings edit triggers a recomposition without round-
    // tripping through SharedPreferences observers — SettingsScreen
    // pushes the new value through `onCustomDisplayNameChange`.
    val settingsPrefs = remember { SettingsPreferences(context) }
    var customDisplayName by remember { mutableStateOf(settingsPrefs.customDisplayName) }
    // Held as Compose state so a Profile-screen edit re-renders the
    // home avatar reactively without a SharedPreferences observer —
    // ProfileScreen pushes the new URI through `onProfilePhotoChange`.
    var profilePhotoUri by remember { mutableStateOf(settingsPrefs.profilePhotoUri) }
    val authStateForName by app.authStore.state.collectAsState()
    val resolvedDisplayName: String? = run {
        val custom = customDisplayName.trim()
        if (custom.isNotEmpty()) custom
        else (authStateForName as? AuthState.SignedIn)
            ?.session?.displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    val controller = remember(userId) {
        ScanFlowController(
            userId     = userId,
            repository = CaptureRepository(
                captureDao   = app.database.captureDao(),
                ocrResultDao = app.database.ocrResultDao(),
            ),
            pipeline       = OcrPipeline(MlKitTextRecognizer(app)),
            notepadDao     = app.database.notepadDao(),
            scope          = scope,
            // Slice 4.2c — kick a one-shot sync at the end of
            // each scan pass. WorkManager's KEEP policy coalesces
            // bursts and the worker no-ops when Drive backup is
            // off (per QuickInkSyncWorker's gate), so this is
            // safe to fire unconditionally.
            onPassComplete = { QuickInkSyncScheduler.requestImmediate(app) },
            // Powers the OCR-first-word → category auto-pick at
            // the end of each pass. Read-only; controller calls
            // `listActive(userId)` once per pass.
            categoryDao    = app.database.categoryDao(),
        )
    }

    // Idempotent first-launch / first-sign-in seed of the default 6
    // categories. `LaunchedEffect(userId)` fires once per signed-in
    // user; the repository skips work when rows already exist.
    LaunchedEffect(userId) {
        try {
            CategoryRepository(app.database.categoryDao())
                .seedDefaultsIfEmpty(userId)
        } catch (_: Exception) { /* best-effort */ }
    }

    val scanState by controller.state.collectAsState()
    if (scanState !is ScanFlowController.State.Idle) {
        ScanReviewScreen(controller, userId = userId)
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                controller     = controller,
                userId         = userId,
                onOpenNotes    = { navController.navigate(Routes.NOTES_LIST) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSearch   = { navController.navigate(Routes.SEARCH) },
                onTapCategory  = { name ->
                    navController.navigate(Routes.categoryEntries(name))
                },
                onOpenEntry    = { entryId ->
                    navController.navigate(Routes.noteEditor(entryId))
                },
                onOpenScan     = { captureId ->
                    navController.navigate(Routes.scanDetail(captureId))
                },
                onOpenProfile  = { navController.navigate(Routes.PROFILE) },
                onSignOut      = { app.authStore.signOut() },
                email          = (authStateForName as? AuthState.SignedIn)?.session?.email.orEmpty(),
                displayName    = resolvedDisplayName,
                profilePhotoUri = profilePhotoUri,
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                userId     = userId,
                onBack     = { navController.popBackStack() },
                onOpenScan = { captureId ->
                    // Replace the search step with the detail so popping
                    // back from the detail returns to Home, not Search.
                    navController.popBackStack()
                    navController.navigate(Routes.scanDetail(captureId))
                },
            )
        }
        composable(Routes.NOTES_LIST) {
            NotesListScreen(
                userId     = userId,
                onBack     = { navController.popBackStack() },
                onOpenScan = { captureId ->
                    navController.navigate(Routes.scanDetail(captureId))
                },
            )
        }
        composable(
            route     = Routes.NOTE_EDITOR,
            arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString("entryId").orEmpty()
            NoteEditorScreen(
                entryId = entryId,
                userId  = userId,
                onBack  = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack                     = { navController.popBackStack() },
                authStore                  = app.authStore,
                onManageCategories         = { navController.navigate(Routes.CATEGORIES) },
                onCustomDisplayNameChange  = { customDisplayName = it },
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onBack               = { navController.popBackStack() },
                authStore            = app.authStore,
                onProfilePhotoChange = { profilePhotoUri = it },
            )
        }
        composable(Routes.CATEGORIES) {
            CategoriesSettingsScreen(
                userId = userId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route     = Routes.SCAN_DETAIL,
            arguments = listOf(navArgument("captureId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val captureId = backStackEntry.arguments?.getString("captureId").orEmpty()
            ScanDetailScreen(
                captureId = captureId,
                userId    = userId,
                onBack    = { navController.popBackStack() },
            )
        }
        composable(
            route     = Routes.CATEGORY_ENTRIES,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name").orEmpty()
            CategoryEntriesScreen(
                userId       = userId,
                categoryName = name,
                onBack       = { navController.popBackStack() },
                onOpenScan   = { captureId ->
                    navController.navigate(Routes.scanDetail(captureId))
                },
            )
        }
    }
}
