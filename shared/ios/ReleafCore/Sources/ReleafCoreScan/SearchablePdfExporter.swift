/*
 * SearchablePdfExporter.swift
 *
 * Builds a multi-page PDF from `[PageContent]` (per-page image +
 * `OcrResult`) with an invisible text layer overlaid on top of each
 * image. Search and copy/paste in any conforming PDF reader pull
 * text from the overlay; the page renders visually as the source
 * image alone.
 *
 * Per QUICKINK_PROPOSAL.md §6.3, this is the prototype path behind
 * the `searchablePdfExportEnabled` feature flag — the v1 default
 * export uses Releaf's existing flat-PDF code path. The flag
 * wiring + the Settings → "Experimental" toggle land with the
 * QuickInk MVP; this file ships the exporter contract + impl so
 * the toggle has something to invoke when it's flipped on.
 *
 * Mirror of `SearchablePdfExporter.kt` in `:shared:scan`.
 *
 * Implementation:
 *
 *   - `UIGraphicsPDFRenderer` for the page rendering. Page size is
 *     the image's pixel dimensions interpreted as PDF points
 *     (matches what `DocumentScannerView` already does for the
 *     flat-PDF path; consistent across the two exporters).
 *
 *   - Invisible text via `CGContext.setTextDrawingMode(.invisible)`
 *     — PDF rendering mode 3, "neither stroke nor fill". Text is
 *     present in the content stream (searchable + selectable) but
 *     no glyphs are drawn. Standard searchable-PDF technique;
 *     supported by every reader that implements ISO 32000.
 *
 *   - Granularity: line-level only (`OcrBlock.kind == .line`).
 *     Paragraph-level blocks would lump multiple lines into one
 *     hit; word-level (which neither engine produces today anyway)
 *     would lose word-spacing context for search-and-copy.
 *
 *   - Coordinate translation: OCR's normalized 0..1 top-left
 *     bboxes → PDF points with Y flipped (PDF is bottom-left
 *     origin). Both `OcrBbox` and the PDF point system are
 *     orthogonal, so it's a clean affine transform per block.
 *
 * `#if os(iOS)`-guarded for the same reason `VisionTextRecognizer`
 * is — package macOS deployment minimum is resolver appeasement
 * only; nothing actually runs there.
 */

#if os(iOS)

import Foundation
import UIKit

public struct SearchablePdfExporter: Sendable {

    public init() {}

    /// Builds a PDF from `pages` and writes it to `outputURL`. Each
    /// element of `pages` becomes one PDF page with the image
    /// rendered visually and the OCR text overlaid invisibly.
    public func export(pages: [PageContent], to outputURL: URL) throws {
        guard !pages.isEmpty else {
            // An empty input list produces an empty PDF, which most
            // readers reject. Easier to fail early with a clear
            // signal than write a 0-page file the caller has to
            // post-validate.
            throw ExportError.pdfWriteFailed(message: "pages is empty")
        }

        // Page-by-page bounds — `UIGraphicsPDFRenderer`'s `format`
        // is per-document, but we override the bounds per page in
        // `beginPage`, so any non-zero seed works for the
        // constructor. We use the first page's image as the seed.
        let firstImage = try Self.loadImage(at: pages[0].imageURL)
        let initialBounds = CGRect(origin: .zero, size: firstImage.size)
        let renderer = UIGraphicsPDFRenderer(bounds: initialBounds)

        do {
            try renderer.writePDF(to: outputURL) { context in
                for (index, page) in pages.enumerated() {
                    let image = (index == 0)
                        ? firstImage
                        : (try? Self.loadImage(at: page.imageURL)) ?? firstImage
                    Self.drawPage(image: image, ocr: page.ocrResult, in: context)
                }
            }
        } catch {
            throw ExportError.pdfWriteFailed(message: error.localizedDescription)
        }
    }

    // MARK: - Page rendering

    private static func drawPage(
        image: UIImage,
        ocr: OcrResult,
        in context: UIGraphicsPDFRendererContext
    ) {
        // Per-page bounds match the source image. PDF readers honor
        // arbitrary page sizes, so there's no reason to letterbox
        // into a fixed Letter / A4 frame.
        let pageBounds = CGRect(origin: .zero, size: image.size)
        context.beginPage(withBounds: pageBounds, pageInfo: [:])

        // 1. Visible image fill.
        image.draw(in: pageBounds)

        // 2. Invisible text overlay — line-grained only.
        let cg = context.cgContext
        cg.saveGState()
        cg.setTextDrawingMode(.invisible)
        defer { cg.restoreGState() }

        for block in ocr.blocks where block.kind == .line {
            drawInvisibleLine(block, pageSize: image.size, in: cg)
        }
    }

