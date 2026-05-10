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

    /// Slide-in (eased back overshoot) → hold → slide-out (eased
    /// in cubic). Multiplied opacity collapses the timeline to a
    /// single composite alpha + tx.
    @ViewBuilder
    private var content: some View {
        let slideIn  = ease(LaunchEasing.easeOutBack, between(time, 0.5, 1.4))
        let slideOut = 1 - ease(LaunchEasing.easeInCubic, between(time, 4.45, 5.0))
        let op = max(0, min(1, slideIn * slideOut))
        let tx = (1 - slideIn) * -120
        let cT = ease(LaunchEasing.easeOutCubic, between(time, 1.85, 4.1))
        let value = Int((Double(target) * cT).rounded())
        let ticking = cT > 0 && cT < 0.99
        let pulse = ticking ? 1 + 0.025 * abs(sin(time * 13.5)) : 1.0
        let celebrate = ease(LaunchEasing.easeOutBack, between(time, 4.0, 4.3))
        let celeFade  = ease(LaunchEasing.easeInCubic, between(time, 4.2, 4.45))
        let celeScale = 1 + celebrate * 0.08 * (1 - celeFade)

        HStack(spacing: 9) {
            // Leaf glyph — same path commands as the JSX
            // `<svg viewBox="0 0 18 18">` source.
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
                ctx.fill(path, with: .color(palette.badgeAccent))
            }
            .frame(width: 18, height: 18)

            VStack(alignment: .leading, spacing: 3) {
                Text("\(value)")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(palette.badgeText)
                    .monospacedDigit()
                Text("Tree Points")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(palette.badgeAccent)
                    .tracking(1.1) // matches JSX 0.12em letterSpacing
                    .textCase(.uppercase)
            }
        }
        .padding(.leading, 11)
        .padding(.trailing, 14)
        .padding(.vertical, 9)
        .background(
            ZStack {
                // Glass-blur on a tinted plate. iOS's `.regularMaterial`
                // gives us the same backdrop-filter feel the JSX uses
                // via `backdropFilter: 'blur(20px)'`.
                if #available(iOS 15.0, *) {
                    Color.clear.background(.regularMaterial)
                }
                palette.badgeBg
            }
            .clipShape(Capsule())
        )
        .overlay(
            Capsule()
                .strokeBorder(palette.badgeAccent.opacity(0.2), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.22), radius: 11, x: 0, y: 8)
        .scaleEffect(pulse * celeScale)
        .offset(x: tx, y: 0)
        .opacity(op)
        // The +1 floater visible while the counter is still climbing.
        .overlay(alignment: .topTrailing) {
            if ticking {
                Text("+1")
                    .font(.system(size: 10, weight: .bold))
                    .monospacedDigit()
                    .foregroundStyle(palette.badgeAccent)
                    .opacity(0.5 + 0.5 * abs(sin(time * 13)))
                    .offset(y: -10 - abs(sin(time * 13)) * 4)
                    .padding(.trailing, 6)
                    .opacity(op)
            }
        }
        .position(x: 18 + 60, y: 64 + 28) // anchor approx top-left
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

// MARK: - Home-feed transition

@MainActor
struct LaunchHomeFeedTransition: View {
    let time: Double
    let palette: LaunchPalette
    let target: Int

    var body: some View {
        let op = ease(LaunchEasing.easeOutCubic, between(time, 4.55, 5.0))
        if op <= 0 {
            Color.clear
        } else {
            content(op: op)
        }
    }

