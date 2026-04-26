/*
 * ReleafApp.swift
 * SwiftUI app entry point.
 *
 * This file lives in the `ReleafFeatures` SwiftPM library; to ship a real
 * iOS app, create an Xcode app target with bundle ID `app.releaf.mobile`
 * that imports `ReleafFeatures` and re-declares `@main` pointing at this
 * struct (or wraps it in a `WindowGroup`).
 *
 * UI preferences (leaf theme + light/dark override) flow from
 * `UiPreferences.shared` and are injected through the environment so every
 * `@Environment(\.accentPalette)` reader re-tints on change — the SwiftUI
 * analog of Android's `LocalAccent` CompositionLocal.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct ReleafApp: App {
    @StateObject private var authStore = AuthStore.shared
    @StateObject private var uiPrefs   = UiPreferences.shared

    public init() {
        // Wire the sync stack once per process. Idempotent.
        SyncEnvironment.shared.install(authStore: .shared)

        // Ensure the default "General" shelf exists. The GRDB
        // migration seeds it on upgrade; this covers fresh installs
        // that run the migrator cleanly but never went through that
        // INSERT path (eraseDatabaseOnSchemaChange dev wipes, etc.).
        Task.detached(priority: .utility) {
            let repo = ShelfRepository()
            _ = try? await repo.ensureDefaultShelf()
        }
    }

    public var body: some Scene {
        WindowGroup {
            let palette = AccentPalettes.forID(uiPrefs.state.paletteID)
            let weight  = uiPrefs.state.fontWeight.fontWeight
            RootView()
                .environmentObject(authStore)
                .environmentObject(uiPrefs)
                .accentPalette(palette)
                .appFontWeight(weight)
                .tint(palette.primary)
        }
    }
}
