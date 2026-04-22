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
    @State private var selection: String = "home"
    @State private var showCapture: Bool = false

    @State private var homePath    = NavigationPath()
    @State private var notebookPath = NavigationPath()
    @State private var notepadPath  = NavigationPath()
    @State private var settingsPath = NavigationPath()

    @State private var hideBottomBar: Bool = false

    public init() {}

    public var body: some View {
        tabContent
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
            .tint(AppColors.coral)
    }

    // MARK: - Tab content

    @ViewBuilder private var tabContent: some View {
        switch selection {
        case "home":
            NavigationStack(path: $homePath) {
                HomeScreen()
                    .navigationDestination(for: NotebookRoute.self) { route in
                        NotebookDetailView(notebookId: route.id)
                    }
                    .navigationDestination(for: PageRoute.self) { route in
                        PageDetailView(pageId: route.id)
                    }
            }

        case "notebook":
            NavigationStack(path: $notebookPath) {
                NotebookTabView()
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
}