    @ViewBuilder
    private func content(op: Double) -> some View {
        let ty = (1 - op) * 24
        let isDark  = palette.feedIsDark
        let surface = isDark ? Color(launchHex: 0x26161e) : Color.white
        let text    = isDark ? Color(launchHex: 0xfff5e3) : Color(launchHex: 0x0e1f15)
        let muted   = isDark ? Color(rgba: 255, 245, 227, 0.55)
                             : Color(rgba: 14, 31, 21, 0.55)

        VStack(alignment: .leading, spacing: 0) {
            // Header.
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("GOOD MORNING")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(2)
                        .foregroundStyle(muted)
                    Text("My QuickInk")
                        .font(.system(size: 26, weight: .bold))
                        .tracking(-0.5)
                        .foregroundStyle(text)
                        .padding(.top, 2)
                }
                Spacer()
                HStack(spacing: 6) {
                    Canvas { ctx, _ in
                        let path = leafPath()
                        ctx.fill(path, with: .color(Color(launchHex: 0x9ade7a)))
                    }
                    .frame(width: 11, height: 11)
                    Text("\(target)")
                        .font(.system(size: 12, weight: .bold))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(palette.feedAccent)
                .clipShape(Capsule())
            }
            .padding(.bottom, 22)

            // Hero — "You've helped plant N trees".
            HStack(spacing: 14) {
                ZStack {
                    Color(rgba: 154, 222, 122, 0.22)
                    Canvas { ctx, _ in
                        let path = leafPath()
                        // Draw at ~26x26 within a 14x14 view space.
                        var c = ctx
                        c.scaleBy(x: 26.0/18.0, y: 26.0/18.0)
                        c.fill(path, with: .color(Color(launchHex: 0x9ade7a)))
                    }
                    .frame(width: 26, height: 26)
                }
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                VStack(alignment: .leading, spacing: 2) {
                    Text("YOU'VE HELPED PLANT")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(2)
                        .foregroundStyle(.white.opacity(0.7))
                    Text("\(target) trees")
                        .font(.system(size: 22, weight: .bold))
                        .monospacedDigit()
                        .tracking(-0.5)
                        .foregroundStyle(Color(launchHex: 0xf3fbe6))
                }
                Spacer()
                Text("›")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.5))
            }
            .padding(18)
            .background(palette.feedAccent)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.bottom, 14)

            // Recent notebooks header.
            Text("RECENT NOTEBOOKS")
                .font(.system(size: 11, weight: .semibold))
                .tracking(2)
                .foregroundStyle(muted)
                .padding(.bottom, 10)

            // List rows.
            VStack(spacing: 8) {
                feedRow(kind: "note", title: "Daily journal",   meta: "24 pages · today",
                        surface: surface, text: text, muted: muted, isDark: isDark)
                feedRow(kind: "idea", title: "Sketch ideas",    meta: "18 pages · 2d ago",
                        surface: surface, text: text, muted: muted, isDark: isDark)
                feedRow(kind: "todo", title: "Garden to-do",    meta: "12 pages · 3d ago",
                        surface: surface, text: text, muted: muted, isDark: isDark)
                feedRow(kind: "mtg",  title: "Family meetings", meta: "9 pages · 1w ago",
                        surface: surface, text: text, muted: muted, isDark: isDark)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.top, 64)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.feedBg)
        .opacity(op)
        .offset(y: ty * 0.4)
        .allowsHitTesting(false)
    }

    @ViewBuilder
    private func feedRow(
        kind: String, title: String, meta: String,
        surface: Color, text: Color, muted: Color, isDark: Bool
    ) -> some View {
        HStack(spacing: 12) {
            ZStack {
                palette.feedAccent.opacity(18.0/255.0)
                Canvas { ctx, _ in feedIcon(ctx: ctx, kind: kind, color: palette.feedAccent) }
                    .frame(width: 18, height: 18)
            }
            .frame(width: 36, height: 36)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .tracking(-0.2)
                    .foregroundStyle(text)
                Text(meta)
                    .font(.system(size: 11))
                    .foregroundStyle(muted)
            }
            Spacer()
            Text("›")
                .font(.system(size: 18))
                .foregroundStyle(muted)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(surface)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: isDark ? .clear : .black.opacity(0.04), radius: 1, y: 1)
    }
}

// MARK: - Helpers

