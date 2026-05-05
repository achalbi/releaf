/*
 * DocumentScannerView.swift
 *
 * UIViewControllerRepresentable wrapper over VisionKit's
 * `VNDocumentCameraViewController` — iOS's built-in multi-page document
 * scanner (same UI as Notes.app's scan-a-document sheet). On completion
 * we render the captured pages into a PDF, save both the PDF and the
 * first-page JPEG to our attachments directory, and hand the file URLs
 * back to the caller.
 *
 * Info.plist (app target side — this package has no target of its own):
 *   NSCameraUsageDescription — required string for the camera permission
 *   prompt. VisionKit refuses to present without it.
 */

import SwiftUI
// PR #4i: file moved into shared ReleafCoreScan, which depends on
// ReleafCoreData directly. AttachmentStorage was extracted there in
// PR #4a; QuickInk doesn't import the app-side ReleafData re-export.
import ReleafCoreData

// VisionKit's `VNDocumentCameraViewController` is iOS-only. macOS
// preview/test builds get a placeholder view at the bottom of this
// file with the same public initializer so the rest of the Notepad
// feature compiles.
#if os(iOS)
import UIKit
import VisionKit

public struct DocumentScannerView: UIViewControllerRepresentable {

    /// Called with the stored PDF + first-page preview JPEG URLs +
    /// per-page JPEG URLs on a successful scan. All file:// URLs in
    /// the app's attachments dir.
    ///
    /// `pageURLs` is one JPEG per scanned page (same byte sequence
    /// as the first-page preview for page index 0; subsequent
    /// indices are written individually). QuickInk's OCR pipeline
    /// consumes these. Releaf's existing call sites can ignore the
    /// param via `_` — the PDF + preview were the only outputs the
    /// pre-Phase-3 flow needed.
    let onComplete: (_ pdfURL: URL, _ previewURL: URL?, _ pageURLs: [URL]) -> Void

    /// Called when the user dismisses the scanner or it fails for any
    /// reason. No bytes written in that case.
    let onCancel: () -> Void

    /// When non-nil, only the first `pageLimit` captured pages are
    /// rendered into the PDF + JPEGs. VisionKit itself doesn't
    /// expose a page-limit option (the user can always tap Add Page
    /// inside `VNDocumentCameraViewController`), so we enforce the
    /// cap on the result side: extra pages past the limit are
    /// dropped before any bytes are written. `nil` (default) keeps
    /// every captured page — Releaf's behaviour and QuickInk's
    /// Multi-page / Auto modes. QuickInk's Single mode passes `1`.
    let pageLimit: Int?

    public init(
        onComplete: @escaping (_ pdfURL: URL, _ previewURL: URL?, _ pageURLs: [URL]) -> Void,
        onCancel: @escaping () -> Void,
        pageLimit: Int? = nil
    ) {
        self.onComplete = onComplete
        self.onCancel = onCancel
        self.pageLimit = pageLimit
    }

    public func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let scanner = VNDocumentCameraViewController()
        scanner.delegate = context.coordinator
        return scanner
    }

    public func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    public func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete, onCancel: onCancel, pageLimit: pageLimit)
    }

    public final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        private let onComplete: (_ pdfURL: URL, _ previewURL: URL?, _ pageURLs: [URL]) -> Void
        private let onCancel: () -> Void
        private let pageLimit: Int?

        init(
            onComplete: @escaping (_ pdfURL: URL, _ previewURL: URL?, _ pageURLs: [URL]) -> Void,
            onCancel: @escaping () -> Void,
            pageLimit: Int?
        ) {
            self.onComplete = onComplete
            self.onCancel = onCancel
            self.pageLimit = pageLimit
        }

        public func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFinishWith scan: VNDocumentCameraScan
        ) {
            // Truncate to `pageLimit` before any bytes hit disk so
            // a stray extra capture from the user doesn't leave an
            // orphan JPEG in attachments. VisionKit allows the
            // user to add as many pages as they like inside the
            // sheet; the caller's intent (Single mode) wins here.
            let total = scan.pageCount
            let kept = pageLimit.map { min(total, max(0, $0)) } ?? total
            let pages = (0..<kept).map { scan.imageOfPage(at: $0) }

            // PDF is the canonical artifact. Per-page JPEGs are
            // written so QuickInk's OCR pipeline can address each
            // page individually; first-page preview JPEG is just
            // a back-compat alias for `pageURLs.first` so existing
            // Releaf code paths that read `previewURL` keep working.
            let pdfURL = Self.writePDF(pages: pages)
            let pageURLs = pages.compactMap { Self.writeJPEG($0) }
            let previewURL = pageURLs.first

            controller.dismiss(animated: true) { [onComplete, onCancel] in
                if let pdfURL {
                    onComplete(pdfURL, previewURL, pageURLs)
                } else {
                    onCancel()
                }
            }
        }

        public func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFailWithError error: Error
        ) {
            controller.dismiss(animated: true) { [onCancel] in onCancel() }
        }

        public func documentCameraViewControllerDidCancel(
            _ controller: VNDocumentCameraViewController
        ) {
            controller.dismiss(animated: true) { [onCancel] in onCancel() }
        }

        // MARK: - File writes

        /// Render a multi-page UIGraphicsPDFRenderer PDF. Page size is
        /// tied to each image's pixel dimensions (so aspect ratio is
        /// preserved; we're not trying to format to US Letter).
        private static func writePDF(pages: [UIImage]) -> URL? {
            guard !pages.isEmpty else { return nil }

            let data = NSMutableData()
            // Start with a nominal page size; we'll redefine each
            // page's bounds to match its image so scanned content
            // fills the page without letterboxing.
            UIGraphicsBeginPDFContextToData(data, .zero, nil)
            for image in pages {
                let bounds = CGRect(origin: .zero, size: image.size)
                UIGraphicsBeginPDFPageWithInfo(bounds, nil)
                image.draw(in: bounds)
            }
            UIGraphicsEndPDFContext()

            return AttachmentStorage.write(data as Data, ext: "pdf")
        }

        /// 0.85-quality JPEG of a single UIImage.
        private static func writeJPEG(_ image: UIImage) -> URL? {
            guard let data = image.jpegData(compressionQuality: 0.85) else { return nil }
            return AttachmentStorage.write(data, ext: "jpg")
        }
    }
}

#else

// MARK: - macOS stub

// macOS preview build — the real document scanner depends on
// VisionKit which is iOS-only. Render a placeholder so the consuming
// view hierarchy still compiles; an actual scan obviously can't run
// here.
public struct DocumentScannerView: View {
    let onComplete: (_ pdfURL: URL, _ previewURL: URL?, _ pageURLs: [URL]) -> Void
    let onCancel: () -> Void
    let pageLimit: Int?

    public init(
        onComplete: @escaping (_ pdfURL: URL, _ previewURL: URL?, _ pageURLs: [URL]) -> Void,
        onCancel: @escaping () -> Void,
        pageLimit: Int? = nil
    ) {
        self.onComplete = onComplete
        self.onCancel = onCancel
        self.pageLimit = pageLimit
    }

    public var body: some View {
        VStack(spacing: 12) {
            Text("Document scanner is iOS-only")
                .font(.headline)
            Button("Cancel", action: onCancel)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#endif