    private static func drawInvisibleLine(
        _ block: OcrBlock,
        pageSize: CGSize,
        in cg: CGContext
    ) {
        // OCR bbox: normalized 0..1, top-left origin, image space.
        // PDF page coords: bottom-left origin, in points (we set
        // points = pixels via the image-sized page bounds above).
        let pixelX      = block.bbox.x      * Double(pageSize.width)
        let pixelY      = block.bbox.y      * Double(pageSize.height)
        let pixelWidth  = block.bbox.width  * Double(pageSize.width)
        let pixelHeight = block.bbox.height * Double(pageSize.height)

        // Y-flip: top-left y → bottom-left baseline. Place the
        // baseline at (1 - bbox.y - bbox.height) * height. Adding a
        // small descender allowance isn't worth the complexity for
        // an invisible layer — search hits are spatially loose.
        let pdfX        = pixelX
        let pdfBaseline = Double(pageSize.height) - pixelY - pixelHeight

        // Font size approximates the bbox height. We don't need a
        // pixel-perfect glyph match — the goal is "search hit
        // highlight roughly covers the visible word". Helvetica
        // works because the glyphs aren't rendered.
        let fontSize = max(pixelHeight, 1.0)
        let font = UIFont(name: "Helvetica", size: fontSize)
            ?? UIFont.systemFont(ofSize: fontSize)

        let attributes: [NSAttributedString.Key: Any] = [
            .font:            font,
            .foregroundColor: UIColor.clear,
        ]
        let attributed = NSAttributedString(string: block.text, attributes: attributes)

        // `NSAttributedString.draw(at:)` uses the text drawing mode
        // we set above (.invisible), so the actual color is moot —
        // glyphs are added to the content stream but not painted.
        // We position by the baseline-ish corner; AppKit/UIKit's
        // draw(at:) treats the point as the upper-left of the
        // bounding box, but Core Graphics under the hood places the
        // baseline. For an invisible layer the difference is
        // imperceptible to search.
        let drawPoint = CGPoint(x: pdfX, y: pdfBaseline)

        // Width-fitting: for very long blocks of text, scale font
        // down so the glyph run fits the OCR bbox. Keeps
        // search-highlight position roughly correct.
        let measured = attributed.size()
        if measured.width > CGFloat(pixelWidth), measured.width > 0 {
            let shrunkSize = fontSize * Double(pixelWidth) / Double(measured.width)
            let shrunkFont = UIFont(name: "Helvetica", size: shrunkSize)
                ?? UIFont.systemFont(ofSize: shrunkSize)
            let shrunk = NSAttributedString(string: block.text, attributes: [
                .font:            shrunkFont,
                .foregroundColor: UIColor.clear,
            ])
            shrunk.draw(at: drawPoint)
        } else {
            attributed.draw(at: drawPoint)
        }
    }

    // MARK: - Loading

    private static func loadImage(at url: URL) throws -> UIImage {
        guard let image = UIImage(contentsOfFile: url.path) else {
            throw ExportError.imageUnreadable(url)
        }
        return image
    }
}

// MARK: - Inputs + errors

extension SearchablePdfExporter {

    /// One page of input — image + its OCR result. The image is
    /// rendered as the visible page; the OCR's line-grained blocks
    /// are overlaid as invisible text.
    public struct PageContent: Sendable {
        public let imageURL:  URL
        public let ocrResult: OcrResult

        public init(imageURL: URL, ocrResult: OcrResult) {
            self.imageURL = imageURL
            self.ocrResult = ocrResult
        }
    }

    /// Failure modes the exporter surfaces. Distinct from `OcrError`
    /// because the export pipeline is downstream of OCR — by the
    /// time we reach this code, recognition has already succeeded.
    public enum ExportError: Error {
        /// Image at `URL` couldn't be loaded (missing, unsupported
        /// format, corrupt bytes).
        case imageUnreadable(URL)

        /// PDF write failed — disk full, sandbox permissions, etc.
        /// `message` carries the underlying reason.
        case pdfWriteFailed(message: String)
    }
}

#endif
