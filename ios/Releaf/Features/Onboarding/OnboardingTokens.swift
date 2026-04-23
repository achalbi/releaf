/*
 * OnboardingTokens.swift
 *
 * Literal colour/typography tokens extracted from
 * docs/onboarding/source/onboarding.css. The wizard intentionally
 * uses fixed coral values (rather than the app's themeable accent)
 * so the first-run experience has a consistent brand identity.
 */

import SwiftUI

enum OnboardTokens {
    // Colours (from onboarding.css)
    static let coral           = Color(hex: 0xFF5F4E)
    static let coralFaded      = Color(hex: 0xFFB8B2)
    static let coralSoftBg     = Color(hex: 0xFFF5F4)
    static let coralSoftBorder = Color(hex: 0xFFD5D0)
    static let modalBg         = Color(hex: 0xFDF8F4)
    static let cardBg          = Color.white
    static let textPrimary     = Color(hex: 0x1B1B1D)
    static let textBody        = Color(hex: 0x4A4845)
    static let textMuted       = Color(hex: 0x7A7670)
    static let textSubtle      = Color(hex: 0x9E9990)
    static let borderRest      = Color(hex: 0xE2DBD4)
    static let lineFill        = Color(hex: 0xEDE8E3)
    static let ghostHoverBg    = Color(hex: 0xF0ECE8)
    static let todoTaskBg      = Color(hex: 0xFFF0E8)
    static let todoTaskFg      = Color(hex: 0xE06020)
    static let scanDoneBg      = Color(hex: 0xFFD5D1)
    static let scanDoneFg      = Color(hex: 0xC0392B)
    static let photoFrameBg    = Color(hex: 0xF0ECE8)
    static let photoFrameBorder = Color(hex: 0xE0D8D0)

    // App-icon gradient (see docs/onboarding/source/app-icon.svg)
    static let iconGradientStart = Color(hex: 0x123524)
    static let iconGradientEnd   = Color(hex: 0x3F7D58)
    static let iconSurface       = Color(hex: 0xF7F5EF)
    static let iconLine          = Color(hex: 0x2F5D50)
    static let iconDotFill       = Color(hex: 0xD98324)

    // Typography
    static let headline      = Font.system(size: 23, weight: .heavy, design: .default)
    static let body          = Font.system(size: 15, weight: .regular, design: .default)
    static let badge         = Font.system(size: 11, weight: .bold, design: .default)
    static let button        = Font.system(size: 14, weight: .bold, design: .default)
    static let skip          = Font.system(size: 12, weight: .regular, design: .default)
    static let calendarHeader = Font.system(size: 11, weight: .bold, design: .default)
    static let calendarNumber = Font.system(size: 38, weight: .heavy, design: .default)
    static let ctaLabel      = Font.system(size: 13, weight: .regular, design: .default)
    static let ctaLabelBold  = Font.system(size: 13, weight: .bold,    design: .default)
    static let scanPill      = Font.system(size: 10, weight: .semibold, design: .default)
    static let todoItem      = Font.system(size: 13, weight: .regular, design: .default)
    static let photoBadge    = Font.system(size: 12, weight: .semibold, design: .default)
}
