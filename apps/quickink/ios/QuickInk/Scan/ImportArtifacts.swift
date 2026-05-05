/*
 * ImportArtifacts.swift
 *
 * Bridge between the system PhotosPicker and `ScanFlowController`.
 * Takes the `UIImage`s the user picked from the gallery, writes each
 * as a JPEG into `AttachmentStorage`, renders a single multi-page
 * PDF wrapping every page, and returns the URL triple
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

enum ImportArtifacts {

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
    /// Compression quality 0.92 matches what `DocumentScannerView`'s
    /// per-page JPEG writer uses, so imported photos and scanned
    /// pages share the same quality budget.
    static func build(from images: [UIImage]) -> Result? {
        guard !images.isEmpty else { return nil }

        // Pass 1 — write every JPEG into AttachmentStorage. We do
        // this first (separately from PDF rendering) so the
        // page-urls list is fully formed before we touch the PDF
        // context. If any one fails we abort the whole import
        // rather than silently dropping pages.
        var jpegURLs: [URL] = []
        jpegURLs.reserveCapacity(images.count)
        for image in images {
            guard let jpegData = image.jpegData(compressionQuality: 0.92),
                  let jpegURL  = AttachmentStorage.write(jpegData, ext: "jpg") else {
                return nil
            }
            jpegURLs.append(jpegURL)
        }

        // Pass 2 — render every image into a single PDF document.
        // Each `UIGraphicsBeginPDFPageWithInfo` opens a new page
        // sized to that image's pixel dimensions; we don't try to
        // fit to A4 / letter since the downstream PDF viewer
        // (PDFKitView) scales to the available width regardless,
        // and a 1:1 page size keeps the embedded image lossless.
        let pdfData = NSMutableData()
        UIGraphicsBeginPDFContextToData(pdfData, .zero, nil)
        for image in images {
            let pageRect = CGRect(origin: .zero, size: image.size)
            UIGraphicsBeginPDFPageWithInfo(pageRect, nil)
            image.draw(in: pageRect)
        }
        UIGraphicsEndPDFContext()

        let pdfURL = AttachmentStorage.write(pdfData as Data, ext: "pdf")

        return Result(
            pdfURL:     pdfURL,
            previewURL: jpegURLs[0],
            pageURLs:   jpegURLs
        )
    }
}
