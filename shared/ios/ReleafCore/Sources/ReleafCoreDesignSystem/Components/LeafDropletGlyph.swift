/*
 * LeafDropletGlyph.swift
 *
 * The tiny leaf/droplet glyph that sits in the corner of every stat
 * tile in the page Overview, and beside the eyebrow on every page
 * header. Drawn as a pointed-top oval — a stylized leaf seen from the
 * side, not a literal water droplet.
 *
 * This shared definition is intentionally CaptureMode-agnostic so it
 * can live in `ReleafCoreDesignSystem` (which the page-header eyebrow
 * needs). The CaptureMode → tint mapping is provided as an extension
 * in the Releaf app target — see `Releaf/DesignSystem/Components/
 * LeafDropletGlyph.swift`.
 */

import SwiftUI

public struct LeafDropletGlyph: View {
    public let tint: Color
    public let size: CGFloat

    public init(tint: Color, size: CGFloat = 11) {
        self.tint = tint
        self.size = size
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
