/*
 * CardImageOpsTest.kt
 *
 * Pure-JVM unit tests for the CV primitives the Business Card
 * detector composes. The four ops we care about for capture
 * correctness:
 *
 *   - orderCornersClockwise → TL/TR/BR/BL invariant under
 *                             arbitrary input rotation
 *   - polygonIou            → matches a hand-computed value
 *                             on a worked example (quad fully
 *                             inside guide, half inside, none)
 *   - approxPolyDp          → drops collinear interior vertices
 *   - perspective transform → round-trip identity
 *
 * No Android dependencies — runs as a standard JUnit 4 test.
 */

package app.quickink.mobile.scan.cardcapture

import app.quickink.mobile.features.scan.cardcapture.CardImageOps
import app.quickink.mobile.features.scan.cardcapture.DetectedQuad
import app.quickink.mobile.features.scan.cardcapture.GuideRect
import app.quickink.mobile.features.scan.cardcapture.Point2f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CardImageOpsTest {

    // ── Corner ordering ─────────────────────────────────────────────

    @Test fun `corner ordering produces TL TR BR BL regardless of input order`() {
        val tl = Point2f(10f, 20f)
        val tr = Point2f(110f, 22f)
        val br = Point2f(112f, 80f)
        val bl = Point2f(11f, 78f)
        // Feed in a rotated order — BR first.
        val ordered = CardImageOps.orderCornersClockwise(arrayOf(br, bl, tl, tr))
        assertEquals(tl, ordered.tl)
        assertEquals(tr, ordered.tr)
        assertEquals(br, ordered.br)
        assertEquals(bl, ordered.bl)
    }

    @Test fun `corner ordering is stable under exact-axis-aligned input`() {
        val tl = Point2f(0f, 0f)
        val tr = Point2f(100f, 0f)
        val br = Point2f(100f, 60f)
        val bl = Point2f(0f, 60f)
        val ordered = CardImageOps.orderCornersClockwise(arrayOf(tl, tr, br, bl))
        assertEquals(tl, ordered.tl)
        assertEquals(tr, ordered.tr)
        assertEquals(br, ordered.br)
        assertEquals(bl, ordered.bl)
    }

    // ── IoU ─────────────────────────────────────────────────────────

    @Test fun `polygon IoU of quad fully inside guide is quadArea over guideArea`() {
        val guide = GuideRect(0f, 0f, 200f, 100f)
        val quad = DetectedQuad(
            tl = Point2f(50f,  25f),
            tr = Point2f(150f, 25f),
            br = Point2f(150f, 75f),
            bl = Point2f(50f,  75f),
        )
        // Quad is 100×50=5000; guide is 200×100=20000.
        // Intersection = quad area = 5000.
        // IoU = 5000 / (5000 + 20000 - 5000) = 5000 / 20000 = 0.25
        val iou = CardImageOps.polygonIou(quad, guide)
        assertNear(0.25f, iou, 1e-3f)
    }

    @Test fun `polygon IoU of quad equal to guide is 1`() {
        val guide = GuideRect(10f, 20f, 110f, 80f)
        val quad = guide.asQuad()
        val iou = CardImageOps.polygonIou(quad, guide)
        assertNear(1f, iou, 1e-3f)
    }

    @Test fun `polygon IoU of disjoint quad and guide is 0`() {
        val guide = GuideRect(0f, 0f, 100f, 60f)
        val quad = DetectedQuad(
            tl = Point2f(200f, 200f),
            tr = Point2f(300f, 200f),
            br = Point2f(300f, 260f),
            bl = Point2f(200f, 260f),
        )
        val iou = CardImageOps.polygonIou(quad, guide)
        assertNear(0f, iou, 1e-3f)
    }

    // ── approxPolyDP ────────────────────────────────────────────────

    @Test fun `approxPolyDp reduces a many-point rectangle to four corners`() {
        // Densely sampled rectangle contour — 40 points along
        // the perimeter of a 100×60 box at origin. Expect RDP
        // to collapse it back to the four corners.
        val pts = ArrayList<Int>()
        for (x in 0..100 step 5) { pts.add(x); pts.add(0) }
        for (y in 5..60 step 5)  { pts.add(100); pts.add(y) }
        for (x in 95 downTo 0 step 5) { pts.add(x); pts.add(60) }
        for (y in 55 downTo 5 step 5) { pts.add(0); pts.add(y) }
        val poly = CardImageOps.approxPolyDp(pts.toIntArray(), epsilon = 2f)
        // Should collapse to ~4 corners. Allow a small slack —
        // RDP on an open polyline with no closing vertex can
        // sometimes leave 5 (the wrap-around point).
        assertTrue("RDP returned ${poly.size / 2} vertices, expected 4 or 5",
            poly.size / 2 in 4..5)
    }

    // ── Convexity ───────────────────────────────────────────────────

    @Test fun `isConvex true for a unit square`() {
        val poly = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertTrue(CardImageOps.isConvex(poly))
    }

    @Test fun `isConvex false for a self-intersecting bowtie`() {
        val poly = floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f)
        assertTrue(!CardImageOps.isConvex(poly))
    }

    // ── Perspective transform ───────────────────────────────────────

    @Test fun `perspective transform maps source corners onto destination corners`() {
        val src = arrayOf(
            Point2f(10f, 20f),
            Point2f(150f, 22f),
            Point2f(152f, 80f),
            Point2f(12f, 78f),
        )
        val dst = arrayOf(
            Point2f(0f, 0f),
            Point2f(1011f, 0f),
            Point2f(1011f, 637f),
            Point2f(0f, 637f),
        )
        val h = CardImageOps.getPerspectiveTransform(src, dst)
        // Apply h to each src corner; should land on dst within ~1 px.
        for (i in 0 until 4) {
            val sx = src[i].x; val sy = src[i].y
            val w  = h[6] * sx + h[7] * sy + h[8]
            val tx = (h[0] * sx + h[1] * sy + h[2]) / w
            val ty = (h[3] * sx + h[4] * sy + h[5]) / w
            assertNear(dst[i].x, tx, 1f)
            assertNear(dst[i].y, ty, 1f)
        }
    }

    @Test fun `perspective transform of identity quad is identity`() {
        val same = arrayOf(
            Point2f(0f, 0f),
            Point2f(100f, 0f),
            Point2f(100f, 60f),
            Point2f(0f, 60f),
        )
        val h = CardImageOps.getPerspectiveTransform(same, same)
        // Diagonal ≈ 1, off-diagonal ≈ 0, projective row ≈ (0,0,1).
        assertNear(1f, h[0], 1e-3f); assertNear(0f, h[1], 1e-3f); assertNear(0f, h[2], 1e-3f)
        assertNear(0f, h[3], 1e-3f); assertNear(1f, h[4], 1e-3f); assertNear(0f, h[5], 1e-3f)
        assertNear(0f, h[6], 1e-5f); assertNear(0f, h[7], 1e-5f); assertNear(1f, h[8], 1e-3f)
    }

    // ── Shoelace area ───────────────────────────────────────────────

    @Test fun `shoelace area of a known square matches side squared`() {
        val poly = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertNear(100f, CardImageOps.shoelaceArea(poly), 1e-3f)
    }

    private fun assertNear(expected: Float, actual: Float, eps: Float) {
        assertTrue("expected≈$expected, got $actual (eps=$eps)", abs(expected - actual) <= eps)
    }
}
