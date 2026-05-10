/*
 * LaunchOverlays.swift
 *
 * The three React-style overlay layers that ride above the SVG
 * Canvas in the launch animation: the Tree-points counter pill
 * (top-left), the centered QuickInk logo lockup, and the home-feed
 * transition that wipes in over the last ~450 ms before the splash
 * dismisses.
 *
 * These are direct ports of the same-named components in
 * `design_handoff_quickink_launch/source/scene.jsx`. We render them
 * as native SwiftUI views (not Canvas draw calls) because each one
 * is dominated by typography + layout rather than shapes — SwiftUI's
 * built-in text rendering, blur backgrounds, and rounded rectangles
 * are a more honest match for the JSX `<div>` markup than re-rolling
 * them inside a Canvas.
 *
 * Coordinates: the JSX literal `top: 64, left: 18` etc. are device-
 * pixel offsets (the prototype's iOS frame is ~402 wide, matching
 * the iPhone). We pass them through unchanged so the overlay sits
 * at the same ratio inside the device viewport without any
 * viewBox-scale gymnastics.
 *
 * Counterpart: Android `LaunchOverlays.kt`. Layout values must stay
 * in lockstep so the counter slides in at the same beat on both
 * platforms.
 */

import SwiftUI

// MARK: - Tree-points counter

@MainActor
struct LaunchPointsCounter: View {
    let target: Int
    let time: Double
    let palette: LaunchPalette
    let show: Bool

    var body: some View {
        if !show { Color.clear.frame(width: 0, height: 0) }
        else { content }
    }

    /// Slide-in (eased back overshoot from above) → hold → fade out.
    /// Centered horizontally at the top of the screen, with no
    /// background pill — the number sits directly on the sky
    /// gradient. Designed to read at a glance from across the room.
    @ViewBuilder
    private var content: some View {
        let slideIn  = ease(LaunchEasing.easeOutBack, between(time, 0.5, 1.4))
        let slideOut = 1 - ease(LaunchEasing.easeInCubic, between(time, 4.45, 5.0))
        let op = max(0, min(1, slideIn * slideOut))
        let ty = (1 - slideIn) * -30                       // drop in from above
        let cT = ease(LaunchEasing.easeOutCubic, between(time, 1.85, 4.1))
        let value = Int((Double(target) * cT).rounded())
        let ticking = cT > 0 && cT < 0.99
        let pulse = ticking ? 1 + 0.02 * abs(sin(time * 13.5)) : 1.0
        let celebrate = ease(LaunchEasing.easeOutBack, between(time, 4.0, 4.3))
        let celeFade  = ease(LaunchEasing.easeInCubic, between(time, 4.2, 4.45))
        let celeScale = 1 + celebrate * 0.10 * (1 - celeFade)

        // Use the deep-green feed accent for ink-on-cream contrast —
        // the badgeAccent (light green) doesn't read on the cream sky.
        let inkColor = palette.feedAccent

        VStack(spacing: 4) {
            HStack(alignment: .center, spacing: 10) {
                // Leaf glyph at hero size.
                Canvas { ctx, _ in
                    let path = Path { p in
                        p.move(to: CGPoint(x: 9, y: 1.5))
                        p.addCurve(
                            to:       CGPoint(x: 2.5, y: 12),
                            control1: CGPoint(x: 4,   y: 3),
                            control2: CGPoint(x: 1.5, y: 7)
                        )
                        p.addCurve(
                            to:       CGPoint(x: 6.8, y: 11.2),
                            control1: CGPoint(x: 4,   y: 12.3),
                            control2: CGPoint(x: 5.5, y: 12)
                        )
                        p.addLine(to: CGPoint(x: 6.8, y: 8))
                        p.addLine(to: CGPoint(x: 7.6, y: 8))
                        p.addLine(to: CGPoint(x: 7.6, y: 10.7))
                        p.addCurve(
                            to:       CGPoint(x: 10.5, y: 7.2),
                            control1: CGPoint(x: 8.7,  y: 9.9),
                            control2: CGPoint(x: 9.7,  y: 8.7)
                        )
                        p.addLine(to: CGPoint(x: 9, y: 7))
                        p.addLine(to: CGPoint(x: 10.8, y: 6.5))
                        p.addCurve(
                            to:       CGPoint(x: 12, y: 3.4),
                            control1: CGPoint(x: 11.3, y: 5.5),
                            control2: CGPoint(x: 11.7, y: 4.5)
                        )
                        p.addCurve(
                            to:       CGPoint(x: 9, y: 1.5),
                            control1: CGPoint(x: 11,  y: 2.4),
                            control2: CGPoint(x: 10,  y: 1.8)
                        )
                        p.closeSubpath()
                    }
                    // Scale the 18×18 viewBox up to 36×36.
                    var c = ctx
                    c.scaleBy(x: 36.0/18.0, y: 36.0/18.0)
                    c.fill(path, with: .color(inkColor))
                }
                .frame(width: 36, height: 36)

                Text("\(value)")
                    .font(.system(size: 56, weight: .bold))
                    .foregroundStyle(inkColor)
                    .monospacedDigit()
            }
            Text("TREE POINTS")
                .font(.system(size: 12, weight: .semibold))
                .tracking(3.5)
                .foregroundStyle(inkColor.opacity(0.7))
        }
        .scaleEffect(pulse * celeScale)
        .offset(y: ty)
        .opacity(op)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .padding(.top, 60)
        .allowsHitTesting(false)
    }
}

