/*
 * AccentPalette.swift
 *
 * Accent-color API. Mirrors the Android `AccentPalette` / `LocalAccent`
 * plumbing so the four user-selectable themes (coral / green / yellow
 * / dry) re-tint every call site that reads `@Environment(\.accentPalette)`.
 *
 * The active palette is injected at the top of the view tree by
 * `ReleafApp`; call sites read it like:
 *
 *     @Environment(\.accentPalette) private var accent
 *     // accent.primary / accent.deep / accent.soft / accent.border
 *
 * Kotlin mapping:
 *   AppAccent.primary → accent.primary
 *   AppAccent.deep    → accent.deep
 *   AppAccent.soft    → accent.soft
 *   AppAccent.border  → accent.border
 */

import SwiftUI

/// Roles surfaced by each of the four user-selectable palettes.
public struct AccentPalette: Equatable {
    /// Main accent — old `AppColors.coral` in the default palette.
    public let primary: Color
    /// Pressed / deep variant.
    public let deep: Color
    /// Soft tint (~10% alpha primary). Reads as a pale accent wash over both
    /// light and dark canvases.
    public let soft: Color
    /// Border variant (~30% alpha primary) for outlined pills / chips.
    public let border: Color

    public init(primary: Color, deep: Color, soft: Color, border: Color) {
        self.primary = primary
        self.deep = deep
        self.soft = soft
        self.border = border
    }
}

/// Stable identifier for the active palette. Persisted by `UiPreferences`.
public enum AccentPaletteID: String, CaseIterable, Codable, Sendable {
    case coral
    case green
    case yellow
    case dry
}

public enum AccentPalettes {
    public static let coral = AccentPalette(
        primary: AppColors.themeCoralPrimary,
        deep:    AppColors.themeCoralDeep,
        soft:    AppColors.themeCoralBgSoft,
        border:  AppColors.themeCoralBorderSoft
    )
    public static let green = AccentPalette(
        primary: AppColors.themeGreenPrimary,
        deep:    AppColors.themeGreenDeep,
        soft:    AppColors.themeGreenBgSoft,
        border:  AppColors.themeGreenBorderSoft
    )
    public static let yellow = AccentPalette(
        primary: AppColors.themeYellowPrimary,
        deep:    AppColors.themeYellowDeep,
        soft:    AppColors.themeYellowBgSoft,
        border:  AppColors.themeYellowBorderSoft
    )
    public static let dry = AccentPalette(
        primary: AppColors.themeDryPrimary,
        deep:    AppColors.themeDryDeep,
        soft:    AppColors.themeDryBgSoft,
        border:  AppColors.themeDryBorderSoft
    )

    public static func forID(_ id: AccentPaletteID) -> AccentPalette {
        switch id {
        case .coral:  return coral
        case .green:  return green
        case .yellow: return yellow
        case .dry:    return dry
        }
    }
}

private struct AccentPaletteKey: EnvironmentKey {
    /// Default is coral so previews that forget to inject a palette still
    /// paint correctly — matches Android's `LocalAccent` default.
    static let defaultValue: AccentPalette = AccentPalettes.coral
}

public extension EnvironmentValues {
    var accentPalette: AccentPalette {
        get { self[AccentPaletteKey.self] }
        set { self[AccentPaletteKey.self] = newValue }
    }
}

public extension View {
    /// Inject an accent palette into the subtree. Typically called once at
    /// the app root; everything below reads via `@Environment(\.accentPalette)`.
    func accentPalette(_ palette: AccentPalette) -> some View {
        environment(\.accentPalette, palette)
    }
}
