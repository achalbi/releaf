/*
 * RichTextFormatBar.swift
 *
 * Tiny SwiftUI toolbar that sits just above the keyboard (or at the
 * bottom of the editor) and dispatches bold / italic / underline
 * toggles to a `RichTextEditorController`. Each button's active state
 * is driven from the controller's `isXActive` published flags — so when
 * the caret is inside a bold run, the bold icon highlights coral.
 *
 * Android parity: only inline styles (Bold, Italic, Underline). Lists,
 * headings, code, links are typed manually — same as the Android
 * toolbar. If we ever add those to Android's rich-text toolbar, add
 * matching actions here.
 */

import SwiftUI
import ReleafDesignSystem

struct RichTextFormatBar: View {
    @ObservedObject var controller: RichTextEditorController

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            ToggleButton(
                systemImage: "bold",
                active: controller.isBoldActive,
                action: controller.toggleBold,
                accessibilityLabel: "Bold"
            )
            ToggleButton(
                systemImage: "italic",
                active: controller.isItalicActive,
                action: controller.toggleItalic,
                accessibilityLabel: "Italic"
            )
            ToggleButton(
                systemImage: "underline",
                active: controller.isUnderlineActive,
                action: controller.toggleUnderline,
                accessibilityLabel: "Underline"
            )
            Spacer()
        }
        .padding(.horizontal, AppSpacing.s3)
        .padding(.vertical, AppSpacing.s2)
        .frame(maxWidth: .infinity)
        .background(AppColors.cardSolid)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(AppColors.borderDefault)
                .frame(height: 1)
        }
    }
}

private struct ToggleButton: View {
    let systemImage: String
    let active: Bool
    let action: () -> Void
    let accessibilityLabel: String

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(AppColors.coral)
                .frame(width: 40, height: 40)
                .background(
                    RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                        .fill(active ? AppColors.coral.opacity(0.15) : Color.clear)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}
