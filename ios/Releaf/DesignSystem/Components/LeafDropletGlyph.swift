/*
 * LeafDropletGlyph.swift
 *
 * The tiny leaf/droplet glyph that sits in the corner of every stat
 * tile in the page Overview. Drawn as a pointed-top oval — a stylized
 * leaf seen from the side, not a literal water droplet.
 *
 * Color is keyed to the capture mode so the six tiles in the AT A
 * GLANCE grid read as a small palette rather than uniform decoration:
 *
 *   photos   → deep green (the main "growth" category)
 *   scans    → mid green
 *   todo     → light green (smaller, sproutier)
 *   contacts → info blue (people, not plants)
 *   place    → coral (location markers — the only coral in the grid)
 *   voice    → warning amber (transcripts decay; ephemeral)
 *
 * The mapping is intentional and lives in `tint(for:)` so call sites
 * don't pick their own colors.
 */

import SwiftUI

public struct LeafDropletGlyph: View {
    public let tint: Color
    public let size: CGFloat

    public init(tint: Color, size: CGFloat = 11) {
        self.tint = tint
        self.size = size
    }

    /// Lookup helper — call sites just pass the CaptureMode the tile
    /// represents and get the right tint back. Kept on the glyph so a
    /// single import covers the whole pattern.
    public static func tint(for mode: CaptureMode) -> Color {
        switch mode {
        case .photos:   return AppColors.green                          // deep forest
        case .scans:    return AppColors.themeGreenPrimary              // mid leaf
        case .todo:     return AppColors.themeGreenPrimary.opacity(0.55) // sprout
        case .contacts: return AppColors.info                           // people
        case .location: return AppColors.coralDeep                      // pin
        case .voice:    return AppColors.warning                        // amber
        case .overview: return AppColors.themeGreenPrimary
        }
    }

    public var body: some View {
        // The droplet shape: a circle pinched to a point at the top.
        // Built as a quadratic-curve path so it scales cleanly.
        Canvas { context, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            var path = Path()
            path.move(to: CGPoint(x: w / 2, y: 0))
            path.addQuadCurve(
                to: CGPoint(x: w / 2, y: h),
                control: CGPoint(x: w * 1.15, y: h * 0.45)
            )
            path.addQuadCurve(
                to: CGPoint(x: w / 2, y: 0),
                control: CGPoint(x: -w * 0.15, y: h * 0.45)
            )
            context.fill(path, with: .color(tint))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

#if DEBUG
struct LeafDropletGlyph_Previews: PreviewProvider {
    static var previews: some View {
        HStack(spacing: AppSpacing.s3) {
            LeafDropletGlyph(tint: AppColors.green)
            LeafDropletGlyph(tint: AppColors.themeGreenPrimary)
            LeafDropletGlyph(tint: AppColors.themeGreenPrimary.opacity(0.55))
            LeafDropletGlyph(tint: AppColors.info)
            LeafDropletGlyph(tint: AppColors.coralDeep)
            LeafDropletGlyph(tint: AppColors.warning)
        }
        .padding()
        .background(AppColors.canvas)
    }
}
#endif