/// Leaf glyph used in both the points pill and the feed accent — a
/// lifted copy of the JSX path data, kept here so the two surfaces
/// can't drift in shape.
private func leafPath() -> Path {
    Path { p in
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
}

private func feedIcon(ctx: GraphicsContext, kind: String, color: Color) {
    var c = ctx
    let stroke = color
    switch kind {
    case "note":
        c.stroke(
            Path(roundedRect: CGRect(x: 3, y: 2.5, width: 12, height: 13), cornerRadius: 2),
            with: .color(stroke), style: StrokeStyle(lineWidth: 1.4)
        )
        for y in [6.0, 9, 12] {
            c.stroke(
                Path { p in
                    p.move(to: CGPoint(x: 6, y: y))
                    p.addLine(to: CGPoint(x: y == 12 ? 10 : 12, y: y))
                },
                with: .color(stroke),
                style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
            )
        }
    case "idea":
        c.stroke(
            Path { p in
                p.move(to: CGPoint(x: 9, y: 2))
                p.addCurve(to: CGPoint(x: 4, y: 6.8),
                           control1: CGPoint(x: 5.5, y: 2),
                           control2: CGPoint(x: 4,   y: 4.5))
                p.addCurve(to: CGPoint(x: 6, y: 10.8),
                           control1: CGPoint(x: 4,  y: 8.5),
                           control2: CGPoint(x: 5,  y: 9.5))
                p.addLine(to: CGPoint(x: 6, y: 12))
                p.addLine(to: CGPoint(x: 12, y: 12))
                p.addLine(to: CGPoint(x: 12, y: 10.8))
                p.addCurve(to: CGPoint(x: 14, y: 6.8),
                           control1: CGPoint(x: 13, y: 9.5),
                           control2: CGPoint(x: 14, y: 8.5))
                p.addCurve(to: CGPoint(x: 9, y: 2),
                           control1: CGPoint(x: 14, y: 4.5),
                           control2: CGPoint(x: 12.5, y: 2))
                p.closeSubpath()
            },
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4)
        )
    case "todo":
        c.stroke(
            Path(roundedRect: CGRect(x: 2.5, y: 2.5, width: 6, height: 6), cornerRadius: 1.4),
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4)
        )
        c.stroke(
            Path { p in
                p.move(to: CGPoint(x: 4, y: 5.5))
                p.addLine(to: CGPoint(x: 5, y: 6.5))
                p.addLine(to: CGPoint(x: 7, y: 4.5))
            },
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4, lineCap: .round, lineJoin: .round)
        )
        c.stroke(
            Path(roundedRect: CGRect(x: 2.5, y: 9.5, width: 6, height: 6), cornerRadius: 1.4),
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4)
        )
        for y in [5.5, 12.5] {
            c.stroke(
                Path { p in
                    p.move(to: CGPoint(x: 10.5, y: y))
                    p.addLine(to: CGPoint(x: 15.5, y: y))
                },
                with: .color(stroke),
                style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
            )
        }
    default: // mtg
        c.stroke(
            Path(ellipseIn: CGRect(x: 3.8, y: 4.3, width: 4.4, height: 4.4)),
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4)
        )
        c.stroke(
            Path(ellipseIn: CGRect(x: 10.2, y: 5.7, width: 3.6, height: 3.6)),
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4)
        )
        c.stroke(
            Path { p in
                p.move(to: CGPoint(x: 2.5, y: 14))
                p.addCurve(to: CGPoint(x: 9.5, y: 14),
                           control1: CGPoint(x: 2.5, y: 11.8),
                           control2: CGPoint(x: 4,   y: 10.5))
            },
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
        )
        c.stroke(
            Path { p in
                p.move(to: CGPoint(x: 9, y: 14))
                p.addCurve(to: CGPoint(x: 15, y: 14),
                           control1: CGPoint(x: 9,    y: 12.4),
                           control2: CGPoint(x: 10.5, y: 11.5))
            },
            with: .color(stroke),
            style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
        )
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
