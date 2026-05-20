/*
 * SundialCaptureMenu.swift
 *
 * Radial capture menu that fans three action buttons out in a 180°
 * arc above the centred ⚡ FAB on the bottom-nav. Tapping the FAB
 * opens the menu; each ray launches a specific capture mode
 * (Document / Business Card / Photo). Replaces the prior
 * tap-for-last-mode + long-press-for-photo idiom with a single
 * explicit choice surface.
 *
 *        Scan
 *      ╱
 *     ●  ← FAB centre (anchor)
 *      ╲
 *        Photo
 *
 *   - Card  → 150° (top-left)
 *   - Scan  →  90° (top, primary)
 *   - Photo →  30° (top-right)
 *
 * Geometry mirrors the design handoff: 110pt radius from the FAB
 * centre, vertical-spring overshoot on open, staggered left → top
 * → right (60ms between rays), simultaneous on close. Dim overlay
 * is the canvas cream at 0.7 + ultra-thin material blur.
 *
 * The menu renders as a full-screen sibling of the NavigationStack
 * so the dim overlay covers the canvas AND the floating nav bar
 * card. Rays sit ABOVE the dim but BELOW the FAB itself — the FAB
 * stays interactive throughout (toggle / close on second tap),
 * matching the prototype's z-stack.
 *
 * Mirror of Android `SundialCaptureMenu.kt`.
 */

import SwiftUI

/// Full-screen overlay that renders the three radial capture
/// buttons. Owns no state — the parent (MainShell) drives
/// `isOpen` and the three select callbacks plumb back into the
/// QuickCapture sheet's `initialMode`.
public struct SundialCaptureMenu: View {

    public let isOpen: Bool
    public let onClose: () -> Void
    public let onSelectDocument: () -> Void
    public let onSelectCard: () -> Void
    public let onSelectPhoto: () -> Void

    public init(
        isOpen: Bool,
        onClose: @escaping () -> Void,
        onSelectDocument: @escaping () -> Void,
        onSelectCard: @escaping () -> Void,
        onSelectPhoto: @escaping () -> Void
    ) {
        self.isOpen = isOpen
        self.onClose = onClose
        self.onSelectDocument = onSelectDocument
        self.onSelectCard = onSelectCard
        self.onSelectPhoto = onSelectPhoto
    }

    // Arc radius from FAB centre to each ray's button centre. 110pt
    // (vs. the spec's 120pt) shaves enough off the leftmost ray that
    // it stays clear of a 320pt iPhone SE's edge by a comfortable
    // margin. Larger devices read the slightly tighter arc as a more
    // confident grouping, not a constraint.
    private let radius: CGFloat = 110

    public var body: some View {
        GeometryReader { proxy in
            // Distance from the screen bottom to the FAB centre. The
            // nav bar uses `padding(.bottom, s6)` = 24pt below its
            // card surface, the card itself is ~64pt tall, and the
            // FAB is lifted -16pt from the card top — so the FAB
            // centre sits roughly safeAreaBottom + 24 + 32 (half
            // bar) + 16 (lift) - 32 (half FAB) = safeAreaBottom + 40.
            // Tuned empirically; matches the FAB drawn in
            // `QuickInkBottomNavBar.swift`.
            let bottomInset = proxy.safeAreaInsets.bottom
            let fabCentreY  = proxy.size.height - (bottomInset + 56)

            ZStack(alignment: .topLeading) {
                // Dim + blur overlay — covers the whole screen
                // including the safe area. Tap dismisses.
                Color.black
                    .opacity(isOpen ? 0.15 : 0)
                    .background(
                        Group {
                            if isOpen {
                                Rectangle()
                                    .fill(QuickInkColors.bg.opacity(0.45))
                                    .background(.ultraThinMaterial)
                            } else {
                                Color.clear
                            }
                        }
                    )
                    .ignoresSafeArea(.all)
                    .contentShape(Rectangle())
                    .onTapGesture { if isOpen { onClose() } }
                    .allowsHitTesting(isOpen)
                    .animation(.easeInOut(duration: 0.28), value: isOpen)

                // Ray anchor — three buttons emanating from the FAB
                // centre point. Each ray's offset is computed from
                // its angle + the shared radius.
                ZStack {
                    ray(
                        label: "Card",
                        systemImage: "person.text.rectangle",
                        angleDeg: 150,
                        openDelay: 0,
                        accessibilityLabel: "Scan business card",
                        action: onSelectCard
                    )
                    ray(
                        label: "Scan",
                        systemImage: "doc.text.viewfinder",
                        angleDeg: 90,
                        openDelay: 60,
                        accessibilityLabel: "Scan document",
                        action: onSelectDocument
                    )
                    ray(
                        label: "Photo",
                        systemImage: "camera",
                        angleDeg: 30,
                        openDelay: 120,
                        accessibilityLabel: "Take photo",
                        action: onSelectPhoto
                    )
                }
                .frame(width: 60, height: 60)
                .position(x: proxy.size.width / 2, y: fabCentreY)
                .allowsHitTesting(isOpen)
            }
        }
    }

    /// Single ray — circular icon button with a label chip beneath.
    /// Closed state: scale 0.4, opacity 0, sitting at the FAB
    /// centre. Open state: scale 1, opacity 1, translated out to its
    /// arc position. Spring + per-ray delay drive the stagger;
    /// reverse animations on close use the same spring with no delay
    /// so all three slingshot back simultaneously.
    @ViewBuilder
    private func ray(
        label: String,
        systemImage: String,
        angleDeg: Double,
        openDelay: Double,
        accessibilityLabel: String,
        action: @escaping () -> Void
    ) -> some View {
        let rad: Double = angleDeg * .pi / 180
        let dx: CGFloat = CGFloat(cos(rad)) * radius
        // CSS Y grows downward; here we negate the sin so positive
        // angles (above horizontal) translate UPWARD on screen.
        let dy: CGFloat = -CGFloat(sin(rad)) * radius

        Button(action: {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            action()
        }) {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .fill(QuickInkColors.surface)
                        .frame(width: 60, height: 60)
                        .shadow(
                            color: QuickInkColors.ink.opacity(0.22),
                            radius: 10,
                            x: 0,
                            y: 5
                        )
                    Image(systemName: systemImage)
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(QuickInkColors.accent)
                }
                Text(label)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(
                        Capsule().fill(QuickInkColors.surface.opacity(0.95))
                    )
                    .shadow(
                        color: QuickInkColors.ink.opacity(0.10),
                        radius: 4,
                        x: 0,
                        y: 2
                    )
                    .fixedSize()
            }
        }
        .buttonStyle(.plain)
        .scaleEffect(isOpen ? 1.0 : 0.4, anchor: .top)
        .offset(x: isOpen ? dx : 0, y: isOpen ? dy : 0)
        .opacity(isOpen ? 1.0 : 0)
        .animation(
            .interpolatingSpring(stiffness: 200, damping: 16)
                .delay(isOpen ? openDelay / 1000 : 0),
            value: isOpen
        )
        .accessibilityLabel(Text(accessibilityLabel))
    }
}
