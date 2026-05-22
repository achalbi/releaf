/*
 * ImportArtifacts.swift
 *
 * Bridge between the system PhotosPicker and `ScanFlowController`.
 * Takes the `UIImage`s the user picked from the gallery, writes each
 * as a bounded JPEG into `AttachmentStorage`, renders a single
 * multi-page PDF wrapping every page, and returns the URL triple
 * `ScanFlowController.onScanComplete` expects
 * (pdfURL / previewURL / pageURLs).
 *
 * Why one PDF, not N: every other capture path in the app stores a
 * multi-page PDF as the canonical "document", with per-page JPEGs
 * as previews / OCR inputs. The Library, the detail screen, and the
 * Drive sync all key off the PDF row, so imports without a PDF
 * would diverge — they'd render as previews with a missing detail
 * view. Imports flow through the same downstream code as a scan
 * regardless of page count.
 *
 * Mirror of Android `ImportArtifacts.kt`.
 */

import Foundation
import UIKit
import ReleafCoreData
import ReleafCoreScan

enum ImportArtifacts {

    private static let pageImageMaxLongEdge: CGFloat = 1800
    private static let pageJpegQuality: CGFloat = 0.82

    /// Result triple — same shape `DocumentScannerView.onComplete`
    /// surfaces, so callers can hand it straight to
    /// `controller.onScanComplete(pdfURL:previewURL:pageURLs:)`.
    struct Result {
        let pdfURL: URL?
        let previewURL: URL
        let pageURLs: [URL]
    }

    /// Encodes each image as JPEG, writes them into AttachmentStorage
    /// in selection order, then builds a single multi-page PDF
    /// wrapping the same bitmaps. Returns nil on empty input or if
    /// JPEG encoding of the first image fails — without a preview
    /// URL the downstream pipeline has nothing to render.
    /// PDF-rendering failure degrades to a JPEG-only result rather
    /// than aborting, since the JPEGs alone are enough to let
    /// downstream OCR run and the library preview show.
    ///
    /// Imported/system-camera photos can arrive at full sensor
    /// resolution, so every page is normalized into a bounded JPEG
    /// before it becomes a preview, OCR input, synced binary, or PDF
    /// source.
    static func build(
        from images: [UIImage],
        compressedPdfEnabled: Bool = SettingsState.compressedPdfSavesDefault
    ) -> Result? {
        guard !images.isEmpty else { return nil }

        // Pass 1 — write every JPEG into AttachmentStorage. We do
        // this first (separately from PDF rendering) so the
        // page-urls list is fully formed before we touch the PDF
        // context. If any one fails we abort the whole import
        // rather than silently dropping pages.
        var pageImages: [UIImage] = []
        pageImages.reserveCapacity(images.count)
        var jpegURLs: [URL] = []
        jpegURLs.reserveCapacity(images.count)
        for image in images {
            guard let pageImage = preparePageImage(image),
                  let jpegData = pageImage.jpegData(compressionQuality: pageJpegQuality),
                  let jpegURL  = AttachmentStorage.write(jpegData, ext: "jpg") else {
                return nil
            }
            pageImages.append(pageImage)
            jpegURLs.append(jpegURL)
        }

        // Pass 2 — render every image into a PDF. Some platform
        // encoders already produce compact PDFs, so when compression
        // is enabled we keep the smaller of the optimized and raw
        // artifacts instead of blindly returning the re-encoded copy.
        // If PDF rendering fails, we degrade to a JPEG-only result.
        let pdfURL: URL?
        if compressedPdfEnabled {
            let compressed = CompressedImagePdfWriter.writeToAttachment(images: pageImages)
            let raw = writeRawImagePdf(pageImages)
            pdfURL = chooseSmallerPDF(compressed: compressed, raw: raw)
        } else {
            pdfURL = writeRawImagePdf(pageImages)
        }

        return Result(
            pdfURL:     pdfURL,
            previewURL: jpegURLs[0],
            pageURLs:   jpegURLs
        )
    }

    private static func preparePageImage(_ image: UIImage) -> UIImage? {
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else { return nil }

        let longest = max(sourceSize.width, sourceSize.height)
        let scale = longest > pageImageMaxLongEdge ? pageImageMaxLongEdge / longest : 1
        let targetSize = CGSize(
            width:  max(1, (sourceSize.width  * scale).rounded()),
            height: max(1, (sourceSize.height * scale).rounded())
        )

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: targetSize))
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }

    private static func writeRawImagePdf(_ images: [UIImage]) -> URL? {
        guard !images.isEmpty else { return nil }

        let pdfData = NSMutableData()
        UIGraphicsBeginPDFContextToData(pdfData, .zero, nil)
        for image in images {
            let pageRect = CGRect(origin: .zero, size: image.size)
            UIGraphicsBeginPDFPageWithInfo(pageRect, nil)
            image.draw(in: pageRect)
        }
        UIGraphicsEndPDFContext()

        return AttachmentStorage.write(pdfData as Data, ext: "pdf")
    }

    private static func chooseSmallerPDF(compressed: URL?, raw: URL?) -> URL? {
        guard let compressed else { return raw }
        guard let raw else { return compressed }

        if let compressedSize = fileSize(compressed),
           let rawSize = fileSize(raw),
           rawSize < compressedSize {
            try? FileManager.default.removeItem(at: compressed)
            return raw
        }

        try? FileManager.default.removeItem(at: raw)
        return compressed
    }

    private static func fileSize(_ url: URL) -> UInt64? {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attributes?[.size] as? NSNumber)?.uint64Value
    }
}
