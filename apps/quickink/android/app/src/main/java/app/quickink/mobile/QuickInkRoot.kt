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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
import app.quickink.mobile.features.nav.QuickInkTimeBar
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.quickink.mobile.data.analytics.AnalyticsFlushWorker
import app.quickink.mobile.data.analytics.AnalyticsRepository
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.features.calendar.CalendarScreen
import app.quickink.mobile.features.daylight.DaylightLocationStore
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.location.LocationRepository
import app.quickink.mobile.data.person.PersonRepository
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.data.smartcollection.SmartCollectionRepository
import app.quickink.mobile.features.workspace.FolderDetailScreen
import app.quickink.mobile.features.workspace.LocationDetailScreen
import app.quickink.mobile.features.workspace.PersonDetailScreen
import app.quickink.mobile.features.workspace.SmartCollectionScreen
import app.quickink.mobile.features.workspace.TagLibraryScreen
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
import app.quickink.mobile.features.scan.CaptureMode
import app.quickink.mobile.features.scan.LocationService
import app.quickink.mobile.features.scan.PendingShare
import app.quickink.mobile.features.scan.PhotoFabHint
import app.quickink.mobile.features.scan.QuickCaptureScreen
import app.quickink.mobile.features.scan.ScanDetailScreen
import app.quickink.mobile.features.scan.ScanFlowController
import app.quickink.mobile.features.scan.ScanCaptureSurface
import app.quickink.mobile.features.scan.ScanReviewScreen
import app.quickink.mobile.features.scan.buildImportArtifacts
import app.quickink.mobile.features.scan.buildPdfImportArtifact
import app.quickink.mobile.BuildConfig
import app.quickink.mobile.data.story.StoryRepository
import app.quickink.mobile.features.search.SearchScreen
import app.quickink.mobile.features.stories.StoriesShelfScreen
import app.quickink.mobile.features.stories.StoryEditorScreen
import app.quickink.mobile.features.stories.StoryReaderScreen
import app.quickink.mobile.features.stories.StorySuggestionPreviewScreen
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
    /** Stories shelf — bottom-nav slot (replaced the Search tab). */
    const val STORIES           = "stories"

    /** Stories editor (Phase 2) — opened from the shelf FAB / card tap. */
    const val STORY_EDITOR      = "story_editor/{storyId}"
    fun storyEditor(storyId: String): String = "story_editor/${Uri.encode(storyId)}"

    /** Stories reader (Phase 3) — opened from the editor's Preview button
     *  and (Phase 6) from a published Drive link. */
    const val STORY_READER      = "story_reader/{storyId}"
    fun storyReader(storyId: String): String = "story_reader/${Uri.encode(storyId)}"

    /** Stories suggestion preview (Phase 5) — opened from the hero
     *  card's "Open preview" link on the shelf. */
    const val STORY_SUGGESTION_PREVIEW = "story_suggestion_preview/{suggestionId}"
    fun storySuggestionPreview(suggestionId: String): String =
        "story_suggestion_preview/${Uri.encode(suggestionId)}"
    const val NOTE_EDITOR       = "note_editor/{entryId}"
    const val CATEGORIES        = "categories"
    const val SCAN_DETAIL       = "scan_detail/{captureId}"
    // Per-category browse — pushed from the Home category grid.
    // `name` is encoded so categories with spaces ("Manage Categories")
    // round-trip cleanly through the nav arg.
    const val CATEGORY_ENTRIES  = "category_entries/{name}"

    /** Workspace v1 home — canonical bottom-nav destination post-GA. */
    const val WORKSPACE_HOME    = "workspace_home"

    /** Workspace v1 folder detail (Phase C — Screen 2). */
    const val FOLDER_DETAIL     = "folder_detail/{folderId}"
    fun folderDetail(folderId: String): String = "folder_detail/${Uri.encode(folderId)}"

    /** Workspace v1 smart-collection view (Phase C — Screen 3). */
    const val SMART_COLLECTION  = "smart_collection/{collectionId}"
    fun smartCollection(id: String): String = "smart_collection/${Uri.encode(id)}"

    /** Workspace v1 tag library (Phase D — Screen 4). */
    const val TAG_LIBRARY       = "tag_library"

    /** Location detail — captures attached to a single location. */
    const val LOCATION_DETAIL   = "location_detail/{locationId}"
    fun locationDetail(locationId: String): String =
        "location_detail/${Uri.encode(locationId)}"

    /** Person detail — captures attached to a single person. */
    const val PERSON_DETAIL     = "person_detail/{personId}"
    fun personDetail(personId: String): String =
        "person_detail/${Uri.encode(personId)}"

    /** Standalone Calendar — panchanga + Indian holidays + per-day
     *  capture dots. Pushed from the home header's calendar button. */
    const val CALENDAR          = "calendar"

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
                captureDao    = app.database.captureDao(),
                ocrResultDao  = app.database.ocrResultDao(),
                tagDao        = app.database.tagDao(),
                captureTagDao = app.database.captureTagDao(),
            ),
            pipeline       = OcrPipeline(MlKitTextRecognizer(app)),
            notepadDao     = app.database.notepadDao(),
            scope          = scope,
            folderDao      = app.database.folderDao(),
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
                                // Post-A.3c the `captures.category`
                                // column is gone; the analytics
                                // server-side `category` slot stays
                                // for back-compat but new captures
                                // emit null. Once the server schema
                                // drops the field this column folds
                                // out of the outbox row too.
                                category   = null,
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
            // One-shot migration that lowercases + kebab-cases the
            // legacy capitalized seed names so existing users align
            // with the new canonical form. Idempotent + flag-guarded.
            tagRepo.migrateLegacySeedNamesToKebabIfNeeded(context, userId)

            // Workspace v1 Phase A.3 — seed Unfiled folder + backfill
            // every capture's folder_id. The legacy
            // `captures.category` → `capture_tags` materialize step
            // shipped in A.3a and is gone post-A.3c column drop.
            // Idempotent via SharedPreferences guards.
            FolderRepository(
                folderDao  = app.database.folderDao(),
                captureDao = app.database.captureDao(),
            ).runFirstLaunchMigrationIfNeeded(context, userId)

            // Workspace v1 Phase C.3 — seed the "Needs review"
            // smart collection. Other design-brief seeds depend on
            // OCR-derived signals (Phase E) or a folder that we
            // don't auto-create, so only this one ships out of
            // the box. Idempotent via is_seeded uniqueness.
            SmartCollectionRepository(
                smartCollectionDao = app.database.smartCollectionDao(),
                captureDao         = app.database.captureDao(),
                captureTagDao      = app.database.captureTagDao(),
                tagDao             = app.database.tagDao(),
            ).seedDefaultsIfNeeded(userId)

            // Seed "Home" + "Work" placeholder locations on first
            // sign-in. Idempotent — short-circuits when the user
            // already has any active rows.
            LocationRepository(
                locationDao = app.database.locationDao(),
            ).seedDefaultsIfEmpty(userId)

            // Seed "Me" placeholder person on first sign-in. Same
            // idempotent contract as the locations seed above.
            PersonRepository(
                personDao = app.database.personDao(),
            ).seedDefaultsIfEmpty(userId)

            // Stories Phase 1 — debug-only dev seeder so the Stories
            // tab has cards to render in QA builds. Idempotent: skips
            // when the user already has any active stories. Gated by
            // `BuildConfig.DEBUG` so release builds never run it. See
            // `design/STORIES_HANDOFF.md` §4 Phase 1 task 1.8.
            if (BuildConfig.DEBUG) {
                StoryRepository(
                    storyDao          = app.database.storyDao(),
                    storyItemDao      = app.database.storyItemDao(),
                    storyVoiceClipDao = app.database.storyVoiceClipDao(),
                ).seedDevStoriesIfEmpty(userId)
            }
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
        ScanCaptureSurface(controller, userId = userId)
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
    // Optional override for QuickCaptureScreen's starting mode.
    // Tap on the FAB leaves this `null` so the screen reads its
    // usual `quickink.capture.last_mode` default. Long-press on
    // the FAB sets `CaptureMode.Photo`, which routes the user
    // straight into the photo surface — the screen's no-op
    // persist on the `.Photo` branch prevents this transient
    // choice from overwriting the user's pill-selected last
    // mode. Cleared back to `null` on dismiss so a subsequent
    // tap-FAB doesn't inherit the override.
    var pendingInitialMode by remember { mutableStateOf<CaptureMode?>(null) }
    // Photo-FAB hint state. Mirror of iOS `PhotoFabHint`
    // `@StateObject`: a simple `mutableStateOf` seeded from
    // SharedPreferences so the chip's visibility re-renders on
    // dismiss without polling. Spec §3.1 — chip shows on every
    // launch until the user long-presses the FAB once, after
    // which it stays dismissed permanently.
    val photoFabHintContext = LocalContext.current
    var photoFabHintDismissed by remember {
        mutableStateOf(PhotoFabHint.isDismissed(photoFabHintContext))
    }
    if (showQuickCapture) {
        // Intercept back so it dismisses the capture sheet instead
        // of falling through to the OS default (which would finish
        // the activity, because we early-return above the NavHost
        // and its root-level exit handler never gets composed).
        androidx.activity.compose.BackHandler {
            showQuickCapture = false
            pendingInitialMode = null
        }
        QuickCaptureScreen(
            controller   = controller,
            onDismiss    = {
                showQuickCapture = false
                // Reset to null on dismiss so a subsequent
                // tap-FAB doesn't inherit the long-press
                // override and land on Photo by accident.
                pendingInitialMode = null
            },
            initialMode  = pendingInitialMode,
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
        if (route == Routes.HOME) {
            // HOME is the start destination, always at the back-stack root.
            // navigate(HOME) with restoreState=true restores HOME's saved
            // nested chain (left over from popUpTo HOME saveState=true on
            // earlier tab-switches), which can drop the user back onto the
            // saved Workspace/Search/Settings child instead of HOME itself.
            // Pop back to HOME instead — same visual result, no state to
            // restore.
            navController.popBackStack(Routes.HOME, inclusive = false)
        } else {
            navController.navigate(route) {
                popUpTo(Routes.HOME) {
                    saveState = true
                    inclusive = false
                }
                launchSingleTop = true
                restoreState    = true
            }
        }
    }

    // Workspace v1 (Phase B) — when the feature flag is on, the
    // bottom-nav "Workspace" tab routes to the new WorkspaceHomeScreen.
    // Workspace v1 is the canonical home for the Library / Workspace
    // tab on every install — the rollout flag was retired alongside
    // the GA flip. `NotesListScreen` stays reachable via deep links
    // and the search timeline, but the bottom-nav tab always lands
    // here now.
    val workspaceTabRoute = Routes.WORKSPACE_HOME

    // Location cache (`DaylightLocationStore`) that the Home tab's
    // `DaylightHero` reads for its sunrise/sunset numbers. The store
    // is seeded from SharedPreferences on construction so the hero
    // paints on cold launch; the LaunchedEffect below refreshes the
    // cache after the location-permission flow above has settled.
    val daylightStore = remember(context.applicationContext) {
        DaylightLocationStore(context.applicationContext)
    }
    LaunchedEffect(Unit) {
        daylightStore.refreshIfNeeded()
    }

    // Drive both the top time-bar visibility and the bottom-nav active
    // tab from the current NavHost destination. Reading via
    // `currentBackStackEntryAsState()` re-triggers recomposition on
    // every nav transition so both pieces stay in sync without per-
    // screen plumbing.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val onHome = currentRoute == Routes.HOME
    // Hide the global time bar on the scan-detail viewer — that
    // screen renders its own auto-hide-on-scroll variant so the
    // preview surface stays dominant. Route template carries the
    // {captureId} placeholder.
    val onScanDetail = currentRoute?.startsWith("scan_detail") == true

    // App-exit confirmation. Fires whenever a back-press would leave
    // QuickInk altogether — i.e., the NavHost has nothing left to
    // pop. The handler is disabled while the scan flow is active
    // (ScanCaptureSurface owns back via controller.dismiss) and
    // while we're not signed in (the gate handles its own back).
    var showExitDialog by remember { mutableStateOf(false) }
    val canExitTriggerDialog =
        scanState is ScanFlowController.State.Idle &&
        navController.previousBackStackEntry == null
    androidx.activity.compose.BackHandler(enabled = canExitTriggerDialog) {
        showExitDialog = true
    }
    if (showExitDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { androidx.compose.material3.Text("Exit QuickInk?") },
            text  = {
                androidx.compose.material3.Text(
                    "You'll leave the app. Captures already saved remain on this device."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitDialog = false
                    (context as? android.app.Activity)?.finish()
                }) {
                    androidx.compose.material3.Text("Exit")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitDialog = false
                }) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
        )
    }

    // Map the route to the bottom-nav slot. `null` hides the bar
    // entirely (Calendar, Profile, Categories, NoteEditor,
    // CategoryEntries — modal-ish surfaces that own the whole canvas).
    // `NavTab.None` keeps the bar visible without painting any active
    // pill (ScanDetail — a pushed surface that still wants the global
    // chrome). The pushed Workspace children (Folder / Smart /
    // TagLibrary) keep the Workspace pill active so the user knows
    // they're inside that tab's tree.
    val activeTab: NavTab? = when (currentRoute) {
        Routes.HOME              -> NavTab.Home
        Routes.WORKSPACE_HOME,
        Routes.NOTES_LIST,
        Routes.FOLDER_DETAIL,
        Routes.SMART_COLLECTION,
        Routes.TAG_LIBRARY,
        Routes.LOCATION_DETAIL,
        Routes.PERSON_DETAIL     -> NavTab.Workspace
        Routes.STORIES,
        Routes.STORY_EDITOR,
        Routes.STORY_READER,
        Routes.STORY_SUGGESTION_PREVIEW -> NavTab.Stories
        // Search no longer owns a bottom-nav slot — Stories replaced
        // it. Search is still reachable via the Home top-bar +
        // Workspace `onOpenSearch` callbacks; when it's the current
        // route we paint the bar with no active pill.
        Routes.SEARCH            -> NavTab.None
        Routes.SETTINGS          -> NavTab.Settings
        Routes.SCAN_DETAIL       -> NavTab.None
        else                     -> null
    }

    // Root is a Box so the bottom nav can hover above the NavHost
    // surface as a sibling layer — moved out of every per-screen
    // composable so a tab transition no longer crossfades two bars
    // (which used to produce the "masked footer" during a switch).
    // The Column inside still owns the time bar + NavHost stack;
    // AnimatedVisibility on the bar replaces the instant insert/
    // remove that previously jumped vertical space at the moment
    // screens were already mid-crossfade.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !onHome && !onScanDetail,
                enter   = fadeIn() + expandVertically(),
                exit    = fadeOut() + shrinkVertically(),
            ) {
                QuickInkTimeBar()
            }
            NavHost(
                navController     = navController,
                startDestination  = Routes.HOME,
                modifier          = Modifier.weight(1f).fillMaxWidth(),
            ) {
        composable(Routes.HOME) {
            HomeScreen(
                controller     = controller,
                userId         = userId,
                onOpenNotes    = { navToTab(workspaceTabRoute) },
                onOpenSettings = { navToTab(Routes.SETTINGS) },
                onOpenSearch   = { navToTab(Routes.SEARCH) },
                onOpenEntry    = { entryId ->
                    navController.navigate(Routes.noteEditor(entryId))
                },
                onOpenScan     = { captureId ->
                    navController.navigate(Routes.scanDetail(captureId))
                },
                onOpenProfile  = { navController.navigate(Routes.PROFILE) },
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onSignOut      = { app.authStore.signOut() },
                email          = (authStateForName as? AuthState.SignedIn)?.session?.email.orEmpty(),
                displayName    = resolvedDisplayName,
                profilePhotoUri   = profilePhotoUri,
                daylightLatitude  = daylightStore.latitude,
                daylightLongitude = daylightStore.longitude,
            )
        }
        composable(Routes.CALENDAR) {
            CalendarScreen(
                userId        = userId,
                onBack        = { navController.popBackStack() },
                onOpenCapture = { captureId ->
                    // Replace the calendar step with the detail so popping
                    // back from detail returns to Home, not the calendar —
                    // matches the search → result idiom.
                    navController.popBackStack()
                    navController.navigate(Routes.scanDetail(captureId))
                },
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
            )
        }
        composable(Routes.STORIES) {
            StoriesShelfScreen(
                userId                  = userId,
                onOpenStory             = { storyId ->
                    navController.navigate(Routes.storyEditor(storyId))
                },
                onOpenSuggestionPreview = { id ->
                    navController.navigate(Routes.storySuggestionPreview(id))
                },
            )
        }
        composable(
            route     = Routes.STORY_SUGGESTION_PREVIEW,
            arguments = listOf(navArgument("suggestionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("suggestionId").orEmpty()
            StorySuggestionPreviewScreen(
                suggestionId = id,
                userId       = userId,
                onBack       = { navController.popBackStack() },
                onOpenStory  = { storyId ->
                    // Replace the preview step with the editor so a
                    // back-press from the editor returns to the shelf,
                    // not back to the preview.
                    navController.popBackStack()
                    navController.navigate(Routes.storyEditor(storyId))
                },
            )
        }
        composable(
            route     = Routes.STORY_EDITOR,
            arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val storyId = backStackEntry.arguments?.getString("storyId").orEmpty()
            StoryEditorScreen(
                storyId    = storyId,
                userId     = userId,
                onBack     = { navController.popBackStack() },
                onPreview  = { navController.navigate(Routes.storyReader(storyId)) },
                onRequestCapture = { _, onCompleted ->
                    // Android currently has only Document scanning,
                    // so we don't switch modes here; the user lands on
                    // whatever their pill-saved last mode is. Set the
                    // one-shot completion hook on the controller and
                    // raise the root-level QuickCapture flag.
                    controller.nextCompletionHandler = onCompleted
                    showQuickCapture = true
                },
            )
        }
        composable(
            route     = Routes.STORY_READER,
            arguments = listOf(navArgument("storyId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val storyId = backStackEntry.arguments?.getString("storyId").orEmpty()
            StoryReaderScreen(
                storyId = storyId,
                userId  = userId,
                onBack  = { navController.popBackStack() },
            )
        }
        composable(Routes.NOTES_LIST) {
            NotesListScreen(
                userId     = userId,
                onOpenScan = { captureId ->
                    navController.navigate(Routes.scanDetail(captureId))
                },
            )
        }
        composable(Routes.WORKSPACE_HOME) {
            WorkspaceHomeScreen(
                userId         = userId,
                onOpenSearch   = { navToTab(Routes.SEARCH) },
                onOpenFolder   = { folder ->
                    navController.navigate(Routes.folderDetail(folder.id))
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
                onOpenSmartCollection = { collection ->
                    navController.navigate(Routes.smartCollection(collection.id))
                },
                onOpenLocation = { location ->
                    navController.navigate(Routes.locationDetail(location.id))
                },
                onOpenPerson = { person ->
                    navController.navigate(Routes.personDetail(person.id))
                },
                onBrowseTags = { navController.navigate(Routes.TAG_LIBRARY) },
            )
        }
        composable(
            route     = Routes.FOLDER_DETAIL,
            arguments = listOf(navArgument("folderId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getString("folderId").orEmpty()
            FolderDetailScreen(
                folderId      = folderId,
                userId        = userId,
                onBack        = { navController.popBackStack() },
                onOpenCapture = { capture ->
                    navController.navigate(Routes.scanDetail(capture.id))
                },
                onOpenSearch  = { navToTab(Routes.SEARCH) },
            )
        }
        composable(
            route     = Routes.LOCATION_DETAIL,
            arguments = listOf(navArgument("locationId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val locationId = backStackEntry.arguments?.getString("locationId").orEmpty()
            LocationDetailScreen(
                locationId    = locationId,
                userId        = userId,
                onBack        = { navController.popBackStack() },
                onOpenCapture = { capture ->
                    navController.navigate(Routes.scanDetail(capture.id))
                },
            )
        }
        composable(
            route     = Routes.PERSON_DETAIL,
            arguments = listOf(navArgument("personId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val personId = backStackEntry.arguments?.getString("personId").orEmpty()
            PersonDetailScreen(
                personId      = personId,
                userId        = userId,
                onBack        = { navController.popBackStack() },
                onOpenCapture = { capture ->
                    navController.navigate(Routes.scanDetail(capture.id))
                },
            )
        }
        composable(
            route     = Routes.SMART_COLLECTION,
            arguments = listOf(navArgument("collectionId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val collectionId = backStackEntry.arguments?.getString("collectionId").orEmpty()
            SmartCollectionScreen(
                collectionId  = collectionId,
                userId        = userId,
                onBack        = { navController.popBackStack() },
                onOpenCapture = { capture ->
                    navController.navigate(Routes.scanDetail(capture.id))
                },
                onOpenSearch  = { navToTab(Routes.SEARCH) },
            )
        }
        composable(Routes.TAG_LIBRARY) {
            TagLibraryScreen(
                userId       = userId,
                onBack       = { navController.popBackStack() },
                onOpenTag    = { tag ->
                    navController.navigate(Routes.categoryEntries(tag.name))
                },
                onOpenSearch = { navToTab(Routes.SEARCH) },
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
        }   // closes NavHost
        }   // closes inner Column
        // Bottom nav sits as a sibling layer over the Column, anchored
        // to the bottom. Painted once at the root so it stays put
        // through every NavHost transition — the crossfade beneath
        // only swaps screen content, not the chrome.
        activeTab?.let { tab ->
            QuickInkBottomNavBar(
                activeTab   = tab,
                onHome      = { navToTab(Routes.HOME) },
                onWorkspace = { navToTab(workspaceTabRoute) },
                onScan      = { showQuickCapture = true },
                onStories   = { navToTab(Routes.STORIES) },
                onSettings  = { navToTab(Routes.SETTINGS) },
                modifier    = Modifier.align(Alignment.BottomCenter),
                // Long-press jumps the FAB directly into the photo
                // surface. Also marks the FAB hint dismissed — the
                // user has discovered the gesture, no need to
                // surface the chip ever again. Routed through both
                // the persisted flag and the in-memory state so
                // the chip's fade-out fires on the same render tick.
                onLongPressScan = {
                    PhotoFabHint.markDismissed(photoFabHintContext)
                    photoFabHintDismissed = true
                    pendingInitialMode = CaptureMode.Photo
                    showQuickCapture = true
                },
                showPhotoHint = !photoFabHintDismissed,
            )
        }
    }   // closes outer Box
}
