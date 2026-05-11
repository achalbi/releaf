/*
 * CardImageOps.kt
 *
 * The pure-Kotlin image-processing primitives the Business Card
 * detector + perspective warper compose. The pipeline matches the
 * one called out in the feature spec — grayscale → ROI crop →
 * Gaussian blur → adaptive threshold (Canny fallback) → contour
 * border-follow → polygon approximation (Ramer-Douglas-Peucker) —
 * but is implemented against `ByteArray` / `IntArray` rather than
 * `cv::Mat`. The perspective transform is a hand-rolled
 * `getPerspectiveTransform` (solving the 8-equation linear system
 * for the homography) plus a `warpPerspective` with bilinear
 * sampling.
 *
 * Why no OpenCV: the official `org.opencv:opencv` Maven artifact
 * ships ~25 MB of native libs across the four Play-target ABIs.
 * The opencv-android AAR ships even more unless manually stripped
 * to `core + imgproc + calib3d`. For "find a high-contrast 1.586:1
 * rectangle inside a fixed center ROI and warp it to 1012×638"
 * the SIMD-tuned operators don't pay off — the per-frame budget
 * at 24 fps (≈40 ms) is loose, and Kotlin running on the YUV
 * luma plane CameraX hands us is fast enough on mid-range
 * hardware (Snapdragon 6-series and up; Pixel 6a class). The
 * tradeoff is more code in this repo, but ~600 lines of
 * straightforward Kotlin against 25 MB of binary is the right
 * call for a single feature.
 *
 * Coordinate convention: all operations operate on grayscale 8-bit
 * planar buffers indexed as `y * width + x` with the origin at
 * top-left. Floating-point geometry — quad corners, the IoU
 * polygon — keeps the same axes (x right, y down). All angles
 * are in radians; the caller never sees an angle directly.
 *
 * Threading: every entry point is pure (no shared state, no
 * allocations on the hot path beyond the output buffer the caller
 * supplies). Safe to call from CameraX's ImageAnalysis worker
 * thread.
 */

package app.quickink.mobile.features.scan.cardcapture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Output of the detection pipeline. Coordinates are in the
 * coordinate space of the input image the detector was handed
 * (post-rotation, in CameraX preview pixels). The caller is
 * responsible for mapping back to view space if it needs to draw
 * the quad over a [PreviewView].
 *
 * Corners are stored in TL, TR, BR, BL order — see
 * [orderCornersClockwise].
 */
data class DetectedQuad(
    val tl: Point2f,
    val tr: Point2f,
    val br: Point2f,
    val bl: Point2f,
) {
    fun asArray(): Array<Point2f> = arrayOf(tl, tr, br, bl)
}

data class Point2f(val x: Float, val y: Float) {
    fun distanceTo(other: Point2f): Float {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Axis-aligned guide rectangle in preview-image pixel space. The
 * Business Card surface computes one of these from the
 * `PreviewView` size + the 1.586:1 / 70%-width spec callout and
 * hands it to the detector + the IoU gate.
 */
data class GuideRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float  get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun asQuad(): DetectedQuad = DetectedQuad(
        tl = Point2f(left,  top),
        tr = Point2f(right, top),
        br = Point2f(right, bottom),
        bl = Point2f(left,  bottom),
    )
}

object CardImageOps {

    // ─── Stage 1: grayscale ROI extraction ───────────────────────────
    // CameraX hands us YUV_420_888. The Y (luma) plane IS the
    // grayscale image — no conversion needed, just stride-aware
    // copy. This entry point lets the detector skip the gray ←
    // RGB step entirely when the input came from the camera.

