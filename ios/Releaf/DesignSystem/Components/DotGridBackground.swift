/*
 * DotGridBackground.swift
 * Releaf canvas + subtle dot-grid texture. Used app-wide at the root so every
 * screen inherits the notebook feel. Specs ported from design-tokens.json
 * (`color.pattern.dotGrid`, `pattern.dotGrid.*`).
 *
 * Cheap to render: one offscreen Canvas layer via `.drawingGroup()`, so it
 * doesn't cost per-frame during scrolls.
 */

import SwiftUI

public struct DotGridBackground: View {
    public var spacing: CGFloat
    public var dotSize: CGFloat
    public var dotColor: Color
    public var background: Color

    public init(
        spacing: CGFloat = 24,
        dotSize: CGFloat = 1,
        dotColor: Color = AppColors.dotGrid,
        background: Color = AppColors.canvas
    ) {
        self.spacing = spacing
        self.dotSize = dotSize
        self.dotColor = dotColor
        self.background = background
    }

    public var body: some View {
        Canvas { context, size in
            let radius = dotSize / 2
            var y: CGFloat = spacing / 2
            while y <= size.height {
                var x: CGFloat = spacing / 2
                while x <= size.width {
                    let rect = CGRect(
                        x: x - radius,
                        y: y - radius,
                        width: dotSize,
                        height: dotSize
                    )
                    context.fill(Path(ellipseIn: rect), with: .color(dotColor))
                    x += spacing
                }
                y += spacing
            }
        }
        .background(background)
        .drawingGroup() // render into one offscreen layer for smooth scrolling
    }
}

#Preview {
    DotGridBackground()
        .ignoresSafeArea()
}
