/*
 * BusinessCardDetector.swift
 *
 * Per-frame detector that runs the CardImageOps pipeline on
 * the Y (luma) plane of a CVPixelBuffer delivered by
 * `AVCaptureVideoDataOutput`. The contract mirrors Android:
 *
 *   in:  CVPixelBuffer (kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
 *        + GuideRect in pixel-buffer space
 *   out: DetectionResult — none / partial(quad) / valid(quad)
 *
 * The detector is allocation-conscious — buffer reuse across
 * frames keeps the per-frame allocations near zero so it
 * doesn't thrash the iOS app heap. Each instance is
 * single-threaded; the AVCaptureVideoDataOutput delegate's
 * `captureOutput(_:didOutput:from:)` is the only call site.
 *
 * Mirror of Android `BusinessCardDetector.kt`.
 */

import Foundation
import CoreVideo

public enum RejectReason: Sendable {
    case notConvex
    case tooSmall
    case wrongAspect
    case skewed
    case offFrame
    case lowIou
    case noQuad
}

public enum DetectionResult: Sendable {
    case none
    case partial(DetectedQuad, RejectReason)
    case valid(DetectedQuad, iou: Float)

    public var quad: DetectedQuad? {
        switch self {
        case .none:               return nil
        case .partial(let q, _):  return q
        case .valid(let q, _):    return q
        }
    }
}

public final class BusinessCardDetector {

    public let analyzerWidth: Int
    public let analyzerHeight: Int

    private let minAspect: Float
    private let maxAspect: Float
    private let edgeMarginFrac: Float
    private let maxOppositeEdgeRatio: Float
    private let minRoiAreaFrac: Float

    private var grayBuf: [UInt8]
    private var roiBuf: [UInt8] = []
    private var scratchBuf: [UInt8] = []
    private var binaryBuf: [UInt8] = []
    private var roiW: Int = 0
    private var roiH: Int = 0

    public init(
        analyzerWidth: Int,
        analyzerHeight: Int,
        minAspect: Float = 1.4,
        maxAspect: Float = 1.8,
        edgeMarginFrac: Float = 0.02,
        maxOppositeEdgeRatio: Float = 1.4,
        minRoiAreaFrac: Float = 0.30,
    ) {
        self.analyzerWidth = analyzerWidth
        self.analyzerHeight = analyzerHeight
        self.minAspect = minAspect
        self.maxAspect = maxAspect
        self.edgeMarginFrac = edgeMarginFrac
        self.maxOppositeEdgeRatio = maxOppositeEdgeRatio
        self.minRoiAreaFrac = minRoiAreaFrac
        self.grayBuf = [UInt8](repeating: 0, count: analyzerWidth * analyzerHeight)
    }

    public func detect(pixelBuffer: CVPixelBuffer, guide: GuideRect) -> DetectionResult {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else {
            return .none
        }
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)

        if grayBuf.count < width * height {
            grayBuf = [UInt8](repeating: 0, count: width * height)
        }
        // Copy plane 0 (Y plane) row-by-row to drop bytesPerRow
        // padding. iOS commonly hands you 384-byte-aligned rows;
        // strip them so the rest of the pipeline reads a dense
        // `width × height` array.
        let src = base.assumingMemoryBound(to: UInt8.self)
        grayBuf.withUnsafeMutableBufferPointer { dst in
            for y in 0..<height {
                memcpy(
                    dst.baseAddress!.advanced(by: y * width),
                    src.advanced(by: y * bytesPerRow),
                    width,
                )
            }
        }

        let targetRoiW = min(width,  Int(guide.width  * 1.10))
        let targetRoiH = min(height, Int(guide.height * 1.10))
        if roiBuf.count < targetRoiW * targetRoiH {
            roiBuf     = [UInt8](repeating: 0, count: targetRoiW * targetRoiH)
            scratchBuf = [UInt8](repeating: 0, count: targetRoiW * targetRoiH)
            binaryBuf  = [UInt8](repeating: 0, count: targetRoiW * targetRoiH)
        }
        let roiX0 = max(0, Int(guide.centerX) - targetRoiW / 2)
        let roiY0 = max(0, Int(guide.centerY) - targetRoiH / 2)
        let roiX1 = min(width,  roiX0 + targetRoiW)
        let roiY1 = min(height, roiY0 + targetRoiH)
        let actualW = roiX1 - roiX0
        let actualH = roiY1 - roiY0
        if actualW < 16 || actualH < 16 { return .none }
        roiW = actualW; roiH = actualH

        for y in roiY0..<roiY1 {
            let srcRow = y * width + roiX0
            let dstRow = (y - roiY0) * actualW
            for x in 0..<actualW {
                roiBuf[dstRow + x] = grayBuf[srcRow + x]
            }
        }

