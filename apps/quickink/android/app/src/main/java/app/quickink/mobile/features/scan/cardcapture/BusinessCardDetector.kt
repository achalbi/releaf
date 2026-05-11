/*
 * BusinessCardDetector.kt
 *
 * Per-frame detector that runs the OpenCV-style pipeline in
 * [CardImageOps] against a CameraX `ImageProxy`. The contract is
 * narrow:
 *
 *   in:  ImageProxy (YUV_420_888) + view-space GuideRect
 *   out: DetectionResult — None / Partial(quad) / Valid(quad)
 *
 * The detector is allocation-conscious — buffer reuse across
 * frames keeps the per-frame allocations to ~zero so it doesn't
 * thrash the GC on a Pixel 6a-class device. Each instance is
 * single-threaded (don't share across analyzer threads); the
 * detector closure inside the analyzer creates one and reuses it.
 *
 * Why None / Partial / Valid:
 *   - None    → no plausible quad in this frame; overlay neutral
 *   - Partial → quad detected but failed aspect / IoU / off-frame
 *               gates; overlay yellow ("we see something")
 *   - Valid   → quad cleared every gate; overlay green, stability
 *               buffer accepts it as a stability vote
 *
 * The three-tier result is the source of the spec's
 * neutral/yellow/green overlay states.
 */

package app.quickink.mobile.features.scan.cardcapture

import androidx.camera.core.ImageProxy
import kotlin.math.min

/**
 * Per-frame outcome. [quad] is null on [None]; the same shape on
 * [Partial] and [Valid] so the overlay can draw it either way and
 * just pick a tint.
 */
sealed interface DetectionResult {
    val quad: DetectedQuad?

    data object None : DetectionResult { override val quad: DetectedQuad? = null }
    data class Partial(override val quad: DetectedQuad, val reason: RejectReason) : DetectionResult
    data class Valid(override val quad: DetectedQuad, val iou: Float) : DetectionResult
}

enum class RejectReason {
    NotConvex,
    TooSmall,
    WrongAspect,
    Skewed,
    OffFrame,
    LowIou,
    NoQuad,
}

/**
 * Stateful detector. Construct once, call [detect] per frame.
 * Buffers grow on first call to the largest frame size seen;
 * subsequent calls at the same size are allocation-free in the
 * detector's own state.
 *
 * The detector operates on a DOWNSCALED grayscale frame —
 * [analyzerWidth] / [analyzerHeight] are the dimensions of the
 * frame CameraX hands to the ImageAnalysis callback. The spec
 * targets 1280×720 for detection (high-res still capture is
 * separate); the analyzer is bound to that resolution upstream.
 *
 * The [guideInAnalyzer] is the guide rect expressed in analyzer-
 * frame coordinates (not view coordinates) so the IoU check and
 * the ROI crop reason against the same pixel grid as the input.
 */
