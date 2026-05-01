// GENERATED — DO NOT EDIT.
// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.
//
// Source: design-system/design-tokens.json

import SwiftUI
import UIKit

public enum AppColors {

    // MARK: - Ramps (appearance-agnostic)

    // neutral — 11 stops
    public static let neutral50 = Color(hex: 0xFAF6F0)
    public static let neutral100 = Color(hex: 0xEFE7DA)
    public static let neutral200 = Color(hex: 0xE0D2BA)
    public static let neutral300 = Color(hex: 0xCEBB9D)
    public static let neutral400 = Color(hex: 0xA99A84)
    public static let neutral500 = Color(hex: 0x8A7C6D)
    public static let neutral600 = Color(hex: 0x5F5245)
    public static let neutral700 = Color(hex: 0x463C31)
    public static let neutral800 = Color(hex: 0x332B22)
    public static let neutral900 = Color(hex: 0x241D17)
    public static let neutral950 = Color(hex: 0x120E0A)

    // coral — 4 stops
    public static let coral50 = Color(hex: 0xFEF4EF)
    public static let coral100 = Color(hex: 0xFCEAE0)
    public static let coral500 = Color(hex: 0xE07856)
    public static let coral700 = Color(hex: 0xC65A3E)

    // success — 4 stops
    public static let success50 = Color(hex: 0xF1F7F4)
    public static let success100 = Color(hex: 0xE3F1E8)
    public static let success600 = Color(hex: 0x4C9A6A)
    public static let success700 = Color(hex: 0x36754F)

    // info — 4 stops
    public static let info50 = Color(hex: 0xEFF5FB)
    public static let info100 = Color(hex: 0xE1ECF8)
    public static let info600 = Color(hex: 0x2E6FB5)
    public static let info700 = Color(hex: 0x23548B)

    // warning — 4 stops
    public static let warning50 = Color(hex: 0xFDF7E5)
    public static let warning100 = Color(hex: 0xFBEECD)
    public static let warning600 = Color(hex: 0xA87418)
    public static let warning700 = Color(hex: 0x7F5711)

    // danger — 4 stops
    public static let danger50 = Color(hex: 0xFDEEE9)
    public static let danger100 = Color(hex: 0xF6D1C9)
    public static let danger600 = Color(hex: 0xC8432E)
    public static let danger700 = Color(hex: 0x983224)

    // MARK: - Leaf theme variants (flat — no dark variant yet)

    // coral theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    public static let themeCoralPrimary = Color(hex: 0xE07856)
    public static let themeCoralDeep = Color(hex: 0xC65A3E)
    public static let themeCoralBgSoft = Color(hex: 0xE07856, alpha: 0.102)
    public static let themeCoralBorderSoft = Color(hex: 0xE07856, alpha: 0.302)

    // green theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    public static let themeGreenPrimary = Color(hex: 0x7AA874)
    public static let themeGreenDeep = Color(hex: 0x5B8C52)
    public static let themeGreenBgSoft = Color(hex: 0x7AA874, alpha: 0.102)
    public static let themeGreenBorderSoft = Color(hex: 0x7AA874, alpha: 0.302)

    // yellow theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    public static let themeYellowPrimary = Color(hex: 0xF4C430)
    public static let themeYellowDeep = Color(hex: 0xE8B923)
    public static let themeYellowBgSoft = Color(hex: 0xF4C430, alpha: 0.102)
    public static let themeYellowBorderSoft = Color(hex: 0xF4C430, alpha: 0.302)

    // dry theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    public static let themeDryPrimary = Color(hex: 0xB8956A)
    public static let themeDryDeep = Color(hex: 0x8B7355)
    public static let themeDryBgSoft = Color(hex: 0xB8956A, alpha: 0.102)
    public static let themeDryBorderSoft = Color(hex: 0xB8956A, alpha: 0.302)

    // MARK: - Roles (theme-aware via dynamicColor(…))

    /// App background — warm cream (light, matches Releaf Branding template) / warm dark (neutral900, dark)
    public static let canvas = dynamicColor(
        light: 0xF5EEDF,
        dark: 0x241D17
    )

    /// Card / input background @ 90% opacity — cream in light, neutral800 in dark
    public static let card = dynamicColor(
        light: 0xFFFAF4,
        lightAlpha: 0.902,
        dark: 0x332B22,
        darkAlpha: 0.902
    )

    /// Solid card fill for opaque contexts
    public static let cardSolid = dynamicColor(
        light: 0xFFFAF4,
        dark: 0x332B22
    )

    /// Subtle alt surface — neutral100 in light, neutral700 in dark
    public static let subtle = dynamicColor(
        light: 0xEFE7DA,
        dark: 0x463C31
    )

    /// Slightly darker cream than canvas — used on disabled buttons and subtle fills. Matches Releaf Branding --muted token.
    public static let muted = dynamicColor(
        light: 0xEBE4D3,
        dark: 0x332B22
    )

    /// Input field fill — 5% opacity of the primary text color so the field reads as a soft tan well on the cream canvas. Matches Releaf Branding --input-background.
    public static let inputBg = dynamicColor(
        light: 0x463C31,
        lightAlpha: 0.051,
        dark: 0xFAF6F0,
        darkAlpha: 0.051
    )

    /// Body + headings — neutral700 in light, neutral100 in dark
    public static let textPrimary = dynamicColor(
        light: 0x463C31,
        dark: 0xEFE7DA
    )

