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

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.quickink.mobile.data.analytics.AnalyticsFlushWorker
import app.quickink.mobile.data.analytics.AnalyticsRepository
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.features.workspace.WorkspaceFeatureFlag
import app.quickink.mobile.features.workspace.WorkspaceHomeScreen
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import kotlinx.coroutines.launch
import app.quickink.mobile.features.home.CategoryEntriesScreen
import app.quickink.mobile.features.home.HomeScreen
import app.quickink.mobile.features.notes.NoteEditorScreen
import app.quickink.mobile.features.notes.NotesListScreen
import app.quickink.mobile.features.onboarding.OnboardingFlow
import app.quickink.mobile.features.onboarding.OnboardingPreferences
import app.quickink.mobile.features.onboarding.OnboardingState
import app.quickink.mobile.features.onboarding.SignInScreen
import app.quickink.mobile.features.scan.LocationService
import app.quickink.mobile.features.scan.PendingShare
import app.quickink.mobile.features.scan.QuickCaptureScreen
import app.quickink.mobile.features.scan.ScanDetailScreen
import app.quickink.mobile.features.scan.ScanFlowController
import app.quickink.mobile.features.scan.ScanReviewScreen
import app.quickink.mobile.features.scan.buildImportArtifacts
import app.quickink.mobile.features.scan.buildPdfImportArtifact
import app.quickink.mobile.features.search.SearchScreen
import app.quickink.mobile.features.settings.CategoriesSettingsScreen
import app.quickink.mobile.features.settings.ProfileScreen
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.features.settings.SettingsScreen
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.shared.scan.MlKitTextRecognizer
import app.releaf.shared.scan.OcrPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QuickInkRoot(
    /// User's Appearance picks (Settings → Theme). Hoisted to
    /// MainActivity so the QuickInkTheme wrapper can react to
    /// changes; threaded through to SettingsScreen so the picker UI
    /// can mutate them. Defaults preserve standalone-Compose-preview
    /// behaviour (no SettingsPreferences read needed at preview time).
    currentPrimaryColor: app.quickink.mobile.ui.theme.PrimaryColor =
        app.quickink.mobile.ui.theme.PrimaryColor.Coral,
    currentThemeMode: app.quickink.mobile.ui.theme.ThemeMode =
        app.quickink.mobile.ui.theme.ThemeMode.System,
    onPrimaryColorChange: (app.quickink.mobile.ui.theme.PrimaryColor) -> Unit = {},
    onThemeModeChange: (app.quickink.mobile.ui.theme.ThemeMode) -> Unit = {},
    /// Share-target plumbing — populated by `MainActivity` from the
    /// system share-sheet intent (image/* or application/pdf).
    /// Forwarded into `MainShell`, which only kicks the import
    /// after the auth/onboarding gates pass and the scan flow is
    /// idle. Held in activity-scoped state so a share delivered
    /// while the user is on the sign-in gate survives until the
    /// gate clears.
    pendingShare: PendingShare? = null,
    onPendingShareConsumed: () -> Unit = {},
) {
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
        else -> MainShell(
            userId                  = (authState as AuthState.SignedIn).session.userId,
            currentPrimaryColor     = currentPrimaryColor,
            currentThemeMode        = currentThemeMode,
            onPrimaryColorChange    = onPrimaryColorChange,
            onThemeModeChange       = onThemeModeChange,
            pendingShare            = pendingShare,
            onPendingShareConsumed  = onPendingShareConsumed,
        )
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

    /** Workspace v1 home (Phase B). Gated by [WorkspaceFeatureFlag]. */
    const val WORKSPACE_HOME    = "workspace_home"

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
private fun MainShell(
    userId: String,
    /// Threaded through from QuickInkRoot → MainActivity. The
    /// SettingsScreen tab consumes these to render the Appearance
    /// section's pickers; mutations bubble back up to MainActivity
    /// which holds the @State that QuickInkTheme reads.
    currentPrimaryColor: app.quickink.mobile.ui.theme.PrimaryColor =
        app.quickink.mobile.ui.theme.PrimaryColor.Coral,
    currentThemeMode: app.quickink.mobile.ui.theme.ThemeMode =
        app.quickink.mobile.ui.theme.ThemeMode.System,
    onPrimaryColorChange: (app.quickink.mobile.ui.theme.PrimaryColor) -> Unit = {},
    onThemeModeChange: (app.quickink.mobile.ui.theme.ThemeMode) -> Unit = {},
    /// Share-target import — see [QuickInkRoot] doc. Consumed via a
    /// LaunchedEffect below.
    pendingShare: PendingShare? = null,
    onPendingShareConsumed: () -> Unit = {},
) {
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
            // Application context drives the per-scan location
            // fetch + reverse-geocode (gated by the
            // `locationForScansEnabled` Settings toggle and the
            // ACCESS_COARSE_LOCATION permission). Captured once
            // from the keyed Compose context so the controller's
            // lifetime can outlive any individual screen.
            appContext     = context.applicationContext,
            // Two pipelines fire on every completed scan/import pass:
            //
            //   1. Drive sync — kick a one-shot push so the fresh
            //      capture lands in the user's Drive without waiting
            //      for the 60s foreground ticker. KEEP coalesces a
            //      burst of back-to-back scans into one worker run,
            //      and the worker still respects the user's "Back up
            //      to Google Drive" toggle + AUTH_REJECTED gate
            //      internally. Settings → "Sync now" remains the
            //      manual force-retry path.
            //   2. Analytics outbox — separate cadence, separate
            //      failure mode. Every pass enqueues a row +
            //      opportunistically flushes so the dashboard
            //      reflects the capture within seconds.
            onPassComplete = { summary ->
                QuickInkSyncScheduler.requestImmediate(context)
                scope.launch {
                    try {
                        AnalyticsRepository(app.database.analyticsOutboxDao())
                            .enqueueCapture(
                                captureId  = summary.captureId,
                                source     = summary.source,
                                pageCount  = summary.pageCount,
                                category   = summary.category,
                                hasOcr     = summary.hasOcr,
                                ocrChars   = summary.ocrChars,
                                capturedAt = summary.capturedAt,
                            )
                        AnalyticsFlushWorker.requestImmediate(context)
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "QuickInkAnalytics",
                            "[analytics] enqueueCapture failed: $e"
                        )
                    }
                }
            },
            // Powers the OCR-first-word → category auto-pick at
            // the end of each pass. Read-only; controller calls
            // `listActive(userId)` once per pass.
            tagDao    = app.database.tagDao(),
        )
    }

    // Idempotent first-launch / first-sign-in seed of the default
    // tags + the Workspace v1 folder/tag migration.
    // `LaunchedEffect(userId)` fires once per signed-in user; every
    // step short-circuits when its work is already done.
    LaunchedEffect(userId) {
        try {
            val tagRepo = TagRepository(
                tagDao     = app.database.tagDao(),
                captureDao = app.database.captureDao(),
            )
            tagRepo.seedDefaultsIfEmpty(userId)
            // One-shot migration for users on the previous seed
            // that included "Study". Idempotent + flag-guarded;
            // safe to call on every launch.
            tagRepo.migrateLegacyStudyToBusinessCardIfNeeded(context, userId)

            // Workspace v1 Phase A.3 — seed Unfiled folder, backfill
            // every capture's folder_id, materialize the legacy
            // `captures.category` value into `capture_tags`.
            // Idempotent via SharedPreferences guards.
            FolderRepository(
                folderDao     = app.database.folderDao(),
                captureDao    = app.database.captureDao(),
                tagDao        = app.database.tagDao(),
                captureTagDao = app.database.captureTagDao(),
            ).runFirstLaunchMigrationIfNeeded(context, userId)
        } catch (_: Exception) { /* best-effort */ }
    }

    // One-shot post-onboarding location-permission ask. Existing
    // users who completed onboarding before the Location step
    // shipped (Phase 7) would otherwise never see the system
    // dialog — `OnboardingPreferences.isCompleted` skips the whole
    // flow on launch. This effect launches the contract once on
    // first launch after the upgrade, then sets a flag so we never
    // re-ask without a Settings flip. New users hit the flag inside
    // `LocationPermissionScreen` so this branch no-ops for them.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        LocationService.markPromptHandled(context)
    }
    LaunchedEffect(Unit) {
        if (LocationService.wasPromptHandled(context)) return@LaunchedEffect
        if (!settingsPrefs.locationForScansEnabled) {
            // Toggle is off — no point asking. Mark handled so the
            // user can flip the toggle later and trigger a fresh
            // ask through a future Settings-toggle-on path.
            LocationService.markPromptHandled(context)
            return@LaunchedEffect
        }
        // Re-ask when we have coarse but no fine — the v2 dialog
        // surfaces the Precise / Approximate toggle that v1 (coarse-
        // only) suppressed. Already-fine users see no dialog
        // because the launcher returns granted immediately.
        if (LocationService.hasPermission(context) && LocationService.hasFinePermission(context)) {
            LocationService.markPromptHandled(context)
            return@LaunchedEffect
        }
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ))
    }

    val scanState by controller.state.collectAsState()
    if (scanState !is ScanFlowController.State.Idle) {
        ScanReviewScreen(controller, userId = userId)
        return
    }

    // Share-target import. Mirrors the in-app Import button on
    // QuickCaptureScreen — same `buildImportArtifacts` (images) /
    // `buildPdfImportArtifact` (PDFs) bridges, same
    // `controller.onScanComplete(..., source = "import")` hand-off,
    // so the resulting capture row is indistinguishable from a
    // photo-picker import.
    //
    // Placement: after the `scanState !is Idle` early-return
    // above, so the effect only mounts when the controller is
    // idle. A share delivered while the user is in
    // ScanReviewScreen waits in the activity-scoped state until
    // they dismiss the review; controller transitions to Idle,
    // MainShell re-renders the rest of its body, this effect
    // mounts, and the queued share kicks off.
    //
    // Once `controller.onScanComplete` fires, the controller flips
    // to Recognizing on the next composition, the early-return
    // triggers, and ScanReviewScreen owns the screen for the rest
    // of the OCR pass.
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            if (share.isPdf) {
                buildPdfImportArtifact(context, share.uris.first())
            } else {
                buildImportArtifacts(context, share.uris)
            }
        }
        if (result != null) {
            controller.onScanComplete(result, source = "import")
            // `onScanComplete` already kicks `requestImmediate` via
            // the controller's `onPassComplete` callback when OCR
            // finishes — no additional schedule here would be
            // redundant. Mentioned explicitly so a future reader
            // doesn't add a second call thinking imports were
            // skipped.
        }
        // Always clear the pending share — even on a null result
        // (corrupt PDF, all-images-failed-to-decode), so a retry
        // doesn't loop on the same broken input. The user can
        // re-share if they want another attempt.
        onPendingShareConsumed()
    }

    // Lifted out of HomeScreen so the ⚡ FAB on Library / Search /
    // Settings can also trigger the QuickCapture sheet without
    // hopping back to Home first. HomeScreen still owns its own
    // local `showQuickCapture` state for the FAB on its own surface;
    // both routes set this same root-level flag, and the early-
    // return below renders QuickCaptureScreen above the NavHost.
    var showQuickCapture by remember { mutableStateOf(false) }
    if (showQuickCapture) {
        QuickCaptureScreen(
            controller = controller,
            onDismiss  = { showQuickCapture = false },
        )
        return
    }

    val navController = rememberNavController()

    /// Tab-style navigation between top-level destinations (Home,
    /// Library, Search, Settings). `popUpTo(HOME, saveState=true)` +
    /// `restoreState=true` gives Material's expected tab semantics:
    /// each tab keeps its own scroll position / draft state across
    /// switches, the back stack stays shallow (one entry), and back
    /// from any tab returns to Home.
    val navToTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Routes.HOME) {
                saveState = true
                inclusive = false
            }
            launchSingleTop = true
            restoreState    = true
        }
    }

    // Workspace v1 (Phase B) — when the feature flag is on, the
    // bottom-nav "Workspace" tab routes to the new WorkspaceHomeScreen.
    // When off, it routes to the legacy NotesListScreen so the rest of
    // the UI keeps shipping unchanged. Read once per composition; flips
    // require a process restart, which is acceptable for a dev-only
    // toggle.
    val workspaceTabRoute = remember(context) {
        if (WorkspaceFeatureFlag.isEnabled(context)) Routes.WORKSPACE_HOME
        else Routes.NOTES_LIST
    }

    /// Variant of [navToTab] used by ScanDetailScreen's bottom nav.
    /// Skips `restoreState` so tapping Library / Search from the
    /// detail screen lands on a fresh tab view (top of list, no
    /// retained search query) rather than the saved state. Matches
    /// iOS where the same callback path resets the navigation stack.
    val navToTabFresh: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Routes.HOME) {
                inclusive = false
            }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                controller     = controller,
                userId         = userId,
                onOpenNotes    = { navToTab(workspaceTabRoute) },
                onOpenSettings = { navToTab(Routes.SETTINGS) },
                onOpenSearch   = { navToTab(Routes.SEARCH) },
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
                onOpenScan = { captureId ->
                    // Replace the search step with the detail so popping
                    // back from the detail returns to Home, not Search.
                    navController.popBackStack()
                    navController.navigate(Routes.scanDetail(captureId))
                },
                onHome     = { navToTab(Routes.HOME) },
                onWorkspace  = { navToTab(workspaceTabRoute) },
                onScan     = { showQuickCapture = true },
                onSearch   = { /* current tab — no-op */ },
                onSettings = { navToTab(Routes.SETTINGS) },
            )
        }
        composable(Routes.NOTES_LIST) {
            NotesListScreen(
                userId     = userId,
                onOpenScan = { captureId ->
                    navController.navigate(Routes.scanDetail(captureId))
                },
                onHome     = { navToTab(Routes.HOME) },
                onWorkspace  = { /* current tab — no-op */ },
                onScan     = { showQuickCapture = true },
                onSearch   = { navToTab(Routes.SEARCH) },
                onSettings = { navToTab(Routes.SETTINGS) },
            )
        }
        composable(Routes.WORKSPACE_HOME) {
            WorkspaceHomeScreen(
                userId         = userId,
                onOpenSearch   = { navToTab(Routes.SEARCH) },
                onOpenFolder   = { _ ->
                    // Folder detail (Screen 2) lands in Phase C — tap is a
                    // no-op for B.0. Wire to a "FolderDetailScreen" route
                    // when that screen exists.
                },
                onOpenContinue = { capture ->
                    navController.navigate(Routes.scanDetail(capture.id))
                },
                onOpenProfile  = { navController.navigate(Routes.PROFILE) },
                onOpenTag      = { tag ->
                    // Tap → drill into captures with this tag. Re-uses the
                    // legacy CATEGORY_ENTRIES route (filter-by-name) for
                    // now; will move to a tag-id-based filter in Phase D.
                    navController.navigate(Routes.categoryEntries(tag.name))
                },
                onHome     = { navToTab(Routes.HOME) },
                onScan     = { showQuickCapture = true },
                onSettings = { navToTab(Routes.SETTINGS) },
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
                primaryColor               = currentPrimaryColor,
                themeMode                  = currentThemeMode,
                onPrimaryColorChange       = onPrimaryColorChange,
                onThemeModeChange          = onThemeModeChange,
                onHome                     = { navToTab(Routes.HOME) },
                onWorkspace                  = { navToTab(workspaceTabRoute) },
                onScan                     = { showQuickCapture = true },
                onSearch                   = { navToTab(Routes.SEARCH) },
                onSettings                 = { /* current tab — no-op */ },
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
                captureId  = captureId,
                userId     = userId,
                onBack     = { navController.popBackStack() },
                // Use the fresh-state variant — tapping Library or
                // Search from a scan detail should land on the
                // tab's default view, not the saved state from
                // before the user opened the detail.
                onHome     = { navToTabFresh(Routes.HOME) },
                onWorkspace  = { navToTabFresh(workspaceTabRoute) },
                onScan     = { showQuickCapture = true },
                onSearch   = { navToTabFresh(Routes.SEARCH) },
                onSettings = { navToTabFresh(Routes.SETTINGS) },
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
