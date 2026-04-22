/*
 * HideBottomBar.swift
 *
 * PreferenceKey-based mechanism for drill-in screens to hide the app-wide
 * BottomNav. The MainShell reads this preference and conditionally renders
 * the nav bar.
 *
 * Usage at a leaf view:
 *
 *     struct PageDetailView: View {
 *         var body: some View {
 *             content
 *                 .hidesBottomBar()        // <- drill-in route
 *         }
 *     }
 *
 * Top-level tab views simply omit the modifier; the default `false` preference
 * keeps the nav bar visible.
 */

import SwiftUI

/// Bubbles a request upward to hide the app-wide BottomNav.
/// Default `false` — BottomNav is visible by default. Any `true` value wins
/// (OR reducer) so nested leaves can opt out.
public struct HideBottomBarKey: PreferenceKey {
    public static var defaultValue: Bool = false
    public static func reduce(value: inout Bool, nextValue: () -> Bool) {
        value = value || nextValue()
    }
}

public extension View {
    /// Tells the enclosing `MainShell` to hide the BottomNav while this view
    /// is the top of the navigation stack.
    func hidesBottomBar(_ hidden: Bool = true) -> some View {
        preference(key: HideBottomBarKey.self, value: hidden)
    }
}