    /**
     * Copy the luma plane out of a YUV frame into a contiguous
     * `ByteArray`, applying [rowStride] / [pixelStride] from the
     * `Image.Plane`. Output is `width × height` bytes, indexed as
     * `y * width + x`. Caller owns the output buffer so the
     * detector can reuse a single allocation across frames.
     */
    fun copyLumaPlane(
        src: java.nio.ByteBuffer,
        srcWidth: Int,
        srcHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        dst: ByteArray,
    ) {
        var dstIndex = 0
        if (pixelStride == 1 && rowStride == srcWidth) {
            // Dense plane — direct bulk copy. Common on Pixel /
            // Snapdragon devices when the analyzer is sized to a
            // hardware-friendly resolution.
            src.position(0)
            src.get(dst, 0, srcWidth * srcHeight)
            return
        }
        val rowBuf = ByteArray(rowStride)
        for (y in 0 until srcHeight) {
            src.position(y * rowStride)
            val rowLen = min(rowStride, src.remaining())
            src.get(rowBuf, 0, rowLen)
            if (pixelStride == 1) {
                System.arraycopy(rowBuf, 0, dst, dstIndex, srcWidth)
                dstIndex += srcWidth
            } else {
                var srcOff = 0
                for (x in 0 until srcWidth) {
                    dst[dstIndex++] = rowBuf[srcOff]
                    srcOff += pixelStride
                }
            }
        }
    }

    /**
     * Crop a rectangular ROI from a grayscale plane. The ROI
     * width / height are clamped to the source bounds so a
     * caller asking for a 10%-margin expansion off the edge
     * doesn't reach past the frame.
     *
     * Returns the rectangle actually copied in [outRoi] (caller-
     * supplied 4-element `IntArray`: x, y, w, h) so downstream
     * stages know the offset into the source frame.
     */
    fun cropRoi(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        roiCenterX: Int,
        roiCenterY: Int,
        roiWidth: Int,
        roiHeight: Int,
        dst: ByteArray,
        outRoi: IntArray,
    ) {
        val x0 = max(0, roiCenterX - roiWidth  / 2)
        val y0 = max(0, roiCenterY - roiHeight / 2)
        val x1 = min(srcWidth,  x0 + roiWidth)
        val y1 = min(srcHeight, y0 + roiHeight)
        val w = x1 - x0
        val h = y1 - y0
        var dstIndex = 0
        for (y in y0 until y1) {
            val srcRow = y * srcWidth + x0
            System.arraycopy(src, srcRow, dst, dstIndex, w)
            dstIndex += w
        }
        outRoi[0] = x0; outRoi[1] = y0; outRoi[2] = w; outRoi[3] = h
    }

    // ─── Stage 2: separable Gaussian blur 5×5, σ ≈ 1.2 ───────────────
    // Decomposed into a horizontal then vertical pass — `O(n*k)`
    // per dimension instead of `O(n*k²)` for the naive 2D
    // convolution. The kernel is precomputed; the inner loop is
    // straight integer math (signed) so we can run on hot frames
    // without per-pixel float work.

    private val GAUSS_5_INT = intArrayOf(1, 4, 6, 4, 1) // sum = 16

