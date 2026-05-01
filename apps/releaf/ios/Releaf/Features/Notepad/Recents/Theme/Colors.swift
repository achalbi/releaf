import SwiftUI
import ReleafDesignSystem

// MARK: - Hex initializer
//
// `ReleafDesignSystem` already exposes `Color(hex: UInt32, alpha: Double = 1.0)`
// (see AppColors.generated.swift). Our color tokens below are defined from
// 6/8-digit hex *strings*, so we use a private string-taking helper named
// `Color(hexString:)` to avoid overload-resolution ambiguity with the
// design-system initializer.

private extension Color {
    /// Initialize a Color from a hex string. Accepts 3-, 6-, or 8-digit forms,
    /// with or without a leading "#". 8-digit form is AARRGGBB.
    init(hexString hex: String) {
        let trimmed = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        var s = trimmed
        if s.hasPrefix("#") { s.removeFirst() }

        var int: UInt64 = 0
        Scanner(string: s).scanHexInt64(&int)

        let a, r, g, b: UInt64
        switch s.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (
                255,
                (int >> 8) * 17,
                (int >> 4 & 0xF) * 17,
                (int & 0xF) * 17
            )
        case 6: // RGB (24-bit)
            (a, r, g, b) = (
                255,
                int >> 16 & 0xFF,
                int >> 8 & 0xFF,
                int & 0xFF
            )
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (
                int >> 24 & 0xFF,
                int >> 16 & 0xFF,
                int >> 8 & 0xFF,
                int & 0xFF
            )
        default:
            (a, r, g, b) = (255, 0, 0, 0)
        }

        self.init(
            .sRGB,
            red: Double(r) / 255.0,
            green: Double(g) / 255.0,
            blue: Double(b) / 255.0,
            opacity: Double(a) / 255.0
        )
    }
}

// MARK: - Brand color tokens

extension Color {
    // Backgrounds
    static let bgCanvas       = Color(hexString: "F4EDDC")
    static let bgSurface      = Color(hexString: "FBF5E2")
    static let bgSurfaceMuted = Color(hexString: "EFE7CD")
    static let bgChip         = Color(hexString: "DDEACD")
    static let bgFeatured     = Color(hexString: "DDEACD")

    // Greens
    static let green900 = Color(hexString: "2C4520")
    static let green800 = Color(hexString: "3F5C2C") // primary
    static let green600 = Color(hexString: "5B7A3F")
    static let green400 = Color(hexString: "7AA055")
    static let green200 = Color(hexString: "C0DD97")
    static let green100 = Color(hexString: "DDEACD")

    // Cream
    static let cream300 = Color(hexString: "EDE3CC")

    // Text on light
    static let textPrimary     = Color(hexString: "1F1E18")
    static let textSecondary   = Color(hexString: "5C5C50")
    static let textMuted       = Color(hexString: "6B6B5E")

    // Text on dark
    static let textOnDark        = Color(hexString: "F4EDDC")
    static let textOnDarkMuted   = Color(hexString: "DDEACD")
    static let textOnDarkSubtle  = Color(hexString: "C0DD97")

    // Greens for text
    static let textGreen      = Color(hexString: "3F5C2C")
    static let textGreenMuted = Color(hexString: "6B8A4F")

    // Amber accent (import / scan / new)
    static let accentImport   = Color(hexString: "BA7517")
    static let accentImportBg = Color(hexString: "F0E0B8")

    // Translucent overlays for use on dark surfaces
    static let onDark10 = Color(hexString: "F4EDDC").opacity(0.10)
    static let onDark14 = Color(hexString: "F4EDDC").opacity(0.14)
    static let onDark16 = Color(hexString: "F4EDDC").opacity(0.16)
    static let onDark25 = Color(hexString: "C0DD97").opacity(0.25)
    static let onDark30 = Color(hexString: "F4EDDC").opacity(0.30)

    // Borders
    static let borderFaint   = Color(hexString: "3F5C2C").opacity(0.10)
    static let borderDashed  = Color(hexString: "3F5C2C").opacity(0.20)
    static let borderDivider = Color(hexString: "3F5C2C").opacity(0.20)
}
