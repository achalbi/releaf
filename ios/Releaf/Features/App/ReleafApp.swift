/*
 * ReleafApp.swift
 * SwiftUI app entry point.
 *
 * This file lives in the `ReleafFeatures` SwiftPM library; to ship a real
 * iOS app, create an Xcode app target with bundle ID `app.releaf.mobile`
 * that imports `ReleafFeatures` and re-declares `@main` pointing at this
 * struct (or wraps it in a `WindowGroup`).
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct ReleafApp: App {
    @StateObject private var authStore = AuthStore.shared

    public init() {}

    public var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(authStore)
                .tint(AppColors.coral)
        }
    }
}
