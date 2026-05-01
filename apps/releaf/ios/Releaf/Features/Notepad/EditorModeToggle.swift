/*
 * EditorModeToggle.swift
 *
 * Binary Edit ⇄ Overview mode for the notepad editor, surfaced as a
 * list/grid icon pair in the top bar (mirrors Android's
 * `EditorModeIconToggle` Composable).
 *
 *   - edit     — list icon, single-scroll editor (rich text + inline sections).
 *   - overview — grid icon, CaptureTabBar layout matching PageDetailView.
 */

import SwiftUI
import ReleafDesignSystem

enum EditorMode: String, Hashable {
    case edit, overview
}

/// Compact two-icon toggle. The active mode renders as a coral-filled
/// pill with an on-accent glyph; the inactive one is a plain coral
/// glyph on the canvas.
struct EditorModeIconToggle: View {
    @Binding var mode: EditorMode

    var body: some View {
        HStack(spacing: AppSpacing.s1) {
            ModeIconButton(
                systemImage: "list.bullet",
                accessibility: "Edit mode",
                isActive: mode == .edit,
                action: { mode = .edit }
            )
            ModeIconButton(
                systemImage: "square.grid.2x2",
                accessibility: "Overview mode",
                isActive: mode == .overview,
                action: { mode = .overview }
            )
        }
    }
}

private struct ModeIconButton: View {
    let systemImage: String
    let accessibility: String
    let isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15))
                .foregroundStyle(isActive ? AppColors.onAccent : AppColors.coral)
                .frame(width: 32, height: 32)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(isActive ? AppColors.coral : Color.clear)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibility)
        .accessibilityAddTraits(isActive ? .isSelected : [])
    }
}
