/*
 * QuickInkRoot.swift
 *
 * QuickInk's top-level SwiftUI view. The eventual Xcode app target
 * (with bundle ID `app.quickink.mobile`) will host this in a
 * `WindowGroup` from its `@main App` struct — same arrangement Releaf
 * uses for its app shell vs. the Features library.
 *
 * Phase 3 scaffold: this is the placeholder root that proves the
 * package compiles, links against `ReleafCore`, and renders a
 * brand-token-aware screen. The MVP navigation graph
 * (Onboarding → Camera-first Home → Scan + OCR → Notes list → Editor)
 * lands incrementally on top of this surface — see QUICKINK_PROPOSAL.md
 * §6.4 for the screen list.
 *
 * The placeholder body intentionally exercises shared design tokens
 * (`AppColors.canvas`, `AppText.pageTitle`, `AppSpacing.s4`) so a
 * regression in the `ReleafCoreDesignSystem` ↔ QuickInk wiring shows
 * up at first build rather than at MVP-feature time.
 */

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
import ReleafCoreAuth
import ReleafCoreDesignSystem

@MainActor
public struct QuickInkRoot: View {

    /// Onboarding gate. Persisted in `UserDefaults`; flipped to
    /// `true` by the sign-in screen on the user's first run.
    @State private var onboardingCompleted: Bool = OnboardingState.isCompleted()

    /// Process-scoped `AuthStore`. Constructed via the factory in
    /// `QuickInkAuthBinding.swift` — stub today, ready for the
    /// real-client swap once the Xcode app target lands. Held as
    /// `@StateObject` so its `@Published state` drives recompositions
    /// in this view.
    @StateObject private var authStore: AuthStore = makeQuickInkAuthStore()

    /// Scene-phase observation drives the foreground-only
    /// pending-push loop in `QuickInkSyncEnvironment`. The loop
    /// polls dirty rows every 60s while the app is `.active` and
    /// stops cleanly when it goes `.background` so we don't burn
    /// battery / cellular when the user isn't looking at the app.
    @Environment(\.scenePhase) private var scenePhase

    /// Splash gate — when `true`, the launch animation owns the
    /// screen and the routing below is paused. Flipped to `false`
    /// once `LaunchAnimationView` reports the cinematic finished
    /// (or its safety timeout fires). Mirror of Android's
    /// `showSplash` `mutableStateOf` in `MainActivity.kt`. Defaults
    /// to `true` so the splash always shows on cold launch; the
    /// animation view itself decides whether to play the bundled
    /// Lottie cinematic or fall through to the minimal-mark splash.
    @State private var showLaunchAnimation: Bool = true

    public init() {}

