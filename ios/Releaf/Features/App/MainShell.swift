/*
 * MainShell.swift
 *
 * Signed-in app shell: holds the selected tab, hosts a per-tab
 * NavigationStack, and pins the shared BottomNav to the bottom edge.
 *
 * Tabs:
 *   home      → HomeScreen          (pushes NotebookDetailView, PageDetailView)
 *   notebook  → NotebookTabView     (placeholder)
 *   leaf      → opens QuickCaptureSheet (no tab switch)
 *   notepad   → NotepadView         (placeholder)
 *   settings  → SettingsView        (placeholder, hosts Sign Out)
 *
 * Drill-in views push onto the currently-selected tab's stack and hide the
 * BottomNav via `.hidesBottomBar()` (see HideBottomBar.swift).
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct MainShell: View {
    @EnvironmentObject private var uiPrefs: UiPreferences
    @EnvironmentObject private var authStore: AuthStore
    @State private var selection: String = "home"
    @State private var showCapture: Bool = false

    @State private var homePath    = NavigationPath()
    @State private var notebookPath = NavigationPath()
    @State private var notepadPath  = NavigationPath()
    @State private var settingsPath = NavigationPath()

    @State private var hideBottomBar: Bool = false
    // Hoisted here so the drawer can sit above the tab content AND
    // the BottomNav safe-area inset, covering the full screen.
    @State private var showDrawer: Bool = false

    // Live metrics driving the drawer's subtitles — reactive to every
    // repository emission, so the numbers update as the user creates
    // notebooks, notepad entries, tasks, and contacts.
    @StateObject private var drawerMetrics = DrawerMetricsViewModel()

    // First-run onboarding state. Auto-shown when
    // `completedAt == 0`; the Home-screen widget re-opens it later.
    @AppStorage("onboarding.completedAt") private var completedAt: Double = 0
    @State private var showOnboarding: Bool = false

    public init() {}

    public var body: some View {
        ZStack {
            tabContent
                .environment(\.showOnboardingWizard, { showOnboarding = true })
                .onPreferenceChange(HideBottomBarKey.self) { hideBottomBar = $0 }
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if !hideBottomBar {
                        BottomNav(
                            selection: Binding(
                                get: { selection },
                                set: { newValue in
                                    // Tapping the selected tab pops its stack to root.
                                    if newValue == selection {
                                        popToRoot(for: newValue)
                                    } else {
                                        selection = newValue
                                    }
                                }
                            ),
                            onBrandTap: { showCapture = true }
                        )
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .animation(.easeInOut(duration: 0.18), value: hideBottomBar)

            // Drawer overlay — lives at shell level so it sits above
            // the BottomNav safe-area inset, giving the forest banner
            // a true edge-to-edge (top to bottom) presence.
            if showDrawer {
                HomeDrawerOverlay(
                    displayName:       authStore.session?.displayName ?? "",
                    email:             authStore.session?.email ?? "",
                    librarySubtitle:   drawerMetrics.state.librarySubtitle,
                    notepadSubtitle:   drawerMetrics.state.notepadSubtitle,
                    tasksSubtitle:     drawerMetrics.state.tasksSubtitle,
                    remindersSubtitle: drawerMetrics.state.remindersSubtitle,
                    contactsSubtitle:  drawerMetrics.state.contactsSubtitle,
                    onClose:           { withAnimation(.easeInOut(duration: 0.22)) { showDrawer = false } },
                    onSignOut: {
                        withAnimation(.easeInOut(duration: 0.22)) { showDrawer = false }
                        Task { await authStore.signOut() }
                    }
                )
                .transition(.move(edge: .leading))
                .zIndex(1)
            }
        }
            .sheet(isPresented: $showCapture) {
                QuickCaptureSheet { mode in
                    // Hide the sheet first so the navigation push
                    // animates over a clean canvas. Capture work
                    // runs in a detached task — it talks to the
                    // shared in-memory repo + a few ms of fake
                    // latency, so we don't want to block the
                    // sheet dismissal on it.
                    showCapture = false
                    Task { await beginQuickCapture(mode: mode) }
                }
            }
            .sheet(
                isPresented: $showOnboarding,
                onDismiss: {
                    // Swipe-to-dismiss bypasses our explicit dismiss
                    // callback; mark complete here so the wizard
                    // doesn't re-fire on the next launch.
                    if completedAt == 0 {
                        completedAt = Date().timeIntervalSince1970
                    }
                }
            ) {
                OnboardingWizard(
                    onDismiss: { showOnboarding = false },
                    onCta: { cta in
                        switch cta {
                        case .notebook: selection = "notebook"
                        case .notepad:  selection = "notepad"
                        }
                    }
                )
            }
            .onAppear {
                // Fire once on first presentation of the shell. The
                // guard against re-fire handles tab switches that would
                // otherwise re-trigger this block.
                if completedAt == 0 && !showOnboarding { showOnboarding = true }
                // Kick off live drawer metrics for the signed-in user
                // so counts are ready the first time the user opens
                // the drawer.
                if let userId = authStore.session?.userId, !userId.isEmpty {
                    drawerMetrics.start(userId: userId)
                }
            }
            .onDisappear { drawerMetrics.stop() }
            .tint(AppColors.coral)
    }

    // MARK: - Tab content

    @ViewBuilder private var tabContent: some View {
        switch selection {
        case "home":
            // Home tab root is always the dashboard HomeScreen (no variant).
            // Its drill-in (notebook → chapters → page) still follows the
            // variant preference so the notebook/chapter/page look swaps
            // whether the user entered from Home or the Notebook tab.
            NavigationStack(path: $homePath) {
                HomeScreen(
                    userId: authStore.session?.userId ?? "",
                    onOpenNotebook: { id in homePath.append(NotebookRoute(id: id)) },
                    onOpenNotebooksTab: { selection = "notebook" },
                    onOpenNotepadTab:   { selection = "notepad" },
                    onOpenNotepadEntry: { id in homePath.append(NotepadEditorRoute(entryId: id)) },
                    onOpenContacts:     { homePath.append(ContactsRoute()) },
                    onOpenDrawer:       { withAnimation(.easeInOut(duration: 0.22)) { showDrawer = true } },
                    onOpenActivityLog:  { homePath.append(ActivityRoute()) }
                )
                .navigationDestination(for: NotebookRoute.self) { route in
                    notebookDetail(id: route.id)
                }
                .navigationDestination(for: PageRoute.self) { route in
                    pageDetail(id: route.id)
                }
                .navigationDestination(for: NotepadEditorRoute.self) { route in
                    NotepadEditorScreen(
                        entryId: route.entryId,
                        initialMode: route.initialMode
                    )
                }
                .navigationDestination(for: TasksRoute.self) { _ in
                    TasksScreen()
                }
                .navigationDestination(for: ContactsRoute.self) { _ in
                    ContactsView(userId: authStore.session?.userId ?? "")
                }
                .navigationDestination(for: CallHistoryRoute.self) { _ in
                    CallHistoryView(userId: authStore.session?.userId ?? "")
                }
                .navigationDestination(for: ActivityRoute.self) { _ in
                    ActivityScreen(userId: authStore.session?.userId ?? "")
                }
            }

        case "notebook":
            NavigationStack(path: $notebookPath) {
                notebookTabRoot
                    .navigationDestination(for: NotebookRoute.self) { route in
                        notebookDetail(id: route.id)
                    }
                    .navigationDestination(for: PageRoute.self) { route in
                        pageDetail(id: route.id)
                    }
            }

        case "notepad":
            NavigationStack(path: $notepadPath) {
                NotepadView(
                    onOpenEntry: { id in
                        notepadPath.append(NotepadEditorRoute(entryId: id))
                    },
                    onOpenEntryWithMode: { id, mode in
                        notepadPath.append(NotepadEditorRoute(entryId: id, initialMode: mode))
                    }
                )
                    .navigationDestination(for: NotepadEditorRoute.self) { route in
                        NotepadEditorScreen(
                        entryId: route.entryId,
                        initialMode: route.initialMode
                    )
                    }
            }

        case "settings":
            NavigationStack(path: $settingsPath) {
                SettingsView()
            }

        default:
            // Fallback when `selection` doesn't match a known tab.
            // Pass the same userId the real "home" case uses so the
            // dashboard's repository observers actually fire.
            NavigationStack {
                HomeScreen(userId: authStore.session?.userId ?? "")
            }
        }
    }

    // MARK: - Variant-aware screen pickers (Notebook tab only)

    /// Notebook tab root — classic shows the Room-backed list; variant-1
    /// shows the editorial "Your shelves" screen. Home tab stays classic.
    @ViewBuilder private var notebookTabRoot: some View {
        switch uiPrefs.state.notebookVariant {
        case .classic:  NotebookTabView()
        case .variant1: HomeScreenVariant1()
        }
    }

    @ViewBuilder private func notebookDetail(id: String) -> some View {
        switch uiPrefs.state.notebookVariant {
        case .classic:  NotebookDetailView(notebookId: id)
        case .variant1: NotebookDetailViewVariant1(notebookId: id)
        }
    }

    @ViewBuilder private func pageDetail(id: String) -> some View {
        switch uiPrefs.state.notebookVariant {
        case .classic:  PageDetailView(pageId: id)
        case .variant1: PageDetailViewVariant1(pageId: id)
        }
    }

    // MARK: - Helpers

    private func popToRoot(for tabId: String) {
        switch tabId {
        case "home":     homePath     = NavigationPath()
        case "notebook": notebookPath = NavigationPath()
        case "notepad":  notepadPath  = NavigationPath()
        case "settings": settingsPath = NavigationPath()
        default: break
        }
    }

    /// Quick Capture handler — picks a default destination, creates
    /// a fresh page in it, and pushes the user straight into the
    /// page editor. The capture-mode argument isn't propagated to
    /// the page yet (the page detail always opens on Overview); a
    /// follow-up can thread an initial mode through PageRoute when
    /// each section grows real add-affordances. For now the user
    /// lands on a new page and uses the existing capture-tab bar
    /// to pick the section they want.
    @MainActor
    private func beginQuickCapture(mode: CaptureMode) async {
        let repo = LocalDriveRepository.shared
        do {
            let (notebookId, chapterId) = try await repo.defaultCaptureDestination()
            let page = try await repo.createPage(
                notebookId: notebookId,
                chapterId:  chapterId,
                title:      "New page"
            )
            // Land in the notebook tab with the freshly-created
            // page on top of the stack. Pushing both the notebook
            // and the page means a back-tap returns the user to
            // the parent notebook rather than the tab root.
            selection = "notebook"
            notebookPath = NavigationPath()
            notebookPath.append(NotebookRoute(id: notebookId))
            notebookPath.append(PageRoute(id: page.id))
        } catch {
            // Most likely cause is `defaultCaptureDestination`
            // throwing because the user has no active notebooks.
            // Surface a thin route so they can see what to do
            // next instead of swallowing silently — a small
            // toast or inline empty-state is the natural
            // follow-up; for now leave a clear breadcrumb.
            #if DEBUG
            print("[QuickCapture] failed to start capture: \(error)")
            #endif
        }
    }
}

#Preview {
    MainShell()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
        .environmentObject(UiPreferences.shared)
}
