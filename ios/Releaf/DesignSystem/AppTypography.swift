/*
 * AppTypography.swift
 * Releaf type roles. Use `.font(AppText.body)`.
 *
 * Roles intentionally omit `weight:` so the user's global weight
 * (injected via `.appFontWeight(_:)` at the app root) cascades down
 * through SwiftUI's `.fontWeight(_:)` mechanism. Setting weight here
 * would freeze the value at app launch and ignore the setting.
 */

import SwiftUI

public enum AppText {
    public static let eyebrow        = Font.system(size: 10, design: .default)
    public static let body           = Font.system(size: 15, design: .default)
    public static let meta           = Font.system(size: 13, design: .default)
    public static let button         = Font.system(size: 13, design: .default)
    public static let sectionTitle   = Font.system(size: 20, design: .default)
    public static let statNumber     = Font.system(size: 32, design: .default)
    public static let editorialTitle = Font.system(size: 26, design: .serif)
    public static let pageTitle      = Font.system(size: 24, design: .serif)
    public static let tag            = Font.system(size: 11, design: .default)
}

/// Letter-spacing constants matching `design-tokens.json`.
public enum AppLetterSpacing {
    /// ≈ 0.08em applied as tracking at eyebrow's 10pt size.
    public static let eyebrow: CGFloat = 0.8
}
