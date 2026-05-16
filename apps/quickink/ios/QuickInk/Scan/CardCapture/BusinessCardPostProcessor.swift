/*
 * BusinessCardPostProcessor.swift
 *
 * Hand-off point between the iOS Business Card capture
 * surface and the existing OCR + contact-extraction
 * pipeline. Given a captured CGImage + the most recent
 * valid quad in image coordinates, this post-processor:
 *
 *   1. Perspective-corrects the source corners to
 *      (0,0)→(1011,0)→(1011,637)→(0,637) using Core Image's
 *      `CIPerspectiveCorrection` filter (GPU-backed on all
 *      modern A-chip devices).
 *   2. Saves the result as a JPEG inside the AttachmentStorage
 *      directory.
 *   3. Calls `ScanFlowController.onScanComplete` with
 *      `category: "business-card"` so the existing scan-detail
 *      screen picks up the `BusinessCardExtractor` flow.
 *
 * Manual capture (user tapped the shutter without a valid
 * stability lock): callers pass `quad = nil` and we fall back
 * to the guide rect's corners as the "card quad" — the user
 * was free-handing it, so a center-of-frame heuristic is the
 * best we can do without rejecting their tap.
 *
 * Mirror of Android `BusinessCardPostProcessor.kt`.
 */

import Foundation
import CoreImage
import CoreGraphics
import UIKit
import ReleafCoreData
import ReleafCoreScan

public enum BusinessCardPostProcessor {

    public static let outputWidth: CGFloat  = 1012
    public static let outputHeight: CGFloat = 638

    /// Run the full post-process and hand the resulting capture
    /// to the controller. Returns the warped JPEG's file URL so
    /// the surface can show an in-screen confirm animation if
    /// it wants.
    ///
    /// The user-visible region of the image is the
    /// resizeAspectFill center crop whose aspect matches
    /// `viewWidth`:`viewHeight` (the on-screen canvas behind
    /// the overlay). The guide rect we crop to is the
    /// 70%-of-width / 1.586:1 / 45%-vertical sub-rect inside
    /// THAT visible region — identical math to the overlay's
    /// draw + the detector's IoU check, so what gets warped is
    /// exactly what the user framed.
    ///
    /// Threading: warp + JPEG encode are CPU/GPU-bound; the
    /// controller's `onScanComplete` kicks Tasks internally so
    /// it doesn't block. Call from a non-MainActor context.
    @MainActor
    public static func process(
        source: CGImage,
        quadInImage: DetectedQuad?,
        viewWidth: Float,
        viewHeight: Float,
        controller: ScanFlowController,
    ) async -> URL? {
        let guideInImage = computeGuideInImage(
            source:     source,
            viewWidth:  viewWidth,
            viewHeight: viewHeight,
        )
        let quad = quadInImage ?? guideInImage.asQuad
        guard let warpedURL = await warpAndSave(source: source, quad: quad) else {
            return nil
        }
        controller.onScanComplete(
            pdfURL:     nil,
            previewURL: warpedURL,
            pageURLs:   [warpedURL],
            category:   "business-card",
            paperSize:  .card,
        )
        return warpedURL
    }

    /// Compute the in-image guide rect that corresponds to the
    /// on-screen overlay, accounting for the resizeAspectFill
    /// center crop the preview layer applies. Centralized in
    /// [CardImageOps] so the detector + post-processor agree on
    /// the user-visible region.
    public static func computeGuideInImage(
        source: CGImage,
        viewWidth: Float,
        viewHeight: Float,
    ) -> GuideRect {
        let visible = CardImageOps.visibleRectForViewAspect(
            imageWidth:  source.width,
            imageHeight: source.height,
            viewWidth:   viewWidth,
            viewHeight:  viewHeight,
        )
        return CardImageOps.guideRectInside(visible)
    }

    /// Apply Core Image's `CIPerspectiveCorrection` filter to
    /// map [quad] → axis-aligned `outputWidth × outputHeight`.
    /// Returns the warped JPEG's local file URL.
    ///
    /// Core Image's perspective-correction filter has its own
    /// coordinate convention — Y is flipped (origin at bottom-
    /// left). We flip the quad Y-coordinates before handing
    /// them in, so the result lands right-side-up.
    public static func warpAndSave(source: CGImage, quad: DetectedQuad) async -> URL? {
        let height = CGFloat(source.height)
        let ci = CIImage(cgImage: source)
        let filter = CIFilter(name: "CIPerspectiveCorrection")
        filter?.setValue(ci, forKey: kCIInputImageKey)
        filter?.setValue(CIVector(x: CGFloat(quad.tl.x), y: height - CGFloat(quad.tl.y)), forKey: "inputTopLeft")
        filter?.setValue(CIVector(x: CGFloat(quad.tr.x), y: height - CGFloat(quad.tr.y)), forKey: "inputTopRight")
        filter?.setValue(CIVector(x: CGFloat(quad.br.x), y: height - CGFloat(quad.br.y)), forKey: "inputBottomRight")
        filter?.setValue(CIVector(x: CGFloat(quad.bl.x), y: height - CGFloat(quad.bl.y)), forKey: "inputBottomLeft")
        guard let outputCi = filter?.outputImage else { return nil }
        // CIPerspectiveCorrection's output extent is the warped
        // rectangle's bounds — we want a fixed 1012×638 canvas,
        // so resample via a CIAffineTransform after the
        // perspective filter.
        let extent = outputCi.extent
        let sx = outputWidth  / extent.width
        let sy = outputHeight / extent.height
        let scaled = outputCi
            .transformed(by: CGAffineTransform(translationX: -extent.origin.x, y: -extent.origin.y))
            .transformed(by: CGAffineTransform(scaleX: sx, y: sy))

        let ctx = CIContext(options: nil)
        let outExtent = CGRect(x: 0, y: 0, width: outputWidth, height: outputHeight)
        guard let cg = ctx.createCGImage(scaled, from: outExtent) else { return nil }
        let warpedUiImage = UIImage(cgImage: cg)
        guard let jpegData = warpedUiImage.jpegData(compressionQuality: 0.92) else { return nil }
        // AttachmentStorage.write handles the
        // Application-Support directory creation + UUIDv7
        // filename + atomic write; it returns nil on any IO
        // failure which we pass straight through.
        return AttachmentStorage.write(jpegData, ext: "jpg")
    }
}