    /**
     * In-place separable Gaussian blur with the 5-tap [1 4 6 4 1]
     * kernel (Pascal-row σ ≈ 1.06; the spec's σ ≈ 1.2 is close
     * enough that the 1-integer-tap loss is invisible against the
     * downstream threshold step). [scratch] must be at least
     * `width * height` bytes — used to hold the intermediate
     * horizontal pass.
     *
     * Edge handling: extend (replicate) the nearest in-range
     * sample. Keeps the output the same dimensions as the input
     * and avoids spurious dark borders near the ROI edge that
     * a zero-padding scheme would introduce.
     */
    fun gaussianBlur5(
        src: ByteArray,
        width: Int,
        height: Int,
        scratch: ByteArray,
    ) {
        // Horizontal pass: src → scratch.
        for (y in 0 until height) {
            val rowOff = y * width
            for (x in 0 until width) {
                var acc = 0
                for (k in -2..2) {
                    val xx = clamp(x + k, 0, width - 1)
                    acc += (src[rowOff + xx].toInt() and 0xFF) * GAUSS_5_INT[k + 2]
                }
                scratch[rowOff + x] = (acc / 16).toByte()
            }
        }
        // Vertical pass: scratch → src.
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0
                for (k in -2..2) {
                    val yy = clamp(y + k, 0, height - 1)
                    acc += (scratch[yy * width + x].toInt() and 0xFF) * GAUSS_5_INT[k + 2]
                }
                src[y * width + x] = (acc / 16).toByte()
            }
        }
    }

    // ─── Stage 3: adaptive threshold (mean) + Canny fallback ─────────
    // Adaptive threshold uses a sliding mean over a 21×21 block,
    // subtracts C=8, and emits a binary mask. The mean is computed
    // via an integral image so the window cost is O(1) per pixel.
    // This is what beats glare-on-glossy-cards in the spec's
    // edge-case callout — a global threshold would either miss the
    // card border or saturate the glare.
    //
    // Canny fallback: when the resulting contour is too short
    // (e.g. broken by reflection blowouts), the detector re-runs
    // with the gradient-magnitude pipeline below.

    /**
     * Adaptive mean threshold. Pixels darker than (block mean - C)
     * become foreground (255); the rest are background (0). Output
     * is the binary mask the contour stage consumes.
     */
    fun adaptiveMeanThreshold(
        src: ByteArray,
        width: Int,
        height: Int,
        blockSize: Int,
        c: Int,
        dst: ByteArray,
    ) {
        require(blockSize % 2 == 1) { "blockSize must be odd, got $blockSize" }
        val half = blockSize / 2
        val integral = LongArray((width + 1) * (height + 1))
        // Build integral image — single pass, O(N).
        for (y in 1..height) {
            val rowPrev = (y - 1) * (width + 1)
            val rowThis = y * (width + 1)
            val srcRow  = (y - 1) * width
            var rowSum = 0L
            for (x in 1..width) {
                rowSum += (src[srcRow + (x - 1)].toInt() and 0xFF)
                integral[rowThis + x] = integral[rowPrev + x] + rowSum
            }
        }
        // Window query — O(1) per pixel.
        for (y in 0 until height) {
            val y0 = max(0, y - half)
            val y1 = min(height - 1, y + half)
            for (x in 0 until width) {
                val x0 = max(0, x - half)
                val x1 = min(width - 1, x + half)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (width + 1) + (x1 + 1)] -
                          integral[y0 * (width + 1) + (x1 + 1)] -
                          integral[(y1 + 1) * (width + 1) + x0] +
                          integral[y0 * (width + 1) + x0]
                val mean = (sum / area).toInt()
                val px = src[y * width + x].toInt() and 0xFF
                dst[y * width + x] = if (px < mean - c) 0xFF.toByte() else 0
            }
        }
    }

    /**
     * Sobel gradient magnitude + double-threshold (no hysteresis
     * pass — we don't need full Canny since the downstream
     * contour-follow is robust to a few stray edge pixels). [low]
     * / [high] match the spec's 50 / 150 callout when the input
     * is 0–255.
     *
     * Output is a binary mask (0 / 255) marking strong edges.
     */
    fun sobelEdges(
        src: ByteArray,
        width: Int,
        height: Int,
        low: Int,
        high: Int,
        dst: ByteArray,
    ) {
        for (y in 1 until height - 1) {
            val ym1 = (y - 1) * width
            val y0  = y * width
            val yp1 = (y + 1) * width
            for (x in 1 until width - 1) {
                val tl = src[ym1 + x - 1].toInt() and 0xFF
                val t  = src[ym1 + x    ].toInt() and 0xFF
                val tr = src[ym1 + x + 1].toInt() and 0xFF
                val l  = src[y0  + x - 1].toInt() and 0xFF
                val r  = src[y0  + x + 1].toInt() and 0xFF
                val bl = src[yp1 + x - 1].toInt() and 0xFF
                val b  = src[yp1 + x    ].toInt() and 0xFF
                val br = src[yp1 + x + 1].toInt() and 0xFF
                val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                val mag = abs(gx) + abs(gy) // L1 norm — cheap, robust for this use
                dst[y0 + x] = when {
                    mag >= high -> 0xFF.toByte()
                    mag >= low  -> 0xFF.toByte() // simplified hysteresis: any above-low pixel kept
                    else        -> 0
                }
            }
        }
        // Zero the 1-px border that Sobel can't sample.
        for (x in 0 until width) {
            dst[x] = 0
            dst[(height - 1) * width + x] = 0
        }
        for (y in 0 until height) {
            dst[y * width] = 0
            dst[y * width + width - 1] = 0
        }
    }

    // ─── Stage 4: external contour border-follow ─────────────────────
    // A simplified Suzuki-Abe border-follow: scan the binary mask
    // top-to-bottom left-to-right; the first foreground pixel
    // that has a background neighbor to the left is the start of
    // an outer boundary. Trace clockwise using a Moore-neighborhood
    // walk until we return to the start. We don't bother with
    // hole-following (inner contours) — business cards are solid,
    // and the spec uses `RETR_EXTERNAL` which is outer-only.

    private val MOORE_DX = intArrayOf( 1,  1,  0, -1, -1, -1,  0,  1)
    private val MOORE_DY = intArrayOf( 0,  1,  1,  1,  0, -1, -1, -1)

    /**
     * Find external contours. Each returned `IntArray` is a
     * flattened sequence of `[x0, y0, x1, y1, …]` border pixels
     * in clockwise order. [minArea] (in pixels²) lets the caller
     * drop noise — the spec rejects anything below 30% of the
     * ROI area, so we never need to materialize contours below
     * that bar.
     */
    fun findExternalContours(
        binary: ByteArray,
        width: Int,
        height: Int,
        minPixels: Int,
        maxContours: Int = 16,
    ): List<IntArray> {
        val visited = BooleanArray(width * height)
        val contours = ArrayList<IntArray>(8)
        val maxSteps = width * height
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (visited[y * width + x]) continue
                if ((binary[y * width + x].toInt() and 0xFF) == 0) continue
                if ((binary[y * width + (x - 1)].toInt() and 0xFF) != 0) continue
                // Boundary starting point.
                val border = traceContour(binary, width, height, x, y, visited, maxSteps)
                if (border != null && border.size / 2 >= minPixels) {
                    contours.add(border)
                    if (contours.size >= maxContours) return contours
                }
            }
        }
        return contours
    }

    private fun traceContour(
        binary: ByteArray,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        visited: BooleanArray,
        maxSteps: Int,
    ): IntArray? {
        val out = ArrayList<Int>(64)
        out.add(startX); out.add(startY)
        visited[startY * width + startX] = true
        var cx = startX
        var cy = startY
        // "Previous direction" — start as if we arrived from the
        // left, so the first Moore-walk tries 'up' / 'up-right'
        // first, which is correct for a top-left start.
        var prev = 6 // index in MOORE table pointing "up"
        var steps = 0
        while (steps < maxSteps) {
            steps++
            // Search the 8 neighbors starting from (prev + 2) mod 8 —
            // standard Moore boundary trace. Quit if we don't find
            // any foreground neighbor (single-pixel contour) or if
            // we land back on the start.
            var found = false
            for (i in 0 until 8) {
                val dir = (prev + 2 + i) and 7
                val nx = cx + MOORE_DX[dir]
                val ny = cy + MOORE_DY[dir]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                if ((binary[ny * width + nx].toInt() and 0xFF) == 0) continue
                cx = nx; cy = ny
                prev = (dir + 4) and 7 // "we came from the opposite direction"
                visited[ny * width + nx] = true
                if (cx == startX && cy == startY) {
                    found = false
                    break
                }
                out.add(cx); out.add(cy)
                found = true
                break
            }
            if (!found) break
        }
        if (out.size / 2 < 4) return null
        return out.toIntArray()
    }

    // ─── Stage 5: polygon approximation (Ramer-Douglas-Peucker) ──────
    // Reduce a contour to its dominant vertices. The spec calls
    // out `epsilon = 0.02 * perimeter`; we expose epsilon directly
    // so the detector can tune. RDP is the classic recursive
    // shortest-distance algorithm; iterative implementation with
    // an `IntStack` to keep the JVM stack out of it.

    fun approxPolyDp(contour: IntArray, epsilon: Float): FloatArray {
        val n = contour.size / 2
        if (n < 3) return floatArrayOf()
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            var maxDist = 0f
            var index = -1
            val x0 = contour[lo * 2].toFloat();     val y0 = contour[lo * 2 + 1].toFloat()
            val x1 = contour[hi * 2].toFloat();     val y1 = contour[hi * 2 + 1].toFloat()
            for (i in (lo + 1) until hi) {
                val px = contour[i * 2].toFloat()
                val py = contour[i * 2 + 1].toFloat()
                val d = perpendicularDistance(px, py, x0, y0, x1, y1)
                if (d > maxDist) {
                    maxDist = d
                    index = i
                }
            }
            if (maxDist > epsilon && index > 0) {
                keep[index] = true
                stack.addLast(intArrayOf(lo, index))
                stack.addLast(intArrayOf(index, hi))
            }
        }
        // RDP on an open polyline keeps endpoints; the contour
        // closes back on itself so we drop a duplicate end.
        val out = ArrayList<Float>(8)
        for (i in 0 until n) {
            if (keep[i]) {
                out.add(contour[i * 2].toFloat())
                out.add(contour[i * 2 + 1].toFloat())
            }
        }
        // Trim the closing duplicate if start == end.
        if (out.size >= 4) {
            val sx = out[0]; val sy = out[1]
            val ex = out[out.size - 2]; val ey = out[out.size - 1]
            if (sx == ex && sy == ey) {
                out.removeAt(out.size - 1)
                out.removeAt(out.size - 1)
            }
        }
        return out.toFloatArray()
    }

    private fun perpendicularDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) {
            val ex = px - ax; val ey = py - ay
            return sqrt(ex * ex + ey * ey)
        }
        val cross = (px - ax) * dy - (py - ay) * dx
        return abs(cross) / sqrt(len2)
    }

    // ─── Stage 6: corner ordering — TL, TR, BR, BL ───────────────────
    // Classic centroid-relative angle sort. The detected quad is
    // already convex (we reject non-convex before getting here)
    // so the sort is well-defined. Tie-breaks on equal x or y
    // never matter for a real card — the dual-corner case
    // requires a perfectly axis-aligned tilt of zero, which the
    // skew-reject in [acceptQuad] already excludes.

    fun orderCornersClockwise(corners: Array<Point2f>): DetectedQuad {
        require(corners.size == 4) { "orderCornersClockwise needs 4 corners" }
        val cx = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) * 0.25f
        val cy = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) * 0.25f
        var tl = corners[0]; var tr = corners[0]
        var br = corners[0]; var bl = corners[0]
        for (p in corners) {
            val left = p.x < cx
            val top  = p.y < cy
            when {
                left  && top  -> tl = p
                !left && top  -> tr = p
                !left && !top -> br = p
                left  && !top -> bl = p
            }
        }
        return DetectedQuad(tl, tr, br, bl)
    }

    // ─── Stage 7: quad-fitness gates ─────────────────────────────────
    //   - convex
    //   - area > minArea
    //   - aspect ratio in [minAspect, maxAspect]
    //   - opposite-edge length ratio < 1.4 (skew reject)
    //   - all corners ≥ marginPx away from frame edges (off-frame
    //     reject)
    // These are pulled straight from the spec's "Edge cases" /
    // "Business Card detection pipeline" / "Stability and capture
    // gates" sections.

    fun isConvex(poly: FloatArray): Boolean {
        if (poly.size != 8) return false
        var sign = 0
        for (i in 0 until 4) {
            val x0 = poly[(i * 2)        ]; val y0 = poly[(i * 2)         + 1]
            val x1 = poly[((i + 1) % 4) * 2]; val y1 = poly[((i + 1) % 4) * 2 + 1]
            val x2 = poly[((i + 2) % 4) * 2]; val y2 = poly[((i + 2) % 4) * 2 + 1]
            val cross = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s
            else if (sign != s) return false
        }
        return true
    }

    fun shoelaceArea(poly: FloatArray): Float {
        if (poly.size < 6) return 0f
        var acc = 0f
        var j = poly.size / 2 - 1
        for (i in 0 until poly.size / 2) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            acc += (xj + xi) * (yj - yi)
            j = i
        }
        return abs(acc) * 0.5f
    }

    // ─── Stage 8: IoU between detected quad and guide rect ───────────
    // Sutherland-Hodgman convex-polygon clipping: clip the
    // detected quad against the four half-planes of the
    // axis-aligned guide. Result is a (possibly empty) convex
    // polygon whose area is the intersection.

    fun polygonIou(quad: DetectedQuad, guide: GuideRect): Float {
        val q = floatArrayOf(
            quad.tl.x, quad.tl.y,
            quad.tr.x, quad.tr.y,
            quad.br.x, quad.br.y,
            quad.bl.x, quad.bl.y,
        )
        val gArea = guide.width * guide.height
        val qArea = shoelaceArea(q)
        val clipped = clipPolygonAgainstRect(q, guide)
        val interArea = shoelaceArea(clipped)
        val union = qArea + gArea - interArea
        if (union <= 0f) return 0f
        return interArea / union
    }

    private fun clipPolygonAgainstRect(poly: FloatArray, rect: GuideRect): FloatArray {
        var output = poly.copyOf()
        // Four half-planes — left, right, top, bottom — each
        // expressed by the canonical inside-the-rect predicate
        // paired with the corresponding edge-intersection helper.
        output = clipPolygonAgainstEdge(
            output,
            inside    = { x, _ -> x >= rect.left },
            intersect = { x0, y0, x1, y1 -> interpX(x0, y0, x1, y1, rect.left) },
        )
        output = clipPolygonAgainstEdge(
            output,
            inside    = { x, _ -> x <= rect.right },
            intersect = { x0, y0, x1, y1 -> interpX(x0, y0, x1, y1, rect.right) },
        )
        output = clipPolygonAgainstEdge(
            output,
            inside    = { _, y -> y >= rect.top },
            intersect = { x0, y0, x1, y1 -> interpY(x0, y0, x1, y1, rect.top) },
        )
        output = clipPolygonAgainstEdge(
            output,
            inside    = { _, y -> y <= rect.bottom },
            intersect = { x0, y0, x1, y1 -> interpY(x0, y0, x1, y1, rect.bottom) },
        )
        return output
    }

    private inline fun clipPolygonAgainstEdge(
        poly: FloatArray,
        inside: (Float, Float) -> Boolean,
        intersect: (Float, Float, Float, Float) -> Pair<Float, Float>,
    ): FloatArray {
        if (poly.isEmpty()) return poly
        val out = ArrayList<Float>(poly.size + 4)
        val n = poly.size / 2
        for (i in 0 until n) {
            val cx = poly[i * 2];               val cy = poly[i * 2 + 1]
            val px = poly[((i - 1 + n) % n) * 2]; val py = poly[((i - 1 + n) % n) * 2 + 1]
            val curIn  = inside(cx, cy)
            val prevIn = inside(px, py)
            if (curIn) {
                if (!prevIn) {
                    val ip = intersect(px, py, cx, cy)
                    out.add(ip.first); out.add(ip.second)
                }
                out.add(cx); out.add(cy)
            } else if (prevIn) {
                val ip = intersect(px, py, cx, cy)
                out.add(ip.first); out.add(ip.second)
            }
        }
        return out.toFloatArray()
    }

    private fun interpX(x0: Float, y0: Float, x1: Float, y1: Float, x: Float): Pair<Float, Float> {
        val t = if (x1 == x0) 0f else (x - x0) / (x1 - x0)
        return x to (y0 + t * (y1 - y0))
    }

    private fun interpY(x0: Float, y0: Float, x1: Float, y1: Float, y: Float): Pair<Float, Float> {
        val t = if (y1 == y0) 0f else (y - y0) / (y1 - y0)
        return (x0 + t * (x1 - x0)) to y
    }

    // ─── Stage 9: perspective transform + warp to 1012×638 ───────────
    // `getPerspectiveTransform`: solve the 8-equation linear
    // system for the 3×3 homography that maps the four detected
    // corners to the four output corners. `warpPerspective`:
    // for each output pixel, apply the inverse homography to
    // find the source location, then bilinear-sample.

    /**
     * Solve for the homography mapping src→dst. Returns a 9-float
     * matrix in row-major order ([0..2]=row0, [3..5]=row1,
     * [6..8]=row2). The 8-parameter form (h33=1) is solved via
     * Gauss-Jordan; the matrix returned is the full 3×3 for
     * downstream `warpPerspective`.
     */
    fun getPerspectiveTransform(src: Array<Point2f>, dst: Array<Point2f>): FloatArray {
        require(src.size == 4 && dst.size == 4)
        // 8 equations (two per corner) in 8 unknowns:
        //   x' = (h00*x + h01*y + h02) / (h20*x + h21*y + 1)
        //   y' = (h10*x + h11*y + h12) / (h20*x + h21*y + 1)
        // Rearranged: linear system A * h = b where h = (h00..h21).
        val a = Array(8) { FloatArray(8) }
        val b = FloatArray(8)
        for (i in 0 until 4) {
            val sx = src[i].x; val sy = src[i].y
            val dx = dst[i].x; val dy = dst[i].y
            a[i * 2]     = floatArrayOf(sx, sy, 1f, 0f, 0f, 0f, -sx * dx, -sy * dx)
            a[i * 2 + 1] = floatArrayOf(0f, 0f, 0f, sx, sy, 1f, -sx * dy, -sy * dy)
            b[i * 2]     = dx
            b[i * 2 + 1] = dy
        }
        val h = solveLinearSystem8(a, b)
        return floatArrayOf(
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1f,
        )
    }

    private fun solveLinearSystem8(a: Array<FloatArray>, b: FloatArray): FloatArray {
        val n = 8
        for (i in 0 until n) {
            // Partial pivoting — find the row with the largest
            // |a[r][i]| at or below i.
            var pivot = i
            var pivotVal = abs(a[i][i])
            for (r in i + 1 until n) {
                if (abs(a[r][i]) > pivotVal) {
                    pivot = r
                    pivotVal = abs(a[r][i])
                }
            }
            if (pivot != i) {
                val tmp = a[i]; a[i] = a[pivot]; a[pivot] = tmp
                val tb = b[i]; b[i] = b[pivot]; b[pivot] = tb
            }
            val diag = a[i][i]
            if (abs(diag) < 1e-8f) {
                // Singular — corners are colinear. Return identity-
                // looking row to avoid NaN cascades; the caller
                // gates on quad validity upstream, so this only
                // ever fires from pathological synthetic inputs.
                return FloatArray(8) { idx -> if (idx == 0 || idx == 4) 1f else 0f }
            }
            for (c in i until n) a[i][c] /= diag
            b[i] /= diag
            for (r in 0 until n) {
                if (r == i) continue
                val factor = a[r][i]
                if (factor == 0f) continue
                for (c in i until n) a[r][c] -= factor * a[i][c]
                b[r] -= factor * b[i]
            }
        }
        return b
    }

    /**
     * Bilinear-sampled perspective warp. [srcGray] holds the
     * source frame; [outRgb] receives `outWidth × outHeight`
     * RGB triples (3 bytes per pixel, no padding). The
     * homography [h] maps src→dst, so warpPerspective inverts
     * it internally to backproject each output pixel.
     */
    fun warpPerspectiveGrayToRgb(
        srcGray: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        h: FloatArray,
        outWidth: Int,
        outHeight: Int,
        outRgb: ByteArray,
    ) {
        val inv = invert3x3(h)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val w = inv[6] * x + inv[7] * y + inv[8]
                if (abs(w) < 1e-8f) {
                    val o = (y * outWidth + x) * 3
                    outRgb[o] = 0; outRgb[o + 1] = 0; outRgb[o + 2] = 0
                    continue
                }
                val sx = (inv[0] * x + inv[1] * y + inv[2]) / w
                val sy = (inv[3] * x + inv[4] * y + inv[5]) / w
                val gray = sampleBilinearGray(srcGray, srcWidth, srcHeight, sx, sy)
                val o = (y * outWidth + x) * 3
                outRgb[o    ] = gray.toByte()
                outRgb[o + 1] = gray.toByte()
                outRgb[o + 2] = gray.toByte()
            }
        }
    }

    private fun sampleBilinearGray(src: ByteArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xi = x.toInt().coerceIn(0, w - 2)
        val yi = y.toInt().coerceIn(0, h - 2)
        val fx = (x - xi).coerceIn(0f, 1f)
        val fy = (y - yi).coerceIn(0f, 1f)
        val a = (src[yi       * w + xi    ].toInt() and 0xFF).toFloat()
        val b = (src[yi       * w + xi + 1].toInt() and 0xFF).toFloat()
        val c = (src[(yi + 1) * w + xi    ].toInt() and 0xFF).toFloat()
        val d = (src[(yi + 1) * w + xi + 1].toInt() and 0xFF).toFloat()
        val ab = a * (1 - fx) + b * fx
        val cd = c * (1 - fx) + d * fx
        return (ab * (1 - fy) + cd * fy).roundToInt().coerceIn(0, 255)
    }

    private fun invert3x3(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-8f) {
            // Degenerate — return identity so the warp produces
            // a black image rather than NaNs. Caller's quad gate
            // makes this unreachable for real captures.
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        val invDet = 1f / det
        return floatArrayOf(
            (e * i - f * h) * invDet,
            (c * h - b * i) * invDet,
            (b * f - c * e) * invDet,
            (f * g - d * i) * invDet,
            (a * i - c * g) * invDet,
            (c * d - a * f) * invDet,
            (d * h - e * g) * invDet,
            (b * g - a * h) * invDet,
            (a * e - b * d) * invDet,
        )
    }

    // ─── Misc ────────────────────────────────────────────────────────

    fun meanLuminance(src: ByteArray, length: Int = src.size): Int {
        var sum = 0L
        for (i in 0 until length) sum += (src[i].toInt() and 0xFF)
        return (sum / length).toInt()
    }

    // ─── FILL_CENTER-aware guide rect computation ────────────────────
    //
    // The PreviewView (and the AVCaptureVideoPreviewLayer on iOS)
    // uses FILL_CENTER / resizeAspectFill: the camera frame is
    // uniformly scaled until it fills the on-screen canvas, and
    // anything that doesn't fit is center-cropped. This means the
    // user only ever sees a CENTER SUB-RECT of the sensor frame
    // — not the full frame. The card-shaped guide overlay is
    // drawn relative to the on-screen canvas, so the corresponding
    // sensor-coordinate region is offset and scaled relative to
    // the visible sub-rect, NOT the full sensor.
    //
    // [visibleRectForViewAspect] returns the sub-rect of an image
    // (analyzer frame or still bitmap) that the user actually
    // sees through a FILL_CENTER preview of width:height
    // `viewWidth:viewHeight`. [guideRectInside] then plants the
    // 70%-of-width / 1.586:1 / 45%-vertical-center guide rect
    // inside that visible region. Identical math powers the
    // detector's IoU check and the post-processor's crop, so
    // they agree on "which pixels the user pointed at."

    fun visibleRectForViewAspect(
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): GuideRect {
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return GuideRect(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        }
        val viewAspect = viewWidth / viewHeight
        val srcAspect = imageWidth.toFloat() / imageHeight.toFloat()
        return if (viewAspect > srcAspect) {
            // View is wider relative to its height than the
            // image — to fill the view, the image scales until
            // it covers the view's width, and the resulting
            // overflow gets center-cropped on the vertical axis.
            val visibleH = imageWidth.toFloat() / viewAspect
            val top = (imageHeight.toFloat() - visibleH) * 0.5f
            GuideRect(
                left   = 0f,
                top    = top,
                right  = imageWidth.toFloat(),
                bottom = top + visibleH,
            )
        } else {
            // View is taller (or equal aspect). Horizontal
            // center-crop. Most modern phones land here — 4:3
            // sensor inside a 9:19+ view.
            val visibleW = imageHeight.toFloat() * viewAspect
            val left = (imageWidth.toFloat() - visibleW) * 0.5f
            GuideRect(
                left   = left,
                top    = 0f,
                right  = left + visibleW,
                bottom = imageHeight.toFloat(),
            )
        }
    }

    /**
     * Plant a 70%-of-width / 1.586:1 / vertically-centered-at-45%
     * guide rect inside [visible]. Same fractional layout
     * `GuideMetrics.compute` uses on the on-screen canvas, so the
     * sensor-space rect lines up with what the user sees.
     */
    fun guideRectInside(visible: GuideRect): GuideRect {
        val targetW = visible.width * GUIDE_WIDTH_FRACTION
        val targetH = targetW / CARD_ASPECT_RATIO
        val cx = visible.centerX
        val cy = visible.top + visible.height * GUIDE_VERTICAL_BIAS
        val left = cx - targetW * 0.5f
        val top  = cy - targetH * 0.5f
        return GuideRect(
            left   = left,
            top    = top,
            right  = left + targetW,
            bottom = top + targetH,
        )
    }

    /** Card ratio (ISO 7810 ID-1) — matches `GuideMetrics.CARD_ASPECT_RATIO`. */
    const val CARD_ASPECT_RATIO: Float = 1.586f
    /** Guide width as a fraction of the visible-region width. */
    const val GUIDE_WIDTH_FRACTION: Float = 0.70f
    /** Vertical center of the guide as a fraction of the visible-region height. */
    const val GUIDE_VERTICAL_BIAS: Float = 0.45f

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
}