    public var body: some View {
        Group {
            if showLaunchAnimation {
                // Read the user's last-known Tree-points balance
                // (written by HomeScreen on every total-page-count
                // observation push) so the cinematic counter pill
                // ticks up to the user's actual current value rather
                // than a hardcoded preview default. Defaults to 0 on
                // a fresh install / first launch — the counter then
                // doesn't tick, which is the correct empty-state read.
                LaunchAnimationView(
                    target: SettingsState.cachedTreePoints,
                    onFinished: {
                        // Slight crossfade so the splash → home
                        // handoff matches the README's "fast-skip"
                        // behaviour ("crossfade the splash out over
                        // ~250ms to whatever screen comes next").
                        // The host swap is the moment that crossfade
                        // fires.
                        withAnimation(.easeInOut(duration: 0.25)) {
                            showLaunchAnimation = false
                        }
                    }
                )
            } else if !onboardingCompleted {
                // First-time users — full 3-screen onboarding.
                OnboardingFlow(
                    authStore:  authStore,
                    onComplete: { onboardingCompleted = true }
                )
            } else {
                switch authStore.state {
                case .signedIn(let session):
                    MainShell(userId: session.userId, authStore: authStore)
                default:
                    // Onboarding done but no active session — Option A:
                    // bounce to the SignIn screen only (skip welcome +
                    // permissions). Persisted Drive choice from earlier
                    // onboarding stays valid; toggling it here overwrites
                    // Settings, same as the first-run flow.
                    ReSignInGate(authStore: authStore)
                }
            }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active:
                QuickInkSyncEnvironment.shared.startPendingPushLoop()
            case .background, .inactive:
                QuickInkSyncEnvironment.shared.stopPendingPushLoop()
            @unknown default:
                break
            }
        }
        // Analytics outbox: enqueue an `/v1/identify` event whenever
        // auth resolves to .signedIn. Tracks the last-identified
        // userId so a transient re-emission (token refresh, observer
        // rebind) doesn't spam duplicates. Reset on .signedOut.
        // Settings: drop identity-leaking overrides on sign-out so
        // the next account on the same device doesn't inherit the
        // previous user's display name / phone / photo / punchline /
        // search MRU. Theme + Drive backup + experimental flags are
        // device-level and intentionally preserved.
        .onChange(of: authStore.state) { newState in
            handleAuthStateForAnalytics(newState)
            handleAuthStateForSettings(newState)
        }
        // Hide the iOS status bar app-wide. SwiftUI's
        // `.statusBarHidden` only reaches descendents that don't
        // wrap themselves in their own UIKit hosting controller —
        // every screen below this point is a plain SwiftUI surface,
        // so a single root-level modifier is enough. The Info.plist
        // toggles below let the splash window itself also launch
        // status-bar-hidden so we don't see a cream→hidden flicker
        // on cold start.
        .statusBarHidden(true)
    }

    /// Drop identity-leaking SettingsState overrides on sign-out so
    /// the next account on the same device doesn't inherit the
    /// previous user's custom photo / display name / phone /
    /// punchline. Theme + Drive-backup + experimental-flag prefs
    /// are device-level and intentionally preserved. Mirror of
    /// Android `SettingsPreferences.clearAllUserOverrides`.
    private func handleAuthStateForSettings(_ state: AuthStore.State) {
        if case .signedOut = state {
            SettingsState.clearAllUserOverrides()
        }
    }

    /// Set of userIds for which an identify has been enqueued in
    /// THIS process. State is per-instance so SwiftUI previews
    /// don't accidentally mutate it.
    @State private var lastIdentifiedUserId: String? = nil

    private func handleAuthStateForAnalytics(_ state: AuthStore.State) {
        guard AnalyticsFlushTask.isEnabled else { return }

        switch state {
        case .signedIn(let session):
            if lastIdentifiedUserId == session.userId { return }
            lastIdentifiedUserId = session.userId

            let appVersion = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
            Task.detached(priority: .background) {
                do {
                    try await AnalyticsRepository(
                        dbQueue: QuickInkDatabase.shared.dbQueue
                    ).enqueueIdentify(
                        deviceOs:   "ios",
                        appVersion: appVersion
                    )
                    AnalyticsFlushTask.requestImmediate()
                } catch {
                    NSLog("[analytics] enqueueIdentify failed: %@", "\(error)")
                }
            }
        case .signedOut:
            lastIdentifiedUserId = nil
        default:
            break
        }
    }
}

/// Standalone re-sign-in surface — renders only the onboarding
/// `SignInScreen` (no welcome, no permissions). Shared with the
/// full onboarding flow's third step; on success, `authStore.state`
/// transitions to `.signedIn` and `QuickInkRoot`'s `switch` flips
/// automatically to `MainShell`.
///
/// Slice 4.3 — the OnboardingState seed reads the user's persisted
/// Drive choice from the SettingsState keyspace so the toggle in
/// the gate reflects current state, not the always-true default.
/// SignInScreen's `commitChoices()` writes the (possibly toggled)
/// value back through `SettingsState.commitOnboardingChoices` on
/// successful re-sign-in, so the gate behaves the same as the
/// first-run flow per the file header's promise.
private struct ReSignInGate: View {
    @ObservedObject var authStore: AuthStore
    @StateObject private var state: OnboardingState

    init(authStore: AuthStore) {
        self.authStore = authStore
        let seed = OnboardingState()
        // Seed from the persisted Settings keyspace.
        // `SettingsState.commitOnboardingChoices` is the writer;
        // here we mirror its UserDefaults key so the read stays in
        // sync without forcing SettingsState to expose a getter
        // for a single bool. `quickink.settings.drive_backup_enabled`
        // is the same key SettingsState uses internally — a single
        // grep refactor when/if that key moves.
        let defaults = UserDefaults.standard
        let key = "quickink.settings.drive_backup_enabled"
        let current: Bool = {
            guard defaults.object(forKey: key) != nil else { return true }
            return defaults.bool(forKey: key)
        }()
        seed.driveBackupEnabled = current
        _state = StateObject(wrappedValue: seed)
    }