        CardImageOps.gaussianBlur5(
            src: &roiBuf,
            width: actualW,
            height: actualH,
            scratch: &scratchBuf,
        )
        CardImageOps.adaptiveMeanThreshold(
            src: roiBuf,
            width: actualW,
            height: actualH,
            blockSize: 21,
            c: 8,
            dst: &binaryBuf,
        )

        var quad = runContourPipeline()
        if quad == nil {
            CardImageOps.sobelEdges(
                src: roiBuf,
                width: actualW,
                height: actualH,
                low: 50, high: 150,
                dst: &binaryBuf,
            )
            quad = runContourPipeline()
        }
        guard let q = quad else { return .none }

        let translated = DetectedQuad(
            tl: Point2f(x: q.tl.x + Float(roiX0), y: q.tl.y + Float(roiY0)),
            tr: Point2f(x: q.tr.x + Float(roiX0), y: q.tr.y + Float(roiY0)),
            br: Point2f(x: q.br.x + Float(roiX0), y: q.br.y + Float(roiY0)),
            bl: Point2f(x: q.bl.x + Float(roiX0), y: q.bl.y + Float(roiY0)),
        )

        return acceptQuad(translated, guide: guide)
    }

    private func runContourPipeline() -> DetectedQuad? {
        let minPx = max(64, Int(Float(roiW * roiH) * minRoiAreaFrac * 0.25))
        let contours = CardImageOps.findExternalContours(
            binary: binaryBuf,
            width: roiW,
            height: roiH,
            minPixels: minPx,
            maxContours: 8,
        )
        guard let largest = contours.max(by: { $0.count < $1.count }) else { return nil }
        let perimeter = Float(largest.count) / 2.0
        let epsilon = 0.02 * perimeter
        let poly = CardImageOps.approxPolyDp(contour: largest, epsilon: epsilon)
        guard poly.count == 8 else { return nil }
        guard CardImageOps.isConvex(poly: poly) else { return nil }
        let corners = [
            Point2f(x: poly[0], y: poly[1]),
            Point2f(x: poly[2], y: poly[3]),
            Point2f(x: poly[4], y: poly[5]),
            Point2f(x: poly[6], y: poly[7]),
        ]
        return CardImageOps.orderCornersClockwise(corners)
    }

    private func acceptQuad(_ q: DetectedQuad, guide: GuideRect) -> DetectionResult {
        let topEdge    = q.tl.distance(to: q.tr)
        let bottomEdge = q.bl.distance(to: q.br)
        let leftEdge   = q.tl.distance(to: q.bl)
        let rightEdge  = q.tr.distance(to: q.br)
        let avgLong  = (topEdge + bottomEdge) * 0.5
        let avgShort = (leftEdge + rightEdge) * 0.5
        if avgShort < 1e-3 { return .partial(q, .wrongAspect) }
        let aspect = avgLong / avgShort
        if aspect < minAspect || aspect > maxAspect {
            return .partial(q, .wrongAspect)
        }

        let topBotRatio = max(topEdge, bottomEdge) / min(topEdge, bottomEdge)
        let lftRgtRatio = max(leftEdge, rightEdge) / min(leftEdge, rightEdge)
        if topBotRatio > maxOppositeEdgeRatio || lftRgtRatio > maxOppositeEdgeRatio {
            return .partial(q, .skewed)
        }

        let edgeMargin = edgeMarginFrac * Float(min(analyzerWidth, analyzerHeight))
        if anyCornerNearEdge(q, margin: edgeMargin) {
            return .partial(q, .offFrame)
        }

        let polyArr: [Float] = [
            q.tl.x, q.tl.y,
            q.tr.x, q.tr.y,
            q.br.x, q.br.y,
            q.bl.x, q.bl.y,
        ]
        let area = CardImageOps.shoelaceArea(poly: polyArr)
        if area < guide.width * guide.height * minRoiAreaFrac {
            return .partial(q, .tooSmall)
        }

        let iou = CardImageOps.polygonIou(quad: q, guide: guide)
        if iou >= 0.85 { return .valid(q, iou: iou) }
        return .partial(q, .lowIou)
    }

    private func anyCornerNearEdge(_ q: DetectedQuad, margin: Float) -> Bool {
        let w = Float(analyzerWidth); let h = Float(analyzerHeight)
        func bad(_ p: Point2f) -> Bool {
            p.x < margin || p.y < margin || p.x > w - margin || p.y > h - margin
        }
        return bad(q.tl) || bad(q.tr) || bad(q.br) || bad(q.bl)
    }
}
