/*
 * ReleafLogo.swift
 * Leaf brand mark. Path is the canonical 24-unit SVG from the Releaf
 * Branding spec (April 2026). Three layers:
 *   1. Green gradient fill (primary → deep, top-to-bottom)
 *   2. Cream (OnAccent) outline — the iconic brand stroke
 *   3. S-curve vein in OnAccent @ 40% alpha
 * Callers can force an outline-only variant (for monochrome contexts,
 * like the splash's cream leaf on the coral/green plate) via
 * `ReleafLogoOutline`.
 */

import SwiftUI

public struct ReleafLeafBody: Shape {
    public init() {}

    public func path(in rect: CGRect) -> Path {
        // Scale the 24-unit viewBox into the provided rect's min dimension
        // so the leaf stays square even if the caller hands us a wider
        // container.
        let s = min(rect.width, rect.height) / 24
        func x(_ n: CGFloat) -> CGFloat { n * s }
        func y(_ n: CGFloat) -> CGFloat { n * s }
        var p = Path()
        p.move(to: CGPoint(x: x(12), y: y(3)))
        p.addCurve(to: CGPoint(x: x(6), y: y(12)),
                   control1: CGPoint(x: x(12), y: y(3)),
                   control2: CGPoint(x: x(6), y: y(6)))
        p.addCurve(to: CGPoint(x: x(11), y: y(20)),
                   control1: CGPoint(x: x(6), y: y(15.5)),
                   control2: CGPoint(x: x(8), y: y(18.5)))
        p.addCurve(to: CGPoint(x: x(12), y: y(21)),
                   control1: CGPoint(x: x(11.5), y: y(20.2)),
                   control2: CGPoint(x: x(12), y: y(20.5)))
        p.addCurve(to: CGPoint(x: x(13), y: y(20)),
                   control1: CGPoint(x: x(12), y: y(20.5)),
                   control2: CGPoint(x: x(12.5), y: y(20.2)))
        p.addCurve(to: CGPoint(x: x(18), y: y(12)),
                   control1: CGPoint(x: x(16), y: y(18.5)),
                   control2: CGPoint(x: x(18), y: y(15.5)))
        p.addCurve(to: CGPoint(x: x(12), y: y(3)),
                   control1: CGPoint(x: x(18), y: y(6)),
                   control2: CGPoint(x: x(12), y: y(3)))
        p.closeSubpath()
        return p
    }
}

public struct ReleafLeafVein: Shape {
    public init() {}

    public func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 24
        func x(_ n: CGFloat) -> CGFloat { n * s }
        func y(_ n: CGFloat) -> CGFloat { n * s }
        var p = Path()
        p.move(to: CGPoint(x: x(12), y: y(6)))
        p.addCurve(to: CGPoint(x: x(14), y: y(11.5)),
                   control1: CGPoint(x: x(12), y: y(6)),
                   control2: CGPoint(x: x(14), y: y(8.5)))
        p.addCurve(to: CGPoint(x: x(12), y: y(16)),
                   control1: CGPoint(x: x(14), y: y(13.5)),
                   control2: CGPoint(x: x(13), y: y(15)))
        return p
    }
}

/// Keeping the old `ReleafLeafShape` type around as an alias — it only
/// stroked the outline path and callers may still reach for it.
public typealias ReleafLeafShape = ReleafLeafBody

public struct ReleafLogo: View {
    public let size: CGFloat
    public let filled: Bool
    public let outlineColor: Color
    public let fillGradientStart: Color
    public let fillGradientEnd: Color
    public let lineWidth: CGFloat

    public init(
        size: CGFloat = 64,
        filled: Bool = true,
        outlineColor: Color = AppColors.onAccent,
        fillGradientStart: Color = AppColors.themeGreenPrimary,
        fillGradientEnd: Color = AppColors.themeGreenDeep,
        lineWidth: CGFloat = 2
    ) {
        self.size = size
        self.filled = filled
        self.outlineColor = outlineColor
        self.fillGradientStart = fillGradientStart
        self.fillGradientEnd = fillGradientEnd
        self.lineWidth = lineWidth
    }

    public var body: some View {
        ZStack {
            if filled {
                ReleafLeafBody()
                    .fill(
                        LinearGradient(
                            colors: [fillGradientStart, fillGradientEnd],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                ReleafLeafBody()
                    .stroke(fillGradientEnd, lineWidth: lineWidth * 0.25)
            }
            ReleafLeafBody()
                .stroke(
                    outlineColor,
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
                )
            ReleafLeafVein()
                .stroke(
                    outlineColor.opacity(0.4),
                    style: StrokeStyle(lineWidth: lineWidth * 0.67, lineCap: .round)
                )
        }
        .frame(width: size, height: size)
    }
}

/// Outline-only variant used where the leaf sits on a colored plate
/// (splash, onboarding hero). The body fill is skipped so the plate
/// color shows through the leaf.
public struct ReleafLogoOutline: View {
    public let size: CGFloat
    public let color: Color
    public let lineWidth: CGFloat

    public init(size: CGFloat = 96, color: Color = AppColors.onAccent, lineWidth: CGFloat = 3) {
        self.size = size
        self.color = color
        self.lineWidth = lineWidth
    }

    public var body: some View {
        ReleafLogo(
            size: size,
            filled: false,
            outlineColor: color,
            lineWidth: lineWidth
        )
    }
}

#Preview {
    VStack(spacing: 32) {
        ReleafLogo(size: 120)
        ZStack {
            AppColors.themeCoralPrimary.ignoresSafeArea()
            ReleafLogoOutline(size: 120, color: AppColors.onAccent, lineWidth: 3)
        }
        .frame(height: 200)
    }
    .padding()
    .background(AppColors.canvas)
}
