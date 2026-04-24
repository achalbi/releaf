/*
 * UiPreferences.swift
 *
 * Process-scoped store for the two UI preferences that affect the whole
 * app chrome — mirrors the Android `UiPreferences`:
 *   - `ThemeMode`        — System / Light / Dark override
 *   - `AccentPaletteID`  — which primary-color palette to paint with
 *
 * Backed by `UserDefaults`. Callers observe the current value via the
 * published `state` and mutate via the `set*` methods. `ReleafApp` reads
 * `state.paletteID` and injects the resolved palette through
 * `.accentPalette(_:)` so every `@Environment(\.accentPalette)` reader
 * re-tints on change.
 */

import Foundation
import Combine

/// How the app should resolve light-vs-dark. Dark-mode application is
/// future work on iOS — the enum exists so the preference persists.
public enum ThemeMode: String, CaseIterable, Codable, Sendable {
    case system
    case light
    case dark
}

/// Which visual treatment the notebook/chapter/page surfaces use.
/// `classic` keeps the current list-card UI; `variant1` swaps in the
/// editorial hero-card Figma design.
public enum NotebookListVariant: String, CaseIterable, Codable, Sendable {
    case classic
    case variant1
}

public struct UiPreferencesState: Equatable, Sendable {
    public let themeMode: ThemeMode
    public let paletteID: AccentPaletteID
    public let notebookVariant: NotebookListVariant

    public init(
        themeMode: ThemeMode = .system,
        paletteID: AccentPaletteID = .coral,
        notebookVariant: NotebookListVariant = .classic
    ) {
        self.themeMode = themeMode
        self.paletteID = paletteID
        self.notebookVariant = notebookVariant
    }
}

public final class UiPreferences: ObservableObject {
    public static let shared = UiPreferences()

    @Published public private(set) var state: UiPreferencesState

    private let defaults: UserDefaults

    private enum Keys {
        static let themeMode       = "releaf.ui.themeMode"
        static let palette         = "releaf.ui.paletteId"
        static let notebookVariant = "releaf.ui.notebookVariant"
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let mode = defaults.string(forKey: Keys.themeMode)
            .flatMap(ThemeMode.init(rawValue:)) ?? .system
        let palette = defaults.string(forKey: Keys.palette)
            .flatMap(AccentPaletteID.init(rawValue:)) ?? .coral
        let variant = defaults.string(forKey: Keys.notebookVariant)
            .flatMap(NotebookListVariant.init(rawValue:)) ?? .classic
        self.state = UiPreferencesState(themeMode: mode, paletteID: palette, notebookVariant: variant)
    }

    public func setThemeMode(_ mode: ThemeMode) {
        defaults.set(mode.rawValue, forKey: Keys.themeMode)
        state = UiPreferencesState(
            themeMode: mode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant
        )
    }

    public func setPalette(_ id: AccentPaletteID) {
        defaults.set(id.rawValue, forKey: Keys.palette)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: id,
            notebookVariant: state.notebookVariant
        )
    }

    public func setNotebookVariant(_ variant: NotebookListVariant) {
        defaults.set(variant.rawValue, forKey: Keys.notebookVariant)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: variant
        )
    }
}
