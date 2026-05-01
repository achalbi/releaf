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
import ReleafData  // AttachmentStorage (moved into ReleafCoreData in PR #4a, re-exported by ReleafData)

// VisionKit's `VNDocumentCameraViewController` is iOS-only. macOS
// preview/test builds get a placeholder view at the bottom of this
// file with the same public initializer so the rest of the Notepad
// feature compiles.
#if os(iOS)
import UIKit
import VisionKit

struct DocumentScannerView: UIViewControllerRepresentable {

    /// Called with the stored PDF + first-page preview JPEG URLs on a
    /// successful scan. Both file:// URLs in the app's attachments dir.
    let onComplete: (_ pdfURL: URL, _ previewURL: URL?) -> Void

    /// Called when the user dismisses the scanner or it fails for any
    /// reason. No bytes written in that case.
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let scanner = VNDocumentCameraViewController()
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete, onCancel: onCancel)
    }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        private let onComplete: (_ pdfURL: URL, _ previewURL: URL?) -> Void
        private let onCancel: () -> Void

        init(
            onComplete: @escaping (_ pdfURL: URL, _ previewURL: URL?) -> Void,
            onCancel: @escaping () -> Void
        ) {
            self.onComplete = onComplete
            self.onCancel = onCancel
        }

        func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFinishWith scan: VNDocumentCameraScan
        ) {
            let pages = (0..<scan.pageCount).map { scan.imageOfPage(at: $0) }

            // PDF is the canonical artifact. Preview JPEG is optional —
            // it's just the first page rendered at reduced quality, used
            // as the thumbnail in the ScansSection grid.
            let pdfURL = Self.writePDF(pages: pages)
            let previewURL = pages.first.flatMap { Self.writeJPEG($0) }

            controller.dismiss(animated: true) { [onComplete, onCancel] in
                if let pdfURL {
                    onComplete(pdfURL, previewURL)
                } else {
                    onCancel()
                }
            }
        }

        func documentCameraViewController(
            _ controller: VNDocumentCameraViewController,
            didFailWithError error: Error
        ) {
            controller.dismiss(animated: true) { [onCancel] in onCancel() }
        }

        func documentCameraViewControllerDidCancel(
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
struct DocumentScannerView: View {
    let onComplete: (_ pdfURL: URL, _ previewURL: URL?) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("Document scanner is iOS-only")
                .font(.headline)
            Button("Cancel", action: onCancel)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#endif
