/*
 * ImageEditorScreen.swift
 *
 * WhatsApp-style image editor that the "Share as Image" flow on
 * `ScanDetailScreen` routes through before the system share sheet.
 *
 * What the user can do per page:
 *   - Crop with a draggable 4-corner overlay.
 *   - Doodle freehand with PencilKit (`PKCanvasView`). 5-color
 *     palette + eraser, all driven by `PKInkingTool` / `PKEraserTool`.
 *
 * Flow for multi-page captures:
 *   - Pages are presented one at a time. Bottom-right CTA says
 *     "Next" until the last page, then flips to "Share".
 *   - Back arrow steps back through edited pages preserving edits.
 *   - Tapping Share commits everything, hands `[UIImage]` to
 *     `onDone`, and the host screen rasterises + opens the share
 *     sheet exactly as before — the editor doesn't own any IO.
 *
 * Crop coords are normalised [0..1] in image space so the per-page
 * state survives view re-renders that change the canvas size.
 * Strokes live inside `PKCanvasView`'s own `PKDrawing`, indexed
 * per page so each tab restores its own annotations on revisit.
 */

import SwiftUI
import PencilKit
import UIKit

struct ImageEditorScreen: View {

    /// Source pages, in capture order. Held in state because the
    /// Crop tool's Done action permanently replaces a page with
    /// its cropped version. The editor hands the final list back
    /// via `onDone`.
    @State private var pages: [UIImage]
    /// Immutable copy of the input pages so Reset can restore the
    /// current page to its pre-edit state.
    private let originalPages: [UIImage]
    let onCancel: () -> Void
    let onDone: ([UIImage]) -> Void

    // MARK: Editor state

    @State private var pageIndex: Int = 0
    /// Normalised crop rect per page (origin + size in 0..1, image
    /// space). Defaults to the full image; resets to (0,0,1,1) on
    /// crop commit so a re-crop session starts fresh.
    @State private var cropRects: [CGRect]
    /// Per-page PKDrawing. Carries the user's pencil strokes between
    /// page changes so navigating back doesn't erase work. Cleared
    /// on crop commit because the canvas bounds change underneath
    /// the strokes.
    @State private var drawings: [PKDrawing]
    @State private var currentTool: EditorTool = .pencil
    @State private var currentColor: PaletteColor = .coral
    /// Brush-size preset (small / medium / large). Pencil and
    /// eraser share the picker; each tool maps to its own width
    /// via [BrushSize.pencilWidth] / `.eraserWidth`. Replaces the
    /// previous fixed `penWidth` constant.
    @State private var brushSize: BrushSize = .medium

    enum EditorTool: Hashable {
        case crop
        case pencil
        case highlighter
        case eraser
    }

    /// Brush-size preset shared between pencil, highlighter and
    /// eraser. Each case carries its own width per tool so the
    /// eraser stays proportionally fatter than the pencil and the
    /// highlighter sits between them at the same step.
    enum BrushSize: CaseIterable, Hashable {
        case small, medium, large

        var pencilWidth: CGFloat {
            switch self {
            case .small:  return 2
            case .medium: return 4
            case .large:  return 8
            }
        }
        var highlighterWidth: CGFloat {
            switch self {
            case .small:  return 12
            case .medium: return 20
            case .large:  return 32
            }
        }
        var eraserWidth: CGFloat {
            switch self {
            case .small:  return 10
            case .medium: return 20
            case .large:  return 36
            }
        }
        /// Visual dot diameter inside the picker — scales with the
        /// step so the picker reads as small/medium/large at a
        /// glance.
        var dotDiameter: CGFloat {
            switch self {
            case .small:  return 6
            case .medium: return 12
            case .large:  return 20
            }
        }
    }