class BusinessCardDetector(
    private val analyzerWidth: Int,
    private val analyzerHeight: Int,
    /**
     * Card aspect ratios accepted by the gate. Default range is
     * 1.4 to 1.8 (spec callout) — slightly wider than the ISO
     * 7810 ID-1 ratio of 1.586 so cards held at a slight pitch
     * still clear.
     */
    private val minAspect: Float = 1.4f,
    private val maxAspect: Float = 1.8f,
    /**
     * Off-frame margin — quads with any corner closer than this
     * fraction to the preview edge are rejected (spec: 2%). The
     * fraction is over the analyzer dimensions, not the view's.
     */
    private val edgeMarginFrac: Float = 0.02f,
    /**
     * Opposite-edge length ratio that flags a quad as too skewed
     * to be a real card. Spec calls out 1.4 as the reject
     * threshold.
     */
    private val maxOppositeEdgeRatio: Float = 1.4f,
    /**
     * Minimum quad area as a fraction of the ROI. Spec calls out
     * 30%. Below this we reject as "noise" — typically corners
     * of a label or a glare highlight that happens to be quadish.
     */
    private val minRoiAreaFrac: Float = 0.30f,
) {
    // Scratch buffers — grown on first frame, then reused.
    private var grayBuf: ByteArray = ByteArray(analyzerWidth * analyzerHeight)
    private var roiBuf: ByteArray = ByteArray(analyzerWidth * analyzerHeight)
    private var scratchBuf: ByteArray = ByteArray(analyzerWidth * analyzerHeight)
    private var binaryBuf: ByteArray = ByteArray(analyzerWidth * analyzerHeight)
    private val roiRect = IntArray(4)

    /**
     * Run one detection pass. The guide rect is what we'll
     * compute IoU against AND what we'll expand by 10% to define
     * the ROI crop bounds.
     */
    fun detect(image: ImageProxy, guide: GuideRect): DetectionResult {
        // 1. Pull the luma plane out into [grayBuf]. CameraX
        //    guarantees YUV_420_888 here; plane 0 is Y.
        val plane = image.planes[0]
        if (grayBuf.size < analyzerWidth * analyzerHeight) {
            grayBuf = ByteArray(analyzerWidth * analyzerHeight)
        }
        CardImageOps.copyLumaPlane(
            src         = plane.buffer,
            srcWidth    = image.width,
            srcHeight   = image.height,
            rowStride   = plane.rowStride,
            pixelStride = plane.pixelStride,
            dst         = grayBuf,
        )

        // 2. Compute ROI bounds — guide rect grown by 10% on each
        //    axis, clamped to the analyzer frame. The spec asks
        //    for the detector to focus inside the guide + margin
        //    so a half-card off the side doesn't waste cycles.
        val roiW = min(analyzerWidth,  (guide.width  * 1.10f).toInt())
        val roiH = min(analyzerHeight, (guide.height * 1.10f).toInt())
        if (roiBuf.size < roiW * roiH) {
            roiBuf = ByteArray(roiW * roiH)
            scratchBuf = ByteArray(roiW * roiH)
            binaryBuf = ByteArray(roiW * roiH)
        }
        CardImageOps.cropRoi(
            src         = grayBuf,
            srcWidth    = analyzerWidth,
            srcHeight   = analyzerHeight,
            roiCenterX  = guide.centerX.toInt(),
            roiCenterY  = guide.centerY.toInt(),
            roiWidth    = roiW,
            roiHeight   = roiH,
            dst         = roiBuf,
            outRoi      = roiRect,
        )
        val roiX0 = roiRect[0]; val roiY0 = roiRect[1]
        val roiActualW = roiRect[2]; val roiActualH = roiRect[3]

        if (roiActualW < 16 || roiActualH < 16) return DetectionResult.None

        // 3. Blur (5×5 separable Gaussian) — runs in-place against
        //    [roiBuf] using [scratchBuf] for the horizontal pass.
        CardImageOps.gaussianBlur5(
            src     = roiBuf,
            width   = roiActualW,
            height  = roiActualH,
            scratch = scratchBuf,
        )

        // 4. Adaptive threshold (block=21, C=8 per spec). Output
        //    is a binary mask in [binaryBuf].
        CardImageOps.adaptiveMeanThreshold(
            src       = roiBuf,
            width     = roiActualW,
            height    = roiActualH,
            blockSize = 21,
            c         = 8,
            dst       = binaryBuf,
        )

        var quad = runContourPipeline(roiActualW, roiActualH)
        if (quad == null) {
            // Adaptive-threshold contour failed — try the Sobel
            // gradient fallback the spec calls out for the
            // glare-on-glossy-cards case.
            CardImageOps.sobelEdges(
                src    = roiBuf,
                width  = roiActualW,
                height = roiActualH,
                low    = 50,
                high   = 150,
                dst    = binaryBuf,
            )
            quad = runContourPipeline(roiActualW, roiActualH)
        }
        quad ?: return DetectionResult.None

        // 5. Translate quad from ROI-local coordinates back into
        //    analyzer-frame coordinates so callers (overlay, IoU
        //    gate, perspective warp) all share the same space.
        val translated = translateQuad(quad, roiX0.toFloat(), roiY0.toFloat())

        // 6. Run the acceptance gates.
        val gate = acceptQuad(translated, guide)
        return gate
    }

    private fun runContourPipeline(roiW: Int, roiH: Int): DetectedQuad? {
        val minPx = (roiW * roiH * minRoiAreaFrac * 0.25f).toInt().coerceAtLeast(64)
        val contours = CardImageOps.findExternalContours(
            binary      = binaryBuf,
            width       = roiW,
            height      = roiH,
            minPixels   = minPx,
            maxContours = 8,
        )
        if (contours.isEmpty()) return null
        // Largest-perimeter wins — area proxy without re-running
        // shoelace on every contour.
        val largest = contours.maxBy { it.size }
        val perimeter = largest.size / 2f
        val epsilon = 0.02f * perimeter
        val poly = CardImageOps.approxPolyDp(largest, epsilon)
        if (poly.size != 8) return null
        if (!CardImageOps.isConvex(poly)) return null
        val corners = arrayOf(
            Point2f(poly[0], poly[1]),
            Point2f(poly[2], poly[3]),
            Point2f(poly[4], poly[5]),
            Point2f(poly[6], poly[7]),
        )
        return CardImageOps.orderCornersClockwise(corners)
    }

    private fun translateQuad(q: DetectedQuad, dx: Float, dy: Float): DetectedQuad =
        DetectedQuad(
            tl = Point2f(q.tl.x + dx, q.tl.y + dy),
            tr = Point2f(q.tr.x + dx, q.tr.y + dy),
            br = Point2f(q.br.x + dx, q.br.y + dy),
            bl = Point2f(q.bl.x + dx, q.bl.y + dy),
        )

    private fun acceptQuad(q: DetectedQuad, guide: GuideRect): DetectionResult {
        // 1. Aspect ratio gate.
        val topEdge    = q.tl.distanceTo(q.tr)
        val bottomEdge = q.bl.distanceTo(q.br)
        val leftEdge   = q.tl.distanceTo(q.bl)
        val rightEdge  = q.tr.distanceTo(q.br)
        val avgLong  = (topEdge + bottomEdge) * 0.5f
        val avgShort = (leftEdge + rightEdge) * 0.5f
        if (avgShort < 1e-3f) return DetectionResult.Partial(q, RejectReason.WrongAspect)
        val aspect = avgLong / avgShort
        if (aspect < minAspect || aspect > maxAspect) {
            return DetectionResult.Partial(q, RejectReason.WrongAspect)
        }

        // 2. Skew gate — opposite-edge length ratios.
        val topBotRatio = max(topEdge, bottomEdge) / min(topEdge, bottomEdge)
        val lftRgtRatio = max(leftEdge, rightEdge) / min(leftEdge, rightEdge)
        if (topBotRatio > maxOppositeEdgeRatio || lftRgtRatio > maxOppositeEdgeRatio) {
            return DetectionResult.Partial(q, RejectReason.Skewed)
        }

        // 3. Off-frame gate.
        val edgeMargin = edgeMarginFrac * min(analyzerWidth, analyzerHeight)
        if (anyCornerNearEdge(q, edgeMargin)) {
            return DetectionResult.Partial(q, RejectReason.OffFrame)
        }

        // 4. ROI area gate.
        val polyArr = floatArrayOf(
            q.tl.x, q.tl.y,
            q.tr.x, q.tr.y,
            q.br.x, q.br.y,
            q.bl.x, q.bl.y,
        )
        val area = CardImageOps.shoelaceArea(polyArr)
        if (area < guide.width * guide.height * minRoiAreaFrac) {
            return DetectionResult.Partial(q, RejectReason.TooSmall)
        }

        // 5. IoU vs guide.
        val iou = CardImageOps.polygonIou(q, guide)
        return if (iou >= 0.85f) DetectionResult.Valid(q, iou)
        else DetectionResult.Partial(q, RejectReason.LowIou)
    }

    private fun anyCornerNearEdge(q: DetectedQuad, margin: Float): Boolean {
        val w = analyzerWidth.toFloat(); val h = analyzerHeight.toFloat()
        fun bad(p: Point2f) =
            p.x < margin || p.y < margin || p.x > w - margin || p.y > h - margin
        return bad(q.tl) || bad(q.tr) || bad(q.br) || bad(q.bl)
    }

    private fun max(a: Float, b: Float) = if (a > b) a else b
    private fun min(a: Float, b: Float) = if (a < b) a else b
}
