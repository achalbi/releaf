/*
 * CheckboxTodoDetector.kt
 *
 * Finds hand-drawn (or printed) checkboxes at the start of OCR lines
 * and classifies each as open or ticked. Unticked lines become open
 * to-dos; ticked lines become done items.
 *
 * Two signals, checked per line:
 *
 *   1. Text glyph — the OCR line itself starts with a box glyph
 *      (`☐`, `☑`, `☒`, `□`, …) or a Markdown-style `[ ]` / `[x]`.
 *      Cheap, needs no pixels, and wins when present because it is
 *      the most explicit signal.
 *   2. Image — crop the page around the line start (at and just left
 *      of the line's bounding box), binarise, trace contours, and
 *      look for a roughly square closed outline sized like the
 *      line's x-height. Ink density inside the box decides ticked vs
 *      empty.
 *
 * Pure function by design: input is the page's OCR lines (normalised
 * 0..1 boxes, top-left origin) plus an optional grayscale buffer, so
 * the whole thing runs in a plain JVM unit test with synthetic
 * images. The Android glue (decode page → luma buffer) lives in
 * `PageImageLoader`. Binarisation and contour tracing reuse
 * `CardImageOps` from the business-card detector.
 *
 * Mirror of iOS `CheckboxTodoDetector.swift` — keep thresholds in
 * lockstep.
 */

package app.quickink.mobile.features.scan.handwriting

import app.quickink.mobile.features.scan.cardcapture.CardImageOps
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One OCR line in normalised page coordinates (0..1, top-left origin). */
data class OcrLine(
    /** Position of the line in the page's line list — kept as the to-do's source line index. */
    val index: Int,
    val text: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
}

/**
 * 8-bit grayscale page buffer, row-major (`y * width + x`), 0 = black,
 * 255 = white. Same layout `CardImageOps` consumes.
 */
