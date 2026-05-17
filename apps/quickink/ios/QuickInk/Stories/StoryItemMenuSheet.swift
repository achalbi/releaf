/*
 * StoryItemMenuSheet.swift
 *
 * Per-item ⋯ menu from §7.3b of the v3 mockup. Item preview at the
 * top anchors which item is being acted on; Edit caption / Set as
 * cover rows; inline Layout pills (Full / Half / Grid); Replace + reorder
 * (Replace stub for Phase 2 follow-up, Move up/down via repo's
 * `commitReorder`); destructive Remove below a divider.
 *
 * Mirror of Android `StoryItemMenuSheet.kt`.
 */

import SwiftUI

struct StoryItemMenuSheet: View {

    let item: StoryItem
    let isCoverItem: Bool
    var onEditCaption: () -> Void
    var onSetAsCover: () -> Void
    var onLayoutChange: (StoryItem.Layout) -> Void
    var onMoveUp: () -> Void
    var onMoveDown: () -> Void
    var onRemove: () -> Void

    @State private var stubToast: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            handle
            preview
            VStack(spacing: 0) {
                actionRow(icon: "pencil",  label: "Edit caption",     action: onEditCaption)
                actionRow(
                    icon: isCoverItem ? "star.fill" : "star",
                    label: isCoverItem ? "Cover item" : "Set as cover",
                    action: onSetAsCover
                )

                HStack(spacing: QuickInkSpacing.s2) {
                    Image(systemName: "rectangle.grid.2x2")
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(width: 24)
                    Text("Layout")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                    Spacer()
                    layoutPills
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.vertical, QuickInkSpacing.s2)

                divider
                actionRow(icon: "arrow.triangle.2.circlepath", label: "Replace with another item",
                          action: stub("Replace coming soon"))
                actionRow(icon: "arrow.up", label: "Move up",   action: onMoveUp)
                actionRow(icon: "arrow.down", label: "Move down", action: onMoveDown)

                divider
                actionRow(icon: "trash", label: "Remove from story",
                          tint: QuickInkColors.accent, action: onRemove)
            }
            if let toast = stubToast {
                Text(toast)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.bottom, QuickInkSpacing.s3)
            }
        }
        .padding(.bottom, QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
    }

    // MARK: - Pieces

    private var handle: some View {
        Capsule()
            .fill(QuickInkColors.border)
            .frame(width: 38, height: 4)
            .padding(.top, QuickInkSpacing.s2)
            .padding(.bottom, QuickInkSpacing.s3)
    }

    private var preview: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            RoundedRectangle(cornerRadius: 8)
                .fill(QuickInkColors.paper1)
                .frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(previewTitle)
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(previewSubtitle)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.vertical, QuickInkSpacing.s2)
        .overlay(alignment: .bottom) {
            Rectangle().fill(QuickInkColors.borderSoft).frame(height: 0.5)
        }
    }

    private var layoutPills: some View {
        HStack(spacing: 4) {
            ForEach([StoryItem.Layout.full, .half, .grid], id: \.self) { layout in
                Button(action: { onLayoutChange(layout) }) {
                    Text(layout.rawValue.capitalized)
                        .font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(
                            Capsule().fill(
                                item.layout == layout.rawValue
                                    ? QuickInkColors.accentSoft
                                    : Color.clear
                            )
                        )
                        .overlay(
                            Capsule().strokeBorder(
                                item.layout == layout.rawValue
                                    ? QuickInkColors.accent.opacity(0.4)
                                    : QuickInkColors.border,
                                lineWidth: 1
                            )
                        )
                        .foregroundStyle(
                            item.layout == layout.rawValue
                                ? QuickInkColors.accent
                                : QuickInkColors.inkSoft
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func actionRow(
        icon: String,
        label: String,
        tint: Color = QuickInkColors.ink,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: icon)
                    .foregroundStyle(tint)
                    .frame(width: 24)
                Text(label)
                    .font(QuickInkText.body)
                    .foregroundStyle(tint)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.vertical, QuickInkSpacing.s2 + 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var divider: some View {
        Rectangle()
            .fill(QuickInkColors.borderSoft)
            .frame(height: 0.5)
            .padding(.vertical, QuickInkSpacing.s1)
            .padding(.horizontal, QuickInkSpacing.s4)
    }

    private var previewTitle: String {
        switch item.kind {
        case StoryItem.Kind.textBlock.rawValue:        return item.text ?? "Paragraph"
        case StoryItem.Kind.handwrittenNote.rawValue:  return item.text ?? "Handwritten note"
        case StoryItem.Kind.dateDivider.rawValue:      return item.text ?? "Date divider"
        case StoryItem.Kind.placePin.rawValue:         return item.text ?? "Place pin"
        case StoryItem.Kind.voiceClip.rawValue:        return "Voice clip"
        default:                                       return item.caption ?? "Item"
        }
    }

    private var previewSubtitle: String {
        item.caption ?? item.kind.replacingOccurrences(of: "_", with: " ")
    }

    private func stub(_ message: String) -> () -> Void {
        return {
            stubToast = message
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 1_800_000_000)
                stubToast = nil
            }
        }
    }
}
