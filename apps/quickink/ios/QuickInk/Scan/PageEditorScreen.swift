/*
 * PageEditorScreen.swift
 *
 * Page-editing surface — re-crop, rotate, retake. Reachable
 * from `ScanReviewScreen` after a capture completes (before the
 * user taps Done) and from `NoteEditorScreen`'s page tab when the
 * user wants to revise an existing scan.
 *
 * This is a UI wireframe today — the actual image transform
 * pipeline (Core Graphics rotation, draggable crop corners, page
 * re-capture via VisionKit) lands in a follow-up. Shipping the
 * picker chrome now means the route + theme + control surface
 * are settled when the transforms slot in.
 *
 * Mirror of Android `PageEditorScreen.kt`.
 */

import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct PageEditorScreen: View {
    /// Page image (or page-preview URL) under edit. Today the
    /// mock renders a lined-paper placeholder when `imageURL` is
    /// nil; once the scan pipeline hands a real URL through, it
    /// renders the captured image.
    var imageURL: URL?

    let onCancel: () -> Void
    let onSave: () -> Void
    let onRetake: () -> Void

    @State private var rotation: Double = 0
    @State private var activeTool: Tool = .crop

    enum Tool { case crop, rotate, retake }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Spacer()
            pageCanvas
                .padding(QuickInkSpacing.s5)
            Spacer()
            toolStrip
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.bottom, QuickInkSpacing.s4)
            actionRow
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.bottom, QuickInkSpacing.s7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onCancel) {
                Text("Cancel")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(QuickInkSpacing.s3)
            }
            .buttonStyle(.plain)

            Spacer()

            Text("Edit page")
                .font(QuickInkText.label)
                .foregroundStyle(QuickInkColors.ink)

            Spacer()

            Button(action: onSave) {
                Text("Save")
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.accent)
                    .padding(QuickInkSpacing.s3)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
    }

    // MARK: - Page canvas

    @ViewBuilder
    private var pageCanvas: some View {
        ZStack {
            // Image area — real captured image when available,
            // lined-paper placeholder otherwise.
            Group {
                #if canImport(UIKit)
                if let url = imageURL, let image = loadImage(url: url) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                } else {
                    placeholderPage
                }
                #else
                placeholderPage
                #endif
            }
            .rotationEffect(.degrees(rotation))
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )

            // Crop handles overlay — only when crop tool active.
            if activeTool == .crop {
                cropHandlesOverlay
            }
        }
    }

    @ViewBuilder
    private var placeholderPage: some View {
        ZStack {
                        QuickInkLinedPaper(
                            tone: QuickInkColors.surface,
                            lineSpacing: 16,
                            lineOpacity: 0.10
                        )
                        HStack(spacing: 0) {
                            Rectangle()
                                .fill(QuickInkColors.accent.opacity(0.6))
                                .frame(width: 1.5)
                                .padding(.leading, 32)
                            Spacer()
                        }
                        .padding(.vertical, 16)
                        Text("scanned page")
                            .font(QuickInkFont.handwritten(28))
                            .foregroundStyle(QuickInkColors.inkSoft.opacity(0.7))
        }
    }

    @ViewBuilder
    private var cropHandlesOverlay: some View {
        GeometryReader { geo in
            let inset: CGFloat = 16
            ZStack {
                cropCorner.position(x: inset,                   y: inset)
                cropCorner.position(x: geo.size.width - inset,  y: inset)
                cropCorner.position(x: inset,                   y: geo.size.height - inset)
                cropCorner.position(x: geo.size.width - inset,  y: geo.size.height - inset)
            }
        }
    }

    @ViewBuilder
    private var cropCorner: some View {
        Circle()
            .fill(QuickInkColors.accent)
            .frame(width: 14, height: 14)
            .shadow(color: QuickInkColors.accent.opacity(0.4), radius: 6, x: 0, y: 0)
    }

    // MARK: - Tool strip (segmented)

    @ViewBuilder
    private var toolStrip: some View {
        HStack(spacing: 4) {
            tool(.crop,   icon: "crop",                   label: "Crop")
            tool(.rotate, icon: "rotate.right",           label: "Rotate")
            tool(.retake, icon: "arrow.triangle.2.circlepath", label: "Retake")
        }
        .padding(4)
        .background(QuickInkColors.borderSoft)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    @ViewBuilder
    private func tool(_ kind: Tool, icon: String, label: String) -> some View {
        let active = (activeTool == kind)
        Button(action: { handleTool(kind) }) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .medium))
                Text(label)
                    .font(QuickInkText.label)
            }
            .foregroundStyle(active ? QuickInkColors.ink : QuickInkColors.inkSoft)
            .frame(maxWidth: .infinity)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                    .fill(active ? QuickInkColors.surface : .clear)
                    .shadow(color: active ? QuickInkColors.ink.opacity(0.06) : .clear, radius: 4, x: 0, y: 2)
            )
        }
        .buttonStyle(.plain)
    }

    private func handleTool(_ kind: Tool) {
        activeTool = kind
        switch kind {
        case .crop:
            // Already shows handles — interaction is the follow-up.
            break
        case .rotate:
            // Step 90° per tap. Real rotation also flips the
            // crop rect when implemented.
            withAnimation(.easeInOut(duration: 0.18)) {
                rotation += 90
                if rotation >= 360 { rotation = 0 }
            }
        case .retake:
            onRetake()
        }
    }

    // MARK: - Action row (placeholder hint)

    @ViewBuilder
    private var actionRow: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "info.circle")
                .font(.system(size: 12))
                .foregroundStyle(QuickInkColors.muted)
            Text("Drag the corners to crop · ⟲ rotates 90°")
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.muted)
        }
    }

    // MARK: - Image loader

    private func loadImage(url: URL) -> UIImage? {
        // Loaded synchronously — fine for a single small page
        // preview. When we swap in the real scan pipeline (large
        // PDFs / multi-page), move this to an async load with a
        // placeholder while in flight.
        guard let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }
}
