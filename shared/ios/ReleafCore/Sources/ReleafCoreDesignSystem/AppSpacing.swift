/*
 * AppSpacing.swift + AppRadius.swift
 * Spacing and radius tokens. 4-pt grid for spacing; four radii.
 */

import CoreGraphics

public enum AppSpacing {
    public static let s0:  CGFloat = 0
    public static let s1:  CGFloat = 4
    public static let s2:  CGFloat = 8
    public static let s3:  CGFloat = 12
    public static let s4:  CGFloat = 16
    public static let s5:  CGFloat = 20
    public static let s6:  CGFloat = 24
    public static let s8:  CGFloat = 32
    public static let s10: CGFloat = 40
}

public enum AppRadius {
    public static let sm:   CGFloat = 6
    public static let md:   CGFloat = 12
    public static let lg:   CGFloat = 16
    public static let pill: CGFloat = 9999

    /// Floating BottomNav radius. Currently aliases `lg` (16pt).
    /// Swappable so shape changes to the floating nav stay in one place.
    public static let nav: CGFloat = lg
}
