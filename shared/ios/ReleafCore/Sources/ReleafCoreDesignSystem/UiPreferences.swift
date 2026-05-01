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

/// Global typographic weight applied to every text style across the
/// app. The user picks one in Settings; `ReleafApp` injects the
/// resolved `Font.Weight` into the environment as `\.appFontWeight`,
/// and the root view applies it via `.fontWeight(_:)` so descendants
/// inherit it.
public enum AppFontWeight: String, CaseIterable, Codable, Sendable {
    case light
    case regular
    case medium
    case semibold
}

/// How the Home-screen activity timeline is rendered. `classic` keeps
/// the dot-on-rail rendering that ships by default; `bramble` swaps in
/// the editorial vine-with-flowers variant from the design exploration
/// (see `design-system/timeline-vine-bramble-garland.html` and
/// `DesignSystem/Components/ActivityTimeline.swift`). Identical data,
/// different rendering — the user picks between them in Settings.
public enum TimelineStyle: String, CaseIterable, Codable, Sendable {
    case classic
    case bramble
}

/// How the notebooks list is ordered. Persisted across launches so
/// the user's choice survives a cold start. Mirrors the
/// `NotebookSortMode` enum on Android. Lives in DesignSystem so
/// `UiPreferences` can store it without a Features dependency.
public enum NotebookSortMode: String, CaseIterable, Codable, Sendable {
    case recent
    case name
    case pages

    public var label: String {
        switch self {
        case .recent: return "Recent activity"
        case .name:   return "Name (A → Z)"
        case .pages:  return "Most pages"
        }
    }

    public var systemIcon: String {
        switch self {
        case .recent: return "clock"
        case .name:   return "textformat"
        case .pages:  return "list.number"
        }
    }
}

public struct UiPreferencesState: Equatable, Sendable {
    public let themeMode: ThemeMode
    public let paletteID: AccentPaletteID
    public let notebookVariant: NotebookListVariant
    public let fontWeight: AppFontWeight
    public let timelineStyle: TimelineStyle
    public let pageViewMode: PageViewMode
    public let notebookSortMode: NotebookSortMode
    /// User-controlled display order for notepad categories — lists
    /// every name (predefined + custom) in the order the user wants
    /// them surfaced in the filter chip row + editor picker. Empty
    /// = no preference, fall back to the built-in default.
    /// Resolved end-to-end via `NotepadCategory.applyOrder(...)`.
    public let notepadCategoryOrder: [String]
    /// Has the first-launch onboarding been seen and dismissed by
    /// the user? Defaults to `false`; flips to `true` the moment the
    /// onboarding view's "Start capturing" CTA fires.
    public let hasSeenOnboarding: Bool

    public init(
        themeMode: ThemeMode = .system,
        paletteID: AccentPaletteID = .coral,
        notebookVariant: NotebookListVariant = .variant1,
        fontWeight: AppFontWeight = .light,
        timelineStyle: TimelineStyle = .classic,
        pageViewMode: PageViewMode = .grid,
        notebookSortMode: NotebookSortMode = .recent,
        notepadCategoryOrder: [String] = [],
        hasSeenOnboarding: Bool = false
    ) {
        self.themeMode = themeMode
        self.paletteID = paletteID
        self.notebookVariant = notebookVariant
        self.fontWeight = fontWeight
        self.timelineStyle = timelineStyle
        self.pageViewMode = pageViewMode
        self.notebookSortMode = notebookSortMode
        self.notepadCategoryOrder = notepadCategoryOrder
        self.hasSeenOnboarding = hasSeenOnboarding
    }
}

public final class UiPreferences: ObservableObject {
    public static let shared = UiPreferences()

    @Published public private(set) var state: UiPreferencesState

    private let defaults: UserDefaults