    var body: some View {
        SignInScreen(
            state:      state,
            authStore:  authStore,
            // No-op — `QuickInkRoot` reads the AuthStore state
            // directly and routes to MainShell on its own. The
            // SignInScreen's internal `commitChoices()` already
            // writes the toggle through to SettingsState on a
            // successful sign-in, so the user's gate-time choice
            // persists either way.
            onSignedIn: {}
        )
    }
}

/// Holds the `ScanFlowController` for the lifetime of the main
/// shell so its state survives recompositions inside the
/// `signedIn` branch. `userId` comes from the active session
/// (Slice 4.1). The shell is only constructed when AuthStore is
/// in `.signedIn` state, so userId is always real.
///
/// Slice 6 routing: NavigationStack with typed `Route` values
/// pushed onto a path binding. Home sits at the stack's root;
/// Notes / NoteEditor / Settings push on top. The scan flow
/// preempts the entire NavigationStack — when the controller is
/// non-idle, ScanReviewScreen replaces the stack until the user
/// dismisses, after which the prior path is restored.
private struct MainShell: View {

    /// Typed routes pushed on the NavigationStack path. Home is
    /// the root; everything else is a destination.
    enum Route: Hashable {
        case notesList
        case noteEditor(entryId: String)
        case settings
        case profile
        case search
        case stories
        case storyEditor(storyId: String)
        case storyReader(storyId: String)
        case storySuggestionPreview(suggestionId: String)
        case manageCategories
        case scanDetail(captureId: String)
        // Per-category browse — pushed from the Home category grid.
        // Carries the canonical category name; the screen filters
        // entries case-insensitively against `entry.category`.
        case categoryEntries(name: String)
        // Workspace v1 — canonical destination of the bottom-nav
        // Workspace tab post-GA. The legacy rollout flag was
        // retired alongside the flip.
        case workspaceHome
        case folderDetail(folderId: String)
        case smartCollection(collectionId: String)
        case tagLibrary
        // Standalone Calendar surface — pushed from the Home header's
        // calendar button. Combines the bundled Vontikoppal panchanga
        // (festival / moon / Rahu Kala) with QuickInk capture dots so
        // users can browse their scans by date alongside the lunar
        // calendar.
        case calendar
    }

    /// Canonical Workspace bottom-nav destination.
    private let workspaceTabRoute: Route = .workspaceHome

    let userId: String
    @ObservedObject var authStore: AuthStore

    @StateObject private var controller: ScanFlowController
    /// Process-stable so a Settings edit to `customDisplayName`
    /// re-renders the Home greeting reactively without round-tripping
    /// through UserDefaults observers.
    @StateObject private var settings = SettingsState()
    /// Lat/long cache the Home `DaylightHero` reads its sunrise/
    /// sunset numbers from. Seeded from UserDefaults so the hero
    /// paints on cold launch; refreshed once permission is granted
    /// via a non-prompting `refreshIfNeeded` call on `.task` + on
    /// every `scenePhase == .active` transition. See
    /// `Calendar/DaylightLocationStore.swift` for the caching rules.
    @StateObject private var daylightLocation = DaylightLocationStore()
    @State private var path: [Route] = []
    /// Lifted out of HomeScreen so the ⚡ FAB on Library / Search /
    /// Settings can also present the QuickCapture sheet without
    /// hopping back to Home first. HomeScreen still owns its own
    /// local `showQuickCapture` for its FAB on its own surface; both
    /// routes present the same `QuickCaptureScreen`, just from
    /// different ownership layers.
    @State private var showQuickCapture = false

    /// Optional override for QuickCaptureScreen's starting mode.
    /// Tap on the FAB leaves this `nil` so the screen reads its
    /// usual `quickink.capture.last_mode` default. Long-press on
    /// the FAB sets `.photo`, which routes the user straight into
    /// the photo surface — the screen's no-op persist on the
    /// `.photo` branch prevents this transient choice from
    /// overwriting the user's pill-selected last mode. Cleared
    /// to `nil` on dismiss so a subsequent tap-FAB doesn't
    /// inherit the override.
    @State private var pendingInitialMode: CaptureMode? = nil