class GrayImage(val pixels: ByteArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(pixels.size >= width * height) { "buffer smaller than width × height" }
    }

    fun luma(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

enum class CheckboxState { Open, Done }

data class DetectedCheckboxLine(
    val lineIndex: Int,
    /** Line text with the box glyph / misread box character removed. */
    val text: String,
    val state: CheckboxState,
)

object CheckboxTodoDetector {

    // ─── Tunables (mirror in CheckboxTodoDetector.swift) ────────────

    /** Box side must be within this multiple of the line height. */
    private const val MIN_SIDE_TO_LINE = 0.45
    private const val MAX_SIDE_TO_LINE = 1.6
    /** Width / height ratio band for "roughly square". */
    private const val MIN_ASPECT = 0.7
    private const val MAX_ASPECT = 1.4
    /** How far left of the line start (in line heights) to look for a box. */
    private const val SEARCH_LEFT = 2.2
    /** How far into the line (in line heights) a box may sit when OCR swallowed it. */
    private const val SEARCH_RIGHT = 1.3
    /** Vertical slack above / below the line box, in line heights. */
    private const val SEARCH_VERTICAL = 0.45
    /** Fraction of each side (5%..95%) that must carry ink for the outline to count as closed. */
    private const val SIDE_COVERAGE = 0.85
    /** Ink fraction inside the box above which it counts as ticked. */
    private const val TICK_DENSITY = 0.06
    /** Adaptive threshold offset (same sense as `CardImageOps.adaptiveMeanThreshold`). */
    private const val THRESHOLD_C = 12
    /** Lines shorter than this (pixels) are too small to inspect reliably. */
    private const val MIN_LINE_HEIGHT_PX = 8

    private val OPEN_GLYPHS = setOf('☐', '□', '▢', '❏', '◻', '⬜', '❑', '❒')
    private val DONE_GLYPHS = setOf('☑', '☒', '✅', '⊠', '▣')
    private val BRACKET_BOX = Regex("""^\[\s*([xX✓✔vV*]?)\s*]""")
    private val LEADING_BULLET = Regex("""^[-*•·]\s+""")

    /** Characters OCR commonly emits when it reads a hand-drawn box as text. */
    private val BOX_LOOKALIKES = setOf(
        "o", "0", "d", "q", "u", "a", "c", "[]", "[", "]", "|", "□", "口", "ㅁ",
        "0.", "o.", "d.",
    )

    /**
     * Detect checkbox lines. [lines] should be line-grained OCR blocks
     * (not paragraphs). [image] is optional: without it only the text
     * glyph signal runs. Output is ordered by [OcrLine.index].
     */
    fun detect(lines: List<OcrLine>, image: GrayImage?): List<DetectedCheckboxLine> {
        val out = ArrayList<DetectedCheckboxLine>()
        for (line in lines.sortedBy { it.index }) {
            val glyph = parseGlyphPrefix(line.text)
            if (glyph != null) {
                if (glyph.second.isNotBlank()) {
                    out += DetectedCheckboxLine(line.index, glyph.second, glyph.first)
                }
                continue
            }
            if (image == null) continue
            val box = findBox(line, image) ?: continue
            val text = cleanText(line.text, line, box, image)
            if (text.isBlank()) continue
            out += DetectedCheckboxLine(line.index, text, box.state)
        }
        return out
    }

    /**
     * `[ ]` / `[x]` / box-glyph prefix → (state, remaining text).
     * Returns null when the line doesn't start with one. Tolerates a
     * leading Markdown bullet (`- [ ] buy milk`).
     */
    fun parseGlyphPrefix(raw: String): Pair<CheckboxState, String>? {
        var text = raw.trim()
        text = text.replaceFirst(LEADING_BULLET, "")
        if (text.isEmpty()) return null
        val first = text.first()
        if (first in OPEN_GLYPHS) return CheckboxState.Open to stripLead(text.substring(1))
        if (first in DONE_GLYPHS) return CheckboxState.Done to stripLead(text.substring(1))
        val m = BRACKET_BOX.find(text) ?: return null
        val mark = m.groupValues[1]
        val state = if (mark.isEmpty()) CheckboxState.Open else CheckboxState.Done
        return state to stripLead(text.substring(m.range.last + 1))
    }

    /** Pixel-space box found next to a line, plus its classification. */
    data class BoxHit(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val inkDensity: Double,
        val state: CheckboxState,
    ) {
        val side: Int get() = max(right - left + 1, bottom - top + 1)
    }

    /**
     * Look for a closed, roughly square outline at the start of [line].
     * Returns the best (largest) passing candidate or null.
     */
    fun findBox(line: OcrLine, image: GrayImage): BoxHit? {
        val w = image.width
        val h = image.height
        val lx = line.x * w
        val ly = line.y * h
        val lh = line.height * h
        if (lh < MIN_LINE_HEIGHT_PX) return null

        val x0 = clamp((lx - SEARCH_LEFT * lh).roundToInt(), 0, w - 1)
        val x1 = clamp((lx + SEARCH_RIGHT * lh).roundToInt(), 0, w - 1)
        val y0 = clamp((ly - SEARCH_VERTICAL * lh).roundToInt(), 0, h - 1)
        val y1 = clamp((ly + lh + SEARCH_VERTICAL * lh).roundToInt(), 0, h - 1)
        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        if (rw < 6 || rh < 6) return null

        // Crop + binarise. Block size spans a few box widths so a
        // fully-shaded box still reads as ink (a block smaller than
        // the fill would average the fill into "background").
        val roi = ByteArray(rw * rh)
        for (yy in 0 until rh) {
            System.arraycopy(image.pixels, (y0 + yy) * w + x0, roi, yy * rw, rw)
        }
        val block = oddAtLeast(max(15, (lh * 3).roundToInt()))
        val binary = ByteArray(rw * rh)
        CardImageOps.adaptiveMeanThreshold(roi, rw, rh, block, THRESHOLD_C, binary)
        // Pad a 1px background border so contours touching the crop
        // edge still start on a background→ink transition.
        val pw = rw + 2
        val ph = rh + 2
        val padded = ByteArray(pw * ph)
        for (yy in 0 until rh) System.arraycopy(binary, yy * rw, padded, (yy + 1) * pw + 1, rw)

        val minSide = MIN_SIDE_TO_LINE * lh
        val maxSide = MAX_SIDE_TO_LINE * lh
        val contours = CardImageOps.findExternalContours(
            binary     = padded,
            width      = pw,
            height     = ph,
            minPixels  = max(8, (2 * minSide).roundToInt()),
            maxContours = 64,
        )

        var best: BoxHit? = null
        for (c in contours) {
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            var i = 0
            while (i < c.size) {
                val cx = c[i]; val cy = c[i + 1]
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                i += 2
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < minSide || bh < minSide || bw > maxSide || bh > maxSide) continue
            val aspect = bw.toDouble() / bh
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue
            // Back to page pixels (undo the 1px pad + crop offset).
            val left = minX - 1 + x0
            val top = minY - 1 + y0
            val right = maxX - 1 + x0
            val bottom = maxY - 1 + y0
            // Box must sit at the line start, vertically on the line.
            val centerY = (top + bottom) / 2.0
            if (centerY < ly - 0.25 * lh || centerY > ly + 1.25 * lh) continue
            if (right > lx + SEARCH_RIGHT * lh) continue
            if (isHoleBoundary(padded, pw, ph, minX, minY, maxX, maxY)) continue
            if (!isClosedSquare(padded, pw, minX, minY, maxX, maxY)) continue
            val density = interiorInk(padded, pw, minX, minY, maxX, maxY)
            val hit = BoxHit(
                left = left, top = top, right = right, bottom = bottom,
                inkDensity = density,
                state = if (density >= TICK_DENSITY) CheckboxState.Done else CheckboxState.Open,
            )
            if (best == null || hit.side > best.side) best = hit
        }
        return best
    }

    // ─── Shape tests ────────────────────────────────────────────────

    /**
     * True when the contour traces the inside of a hole (e.g. the
     * inner edge of a ring) rather than an outer outline. The 1px
     * frame just outside an outer outline is mostly paper; outside a
     * hole's boundary it is the surrounding stroke. Without this, the
     * hole inside an "O" passes the corner test because the ring's
     * thick diagonals fill the hole's bounding-box corners.
     */
    private fun isHoleBoundary(bin: ByteArray, stride: Int, height: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        val fx0 = x0 - 1; val fx1 = x1 + 1
        val fy0 = y0 - 1; val fy1 = y1 + 1
        if (fx0 < 0 || fy0 < 0 || fx1 >= stride || fy1 >= height) return false
        var ink = 0; var total = 0
        fun count(x: Int, y: Int) { total++; if ((bin[y * stride + x].toInt() and 0xFF) != 0) ink++ }
        for (x in fx0..fx1) { count(x, fy0); count(x, fy1) }
        for (y in y0..y1) { count(fx0, y); count(fx1, y) }
        return ink.toDouble() / total > 0.5
    }

    /**
     * Closed-square test on the binary mask: each of the four sides
     * must carry ink along ≥ [SIDE_COVERAGE] of its length (5%–95%,
     * within a thin band), and each corner patch must carry some ink.
     * The corner check is what separates a box from an "O" / "D": a
     * circle passes the side test but leaves its corners empty.
     */
    private fun isClosedSquare(bin: ByteArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val band = max(2, (min(bw, bh) * 0.14).roundToInt())
        fun ink(x: Int, y: Int) = (bin[y * stride + x].toInt() and 0xFF) != 0

        fun horizontalCoverage(yStart: Int, yEnd: Int): Double {
            val from = x0 + (bw * 0.05).roundToInt()
            val to = x1 - (bw * 0.05).roundToInt()
            var hits = 0; var total = 0
            for (x in from..to) {
                total++
                if ((yStart..yEnd).any { y -> ink(x, y) }) hits++
            }
            return if (total == 0) 0.0 else hits.toDouble() / total
        }
        fun verticalCoverage(xStart: Int, xEnd: Int): Double {
            val from = y0 + (bh * 0.05).roundToInt()
            val to = y1 - (bh * 0.05).roundToInt()
            var hits = 0; var total = 0
            for (y in from..to) {
                total++
                if ((xStart..xEnd).any { x -> ink(x, y) }) hits++
            }
            return if (total == 0) 0.0 else hits.toDouble() / total
        }
        if (horizontalCoverage(y0, y0 + band - 1) < SIDE_COVERAGE) return false
        if (horizontalCoverage(y1 - band + 1, y1) < SIDE_COVERAGE) return false
        if (verticalCoverage(x0, x0 + band - 1) < SIDE_COVERAGE) return false
        if (verticalCoverage(x1 - band + 1, x1) < SIDE_COVERAGE) return false

        val patch = max(2, (min(bw, bh) * 0.12).roundToInt())
        fun cornerInk(cx0: Int, cy0: Int): Boolean {
            for (y in cy0 until cy0 + patch) for (x in cx0 until cx0 + patch) if (ink(x, y)) return true
            return false
        }
        return cornerInk(x0, y0) && cornerInk(x1 - patch + 1, y0) &&
            cornerInk(x0, y1 - patch + 1) && cornerInk(x1 - patch + 1, y1 - patch + 1)
    }

    /**
     * Ink fraction of the box interior, shrunk by ~22% per side so the
     * outline strokes themselves don't count.
     */
    private fun interiorInk(bin: ByteArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val mx = max(2, (bw * 0.22).roundToInt())
        val my = max(2, (bh * 0.22).roundToInt())
        val ix0 = x0 + mx; val ix1 = x1 - mx
        val iy0 = y0 + my; val iy1 = y1 - my
        if (ix1 < ix0 || iy1 < iy0) return 0.0
        var ink = 0; var total = 0
        for (y in iy0..iy1) for (x in ix0..ix1) {
            total++
            if ((bin[y * stride + x].toInt() and 0xFF) != 0) ink++
        }
        return ink.toDouble() / total
    }

    // ─── Text cleanup ───────────────────────────────────────────────

    /**
     * When the box sits inside the OCR line's box, the recogniser
     * usually read it as a character ("O", "D", "口", "[]"). Drop that
     * leading token so the to-do text is just the handwriting.
     */
    private fun cleanText(raw: String, line: OcrLine, box: BoxHit, image: GrayImage): String {
        var text = raw.trim()
        val lineLeftPx = line.x * image.width
        val lineHeightPx = line.height * image.height
        val swallowed = box.right > lineLeftPx + 0.15 * lineHeightPx
        if (swallowed) {
            val firstSpace = text.indexOfFirst { it.isWhitespace() }
            val firstToken = if (firstSpace < 0) text else text.substring(0, firstSpace)
            if (firstToken.lowercase() in BOX_LOOKALIKES ||
                (firstToken.length <= 2 && firstToken.none { it.isLetterOrDigit() })
            ) {
                text = if (firstSpace < 0) "" else text.substring(firstSpace)
            }
        }
        return stripLead(text)
    }

    private fun stripLead(s: String): String =
        s.trim().trimStart { it == '-' || it == ':' || it == '.' || it == ')' || it.isWhitespace() }.trim()

    private fun oddAtLeast(v: Int): Int = if (v % 2 == 1) v else v + 1

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
}
