/*
 * AppShadow.swift
 *
 * Shadow scale. Shape is Untitled UI-inspired (two layers, cool-neutral)
 * but darkened slightly to read on the cream canvas.
 *
 * Usage:
 *   .appShadow(.sm)    // cards, rows
 *   .appShadow(.md)    // popovers, raised surfaces
 *   .appShadow(.fab)   // floating action button (coral-tinted)
 */

import SwiftUI

public enum AppShadow {
    case xs
    case sm
    case md
    case lg
    case fab
}

public extension View {
    /// Apply an Untitled-UI-style elevation. Stacks two shadows to give
    /// the soft "sitting on the surface" feel rather than the single
    /// heavy blur SwiftUI defaults to.
    @ViewBuilder
    func appShadow(_ style: AppShadow) -> some View {
        switch style {
        case .xs:
            self
                .shadow(color: Color(hex: 0x241D17, alpha: 0.05), radius: 1, x: 0, y: 1)

        case .sm:
            self
                .shadow(color: Color(hex: 0x241D17, alpha: 0.06), radius: 2, x: 0, y: 1)
                .shadow(color: Color(hex: 0x241D17, alpha: 0.04), radius: 3, x: 0, y: 2)

        case .md:
            self
                .shadow(color: Color(hex: 0x241D17, alpha: 0.08), radius: 4, x: 0, y: 2)
                .shadow(color: Color(hex: 0x241D17, alpha: 0.05), radius: 8, x: 0, y: 4)

        case .lg:
            self
                .shadow(color: Color(hex: 0x241D17, alpha: 0.10), radius: 8,  x: 0, y: 4)
                .shadow(color: Color(hex: 0x241D17, alpha: 0.06), radius: 16, x: 0, y: 8)

        case .fab:
            // Warmer coral-tinted drop so the FAB doesn't read as purely grey.
            self
                .shadow(color: Color(hex: 0xC85A30, alpha: 0.28), radius: 10, x: 0, y: 6)
                .shadow(color: Color(hex: 0x241D17, alpha: 0.10), radius: 4,  x: 0, y: 2)
        }
    }
}