    private enum Keys {
        static let themeMode             = "releaf.ui.themeMode"
        static let palette               = "releaf.ui.paletteId"
        static let notebookVariant       = "releaf.ui.notebookVariant"
        static let fontWeight            = "releaf.ui.fontWeight"
        static let timelineStyle         = "releaf.ui.timelineStyle"
        static let pageViewMode          = "releaf.ui.pageViewMode"
        static let notebookSortMode      = "releaf.ui.notebookSortMode"
        static let notepadCategoryOrder  = "releaf.ui.notepadCategoryOrder"
        static let hasSeenOnboarding     = "releaf.ui.hasSeenOnboarding"
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let mode = defaults.string(forKey: Keys.themeMode)
            .flatMap(ThemeMode.init(rawValue:)) ?? .system
        let palette = defaults.string(forKey: Keys.palette)
            .flatMap(AccentPaletteID.init(rawValue:)) ?? .coral
        let variant = defaults.string(forKey: Keys.notebookVariant)
            .flatMap(NotebookListVariant.init(rawValue:)) ?? .variant1
        let weight = defaults.string(forKey: Keys.fontWeight)
            .flatMap(AppFontWeight.init(rawValue:)) ?? .light
        let timelineStyle = defaults.string(forKey: Keys.timelineStyle)
            .flatMap(TimelineStyle.init(rawValue:)) ?? .classic
        let pageViewMode = defaults.string(forKey: Keys.pageViewMode)
            .flatMap(PageViewMode.init(rawValue:)) ?? .grid
        let notebookSortMode = defaults.string(forKey: Keys.notebookSortMode)
            .flatMap(NotebookSortMode.init(rawValue:)) ?? .recent
        // Stored as `[String]` directly via UserDefaults — no need to
        // join/split. Newly-installed apps get an empty array, which
        // resolves to the built-in default order.
        let categoryOrder = (defaults.array(forKey: Keys.notepadCategoryOrder) as? [String]) ?? []
        let hasSeenOnboarding = defaults.bool(forKey: Keys.hasSeenOnboarding)
        self.state = UiPreferencesState(
            themeMode: mode,
            paletteID: palette,
            notebookVariant: variant,
            fontWeight: weight,
            timelineStyle: timelineStyle,
            pageViewMode: pageViewMode,
            notebookSortMode: notebookSortMode,
            notepadCategoryOrder: categoryOrder,
            hasSeenOnboarding: hasSeenOnboarding
        )
    }

    public func setThemeMode(_ mode: ThemeMode) {
        defaults.set(mode.rawValue, forKey: Keys.themeMode)
        state = UiPreferencesState(
            themeMode: mode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    public func setPalette(_ id: AccentPaletteID) {
        defaults.set(id.rawValue, forKey: Keys.palette)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: id,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    public func setNotebookVariant(_ variant: NotebookListVariant) {
        defaults.set(variant.rawValue, forKey: Keys.notebookVariant)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: variant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    public func setFontWeight(_ weight: AppFontWeight) {
        defaults.set(weight.rawValue, forKey: Keys.fontWeight)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: weight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    public func setTimelineStyle(_ style: TimelineStyle) {
        defaults.set(style.rawValue, forKey: Keys.timelineStyle)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: style,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    public func setPageViewMode(_ mode: PageViewMode) {
        defaults.set(mode.rawValue, forKey: Keys.pageViewMode)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: mode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    public func setNotebookSortMode(_ mode: NotebookSortMode) {
        defaults.set(mode.rawValue, forKey: Keys.notebookSortMode)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: mode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    /// Persist the user's preferred display order for notepad
    /// categories. Trims + drops blanks so the round-trip is stable
    /// even if the caller hands us a list with stray whitespace.
    public func setNotepadCategoryOrder(_ order: [String]) {
        let cleaned = order
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        defaults.set(cleaned, forKey: Keys.notepadCategoryOrder)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: cleaned,
            hasSeenOnboarding: state.hasSeenOnboarding
        )
    }

    /// Mark the first-launch onboarding as seen. Called by the
    /// onboarding view's CTA. Idempotent — repeated calls are safe.
    public func markOnboardingSeen() {
        defaults.set(true, forKey: Keys.hasSeenOnboarding)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: true
        )
    }

    /// Inverse of `markOnboardingSeen` — flips the flag back to
    /// false so the onboarding screen surfaces again. Wired to the
    /// "Show onboarding again" row in Settings; useful for testing
    /// and for users who want to revisit the welcome flow.
    public func resetOnboarding() {
        defaults.set(false, forKey: Keys.hasSeenOnboarding)
        state = UiPreferencesState(
            themeMode: state.themeMode,
            paletteID: state.paletteID,
            notebookVariant: state.notebookVariant,
            fontWeight: state.fontWeight,
            timelineStyle: state.timelineStyle,
            pageViewMode: state.pageViewMode,
            notebookSortMode: state.notebookSortMode,
            notepadCategoryOrder: state.notepadCategoryOrder,
            hasSeenOnboarding: false
        )
    }
}
