import SwiftUI

/// Centralized typography tokens for the Recents screen.
enum Typography {
    static let displayFont    = Font.system(size: 34, weight: .regular, design: .serif)
    static let h2Font         = Font.system(size: 24, weight: .regular, design: .serif)
    static let bodyFont       = Font.system(size: 14, weight: .regular)
    static let bodySmallFont  = Font.system(size: 13, weight: .regular)
    static let captionFont    = Font.system(size: 12, weight: .regular)
    static let microFont      = Font.system(size: 10, weight: .medium)
    static let microWideFont  = Font.system(size: 9,  weight: .medium)

    /// Numbers in the stats strip — serif at 18pt/medium so the
    /// numerals carry the same editorial feel as the H1, theme
    /// names, and earlier-card titles. Pairs with the
    /// `microWideFont` sans eyebrow underneath.
    static let statNumberFont = Font.system(size: 18, weight: .medium, design: .serif)
}
