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

    // First-run onboarding state. Auto-shown when
    // `completedAt == 0`; the Home-screen widget re-opens it later.
    @AppStorage("onboarding.completedAt") private var completedAt: Double = 0
    @State private var showOnboarding: Bool = false

    public init() {}

    public var body: some View {
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
            .sheet(isPresented: $showCapture) {
                QuickCaptureSheet { _ in
                    // TODO: route to capture flow once implemented
                    showCapture = false
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
            }
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
                    onOpenContacts:     { homePath.append(ContactsRoute()) }
                )
                .navigationDestination(for: NotebookRoute.self) { route in
                    notebookDetail(id: route.id)
                }
                .navigationDestination(for: PageRoute.self) { route in
                    pageDetail(id: route.id)
                }
                .navigationDestination(for: NotepadEditorRoute.self) { route in
                    NotepadEditorScreen(entryId: route.entryId)
                }
                .navigationDestination(for: TasksRoute.self) { _ in
                    TasksScreen()
                }
                .navigationDestination(for: ContactsRoute.self) { _ in
                    ContactsView(userId: authStore.session?.userId ?? "")
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
                NotepadView()
                    .navigationDestination(for: NotepadEditorRoute.self) { route in
                        NotepadEditorScreen(entryId: route.entryId)
                    }
            }

        case "settings":
            NavigationStack(path: $settingsPath) {
                SettingsView()
            }

        default:
            NavigationStack { HomeScreen() }
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
}

#Preview {
    MainShell()
        .environmentObject(AuthStore(client: StubGoogleAuthClient()))
        .environmentObject(UiPreferences.shared)
}