    /// Muted text, meta labels — neutral600 in light, neutral300 in dark
    public static let textSecondary = dynamicColor(
        light: 0x5F5245,
        dark: 0xCEBB9D
    )

    /// Very muted, placeholder — neutral500 in light, neutral400 in dark
    public static let textTertiary = dynamicColor(
        light: 0x8A7C6D,
        dark: 0xA99A84
    )

    /// Text on coral / action pill — cream in both themes (matches Releaf Branding template; warmer than pure white on the coral fill)
    public static let onAccent = dynamicColor(
        light: 0xF5EEDF,
        dark: 0xF5EEDF
    )

    /// Card/input border @ ~12% alpha — warm brown on light, neutral50 on dark
    public static let borderDefault = dynamicColor(
        light: 0x503E2D,
        lightAlpha: 0.1216,
        dark: 0xFAF6F0,
        darkAlpha: 0.1216
    )

    /// Stronger border @ ~24% alpha
    public static let borderStrong = dynamicColor(
        light: 0x503E2D,
        lightAlpha: 0.2392,
        dark: 0xFAF6F0,
        darkAlpha: 0.2392
    )

    /// Primary accent — coral stays coral across themes (matches Releaf Branding template)
    public static let coral = dynamicColor(
        light: 0xE07856,
        dark: 0xE07856
    )

    /// Coral tint — pale apricot in light, dark coral wash in dark
    public static let coralSoft = dynamicColor(
        light: 0xFCEAE0,
        dark: 0x3A2118
    )

    /// Pressed coral — darker in light, lighter in dark (pressed direction inverts). Light hex matches Releaf Branding template.
    public static let coralDeep = dynamicColor(
        light: 0xC65A3E,
        dark: 0xFA9975
    )

    /// Coral outline button border + text
    public static let coralOutline = dynamicColor(
        light: 0xE07856,
        dark: 0xEF8B66
    )

    /// Install / sync-success primary action
    public static let green = dynamicColor(
        light: 0x1E5943,
        dark: 0x7FC19A
    )

    /// Green accent soft tint
    public static let greenSoft = dynamicColor(
        light: 0xD9EDE2,
        dark: 0x20352B
    )

    /// Success eyebrow, sync labels
    public static let greenText = dynamicColor(
        light: 0x1E5943,
        dark: 0x7FC19A
    )

    /// Active status text
    public static let success = dynamicColor(
        light: 0x4C9A6A,
        dark: 0x7FC19A
    )

    /// Active pill background
    public static let successSoft = dynamicColor(
        light: 0xE3F1E8,
        dark: 0x1B2E23
    )

    /// Count-pill text
    public static let info = dynamicColor(
        light: 0x2E6FB5,
        dark: 0x7FA7D4
    )

    /// Count-pill background
    public static let infoSoft = dynamicColor(
        light: 0xE1ECF8,
        dark: 0x17283D
    )

    /// Date-based tag text
    public static let warning = dynamicColor(
        light: 0xA87418,
        dark: 0xD9A45C
    )

    /// Date-based tag background
    public static let warningSoft = dynamicColor(
        light: 0xFBEECD,
        dark: 0x2E2516
    )

    /// Archived / neutral tag text
    public static let neutral = dynamicColor(
        light: 0x5F5245,
        dark: 0xCEBB9D
    )

    /// Archived / neutral tag background
    public static let neutralSoft = dynamicColor(
        light: 0xEFE7DA,
        dark: 0x332B22
    )

    /// Destructive + photo count badge
    public static let danger = dynamicColor(
        light: 0xC8432E,
        dark: 0xE87058
    )

    /// Primary pill button background — inverts direction in dark
    public static let actionPrimary = dynamicColor(
        light: 0x1A1A1A,
        dark: 0xFAF6F0
    )

    public static let actionPrimaryPressed = dynamicColor(
        light: 0x000000,
        dark: 0xFFFFFF
    )

    /// Text on primary pill — white on black (light), neutral900 on cream (dark)
    public static let onPrimary = dynamicColor(
        light: 0xFFFFFF,
        dark: 0x241D17
    )

    /// App-wide dot-grid tint — warm brown @ 35% in light; neutral50 @ ~5% in dark (texture fades back)
    public static let dotGrid = dynamicColor(
        light: 0x503E2D,
        lightAlpha: 0.349,
        dark: 0xFAF6F0,
        darkAlpha: 0.051
    )

    // MARK: - Aliases (historical names for the same role)

    /// Alias of `onAccent`
    public static let textOnAccent = onAccent
}

// MARK: - Helpers

private func dynamicColor(
    light: UInt32, lightAlpha: CGFloat = 1,
    dark: UInt32,  darkAlpha:  CGFloat = 1
) -> Color {
    Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(rgb: dark, alpha: darkAlpha)
            : UIColor(rgb: light, alpha: lightAlpha)
    })
}

private extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        let r = CGFloat((rgb >> 16) & 0xFF) / 255
        let g = CGFloat((rgb >>  8) & 0xFF) / 255
        let b = CGFloat( rgb        & 0xFF) / 255
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }
}

public extension Color {
    /// `Color(hex: 0xE77850)` — read hex literals the same way you write them.
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >>  8) & 0xFF) / 255.0
        let b = Double( hex        & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