    enum PaletteColor: CaseIterable, Hashable {
        case coral, charcoal, white, yellow, blue
        var uiColor: UIColor {
            switch self {
            case .coral:    return UIColor(red: 0.88, green: 0.40, blue: 0.34, alpha: 1)
            case .charcoal: return UIColor(red: 0.15, green: 0.13, blue: 0.12, alpha: 1)
            case .white:    return .white
            case .yellow:   return UIColor(red: 0.99, green: 0.79, blue: 0.20, alpha: 1)
            case .blue:     return UIColor(red: 0.20, green: 0.45, blue: 0.85, alpha: 1)
            }
        }
        var swiftUI: Color { Color(uiColor) }
    }

    init(
        pages: [UIImage],
        onCancel: @escaping () -> Void,
        onDone: @escaping ([UIImage]) -> Void
    ) {
        _pages = State(initialValue: pages)
        self.originalPages = pages
        self.onCancel = onCancel
        self.onDone = onDone
        _cropRects = State(initialValue: Array(
            repeating: CGRect(x: 0, y: 0, width: 1, height: 1),
            count: pages.count
        ))
        _drawings = State(initialValue: Array(repeating: PKDrawing(), count: pages.count))
    }

    var body: some View {
        // Edge-to-edge dark canvas: the Color sits behind the
        // VStack with `.ignoresSafeArea()` so it paints under the
        // notch / Dynamic Island and the home-indicator strip.
        // The VStack itself keeps its default safe-area inset so
        // the Close / Share chips and the bottom tool row sit
        // comfortably inside the safe region.
        ZStack(alignment: .top) {
            Color(white: 0.06).ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                canvas
                    .padding(.horizontal, 16)
                toolbar
            }
        }
        .statusBarHidden(true)
        .preferredColorScheme(.dark)
    }

    // MARK: Top bar

    private var topBar: some View {
        // The X chip (36pt circle) and the Share pill (wider, with
        // pill padding) are different widths, so equal Spacers
        // would push the title off-centre. Layering the title as
        // a centred overlay pins it to the screen midline
        // regardless of what the chips on either side weigh.
        ZStack {
            Text(pages.count > 1 ? "Page \(pageIndex + 1) of \(pages.count)" : "Edit")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)

            HStack(alignment: .center) {
                Button(action: onCancel) {
                    Image(systemName: "xmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 36, height: 36)
                        .background(Color.white.opacity(0.12), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Cancel")

                Spacer()

                Button(action: advance) {
                    Text(pageIndex == pages.count - 1 ? "Share" : "Next")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.orange, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(pageIndex == pages.count - 1 ? "Share" : "Next page")
            }
        }
        .padding(.horizontal, 16)
        // Top padding so the "Edit" title clears the Dynamic Island
        // / notch on iPhones with a cutout. We hide the status bar
        // app-wide, which can shrink the safe-area inset on some
        // devices and let the chip drift under the camera — this
        // constant gives a consistent margin.
        .padding(.top, 20)
        .padding(.bottom, 10)
    }

    // MARK: Canvas

    private var canvas: some View {
        GeometryReader { geo in
            let image = pages[pageIndex]
            let imageSize = image.size
            // Display rect inside `geo` honoring image aspect.
            let displayRect = aspectFit(image: imageSize, into: geo.size)
            ZStack {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: displayRect.width, height: displayRect.height)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)

                // PencilKit drawing layer pinned to the image's
                // visible rect so strokes match the image regardless
                // of letterboxing.
                PencilCanvasView(
                    drawing: Binding(
                        get: { drawings[pageIndex] },
                        set: { drawings[pageIndex] = $0 }
                    ),
                    tool: pencilKitTool(),
                    isUserInteractionEnabled: currentTool != .crop
                )
                .frame(width: displayRect.width, height: displayRect.height)
                .position(x: geo.size.width / 2, y: geo.size.height / 2)
                .allowsHitTesting(currentTool != .crop)

                // Crop overlay — dims everything outside the rect,
                // exposes 4 draggable corner handles. Only interactive
                // when the Crop tool is selected.
                CropOverlay(
                    normalizedRect: Binding(
                        get: { cropRects[pageIndex] },
                        set: { cropRects[pageIndex] = $0 }
                    ),
                    displayRect: CGRect(
                        x: (geo.size.width  - displayRect.width)  / 2,
                        y: (geo.size.height - displayRect.height) / 2,
                        width:  displayRect.width,
                        height: displayRect.height
                    ),
                    enabled: currentTool == .crop
                )
                .frame(width: geo.size.width, height: geo.size.height)
            }
        }
    }

    // MARK: Toolbar

    private var toolbar: some View {
        VStack(spacing: 12) {
            // Color palette + eraser — only visible while the pencil
            // tool is active.
            // Brush-size picker — only visible while drawing tools
            // are active. Three steps shared between pencil and
            // eraser. Visual dot scales with size so the picker
            // reads as small/medium/large at a glance.
            if currentTool == .pencil || currentTool == .highlighter || currentTool == .eraser {
                HStack(spacing: 14) {
                    ForEach(BrushSize.allCases, id: \.self) { size in
                        Button { brushSize = size } label: {
                            ZStack {
                                Circle()
                                    .fill(brushSize == size
                                        ? Color.white.opacity(0.25)
                                        : Color.white.opacity(0.08))
                                    .frame(width: 32, height: 32)
                                Circle()
                                    .fill(Color.white)
                                    .frame(width: size.dotDiameter, height: size.dotDiameter)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Brush size \(String(describing: size))")
                    }
                }
            }

            // Colour palette — only visible while drawing tools are
            // active. Eraser has moved into the tool row below so
            // the palette stays colours-only.
            if currentTool == .pencil || currentTool == .highlighter || currentTool == .eraser {
                HStack(spacing: 14) {
                    ForEach(PaletteColor.allCases, id: \.self) { swatch in
                        Button {
                            currentColor = swatch
                            // Stick with whichever inking tool is
                            // active so the user can recolour the
                            // highlighter without falling back to
                            // pencil. Eraser jumps to pencil since
                            // it doesn't carry colour.
                            if currentTool != .pencil && currentTool != .highlighter {
                                currentTool = .pencil
                            }
                        } label: {
                            let isInkingTool = currentTool == .pencil || currentTool == .highlighter
                            Circle()
                                .fill(swatch.swiftUI)
                                .frame(width: 28, height: 28)
                                .overlay(
                                    Circle().stroke(
                                        isInkingTool && currentColor == swatch
                                            ? Color.white : Color.white.opacity(0.4),
                                        lineWidth: isInkingTool && currentColor == swatch ? 2 : 1
                                    )
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Color \(String(describing: swatch))")
                    }
                }
            }

            // Crop confirm CTA — only visible while the Crop tool
            // is active. Tap commits the rect into the working
            // image and flips the editor back to the pencil tool so
            // the user sees the cropped preview.
            if currentTool == .crop {
                Button(action: commitCrop) {
                    Text("Done")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 8)
                        .background(Color.orange, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Commit crop")
            }

            HStack(spacing: 20) {
                Button(action: resetCurrentPage) {
                    Image(systemName: "arrow.counterclockwise")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Color.white.opacity(0.08), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Reset edits")
                toolButton(.crop, systemImage: "crop")
                toolButton(.pencil, systemImage: "pencil.tip")
                // Highlighter sits between Pencil and Eraser. Uses
                // `highlighter` SF Symbol — the literal marker
                // glyph — paired with PencilKit's `.marker` ink
                // type for an authentic semi-transparent overlay.
                toolButton(.highlighter, systemImage: "highlighter")
                // Eraser sits next to Pencil now (was in the colour
                // palette row). Uses `eraser.line.dashed` — a more
                // literal eraser glyph than the previous wand-and-
                // sparkles icon.
                toolButton(.eraser, systemImage: "eraser.line.dashed")
                if pageIndex > 0 {
                    Button(action: stepBack) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 36, height: 36)
                            .background(Color.white.opacity(0.08), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Previous page")
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 16)
    }

    @ViewBuilder
    private func toolButton(_ tool: EditorTool, systemImage: String) -> some View {
        Button {
            currentTool = tool
        } label: {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(
                    Circle().fill(currentTool == tool
                        ? Color.white.opacity(0.25)
                        : Color.white.opacity(0.08))
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(describing: tool))
    }

    // MARK: Actions

    private func advance() {
        if pageIndex < pages.count - 1 {
            pageIndex += 1
        } else {
            // Apply all edits and hand the final images back.
            var out: [UIImage] = []
            out.reserveCapacity(pages.count)
            for i in 0..<pages.count {
                out.append(renderPage(index: i))
            }
            onDone(out)
        }
    }

    private func stepBack() {
        if pageIndex > 0 { pageIndex -= 1 }
    }

    /// Restore the current page to its original input bitmap, drop
    /// any pending crop rect, and clear the drawing. Flips back to
    /// the pencil tool so a fresh edit can begin immediately.
    private func resetCurrentPage() {
        pages[pageIndex] = originalPages[pageIndex]
        cropRects[pageIndex] = CGRect(x: 0, y: 0, width: 1, height: 1)
        drawings[pageIndex] = PKDrawing()
        currentTool = .pencil
    }

    /// Commit the in-flight crop rect: replace the working image
    /// with the cropped version, reset the rect to full extent,
    /// drop any pre-crop drawings (PencilKit coords are tied to the
    /// pre-crop canvas), and flip the active tool to pencil so the
    /// user sees the cropped preview immediately.
    private func commitCrop() {
        let src = pages[pageIndex]
        let crop = cropRects[pageIndex]
        let cropPx = CGRect(
            x: crop.origin.x * src.size.width,
            y: crop.origin.y * src.size.height,
            width:  crop.size.width  * src.size.width,
            height: crop.size.height * src.size.height
        ).integral
        guard let cg = src.cgImage,
              let cropped = cg.cropping(to: cropPx) else {
            return
        }
        pages[pageIndex] = UIImage(
            cgImage:     cropped,
            scale:       src.scale,
            orientation: src.imageOrientation
        )
        cropRects[pageIndex] = CGRect(x: 0, y: 0, width: 1, height: 1)
        drawings[pageIndex] = PKDrawing()
        currentTool = .pencil
    }

    /// Composite the source image with its crop rect + drawing into
    /// a single output UIImage. Crop is applied in image coordinates;
    /// drawing is rendered scaled to the cropped extent.
    private func renderPage(index: Int) -> UIImage {
        let src = pages[index]
        let crop = cropRects[index]
        let drawing = drawings[index]

        // 1. Crop the source.
        let imageSize = src.size
        let cropPx = CGRect(
            x: crop.origin.x * imageSize.width,
            y: crop.origin.y * imageSize.height,
            width:  crop.size.width  * imageSize.width,
            height: crop.size.height * imageSize.height
        ).integral
        guard let cg = src.cgImage,
              let cropped = cg.cropping(to: cropPx) else {
            return src
        }
        let croppedImage = UIImage(
            cgImage: cropped,
            scale: src.scale,
            orientation: src.imageOrientation
        )

        // 2. Render the drawing on top.
        let size = croppedImage.size
        guard !drawing.bounds.isEmpty else { return croppedImage }
        let format = UIGraphicsImageRendererFormat()
        format.scale = src.scale
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { ctx in
            croppedImage.draw(in: CGRect(origin: .zero, size: size))
            // The drawing is in PKCanvasView's local coords (the
            // displayed image rect). Scale it to the cropped image
            // size by deriving the same crop transform.
            let displayed = src.size                   // canvas was full image size
            let scaleX = size.width  / (displayed.width  * crop.size.width)
            let scaleY = size.height / (displayed.height * crop.size.height)
            ctx.cgContext.saveGState()
            ctx.cgContext.translateBy(
                x: -crop.origin.x * displayed.width  * scaleX,
                y: -crop.origin.y * displayed.height * scaleY
            )
            ctx.cgContext.scaleBy(x: scaleX, y: scaleY)
            let drawingImage = drawing.image(
                from: CGRect(origin: .zero, size: displayed),
                scale: src.scale
            )
            drawingImage.draw(at: .zero)
            ctx.cgContext.restoreGState()
        }
    }

    private func pencilKitTool() -> PKTool {
        switch currentTool {
        case .eraser:
            // The width-bearing PKEraserTool init is iOS 16.4+.
            // On 16.0–16.3 we fall back to the default vector
            // eraser; the visible-width preset just doesn't move
            // on those devices. The pencil/draw widths still
            // honour the picker via PKInkingTool.
            if #available(iOS 16.4, *) {
                return PKEraserTool(.bitmap, width: brushSize.eraserWidth)
            } else {
                return PKEraserTool(.vector)
            }
        case .highlighter:
            // PencilKit's `.marker` ink type renders as a
            // semi-transparent highlighter with its own alpha
            // baked in — exactly the WhatsApp-style behaviour.
            return PKInkingTool(
                .marker,
                color: currentColor.uiColor,
                width: brushSize.highlighterWidth
            )
        default:
            return PKInkingTool(
                .pen,
                color: currentColor.uiColor,
                width: brushSize.pencilWidth
            )
        }
    }

    /// Fit `image` into `box` preserving aspect, centred.
    private func aspectFit(image: CGSize, into box: CGSize) -> CGRect {
        guard image.width > 0, image.height > 0 else { return .zero }
        let scale = min(box.width / image.width, box.height / image.height)
        let w = image.width  * scale
        let h = image.height * scale
        return CGRect(x: 0, y: 0, width: w, height: h)
    }
}

// MARK: - Crop overlay

/// Dim-outside-rect overlay with four draggable corner handles.
/// `normalizedRect` is in 0..1 image-space and is kept in sync with
/// the visible rect via the supplied `displayRect` (the on-screen
/// image bounds inside the canvas container).
private struct CropOverlay: View {
    @Binding var normalizedRect: CGRect
    let displayRect: CGRect
    let enabled: Bool

    var body: some View {
        ZStack {
            // Dimmed area outside the crop rect, drawn as four
            // rectangles framing the inner clear area. Sticking to
            // rectangles instead of `Path.subtracting` keeps the
            // iOS 16 deployment target happy.
            Canvas { ctx, size in
                let dimColor = Color.black.opacity(0.45)
                let rect = currentScreenRect
                // Top
                ctx.fill(Path(CGRect(x: 0, y: 0, width: size.width, height: rect.minY)),
                         with: .color(dimColor))
                // Bottom
                ctx.fill(Path(CGRect(x: 0, y: rect.maxY, width: size.width, height: size.height - rect.maxY)),
                         with: .color(dimColor))
                // Left
                ctx.fill(Path(CGRect(x: 0, y: rect.minY, width: rect.minX, height: rect.height)),
                         with: .color(dimColor))
                // Right
                ctx.fill(Path(CGRect(x: rect.maxX, y: rect.minY, width: size.width - rect.maxX, height: rect.height)),
                         with: .color(dimColor))
                // Inner border.
                var border = Path()
                border.addRect(rect)
                ctx.stroke(border, with: .color(.white.opacity(0.85)), lineWidth: 1)
            }
            .allowsHitTesting(false)

            if enabled {
                // Four corner handles.
                handle(at: currentScreenRect.minX, currentScreenRect.minY, corner: .topLeading)
                handle(at: currentScreenRect.maxX, currentScreenRect.minY, corner: .topTrailing)
                handle(at: currentScreenRect.minX, currentScreenRect.maxY, corner: .bottomLeading)
                handle(at: currentScreenRect.maxX, currentScreenRect.maxY, corner: .bottomTrailing)
            }
        }
    }

    private var currentScreenRect: CGRect {
        CGRect(
            x: displayRect.minX + normalizedRect.minX * displayRect.width,
            y: displayRect.minY + normalizedRect.minY * displayRect.height,
            width:  normalizedRect.width  * displayRect.width,
            height: normalizedRect.height * displayRect.height
        )
    }

    private enum Corner { case topLeading, topTrailing, bottomLeading, bottomTrailing }

    @ViewBuilder
    private func handle(at x: CGFloat, _ y: CGFloat, corner: Corner) -> some View {
        Circle()
            .fill(Color.white)
            .frame(width: 18, height: 18)
            .position(x: x, y: y)
            .gesture(
                DragGesture()
                    .onChanged { value in
                        applyDrag(value.location, to: corner)
                    }
            )
    }

    private func applyDrag(_ point: CGPoint, to corner: Corner) {
        // Map screen point to normalised image space.
        let nx = (point.x - displayRect.minX) / displayRect.width
        let ny = (point.y - displayRect.minY) / displayRect.height
        let clampedX = min(max(nx, 0), 1)
        let clampedY = min(max(ny, 0), 1)
        var rect = normalizedRect
        let minSize: CGFloat = 0.1
        switch corner {
        case .topLeading:
            let maxX = rect.maxX - minSize
            let maxY = rect.maxY - minSize
            rect.origin.x = min(clampedX, maxX)
            rect.origin.y = min(clampedY, maxY)
            rect.size.width  = rect.maxX - rect.origin.x + (rect.origin.x == clampedX ? 0 : 0)
            rect.size.width  = max(minSize, rect.maxX - rect.origin.x)
            rect.size.height = max(minSize, rect.maxY - rect.origin.y)
        case .topTrailing:
            let minX = rect.origin.x + minSize
            let maxY = rect.maxY - minSize
            let newMaxX = max(minX, clampedX)
            rect.origin.y = min(clampedY, maxY)
            rect.size.width  = newMaxX - rect.origin.x
            rect.size.height = max(minSize, rect.maxY - rect.origin.y)
        case .bottomLeading:
            let maxX = rect.maxX - minSize
            let minY = rect.origin.y + minSize
            rect.origin.x = min(clampedX, maxX)
            rect.size.width  = max(minSize, rect.maxX - rect.origin.x)
            rect.size.height = max(minY, clampedY) - rect.origin.y
        case .bottomTrailing:
            let minX = rect.origin.x + minSize
            let minY = rect.origin.y + minSize
            rect.size.width  = max(minX, clampedX) - rect.origin.x
            rect.size.height = max(minY, clampedY) - rect.origin.y
        }
        normalizedRect = rect
    }
}

// MARK: - PencilKit bridge

private struct PencilCanvasView: UIViewRepresentable {
    @Binding var drawing: PKDrawing
    let tool: PKTool
    let isUserInteractionEnabled: Bool

    func makeUIView(context: Context) -> PKCanvasView {
        let canvas = PKCanvasView()
        canvas.backgroundColor = .clear
        canvas.isOpaque = false
        canvas.drawingPolicy = .anyInput
        canvas.delegate = context.coordinator
        canvas.drawing = drawing
        canvas.tool = tool
        canvas.isUserInteractionEnabled = isUserInteractionEnabled
        return canvas
    }

    func updateUIView(_ uiView: PKCanvasView, context: Context) {
        if uiView.drawing != drawing { uiView.drawing = drawing }
        uiView.tool = tool
        uiView.isUserInteractionEnabled = isUserInteractionEnabled
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, PKCanvasViewDelegate {
        let parent: PencilCanvasView
        init(parent: PencilCanvasView) { self.parent = parent }
        func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
            DispatchQueue.main.async {
                self.parent.drawing = canvasView.drawing
            }
        }
    }
}
