/*
 * BottomNav.swift
 *
 * Floating editorial card bottom navigation — five tabs on an opaque warm
 * cream surface with a hairline border and soft shadow, hovering over the
 * canvas with a lifted coral leaf in the center.
 *
 * Layout:
 *   [ Home ] [ Library ] [ 🌿 Leaf ] [ Notepad ] [ Settings ]
 *
 * Surface:
 *   - Shape  : rounded card @ AppRadius.nav (16pt).
 *   - Fill   : AppColors.cardSolid (opaque warm cream — matches the Card DS).
 *   - Border : 1pt AppColors.borderDefault hairline.
 *   - Shadow : `md` — soft lift so the pill reads as hovering.
 *
 * Active tab: coralSoft rounded rect behind icon, coral tint.
 * Inactive  : textPrimary icon, no background.
 * Center    : coral-filled circle, white leaf icon, lifted ~10pt.
 * Margins   : 16pt horizontal, 4pt bottom (leaves space for safe area).
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

// MARK: - Item model

public struct BottomNavItem: Identifiable, Equatable {
    public enum Kind: Equatable { case regular, brand }

    public let id: String
    public let title: String
    public let systemIcon: String
    public let kind: Kind

    public init(id: String, title: String, systemIcon: String, kind: Kind = .regular) {
        self.id = id
        self.title = title
        self.systemIcon = systemIcon
        self.kind = kind
    }

    /// Default Releaf IA.
    public static let defaults: [BottomNavItem] = [
        BottomNavItem(id: "home",     title: "Home",     systemIcon: "house"),
        // Tab id stays "notebook" so existing navigation keeps
        // working; label + icon reflect the Library rename — the
        // bookshelf-row glyph (`books.vertical`) reads as a row of
        // book spines, the same metaphor as the prior Shelf icon
        // but tied to the new Library label.
        BottomNavItem(id: "notebook", title: "Library", systemIcon: "books.vertical"),
        BottomNavItem(id: "leaf",     title: "",         systemIcon: "leaf.fill", kind: .brand),
        BottomNavItem(id: "notepad",  title: "Notepad",  systemIcon: "note.text"),
        BottomNavItem(id: "settings", title: "Settings", systemIcon: "gearshape"),
    ]
}

// MARK: - BottomNav

public struct BottomNav: View {
    private let items: [BottomNavItem]
    @Binding private var selection: String
    private let onBrandTap: (() -> Void)?

    public init(
        items: [BottomNavItem] = BottomNavItem.defaults,
        selection: Binding<String>,
        onBrandTap: (() -> Void)? = nil
    ) {
        self.items = items
        self._selection = selection
        self.onBrandTap = onBrandTap
    }

    public var body: some View {
        HStack(alignment: .center, spacing: 0) {
            ForEach(items) { item in
                switch item.kind {
                case .regular:
                    RegularTab(
                        item: item,
                        isSelected: selection == item.id,
                        onTap: { selection = item.id }
                    )
                    .frame(maxWidth: .infinity)

                case .brand:
                    BrandTab(
                        item: item,
                        isSelected: selection == item.id
                    ) {
                        if let onBrandTap {
                            onBrandTap()
                        } else {
                            selection = item.id
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        // Inner horizontal tightened s1 → s0 so each cell gets the full
        // bar width to lay out its icon + label without crowding.
        .padding(.horizontal, AppSpacing.s0)
        .padding(.vertical, AppSpacing.s1)
        .background(cardBackground)
        .overlay(hairlineBorder)
        // NOTE: no clipShape here — that would clip the lifted center leaf
        // button, which intentionally renders above the pill's top edge.
        // Outer horizontal widened s6 → s4 so the bar fits 5 cells with
        // text labels without crowding; bottom s6 still lifts it clear
        // of the system nav handle.
        .padding(.horizontal, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s6)
    }

    // MARK: - Editorial card surface

    /// Opaque warm cream card with a soft `md` shadow — reads as a
    /// hovering paper chip over the canvas.
    private var cardBackground: some View {
        RoundedRectangle(cornerRadius: AppRadius.nav, style: .continuous)
            .fill(AppColors.cardSolid)
            .appShadow(.md)
    }

    /// Warm hairline border — matches the Card DS on every other surface.
    private var hairlineBorder: some View {
        RoundedRectangle(cornerRadius: AppRadius.nav, style: .continuous)
            .strokeBorder(AppColors.borderDefault, lineWidth: 1)
    }
}

// MARK: - Regular tab

private struct RegularTab: View {
    let item: BottomNavItem
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        // Vertical icon-over-label cell — matches Android's BottomNav.kt
        // RegularTab. When selected, both the icon and label sit inside a
        // coralSoft rounded-rectangle pill so the tab reads as a single
        // highlighted chip. Inactive tabs render flat — same icon + label,
        // no background, primary-text colour.
        let tint = isSelected ? AppColors.coral : AppColors.textPrimary
        let bg   = isSelected ? AppColors.coralSoft : Color.clear

        Button(action: onTap) {
            VStack(spacing: 2) {
                Image(systemName: item.systemIcon)
                    .font(.system(size: 20))
                    .foregroundColor(tint)
                Text(item.title)
                    .font(AppText.tag)
                    .foregroundColor(tint)
            }
            .padding(.horizontal, AppSpacing.s2)
            .padding(.vertical, AppSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(bg)
            )
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(item.title))
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}

// MARK: - Brand (center leaf) tab

private struct BrandTab: View {
    let item: BottomNavItem
    let isSelected: Bool
    let onTap: () -> Void

    private let diameter: CGFloat = 56
    private let lift: CGFloat = 16
    private let activeRing: CGFloat = 1.5

    /// Coral → coralDeep top-to-bottom gradient. Lighter at top, deeper at
    /// bottom — reads as a subtle 3D lift under ambient light.
    private var coralGradient: LinearGradient {
        LinearGradient(
            colors: [AppColors.coral, AppColors.coralDeep],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                // Coral disc.
                Circle()
                    .fill(coralGradient)
                    .frame(width: diameter, height: diameter)

                // Active-tab ring — thin coral outline at the outer
                // edge of the disc when Capture is the current tab.
                // Hidden otherwise. Mirrors Android's BottomNav.kt
                // BrandTab per CAPTURE_TAB_PLAN.md Phase 3.
                if isSelected {
                    Circle()
                        .strokeBorder(AppColors.coral, lineWidth: activeRing)
                        .frame(
                            width: diameter + 8,
                            height: diameter + 8
                        )
                }

                // Brand mark — cream Releaf leaf on the coral disc.
                // Uses ReleafLogoSolid so the leaf silhouette matches the
                // app icon, splash, and logo lockup. `item.systemIcon`
                // is ignored for the .brand kind; it stays on the data
                // model for parity with .regular but isn't rendered.
                ReleafLogoSolid(
                    size: 30,
                    leafColor: AppColors.textOnAccent,
                    veinColor: AppColors.coralDeep,
                    veinWidth: 1.5
                )
            }
            .appShadow(.fab)
            .offset(y: -lift)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Releaf")
        .accessibilityHint(isSelected ? "Capture tab" : "Opens Capture tab")
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}

#if DEBUG
struct BottomNav_Previews: PreviewProvider {
    struct Host: View {
        @State private var selection = "home"
        var body: some View {
            ZStack {
                DotGridBackground().ignoresSafeArea()
                VStack {
                    Spacer()
                    Text("Selected: \(selection)")
                        .font(AppText.meta)
                        .foregroundColor(AppColors.textSecondary)
                    Spacer()
                    BottomNav(selection: $selection)
                }
            }
        }
    }

    static var previews: some View {
        Host().previewDisplayName("Releaf BottomNav · Editorial Card")
    }
}
#endif
