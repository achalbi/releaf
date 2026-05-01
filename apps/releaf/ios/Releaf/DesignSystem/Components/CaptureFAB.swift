/*
 * CaptureFAB.swift
 *
 * Floating action button for opening the quick-capture sheet.
 *
 * Visual spec:
 *   - 56pt coral circle (#E77850)
 *   - White plus glyph, semibold
 *   - Soft coral-tinted shadow (AppShadow.fab)
 *   - Bottom-right anchored, 16–20pt clearance above the BottomNav
 *   - Respects safe-area bottom inset
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

// MARK: - CaptureFAB

public struct CaptureFAB: View {
    private let action: () -> Void
    private let icon: String
    private let size: CGFloat = 56

    public init(icon: String = "plus", action: @escaping () -> Void) {
        self.icon = icon
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(AppColors.coral)
                Image(systemName: icon)
                    .font(.system(size: 22))
                    .foregroundColor(AppColors.textOnAccent)
            }
            .frame(width: size, height: size)
            .appShadow(.fab)
            .contentShape(Circle())
        }
        .buttonStyle(CaptureFABButtonStyle())
        .accessibilityLabel("New capture")
        .accessibilityHint("Opens the quick-capture sheet")
    }
}

// MARK: - Press style (subtle scale + darken)

private struct CaptureFABButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1.0)
            .opacity(configuration.isPressed ? 0.92 : 1.0)
            .animation(.spring(response: 0.28, dampingFraction: 0.75), value: configuration.isPressed)
    }
}

// MARK: - Overlay helper

public extension View {
    /// Pins a CaptureFAB to the trailing-bottom corner, sitting
    /// `liftAboveBar` above the bottom nav while respecting the safe area.
    ///
    ///     content
    ///         .overlayCaptureFAB(liftAboveBar: 72) { viewModel.openCapture() }
    ///
    func overlayCaptureFAB(
        liftAboveBar: CGFloat = 72,
        trailingInset: CGFloat = AppSpacing.s4,
        action: @escaping () -> Void
    ) -> some View {
        self.overlay(alignment: .bottomTrailing) {
            CaptureFAB(action: action)
                .padding(.trailing, trailingInset)
                .padding(.bottom, liftAboveBar)
        }
    }
}

#if DEBUG
struct CaptureFAB_Previews: PreviewProvider {
    static var previews: some View {
        AppColors.canvas
            .ignoresSafeArea()
            .overlayCaptureFAB { }
            .previewDisplayName("CaptureFAB (pinned)")
    }
}
#endif
