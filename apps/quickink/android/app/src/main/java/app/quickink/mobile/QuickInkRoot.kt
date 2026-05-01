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
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.features.home.HomeScreen
import app.quickink.mobile.features.notes.NoteEditorScreen
import app.quickink.mobile.features.notes.NotesListScreen
import app.quickink.mobile.features.onboarding.OnboardingFlow
import app.quickink.mobile.features.onboarding.OnboardingPreferences
import app.quickink.mobile.features.onboarding.OnboardingState
import app.quickink.mobile.features.onboarding.SignInScreen
import app.quickink.mobile.features.scan.ScanFlowController
import app.quickink.mobile.features.scan.ScanReviewScreen
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
    const val HOME        = "home"
    const val NOTES_LIST  = "notes_list"
    const val SETTINGS    = "settings"
    const val NOTE_EDITOR = "note_editor/{entryId}"

    fun noteEditor(entryId: String): String =
        "note_editor/${Uri.encode(entryId)}"
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

    val controller = remember(userId) {
        ScanFlowController(
            userId     = userId,
            repository = CaptureRepository(
                captureDao   = app.database.captureDao(),
                ocrResultDao = app.database.ocrResultDao(),
            ),
            pipeline       = OcrPipeline(MlKitTextRecognizer(app)),
            scope          = scope,
            // Slice 4.2c — kick a one-shot sync at the end of
            // each scan pass. WorkManager's KEEP policy coalesces
            // bursts and the worker no-ops when Drive backup is
            // off (per QuickInkSyncWorker's gate), so this is
            // safe to fire unconditionally.
            onPassComplete = { QuickInkSyncScheduler.requestImmediate(app) },
        )
    }

    val scanState by controller.state.collectAsState()
    if (scanState !is ScanFlowController.State.Idle) {
        ScanReviewScreen(controller)
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                controller     = controller,
                onOpenNotes    = { navController.navigate(Routes.NOTES_LIST) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.NOTES_LIST) {
            NotesListScreen(
                dao         = app.database.notepadDao(),
                userId      = userId,
                onBack      = { navController.popBackStack() },
                onOpenEntry = { entryId ->
                    navController.navigate(Routes.noteEditor(entryId))
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
                onBack    = { navController.popBackStack() },
                authStore = app.authStore,
            )
        }
    }
}
