/*
 * AppFontWeight+Env.swift
 *
 * EnvironmentValue carrying the user's chosen typographic weight.
 * `ReleafApp` resolves `UiPreferences.state.fontWeight` to a SwiftUI
 * `Font.Weight` and injects it via `.appFontWeight(_:)` at the top
 * of the view tree.
 *
 * The root view applies `.fontWeight(env.appFontWeight)` once, and
 * descendants inherit that weight. Inline `Font.system(size:)` calls
 * through the app are written *without* an explicit `weight:` so the
 * inherited weight wins. NSAttributedString fonts (rich-text editor)
 * read `UiPreferences.shared.state.fontWeight.uiKitWeight` directly.
 */

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

public extension AppFontWeight {
    /// Maps the persisted enum to the SwiftUI weight applied at the
    /// root via `.fontWeight(_:)`.
    var fontWeight: Font.Weight {
        switch self {
        case .light:    return .light
        case .regular:  return .regular
        case .medium:   return .medium
        case .semibold: return .semibold
        }
    }

    #if canImport(UIKit)
    /// Equivalent for UIKit / NSAttributedString. Same scale as the
    /// SwiftUI weights — Apple keeps these aligned. iOS only.
    var uiKitWeight: UIFont.Weight {
        switch self {
        case .light:    return .light
        case .regular:  return .regular
        case .medium:   return .medium
        case .semibold: return .semibold
        }
    }
    #endif
}

private struct AppFontWeightKey: EnvironmentKey {
    /// Default matches the persisted-state default so previews / tests
    /// that forget to inject paint with the same starting weight as a
    /// fresh install.
    static let defaultValue: Font.Weight = .light
}

public extension EnvironmentValues {
    var appFontWeight: Font.Weight {
        get { self[AppFontWeightKey.self] }
        set { self[AppFontWeightKey.self] = newValue }
    }
}

public extension View {
    /// Inject the active typographic weight into the subtree. Typically
    /// called once at the app root alongside `.accentPalette(_:)`.
    ///
    /// `View.fontWeight(_:)` shipped in iOS 16 / macOS 13. The package
    /// already requires iOS 16, so iOS always gets the modifier; macOS
    /// 12 (the package's macOS floor, set so previews build on older
    /// Macs) doesn't have it, so we no-op the weight modifier on that
    /// path. Descendants on macOS 12 can still read `\.appFontWeight`
    /// from the environment if they need to apply weight via
    /// `.font(.system(size:weight:))` directly.
    func appFontWeight(_ weight: Font.Weight) -> some View {
        environment(\.appFontWeight, weight)
            .applyFontWeightIfAvailable(weight)
    }
}

private extension View {
    @ViewBuilder
    func applyFontWeightIfAvailable(_ weight: Font.Weight) -> some View {
        if #available(macOS 13.0, *) {
            self.fontWeight(weight)
        } else {
            self
        }
    }
}
