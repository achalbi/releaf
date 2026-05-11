/*
 * CardImageOpsTests.swift
 *
 * Pure-XCTest unit coverage for the iOS CV primitives the
 * Business Card detector composes. Mirror of Android's
 * `CardImageOpsTest.kt` — same test inputs, same expected
 * outputs, so a regression on one platform is visible on the
 * other.
 */

import XCTest
@testable import QuickInkFeatures

final class CardImageOpsTests: XCTestCase {

    // MARK: - Corner ordering

    func testCornerOrderingProducesTLTRBRBLRegardlessOfInputOrder() {
        let tl = Point2f(x: 10, y: 20)
        let tr = Point2f(x: 110, y: 22)
        let br = Point2f(x: 112, y: 80)
        let bl = Point2f(x: 11, y: 78)
        let ordered = CardImageOps.orderCornersClockwise([br, bl, tl, tr])
        XCTAssertEqual(ordered.tl, tl)
        XCTAssertEqual(ordered.tr, tr)
        XCTAssertEqual(ordered.br, br)
        XCTAssertEqual(ordered.bl, bl)
    }

    func testCornerOrderingIsStableUnderAxisAlignedInput() {
        let tl = Point2f(x: 0, y: 0)
        let tr = Point2f(x: 100, y: 0)
        let br = Point2f(x: 100, y: 60)
        let bl = Point2f(x: 0, y: 60)
        let ordered = CardImageOps.orderCornersClockwise([tl, tr, br, bl])
        XCTAssertEqual(ordered.tl, tl)
        XCTAssertEqual(ordered.tr, tr)
        XCTAssertEqual(ordered.br, br)
        XCTAssertEqual(ordered.bl, bl)
    }

    // MARK: - IoU

    func testPolygonIoUOfQuadFullyInsideGuide() {
        let guide = GuideRect(left: 0, top: 0, right: 200, bottom: 100)
        let quad = DetectedQuad(
            tl: Point2f(x: 50,  y: 25),
            tr: Point2f(x: 150, y: 25),
            br: Point2f(x: 150, y: 75),
            bl: Point2f(x: 50,  y: 75),
        )
        // quad area = 5000, guide area = 20000, intersection = 5000
        // IoU = 5000 / 20000 = 0.25
        XCTAssertEqual(CardImageOps.polygonIou(quad: quad, guide: guide), 0.25, accuracy: 1e-3)
    }

    func testPolygonIoUOfQuadEqualToGuideIsOne() {
        let guide = GuideRect(left: 10, top: 20, right: 110, bottom: 80)
        let quad = guide.asQuad
        XCTAssertEqual(CardImageOps.polygonIou(quad: quad, guide: guide), 1.0, accuracy: 1e-3)
    }

    func testPolygonIoUOfDisjointQuadAndGuideIsZero() {
        let guide = GuideRect(left: 0, top: 0, right: 100, bottom: 60)
        let quad = DetectedQuad(
            tl: Point2f(x: 200, y: 200),
            tr: Point2f(x: 300, y: 200),
            br: Point2f(x: 300, y: 260),
            bl: Point2f(x: 200, y: 260),
        )
        XCTAssertEqual(CardImageOps.polygonIou(quad: quad, guide: guide), 0.0, accuracy: 1e-3)
    }

    // MARK: - Convexity + area

    func testConvexityTrueForUnitSquare() {
        let poly: [Float] = [0, 0, 1, 0, 1, 1, 0, 1]
        XCTAssertTrue(CardImageOps.isConvex(poly: poly))
    }

    func testConvexityFalseForSelfIntersectingBowtie() {
        let poly: [Float] = [0, 0, 1, 1, 1, 0, 0, 1]
        XCTAssertFalse(CardImageOps.isConvex(poly: poly))
    }

    func testShoelaceAreaOfKnownSquare() {
        let poly: [Float] = [0, 0, 10, 0, 10, 10, 0, 10]
        XCTAssertEqual(CardImageOps.shoelaceArea(poly: poly), 100, accuracy: 1e-3)
    }

    // MARK: - approxPolyDp

    func testApproxPolyDpReducesDenseRectangleToFourCorners() {
        var pts: [Int] = []
        for x in stride(from: 0, through: 100, by: 5) { pts.append(x); pts.append(0) }
        for y in stride(from: 5, through: 60, by: 5)  { pts.append(100); pts.append(y) }
        for x in stride(from: 95, through: 0, by: -5) { pts.append(x); pts.append(60) }
        for y in stride(from: 55, through: 5, by: -5) { pts.append(0); pts.append(y) }
        let poly = CardImageOps.approxPolyDp(contour: pts, epsilon: 2)
        // Allow 4-5 vertices — RDP may keep the closing pixel.
        XCTAssertTrue((4...5).contains(poly.count / 2),
                      "Expected 4-5 vertices, got \(poly.count / 2)")
    }

    // MARK: - Perspective transform

    func testPerspectiveTransformMapsSourceCornersOntoDestinationCorners() {
        let src = [
            Point2f(x: 10, y: 20),
            Point2f(x: 150, y: 22),
            Point2f(x: 152, y: 80),
            Point2f(x: 12, y: 78),
        ]
        let dst = [
            Point2f(x: 0, y: 0),
            Point2f(x: 1011, y: 0),
            Point2f(x: 1011, y: 637),
            Point2f(x: 0, y: 637),
        ]
        let h = CardImageOps.getPerspectiveTransform(src: src, dst: dst)
        for i in 0..<4 {
            let sx = src[i].x; let sy = src[i].y
            let w  = h[6] * sx + h[7] * sy + h[8]
            let tx = (h[0] * sx + h[1] * sy + h[2]) / w
            let ty = (h[3] * sx + h[4] * sy + h[5]) / w
            XCTAssertEqual(tx, dst[i].x, accuracy: 1)
            XCTAssertEqual(ty, dst[i].y, accuracy: 1)
        }
    }

    func testPerspectiveTransformOfIdentityQuadIsIdentity() {
        let same = [
            Point2f(x: 0, y: 0),
            Point2f(x: 100, y: 0),
            Point2f(x: 100, y: 60),
            Point2f(x: 0, y: 60),
        ]
        let h = CardImageOps.getPerspectiveTransform(src: same, dst: same)
        XCTAssertEqual(h[0], 1, accuracy: 1e-3); XCTAssertEqual(h[1], 0, accuracy: 1e-3); XCTAssertEqual(h[2], 0, accuracy: 1e-3)
        XCTAssertEqual(h[3], 0, accuracy: 1e-3); XCTAssertEqual(h[4], 1, accuracy: 1e-3); XCTAssertEqual(h[5], 0, accuracy: 1e-3)
        XCTAssertEqual(h[6], 0, accuracy: 1e-5); XCTAssertEqual(h[7], 0, accuracy: 1e-5); XCTAssertEqual(h[8], 1, accuracy: 1e-3)
    }
}