    /// Photo-FAB hint state. Owns the single persisted `dismissed`
    /// flag that gates the "Hold ⚡ for a quick photo" chip above
    /// the ⚡ FAB. Routed through a `@StateObject` (rather than
    /// `@AppStorage`) because `@AppStorage` doesn't reliably re-
    /// render when the underlying UserDefaults key is written via
    /// a direct `UserDefaults.standard.set(...)` call — the chip
    /// would otherwise stay stuck on the previous value until the
    /// next unrelated state change nudged the view tree. The chip
    /// shows on every launch until the user long-presses once,
    /// after which it stays dismissed permanently.
    @StateObject private var photoFabHint = PhotoFabHint()

    /// Derived gate for the bar's `showPhotoHint` prop.
    private var showPhotoFabHint: Bool {
        !photoFabHint.dismissed
    }

    /// Tab-style switch between top-level destinations (Library,
    /// Search, Settings). Replaces the nav stack with a single entry,
    /// so back from any tab returns to Home — matches the standard
    /// bottom-tab UX. Calling with the route the user is already on
    /// is a no-op caller-side (the bar's per-tab callback short-
    /// circuits to `{ }`).
    private func navToTab(_ route: Route) {
        path = [route]
    }

    /// Bottom-nav slot derived from the current path. `nil` hides the
    /// bar entirely (Calendar, Profile, ManageCategories, NoteEditor,
    /// CategoryEntries — modal-ish surfaces that own the canvas).
    /// `.none` keeps the bar painted with no active pill on pushed
    /// detail screens (ScanDetail). The pushed Workspace children
    /// (FolderDetail / SmartCollection / TagLibrary / NotesList) keep
    /// the Workspace pill so the user knows which tree they're in.
    private var activeTab: NavTab? {
        guard let last = path.last else { return NavTab.home }
        switch last {
        case .workspaceHome, .notesList, .folderDetail, .smartCollection, .tagLibrary:
            return NavTab.workspace
        case .stories, .storyEditor, .storyReader,
             .storySuggestionPreview:                                return NavTab.stories
        // Search no longer owns a bottom-nav slot — Stories replaced
        // it. Search is still reachable from the Home top-bar + the
        // Workspace `onOpenSearch` callbacks; when it's on top of the
        // stack we paint the bar with no active pill.
        case .search:                                                return NavTab.none
        case .settings:                                              return NavTab.settings
        case .scanDetail:                                            return NavTab.none
        case .calendar, .profile, .manageCategories,
             .noteEditor, .categoryEntries:                          return nil
        }
    }

    /// Whether the global time bar at the top of the screen should
    /// be visible. Hidden on Home (which already has its own status
    /// strip) and on the ScanDetail viewer (which renders its own
    /// auto-hide-on-scroll variant so the preview surface stays
    /// dominant).
    private var showsTimeBar: Bool {
        guard let last = path.last else { return false }
        if case .scanDetail = last { return false }
        return true
    }

    /// Settings override > Google session displayName > nil.
    /// The home screen falls back to "QuickInk" when this is nil.
    private var resolvedDisplayName: String? {
        let custom = settings.customDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !custom.isEmpty { return custom }
        if case .signedIn(let session) = authStore.state {
            let google = session.displayName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !google.isEmpty { return google }
        }
        return nil
    }

    init(userId: String, authStore: AuthStore) {
        self.userId = userId
        self.authStore = authStore
        // Drive sync is user-initiated only — no auto-kick after
        // a scan. The user taps Settings → "Sync now" when they
        // want their captures pushed to Drive.
        //
        // Analytics outbox runs in parallel (different cadence,
        // different failure mode, parallel pipeline) and enqueues
        // a capture event the moment the pass completes. The flush
        // task itself is gated by Info.plist `AnalyticsEnabled`,
        // so the enqueue is cheap even when the flag is off.
        _controller = StateObject(wrappedValue: ScanFlowController(
            userId: userId,
            onPassComplete: { summary in
                Task.detached(priority: .background) {
                    do {
                        try await AnalyticsRepository(
                            dbQueue: QuickInkDatabase.shared.dbQueue
                        ).enqueueCapture(
                            captureId:  summary.captureId,
                            source:     summary.source,
                            pageCount:  summary.pageCount,
                            // Post-A.3c the `captures.category`
                            // column is gone; the analytics
                            // server-side `category` slot stays
                            // for back-compat but new captures
                            // emit nil. Once the server schema
                            // drops the field this column folds
                            // out of the outbox row too.
                            category:   nil,
                            hasOcr:     summary.hasOcr,
                            ocrChars:   summary.ocrChars,
                            capturedAt: summary.capturedAt
                        )
                        AnalyticsFlushTask.requestImmediate()
                    } catch {
                        NSLog("[analytics] enqueueCapture failed: %@", "\(error)")
                    }
                }
            }
        ))
    }