// MARK: - Logo lockup

@MainActor
struct LaunchLogoLockup: View {
    let time: Double
    let palette: LaunchPalette

    var body: some View {
        let inOp  = ease(LaunchEasing.easeOutCubic, between(time, 2.0, 2.6))
        let outOp = 1 - ease(LaunchEasing.easeInCubic, between(time, 4.5, 4.85))
        let op = inOp * outOp
        let ty = (1 - inOp) * 14
        let logoScale = 0.9 + 0.1 * inOp

        // Dark feedBg → invert glyph to read on dark canvas, otherwise
        // ink-dark on cream.
        let isDark = palette.feedIsDark

        VStack(spacing: 0) {
            Image("QuickInkMark", bundle: .module)
                .resizable()
                .scaledToFit()
                .frame(width: 110, height: 110)
                .colorInvertIfDark(isDark)
                .scaleEffect(logoScale)
            Text("QuickInk")
                .font(.system(size: 38, weight: .bold))
                .tracking(-1.5) // matches JSX letterSpacing -0.04em
                .foregroundStyle(isDark ? Color(launchHex: 0xfff5e3) : Color(launchHex: 0x0e1f15))
                .padding(.top, -6)
            Text("Ideas  ·  that  ·  grow")
                .font(.system(size: 11, weight: .semibold))
                .tracking(3.5) // matches JSX 0.32em
                .textCase(.uppercase)
                .foregroundStyle(palette.logoTagline)
                .padding(.top, 12)
        }
        .frame(maxWidth: .infinity)
        .offset(y: ty)
        .opacity(op)
        .allowsHitTesting(false)
    }
}

// MARK: - Palette extension + small helpers

extension LaunchPalette {
    /// Treat the feed background as "dark" if its first hex digit is 0
    /// or 1 (i.e. very low luminance), matching the JSX heuristic.
    /// Used by the logo + feed transition to flip text and glyph
    /// colors when the palette is `sunset`.
    var feedIsDark: Bool {
        // Cheap luminance proxy — extract sRGB components via UIColor.
        // SwiftUI's Color → UIColor bridge handles dynamic colors at
        // runtime; we resolve in light trait collection because the
        // launch animation always renders against the cream canvas
        // regardless of the user's system theme.
        #if canImport(UIKit)
        let ui = UIColor(self.feedBg)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        let lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum < 0.4
        #else
        return false
        #endif
    }
}

private extension View {
    /// Invert the rendered image to flip the dark calligraphic Q
    /// glyph to a light variant when the launch palette ships with
    /// a near-black canvas (`sunset`).
    @ViewBuilder
    func colorInvertIfDark(_ dark: Bool) -> some View {
        if dark { self.colorInvert().hueRotation(.degrees(180)) }
        else    { self }
    }
}
