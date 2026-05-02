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

    public init() {
        // Slice 4.2b — wire the sync stack once per process.
        // Idempotent. When the eventual Xcode app target lands,
        // this call moves into its `@main App.init()` (mirror of
        // Releaf's `ReleafApp.swift`); installing it here keeps
        // QuickInk's library-only build self-contained. The
        // environment's auth observer kicks off a sync pass on
        // every signed-in transition + cancels on sign-out.
        //
        // `makeQuickInkAuthStore()` is cached, so this call sees
        // the same instance the @StateObject above wraps — see
        // `QuickInkAuthBinding.swift`'s header.
        QuickInkSyncEnvironment.shared.install(authStore: makeQuickInkAuthStore())
    }

    public var body: some View {
        if !onboardingCompleted {
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
        case search
    }

    let userId: String
    @ObservedObject var authStore: AuthStore

    @StateObject private var controller: ScanFlowController
    @State private var path: [Route] = []

    init(userId: String, authStore: AuthStore) {
        self.userId = userId
        self.authStore = authStore
        // Slice 4.2c — kick a one-shot sync at the end of each
        // scan pass. The scheduler's coalesce-while-running guard
        // dedupes bursts and the closure no-ops when Drive backup
        // is off (per QuickInkSyncEnvironment's drive-toggle gate),
        // so this is safe to fire unconditionally. Captured by
        // value so the closure doesn't retain `self`.
        _controller = StateObject(wrappedValue: ScanFlowController(
            userId:         userId,
            onPassComplete: { QuickInkSyncEnvironment.shared.scheduler.requestImmediate() }
        ))
    }

    var body: some View {
        // The scan flow takes over the shell whenever its
        // controller is non-idle, regardless of where the user
        // was in the navigation stack — OCR is an interruption
        // that should land them on the review surface. When the
        // user dismisses, `path` is preserved (still @State on
        // MainShell), so they return to wherever they were.
        switch controller.state {
        case .recognizing, .complete, .failed:
            ScanReviewScreen(controller: controller)
        case .idle:
            NavigationStack(path: $path) {
                HomeScreen(
                    controller:     controller,
                    userId:         userId,
                    onOpenNotes:    { path.append(.notesList) },
                    onOpenSettings: { path.append(.settings) },
                    onOpenSearch:   { path.append(.search) },
                    onTapCategory:  nil, // Wired in a follow-up.
                    onOpenEntry:    { entryId in path.append(.noteEditor(entryId: entryId)) }
                )
                .navigationBarBackButtonHidden(true)
                .toolbar(.hidden, for: .navigationBar)
                .navigationDestination(for: Route.self) { route in
                    destination(for: route)
                }
            }
        }
    }

    @ViewBuilder
    private func destination(for route: Route) -> some View {
        // Each destination renders its own custom top bar (with
        // back chevron). Hide the system nav bar to avoid two
        // back affordances stacked on top of each other.
        switch route {
        case .notesList:
            NotesListScreen(
                userId:      userId,
                onBack:      { path.removeLast() },
                onOpenEntry: { entryId in path.append(.noteEditor(entryId: entryId)) }
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
                authStore: authStore
            )
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)

        case .search:
            SearchScreen(
                userId:      userId,
                onBack:      { path.removeLast() },
                onOpenEntry: { entryId in
                    // Replace the search step with the editor so
                    // popping back from the editor returns to Home,
                    // not Search. This matches the typical
                    // "search → tap result" UX.
                    path.removeLast()
                    path.append(.noteEditor(entryId: entryId))
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
