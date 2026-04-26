/*
 * LeafColorPicker.swift
 *
 * Small, reusable color picker keyed off the four leaf theme tokens
 * (coral / green / yellow / dry). Used in the create-shelf and
 * create-notebook flows to let users pick a color *symbolically*
 * (i.e. by leaf-theme name) rather than via a generic hex picker.
 *
 * Each chip is a 32pt circle filled with the theme's primary color,
 * with a thin coral ring around the active selection. Tap to pick.
 * Spacing matches the rest of the app's chip rows.
 *
 * The selected value is the brand's `colorToken` string ("coral",
 * "green", "yellow", "dry") — the same shape the data layer
 * already stores on `Notebook.colorToken` and the Shelf entity.
 */

import SwiftUI

public struct LeafColorPicker: View {
    /// The currently-selected leaf-theme token.
    @Binding public var selection: String
    /// When true, a small `releaf · {token}` eyebrow preview
    /// renders below the chip row in the selected color so the
    /// user can see how their pick will read in the rest of the
    /// chrome before committing. Off by default to keep the
    /// picker compact at call sites that don't need it.
    public let showPreview: Bool

    /// All four leaf themes ordered the way the rest of the app
    /// renders them — coral (default) → green → yellow → dry.
    private let options: [(token: String, label: String, color: Color)] = [
        ("coral",  "Coral",  AppColors.themeCoralPrimary),
        ("green",  "Green",  AppColors.themeGreenPrimary),
        ("yellow", "Yellow", AppColors.themeYellowPrimary),
        ("dry",    "Dry",    AppColors.themeDryPrimary),
    ]

    public init(selection: Binding<String>, showPreview: Bool = false) {
        self._selection = selection
        self.showPreview = showPreview
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            HStack(spacing: AppSpacing.s3) {
                ForEach(options, id: \.token) { option in
                    Button {
                        selection = option.token
                    } label: {
                        chip(for: option)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(option.label))
                    .accessibilityAddTraits(option.token == selection ? [.isSelected] : [])
                }
            }
            if showPreview {
                let palette = ShelfTheme.palette(for: selection)
                HStack(spacing: AppSpacing.s1) {
                    LeafDropletGlyph(tint: palette.background, size: 11)
                    Text("releaf · \(selection)".uppercased())
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(palette.background)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(Text("Preview · \(selection)"))
            }
        }
    }

    private func chip(for option: (token: String, label: String, color: Color)) -> some View {
        let isActive = option.token == selection
        return ZStack {
            Circle()
                .fill(option.color)
                .frame(width: 32, height: 32)
            // Active chip — thin ring around the chip in the same
            // color, drawn slightly outside so the chip doesn't
            // visually shrink when selected.
            if isActive {
                Circle()
                    .stroke(option.color, lineWidth: 2)
                    .frame(width: 40, height: 40)
                Circle()
                    .stroke(AppColors.cardSolid, lineWidth: 2)
                    .frame(width: 36, height: 36)
            }
        }
        .frame(width: 40, height: 40)
        .contentShape(Circle())
    }
}

#if DEBUG
struct LeafColorPicker_Previews: PreviewProvider {
    struct Host: View {
        @State private var token: String = "green"
        var body: some View {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("PICK A COLOR")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                LeafColorPicker(selection: $token)
                Text("Selected: \(token)")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            .padding()
            .background(AppColors.canvas)
        }
    }
    static var previews: some View { Host() }
}
#endif
