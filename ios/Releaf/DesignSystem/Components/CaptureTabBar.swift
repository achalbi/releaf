/*
 * CaptureTabBar.swift
 *
 * Row of 7 icon buttons inside page detail:
 *   Overview · Photos · Voice · To-do · Scans · Contacts · Location
 *
 * - Active tab: filled coral rounded rectangle, white icon.
 * - Inactive : transparent, dark-brown icon, no label on mobile.
 * - 1pt canvas-tinted bottom border separates the bar from content.
 * - Spacing is tuned so all 7 tabs fit on the narrowest phone widths
 *   we support (iPhone SE at 375pt). The horizontal ScrollView
 *   wrapper stays as a safety net for locale/dynamic-type edge
 *   cases, but in practice nothing scrolls on production devices.
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

public struct CaptureTabBar: View {
    private let modes: [CaptureMode]
    @Binding private var selected: CaptureMode

    public init(
        modes: [CaptureMode] = CaptureMode.allCases,
        selected: Binding<CaptureMode>
    ) {
        self.modes = modes
        self._selected = selected
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            // Tight layout so all 7 tabs fit on a 375pt-wide screen
            // without scrolling: 7 × 44 (buttons) + 6 × 4 (gaps) +
            // 2 × 12 (side padding) = 356, leaves 19pt of buffer.
            HStack(spacing: AppSpacing.s1) {
                ForEach(modes) { mode in
                    CaptureTabButton(
                        mode: mode,
                        isSelected: selected == mode,
                        onTap: {
                            withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                                selected = mode
                            }
                        }
                    )
                }
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, AppSpacing.s2)
        }
        .background(
            Rectangle()
                .fill(AppColors.canvas)
                .overlay(
                    Rectangle()
                        .frame(height: 1)
                        .foregroundColor(AppColors.borderDefault),
                    alignment: .bottom
                )
        )
    }
}

// MARK: - Button

private struct CaptureTabButton: View {
    let mode: CaptureMode
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Image(systemName: mode.systemIcon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(isSelected ? AppColors.textOnAccent : AppColors.textPrimary)
                .frame(width: 44, height: 36)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                        .fill(isSelected ? AppColors.coral : Color.clear)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(mode.title))
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}

#if DEBUG
struct CaptureTabBar_Previews: PreviewProvider {
    struct Host: View {
        @State private var mode: CaptureMode = .overview
        var body: some View {
            VStack(spacing: AppSpacing.s4) {
                CaptureTabBar(selected: $mode)
                Text("Selected: \(mode.title)")
                    .font(AppText.meta)
                    .foregroundColor(AppColors.textSecondary)
                Spacer()
            }
            .background(AppColors.canvas)
        }
    }

    static var previews: some View {
        Host().frame(width: 390, height: 200)
    }
}
#endif
