/*
 * CardGuideOverlay.swift
 *
 * Translucent business-card-shaped guide drawn over the
 * AVCaptureSession preview. Three responsibilities (same as
 * Android's `CardGuideOverlay.kt`):
 *
 *   1. Frame the 1.586:1 / 70%-of-preview-width target so the
 *      user knows where to hold the card.
 *   2. Tint the frame neutral / yellow / green based on
 *      detection state.
 *   3. Render the "Hold steady…" / "Position card inside the
 *      frame" hint text below the guide.
 *
 * SwiftUI-pure — no UIView interop. The parent surface stacks
 * this on top of a video preview layer via ZStack.
 */

import SwiftUI

public enum OverlayState: Sendable {
    /// No valid quad anywhere. Neutral tint; hint blank.
    case neutral
    /// A quad is being seen but failed at least one gate. Yellow tint.
    case partial
    /// A valid quad — passed all gates. Green tint.
    case valid
    /// No detection for ≥8 s. Neutral tint; "Position card" hint.
    case idle
}

public struct GuideMetrics: Equatable, Sendable {
    public let rect: CGRect
    public let cornerRadius: CGFloat
    /// The canvas size this metrics block was computed against,
    /// in points. Carried through to the detector + post-
    /// processor so they can apply the same resizeAspectFill
    /// center-crop math the preview layer uses on-screen.
    public let canvasSize: CGSize

    /// ISO 7810 ID-1 aspect — same number as the spec's 1.586:1.
    public static let cardAspectRatio: CGFloat = 1.586
    /// Target guide width as a fraction of the preview width.
    public static let guideWidthFraction: CGFloat = 0.70

    public static func compute(canvasSize: CGSize) -> GuideMetrics {
        let targetW = canvasSize.width * guideWidthFraction
        let targetH = targetW / cardAspectRatio
        let cx = canvasSize.width * 0.5
        // 45% rather than 50% — leaves room for the hint text
        // between the guide and the shutter row.
        let cy = canvasSize.height * 0.45
        let rect = CGRect(
            x: cx - targetW / 2,
            y: cy - targetH / 2,
            width: targetW,
            height: targetH,
        )
        return GuideMetrics(
            rect: rect,
            cornerRadius: targetW * 0.04,
            canvasSize: canvasSize,
        )
    }
}

public struct CardGuideOverlay: View {

    public let state: OverlayState
    public let onMetricsKnown: (GuideMetrics) -> Void

    public init(state: OverlayState, onMetricsKnown: @escaping (GuideMetrics) -> Void) {
        self.state = state
        self.onMetricsKnown = onMetricsKnown
    }

    public var body: some View {
        GeometryReader { geo in
            let metrics = GuideMetrics.compute(canvasSize: geo.size)
            ZStack {
                // Punched-hole dim — scrim everywhere except the
                // card rect. SwiftUI achieves the cutout via an
                // even-odd-filled path that includes both the
                // full canvas and the rounded card rect; only
                // the difference renders as filled.
                Path { path in
                    path.addRect(CGRect(origin: .zero, size: geo.size))
                    path.addRoundedRect(
                        in: metrics.rect,
                        cornerSize: CGSize(width: metrics.cornerRadius, height: metrics.cornerRadius),
                    )
                }
                .fill(Color(red: 15/255, green: 14/255, blue: 13/255).opacity(0.80), style: FillStyle(eoFill: true))

                // Card-frame stroke.
                RoundedRectangle(cornerRadius: metrics.cornerRadius, style: .continuous)
                    .stroke(frameColor, lineWidth: 3)
                    .frame(width: metrics.rect.width, height: metrics.rect.height)
                    .position(x: metrics.rect.midX, y: metrics.rect.midY)
                    .animation(.easeInOut(duration: 0.15), value: state)

                // Hint text — anchored below the guide rect.
                if !hintText.isEmpty {
                    Text(hintText)
                        .font(QuickInkText.label)
                        .foregroundStyle(Color.white.opacity(0.85))
                        .position(
                            x: geo.size.width * 0.5,
                            y: metrics.rect.maxY + 32,
                        )
                }
            }
            .onAppear { onMetricsKnown(metrics) }
            .onChange(of: metrics) { newMetrics in onMetricsKnown(newMetrics) }
        }
    }

    private var frameColor: Color {
        switch state {
        case .valid:                return Color(red: 0x34/255, green: 0xD3/255, blue: 0x99/255)
        case .partial:              return Color(red: 0xFA/255, green: 0xCC/255, blue: 0x15/255)
        case .neutral, .idle:       return Color.white.opacity(0.65)
        }
    }

    private var hintText: String {
        switch state {
        case .valid:   return "Hold steady…"
        case .partial: return "Hold the card flat"
        case .idle:    return "Position card inside the frame"
        case .neutral: return ""
        }
    }
}
