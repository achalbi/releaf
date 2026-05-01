/*
 * CaptureTabBar.swift
 *
 * Segmented-picker pill for the page editor tabs:
 *   Overview · Photos · Voice · To-do · Scans · Contacts · Location
 *
 * Visual: a rounded-rect Card-toned pill with seven equal-weight icon
 * segments. A single rounded-rect indicator sits behind the icons and
 * SLIDES between segments via a spring animation when the selection
 * changes — mirrors the Android `CaptureTabBar` pattern. The
 * indicator's fill is the user's active accent palette
 * (`@Environment(\.accentPalette).primary`) so a green-themed notebook
 * gets a green active tab without each notebook overriding the bar.
 *
 * No horizontal scroll — at 360pt width seven equal-weight segments
 * fit cleanly inside the pill with 4pt gutters.
 */

import SwiftUI

// Inner height of the segmented row. The active indicator inside
// matches this height. Was 36 originally; the tile felt undersized at
// the first/last tab so it was bumped to 42.
private let segmentInnerHeight: CGFloat = 42

public struct CaptureTabBar: View {
    private let modes: [CaptureMode]
    @Binding private var selected: CaptureMode
    /// Optional override for the active-tile fill color. When nil,
    /// the bar falls back to the global accent palette set on the
    /// environment. Used by PageDetail to thread the parent
    /// notebook's color into the switcher so the whole surface
    /// reads as one family.
    private let accentOverride: Color?

    @Environment(\.accentPalette) private var accent

    public init(
        modes: [CaptureMode] = CaptureMode.allCases,
        selected: Binding<CaptureMode>,
        accentOverride: Color? = nil
    ) {
        self.modes = modes
        self._selected = selected
        self.accentOverride = accentOverride
    }

    public var body: some View {
        // Defensive: an empty `modes` list would break the divide
        // below. Not expected in production — `CaptureMode.allCases`
        // is never empty — but cheap to guard.
        guard !modes.isEmpty else { return AnyView(EmptyView()) }

        let selectedIndex = max(modes.firstIndex(of: selected) ?? 0, 0)

        // GeometryReader gives us the inner padded width of the pill
        // so the indicator and the segment cells measure against the
        // same canvas — offsets line up to the pixel.
        return AnyView(
            GeometryReader { geo in
                let segmentWidth = geo.size.width / CGFloat(modes.count)
                ZStack(alignment: .topLeading) {
                    // Sliding indicator. Renders behind the icon row
                    // so the icon's tint flips on the
                    // currently-selected cell. `.animation` watches
                    // `selected` so any selection change springs the
                    // offset to the new segment.
                    //
                    // Radius is `AppRadius.md` (12pt) — chosen so the
                    // indicator sits concentrically inside the outer
                    // pill (`AppRadius.lg` = 16pt) with the 4pt
                    // gutter accounting for the 4pt difference. At
                    // `sm` the corners read as too sharp against the
                    // softer outer pill, most visibly when the
                    // indicator parks at the first or last tab.
                    RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                        .fill(accentOverride ?? accent.primary)
                        .frame(width: segmentWidth, height: geo.size.height)
                        .offset(x: segmentWidth * CGFloat(selectedIndex))
                        .animation(
                            .spring(response: 0.32, dampingFraction: 1.0),
                            value: selected
                        )

                    // Tap-targets + icons. Each segment claims equal
                    // weight so the indicator's geometry
                    // (segmentWidth) matches the segment's own bounds.
                    HStack(spacing: 0) {
                        ForEach(modes) { mode in
                            CaptureTabButton(
                                mode: mode,
                                isSelected: selected == mode,
                                onTap: { selected = mode }
                            )
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                }
            }
            .frame(height: segmentInnerHeight)
            .padding(AppSpacing.s1)
            .background(AppColors.cardSolid.opacity(0.55))
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous))
        )
    }
}

// MARK: - Button

private struct CaptureTabButton: View {
    let mode: CaptureMode
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        // Icon-only tap target. The active fill lives on the sliding
        // indicator behind this layer — the button itself stays
        // transparent so the indicator shows through with the
        // selected tint flipped via `foregroundColor`.
        Button(action: onTap) {
            Image(systemName: mode.systemIcon)
                .font(.system(size: 18, weight: isSelected ? .semibold : .regular))
                .foregroundColor(isSelected ? AppColors.textOnAccent : AppColors.textPrimary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        let palette: AccentPalette
        var body: some View {
            VStack(spacing: AppSpacing.s4) {
                CaptureTabBar(selected: $mode)
                    .accentPalette(palette)
                Text("Selected: \(mode.title)")
                    .font(AppText.meta)
                    .foregroundColor(AppColors.textSecondary)
                Spacer()
            }
            .padding()
            .background(AppColors.canvas)
        }
    }

    static var previews: some View {
        Group {
            Host(palette: AccentPalettes.green)
                .frame(width: 360, height: 200)
                .previewDisplayName("Green palette (notebook themed)")
            Host(palette: AccentPalettes.coral)
                .frame(width: 360, height: 200)
                .previewDisplayName("Coral palette (default)")
        }
    }
}
#endif