    var body: some View {
        // The scan flow takes over the shell whenever its
        // controller is non-idle, regardless of where the user
        // was in the navigation stack — OCR is an interruption
        // that should land them on the review surface. When the
        // user dismisses, `path` is preserved (still @State on
        // MainShell), so they return to wherever they were.
        //
        // The time bar wraps only the .idle navigation surfaces —
        // full-screen task surfaces (ScanReviewScreen, the
        // QuickCaptureScreen fullScreenCover below) intentionally
        // take over the whole screen, including the bar's slot, so
        // OCR review + capture remain immersive.
        Group {
            switch controller.state {
            case .recognizing, .complete, .failed:
                ScanCaptureSurface(controller: controller, userId: userId)
            case .idle:
                VStack(spacing: 0) {
                    // Time bar hides on Home (which has its own
                    // status strip) and on ScanDetail (which renders
                    // its own auto-hide-on-scroll variant inline).
                    //
                    // Wrap the conditional in a Group with its own
                    // `.animation(_, value:)` so the bar slides in/out
                    // independently of the NavigationStack's screen
                    // transitions. An outer-VStack animation modifier
                    // was getting overridden by NavigationStack's own
                    // animation context and the bar popped abruptly.
                    Group {
                        if showsTimeBar {
                            QuickInkTimeBar()
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                    .animation(.easeInOut(duration: 0.22), value: showsTimeBar)
                    NavigationStack(path: $path) {
                    HomeScreen(
                        controller:     controller,
                        userId:         userId,
                        onOpenNotes:    { path.append(workspaceTabRoute) },
                        onOpenSettings: { path.append(.settings) },
                        onOpenSearch:   { path.append(.search) },
                        onOpenEntry:    { entryId in path.append(.noteEditor(entryId: entryId)) },
                        onOpenScan:     { captureId in path.append(.scanDetail(captureId: captureId)) },
                        onOpenProfile:  { path.append(.profile) },
                        onOpenCalendar: { path.append(.calendar) },
                        onSignOut:      { Task { await authStore.signOut() } },
                        displayName:    resolvedDisplayName,
                        email: {
                            if case .signedIn(let s) = authStore.state { return s.email }
                            return ""
                        }(),
                        profilePhotoUri:   settings.profilePhotoUri,
                        daylightLatitude:  daylightLocation.latitude,
                        daylightLongitude: daylightLocation.longitude
                    )
                    .navigationBarBackButtonHidden(true)
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        destination(for: route)
                    }
                }
                // Bottom nav attaches to the NavigationStack via
                // `safeAreaInset`, not to each screen's body — so a
                // push / pop slides the screen content underneath
                // while the bar stays stationary. Eliminates the
                // double-footer crossfade the per-screen attachment
                // used to produce, and reserves a stable safe-area
                // for scroll content inside every destination.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if let tab = activeTab {
                        QuickInkBottomNavBar(
                            activeTab:       tab,
                            onHome:          { path.removeAll() },
                            onWorkspace:     { navToTab(workspaceTabRoute) },
                            onScan:          { showQuickCapture = true },
                            // Long-press jumps the FAB directly into
                            // the photo surface. Setting both the
                            // pending mode AND the cover flag in the
                            // same tick guarantees the fullScreenCover
                            // body re-runs with `pendingInitialMode`
                            // resolved before SwiftUI evaluates the
                            // `QuickCaptureScreen` init. Also marks
                            // the FAB hint dismissed — the user has
                            // discovered the gesture, no need to
                            // surface the chip ever again. Routed
                            // through the @StateObject so the chip's
                            // fade-out fires on the same render tick.
                            onLongPressScan: {
                                photoFabHint.markDismissed()
                                pendingInitialMode = .photo
                                showQuickCapture = true
                            },
                            onStories:       { navToTab(.stories) },
                            onSettings:      { navToTab(.settings) },
                            showPhotoHint:   showPhotoFabHint
                        )
                    }
                }
                }   // closes VStack opened at the top of `case .idle:`
            }
        }
        .task(id: userId) {
            // Idempotent first-launch / first-sign-in seed of the
            // default 6 categories. Skipped for users who already
            // have rows (e.g. on second launch). Picker chips in
            // ScanReviewScreen + the Settings → Categories list
            // both observe the same table — a freshly-seeded user
            // sees the chips on the very next scan.
            let categoryRepo = TagRepository()
            try? await categoryRepo.seedDefaultsIfEmpty(userId: userId)
            // One-shot migration for users on the previous seed
            // that included "Study". Idempotent + flag-guarded;
            // safe to call on every launch.
            try? await categoryRepo.migrateLegacyStudyToBusinessCardIfNeeded(userId: userId)
            // One-shot migration that lowercases + kebab-cases the
            // legacy capitalized seed names so existing users align
            // with the new canonical form. Idempotent + flag-guarded.
            try? await categoryRepo.migrateLegacySeedNamesToKebabIfNeeded(userId: userId)

            // Workspace v1 first-launch migration — seed Unfiled
            // folder + backfill every capture's folder_id. The
            // legacy `captures.category` → `capture_tags`
            // materialize step shipped in v8 and the column drop
            // shipped in v9; both now run inside the GRDB
            // migration script. Each app-side step is idempotent
            // via UserDefaults guards so on-every-launch invocation
            // is safe.
            let folderRepo = FolderRepository()
            try? await folderRepo.runFirstLaunchMigrationIfNeeded(userId: userId)

            // Workspace v1 Phase C.3 — seed "Needs review" smart
            // collection. Depends on the #needs-review tag landing
            // via TagRepository.defaultSeed (above). Idempotent
            // via is_seeded.
            let smartRepo = SmartCollectionRepository()
            try? await smartRepo.seedDefaultsIfNeeded(userId: userId)

            // Stories Phase 1 — debug-only dev seeder so the Stories
            // tab has cards to render in QA builds. Idempotent: skips
            // when the user already has any active stories. Stripped
            // out of release builds by `#if DEBUG`. See
            // `design/STORIES_HANDOFF.md` §4 Phase 1 task 1.8.
            #if DEBUG
            try? await StoryRepository().seedDevStoriesIfEmpty(userId: userId)
            #endif
            // One-shot post-onboarding location-permission ask.
            // Existing users who completed onboarding before the
            // Location step shipped (Phase 7) would otherwise never
            // see the system dialog — onboarding's `isCompleted`
            // gate skips the whole flow. This task brings up the
            // dialog directly on first launch after the upgrade,
            // then sets a flag so we never re-ask without a
            // Settings flip. New users hit the flag inside
            // `LocationPermissionScreen` so this branch no-ops for
            // them.
            await Self.requestLocationIfNeeded(settings: settings)

            // Refresh the daylight-bar location cache once permission
            // is settled. No-op when permission is denied or the cache
            // is still fresh (<24h); see DaylightLocationStore for the
            // staleness + de-dup rules. Runs after
            // `requestLocationIfNeeded` so a brand-new user grants
            // permission first, fetch second.
            daylightLocation.refreshIfNeeded()
        }
        // Quick-capture modal lifted from HomeScreen so the ⚡ FAB
        // on Library / Search / Settings can present it too. Same
        // QuickCaptureScreen the Home FAB shows.
        .fullScreenCover(isPresented: $showQuickCapture) {
            QuickCaptureScreen(
                controller:  controller,
                userId:      userId,
                initialMode: pendingInitialMode,
                onDismiss:   {
                    showQuickCapture = false
                    // Reset to nil on dismiss so a subsequent
                    // tap-FAB doesn't inherit the long-press
                    // override and land on Photo by accident.
                    pendingInitialMode = nil
                }
            )
        }
        // Appearance overrides — `preferredColorScheme(nil)` lets
        // the OS decide; passing .light / .dark forces the override
        // for every descendent SwiftUI view. `tint` paints all
        // system controls (Toggle, Button, NavigationLink chevrons,
        // etc.) with the picked primary's resolved variant so the
        // picker's effect propagates without each screen needing an
        // environment lookup.
        .preferredColorScheme(settings.themeMode.colorScheme)
        .tint(resolvedAccent)
        // ── UIWindow override ───────────────────────────────────
        // `preferredColorScheme` modifies SwiftUI's environment but
        // does NOT propagate down into UIKit's UITraitCollection —
        // and our dynamic colors (`Color(uiColor: UIColor { trait
        // in ... })`) resolve through UIKit. Without forcing the
        // window's `overrideUserInterfaceStyle`, the picker's
        // Light / Dark choice would only flip SwiftUI primitives
        // (Toggle / NavigationLink chrome) while every QuickInk
        // color stayed on the OS's trait. Apply at first appear +
        // on every change.
        .task(id: settings.themeMode) {
            applyWindowInterfaceStyle(settings.themeMode)
        }
    }

    /// Push the picked theme down into UIKit by mutating every
    /// connected scene's `overrideUserInterfaceStyle`. This is what
    /// `UIColor(dynamicProvider:)` reads — without it, the SwiftUI-
    /// level `.preferredColorScheme()` is invisible to our dynamic
    /// colors.
    /// One-shot location-permission ask invoked from the MainShell's
    /// `.task`. Only fires for users who haven't been through the
    /// onboarding Location step yet AND have the Settings toggle on
    /// AND haven't been asked at the system level. Once we ask
    /// (regardless of outcome) we mark the flag so we don't re-ask
    /// on every launch — the user can change their mind via the
    /// system Settings app or the in-app Location toggle.
    private static func requestLocationIfNeeded(settings: SettingsState) async {
        if LocationService.wasPromptHandled { return }
        guard settings.locationForScansEnabled else {
            // Toggle is off — no point asking. Mark handled so the
            // user can flip the toggle later and trigger a fresh
            // ask through a future Settings-toggle-on path.
            LocationService.markPromptHandled()
            return
        }
        let status = LocationService.shared.authorizationStatus
        guard status == .notDetermined else {
            // Already decided (granted, denied, or restricted). No
            // dialog to show — just mark handled so we don't re-
            // check on every shell mount.
            LocationService.markPromptHandled()
            return
        }
        _ = await LocationService.shared.requestAuthorization()
        LocationService.markPromptHandled()
    }

    private func applyWindowInterfaceStyle(_ mode: ThemeMode) {
        #if canImport(UIKit)
        let style: UIUserInterfaceStyle = {
            switch mode {
            case .system: return .unspecified
            case .light:  return .light
            case .dark:   return .dark
            }
        }()
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.overrideUserInterfaceStyle = style
            }
        }
        #endif
    }

    /// Resolves the picked primary against the effective theme.
    /// Light mode → deep variant (more contrast on cream). Dark
    /// mode → base variant (more contrast on dark stone). The
    /// `themeMode` override takes precedence over the system trait
    /// where it's set; `system` falls through to the OS preference.
    private var resolvedAccent: Color {
        let isDark: Bool = {
            switch settings.themeMode {
            case .light: return false
            case .dark:  return true
            case .system:
                #if canImport(UIKit)
                return UITraitCollection.current.userInterfaceStyle == .dark
                #else
                return false
                #endif
            }
        }()
        return isDark ? settings.primaryColor.base : settings.primaryColor.deep
    }

    @ViewBuilder
    private func destination(for route: Route) -> some View {
        // Each destination renders its own custom top bar (with
        // back chevron). Hide the system nav bar to avoid two
        // back affordances stacked on top of each other.
        switch route {
        case .notesList:
            NotesListScreen(
                userId:     userId,
                onBack:     { path.removeLast() },
                onOpenScan: { captureId in path.append(.scanDetail(captureId: captureId)) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .noteEditor(let entryId):
            NoteEditorScreen(
                entryId: entryId,
                userId:  userId,
                onBack:  { path.removeLast() }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .settings:
            SettingsScreen(
                onBack:    { path.removeLast() },
                authStore: authStore,
                settings:  settings,
                onManageCategories: { path.append(.manageCategories) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .profile:
            ProfileScreen(
                onBack:    { path.removeLast() },
                authStore: authStore,
                settings:  settings
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .manageCategories:
            CategoriesSettingsScreen(
                userId: userId,
                onBack: { path.removeLast() }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .scanDetail(let captureId):
            ScanDetailScreen(
                captureId: captureId,
                userId:    userId,
                onBack:    { path.removeLast() }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .search:
            SearchScreen(
                userId:     userId,
                onBack:     { path.removeLast() },
                onOpenScan: { captureId in
                    // Replace the search step with the detail so
                    // popping back from detail returns to Home,
                    // not Search. Matches the typical
                    // "search → tap result" UX.
                    path.removeLast()
                    path.append(.scanDetail(captureId: captureId))
                },
                settings: settings
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .stories:
            StoriesShelfScreen(
                userId:                  userId,
                onOpenStory:             { storyId in path.append(.storyEditor(storyId: storyId)) },
                onOpenSuggestionPreview: { id in path.append(.storySuggestionPreview(suggestionId: id)) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .storySuggestionPreview(let suggestionId):
            StorySuggestionPreviewScreen(
                suggestionId: suggestionId,
                userId:       userId,
                onBack:       { path.removeLast() },
                onOpenStory:  { storyId in
                    // Replace the preview step with the editor so a
                    // back-press from the editor returns to the shelf,
                    // not back to the preview the user just acted on.
                    path.removeLast()
                    path.append(.storyEditor(storyId: storyId))
                }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .storyEditor(let storyId):
            StoryEditorScreen(
                storyId:    storyId,
                userId:     userId,
                onBack:     { path.removeLast() },
                onPreview:  { path.append(.storyReader(storyId: storyId)) },
                onRequestCapture: { mode, completion in
                    controller.nextCompletionHandler = completion
                    pendingInitialMode = mode
                    showQuickCapture   = true
                }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .storyReader(let storyId):
            StoryReaderScreen(
                storyId: storyId,
                userId:  userId,
                onBack:  { path.removeLast() }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .categoryEntries(let name):
            CategoryEntriesScreen(
                userId:       userId,
                categoryName: name,
                onBack:       { path.removeLast() },
                onOpenScan:   { captureId in path.append(.scanDetail(captureId: captureId)) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .workspaceHome:
            WorkspaceHomeScreen(
                userId:                userId,
                onOpenSearch:          { navToTab(.search) },
                onOpenFolder:          { folder in
                    path.append(.folderDetail(folderId: folder.id))
                },
                onOpenContinue:        { capture in
                    path.append(.scanDetail(captureId: capture.id))
                },
                onOpenProfile:         { path.append(.profile) },
                onOpenTag:             { tag in
                    // Per-tag drill re-uses the legacy categoryEntries
                    // route (filter-by-name) until tag-id filtering
                    // ships in iOS D.
                    path.append(.categoryEntries(name: tag.name))
                },
                onOpenSmartCollection: { sc in
                    path.append(.smartCollection(collectionId: sc.id))
                },
                onBrowseTags:          { path.append(.tagLibrary) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .folderDetail(let folderId):
            FolderDetailScreen(
                folderId:      folderId,
                userId:        userId,
                onBack:        { path.removeLast() },
                onOpenCapture: { capture in
                    path.append(.scanDetail(captureId: capture.id))
                },
                onOpenSearch:  { navToTab(.search) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .smartCollection(let collectionId):
            SmartCollectionScreen(
                collectionId: collectionId,
                userId:       userId,
                onBack:       { path.removeLast() },
                onOpenCapture: { capture in
                    path.append(.scanDetail(captureId: capture.id))
                },
                onOpenSearch: { navToTab(.search) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .tagLibrary:
            TagLibraryScreen(
                userId:      userId,
                onBack:      { path.removeLast() },
                onOpenTag:   { tag in
                    path.append(.categoryEntries(name: tag.name))
                },
                onOpenSearch: { navToTab(.search) }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .calendar:
            CalendarScreen(
                userId:         userId,
                onBack:         { path.removeLast() },
                onOpenCapture:  { captureId in
                    // Replace the calendar step with the detail so
                    // popping back from detail returns to Home, not
                    // the calendar — matches the search → result
                    // navigation idiom.
                    path.removeLast()
                    path.append(.scanDetail(captureId: captureId))
                }
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)
        }
    }
}

#if DEBUG
struct QuickInkRoot_Previews: PreviewProvider {
    static var previews: some View {
        QuickInkRoot()
    }
}
#endif
