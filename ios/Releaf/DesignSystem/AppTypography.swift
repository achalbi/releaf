/*
 * AppTypography.swift
 * Releaf type roles. Use `.font(AppText.body)`.
 */

import SwiftUI

public enum AppText {
    public static let eyebrow        = Font.system(size: 10, weight: .semibold, design: .default)
    public static let body           = Font.system(size: 15, weight: .regular,  design: .default)
    public static let meta           = Font.system(size: 13, weight: .regular,  design: .default)
    public static let button         = Font.system(size: 13, weight: .semibold, design: .default)
    public static let sectionTitle   = Font.system(size: 20, weight: .bold,     design: .default)
    public static let statNumber     = Font.system(size: 32, weight: .bold,     design: .default)
    public static let editorialTitle = Font.system(size: 26, weight: .medium,   design: .serif)
    public static let pageTitle      = Font.system(size: 24, weight: .semibold, design: .serif)
    public static let tag            = Font.system(size: 11, weight: .semibold, design: .default)
}

/// Letter-spacing constants matching `design-tokens.json`.
public enum AppLetterSpacing {
    /// ≈ 0.08em applied as tracking at eyebrow's 10pt size.
    public static let eyebrow: CGFloat = 0.8
}
