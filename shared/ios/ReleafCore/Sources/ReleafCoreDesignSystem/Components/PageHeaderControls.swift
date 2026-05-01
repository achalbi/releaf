/*
 * PageHeaderControls.swift
 *
 * Three small components that compose the top zone of PageDetailView:
 *
 *   - LeafEyebrow         — leaf glyph + tracked uppercase label
 *   - PageViewToggle      — two-tab segmented pill (list / grid)
 *   - PageOverflowButton  — round kebab button
 *
 * Decorative for now — the toggle is wired to a Binding so the parent
 * tracks the view mode, but routing list-vs-grid to actual layouts is
 * a follow-up. Same for the overflow: it accepts an action closure
 * but the action sheet hasn't been designed yet.
 *
 * Mirrors the Android `LeafEyebrow.kt` / `PageViewToggle.kt` /
 * `PageOverflowButton.kt`.
 */

import SwiftUI

// MARK: - LeafEyebrow

public struct LeafEyebrow: View {
    public let label: String
    public let onTap: (() -> Void)?
    /// Optional tint pair — when supplied, overrides the default
    /// green leaf color for the glyph and the eyebrow label. Used
    /// to thread per-notebook / per-shelf color into the header so
    /// the eyebrow matches the row chip the user just tapped.
    public let glyphTint: Color?
    public let labelTint: Color?

    public init(
        _ label: String,
        glyphTint: Color? = nil,
        labelTint: Color? = nil,
        onTap: (() -> Void)? = nil
    ) {
        self.label = label
        self.glyphTint = glyphTint
        self.labelTint = labelTint
        self.onTap = onTap
    }

    public var body: some View {
        let glyphAndText = HStack(spacing: AppSpacing.s1) {
            LeafDropletGlyph(
                tint: glyphTint ?? AppColors.themeGreenPrimary,
                size: 11
            )
            Text(label.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(labelTint ?? AppColors.themeGreenDeep)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(label))

        if let onTap {
            Button(action: onTap) { glyphAndText }
                .buttonStyle(.plain)
                .accessibilityHint("Go back")
        } else {
            glyphAndText
        }
    }
}

// MARK: - PageViewMode

public enum PageViewMode: String, CaseIterable, Codable, Sendable {
    case list
    case grid

    public var systemIcon: String {
        switch self {
        case .list: return "list.bullet"
        case .grid: return "square.grid.2x2"
        }
    }
}

// MARK: - PageViewToggle

public struct PageViewToggle: View {
    @Binding private var selected: PageViewMode

    @Environment(\.accentPalette) private var accent

    public init(selected: Binding<PageViewMode>) {
        self._selected = selected
    }

    public var body: some View {
        HStack(spacing: 2) {
            ForEach(PageViewMode.allCases, id: \.self) { mode in
                Button {
                    withAnimation(.spring(response: 0.25, dampingFraction: 0.85)) {
                        selected = mode
                    }
                } label: {
                    Image(systemName: mode.systemIcon)
                        .font(.system(size: 11, weight: selected == mode ? .semibold : .regular))
                        .foregroundStyle(selected == mode
                                         ? AppColors.textOnAccent
                                         : AppColors.textSecondary)
                        .frame(width: 26, height: 22)
                        .background(
                            Capsule()
                                .fill(selected == mode ? accent.primary : Color.clear)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(mode.rawValue.capitalized))
                .accessibilityAddTraits(selected == mode ? [.isSelected] : [])
            }
        }
        .padding(3)
        .background(AppColors.cardSolid.opacity(0.6))
        .overlay(
            Capsule().stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(Capsule())
    }
}

// MARK: - PageOverflowButton

/// Round overflow button that opens a SwiftUI Menu of the parent's
/// items. Caller provides the menu items as a `@ViewBuilder` closure
/// — typically a stack of `Button("Label") { action }` rows. The
/// button's chrome (size, fill, border, glyph) lives here so the
/// menu's call site only ever has to think about the actions.
public struct PageOverflowButton<MenuContent: View>: View {
    @ViewBuilder private let content: () -> MenuContent

    public init(@ViewBuilder content: @escaping () -> MenuContent) {
        self.content = content
    }

    public var body: some View {
        Menu {
            content()
        } label: {
            Image(systemName: "ellipsis")
                .rotationEffect(.degrees(90))
                .font(.system(size: 13))
                .foregroundStyle(AppColors.textPrimary)
                .frame(width: 28, height: 28)
                .background(
                    Circle().fill(AppColors.cardSolid.opacity(0.6))
                )
                .overlay(
                    Circle().stroke(AppColors.borderDefault, lineWidth: 1)
                )
        }
        .menuStyle(.borderlessButton)
        .accessibilityLabel(Text("More"))
    }
}

#if DEBUG
struct PageHeaderControls_Previews: PreviewProvider {
    struct Host: View {
        @State private var mode: PageViewMode = .grid
        var body: some View {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                LeafEyebrow("notepad · today")
                HStack(spacing: AppSpacing.s2) {
                    PageViewToggle(selected: $mode)
                    PageOverflowButton {
                        Button("Move to notebook") {}
                        Button("Apply template") {}
                        Button("Duplicate") {}
                        Button("Archive page", role: .destructive) {}
                    }
                }
            }
            .padding()
            .background(AppColors.canvas)
            .accentPalette(AccentPalettes.green)
        }
    }
    static var previews: some View {
        Host().frame(width: 360)
    }
}
#endif
