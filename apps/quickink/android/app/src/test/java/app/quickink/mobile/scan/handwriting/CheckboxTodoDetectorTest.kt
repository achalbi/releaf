/*
 * CheckboxTodoDetectorTest.kt
 *
 * Pure-JVM tests for the checkbox → to-do detector. Pages are
 * synthetic grayscale buffers with boxes, ticks and letters drawn
 * in, so the image path runs without a device or real scans.
 * Mirror of iOS `CheckboxTodoDetectorTests.swift`.
 */

package app.quickink.mobile.scan.handwriting

import app.quickink.mobile.features.scan.handwriting.CheckboxState
import app.quickink.mobile.features.scan.handwriting.CheckboxTodoDetector
import app.quickink.mobile.features.scan.handwriting.GrayImage
import app.quickink.mobile.features.scan.handwriting.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class CheckboxTodoDetectorTest {

    // ─── Synthetic page helpers ─────────────────────────────────────

    private class Canvas(val w: Int = 800, val h: Int = 600, paper: Int = 245) {
        val px = ByteArray(w * h) { paper.toByte() }

        fun dot(x: Int, y: Int, ink: Int = 30) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = ink.toByte()
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, ink: Int = 30) {
            for (y in y0..y1) for (x in x0..x1) dot(x, y, ink)
        }

        /** Outline box with stroke [t]; [gapSide] leaves a hole in one side. */
        fun box(x: Int, y: Int, side: Int, t: Int = 3, gapSide: Char? = null) {
            rect(x, y, x + side - 1, y + t - 1)                       // top
            rect(x, y + side - t, x + side - 1, y + side - 1)         // bottom
            rect(x, y, x + t - 1, y + side - 1)                       // left
            rect(x + side - t, y, x + side - 1, y + side - 1)         // right
            if (gapSide == 'r') rect(x + side - t, y + side / 4, x + side - 1, y + 3 * side / 4, ink = 245)
        }

        fun line(x0: Int, y0: Int, x1: Int, y1: Int, t: Int = 3) {
            val steps = (hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()) * 2).roundToInt().coerceAtLeast(1)
            for (i in 0..steps) {
                val cx = x0 + (x1 - x0) * i / steps.toDouble()
                val cy = y0 + (y1 - y0) * i / steps.toDouble()
                for (dy in -t / 2..t / 2) for (dx in -t / 2..t / 2) dot((cx + dx).roundToInt(), (cy + dy).roundToInt())
            }
        }

        fun ring(cx: Int, cy: Int, r: Int, t: Int = 3) {
            for (y in cy - r - t..cy + r + t) for (x in cx - r - t..cx + r + t) {
                val d = hypot((x - cx).toDouble(), (y - cy).toDouble())
                if (abs(d - r) <= t / 2.0 + 0.5) dot(x, y)
            }
        }

        /** Fake handwriting: a row of short vertical strokes. */
        fun scribble(x0: Int, y0: Int, x1: Int, h: Int) {
            var x = x0
            while (x < x1) { rect(x, y0 + 4, x + 2, y0 + h - 4); x += 9 }
        }

        fun image() = GrayImage(px, w, h)

        fun lineAt(index: Int, text: String, x: Int, y: Int, width: Int, height: Int) =
            OcrLine(index, text, x / w.toDouble(), y / h.toDouble(), width / w.toDouble(), height / h.toDouble())
    }

    // ─── Image path ─────────────────────────────────────────────────

    @Test
    fun `empty box before a line becomes an open to-do`() {
        val c = Canvas()
        c.box(100, 100, 30)
        c.scribble(150, 100, 400, 30)
        val line = c.lineAt(0, "buy milk", 150, 100, 250, 30)
        val out = CheckboxTodoDetector.detect(listOf(line), c.image())
        assertEquals(1, out.size)
        assertEquals(CheckboxState.Open, out[0].state)
        assertEquals("buy milk", out[0].text)
        assertEquals(0, out[0].lineIndex)
    }

    @Test
    fun `wobbly slightly rotated hand-drawn box is still found`() {
        val c = Canvas()
        // Corners of a ~30px box rotated ~5°, with strokes that
        // overshoot the corners a little, as pen-drawn boxes do.
        val tl = 100 to 102; val tr = 130 to 99; val br = 133 to 129; val bl = 103 to 132
        c.line(tl.first - 2, tl.second, tr.first + 2, tr.second)
        c.line(tr.first, tr.second - 2, br.first, br.second + 2)
        c.line(br.first + 1, br.second, bl.first - 2, bl.second)
        c.line(bl.first, bl.second + 1, tl.first, tl.second - 2)
        c.scribble(150, 100, 400, 30)
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(0, "post the letter", 150, 100, 250, 30)), c.image())
        assertEquals(CheckboxState.Open, out.single().state)
    }

    @Test
    fun `ticked box becomes a done item`() {
        val c = Canvas()
        c.box(100, 100, 30)
        c.line(106, 115, 113, 123)
        c.line(113, 123, 124, 106)
        c.scribble(150, 100, 400, 30)
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(2, "call the bank", 150, 100, 250, 30)), c.image())
        assertEquals(1, out.size)
        assertEquals(CheckboxState.Done, out[0].state)
        assertEquals(2, out[0].lineIndex)
    }

    @Test
    fun `crossed and fully shaded boxes read as done`() {
        val c = Canvas()
        c.box(100, 100, 30)
        c.line(104, 104, 125, 125)
        c.line(125, 104, 104, 125)
        c.rect(100, 200, 129, 229)
        val lines = listOf(
            c.lineAt(0, "crossed", 150, 100, 250, 30),
            c.lineAt(1, "shaded", 150, 200, 250, 30),
        )
        val out = CheckboxTodoDetector.detect(lines, c.image())
        assertEquals(listOf(CheckboxState.Done, CheckboxState.Done), out.map { it.state })
    }

    @Test
    fun `box the OCR swallowed as a character is stripped from the text`() {
        val c = Canvas()
        c.box(100, 100, 30)
        c.scribble(140, 100, 400, 30)
        // OCR line starts at the box and read it as "O".
        val line = c.lineAt(0, "O pay rent", 100, 100, 300, 30)
        val out = CheckboxTodoDetector.detect(listOf(line), c.image())
        assertEquals(1, out.size)
        assertEquals("pay rent", out[0].text)
        assertEquals(CheckboxState.Open, out[0].state)
    }

    @Test
    fun `a circle at the line start is not a checkbox`() {
        val c = Canvas()
        c.ring(115, 115, 14)
        c.scribble(150, 100, 400, 30)
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(0, "Oranges", 150, 100, 250, 30)), c.image())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `an open bracket shape with a gap is not a checkbox`() {
        val c = Canvas()
        c.box(100, 100, 30, gapSide = 'r')
        c.scribble(150, 100, 400, 30)
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(0, "notes", 150, 100, 250, 30)), c.image())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `plain text lines and boxes far from the line are ignored`() {
        val c = Canvas()
        c.scribble(150, 100, 400, 30)
        c.box(20, 300, 30) // unrelated box elsewhere on the page
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(0, "Meeting notes", 150, 100, 250, 30)), c.image())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `boxes far off the line height are rejected`() {
        val c = Canvas()
        c.box(60, 90, 70) // 70px box next to a 30px line: too big
        c.box(130, 112, 6) // 6px speck: too small
        c.scribble(150, 100, 400, 30)
        assertNull(CheckboxTodoDetector.findBox(c.lineAt(0, "x", 150, 100, 250, 30), c.image()))
    }

    @Test
    fun `mixed checklist keeps line order and states`() {
        val c = Canvas()
        for ((i, y) in listOf(80, 140, 200).withIndex()) {
            c.box(100, y, 28)
            c.scribble(150, y, 380, 28)
            if (i == 1) { c.line(105, y + 14, 112, y + 22); c.line(112, y + 22, 123, y + 5) }
        }
        val lines = listOf(
            c.lineAt(0, "first", 150, 80, 230, 28),
            c.lineAt(1, "second", 150, 140, 230, 28),
            c.lineAt(2, "third", 150, 200, 230, 28),
            c.lineAt(3, "no box here", 150, 260, 230, 28),
        )
        val out = CheckboxTodoDetector.detect(lines, c.image())
        assertEquals(listOf(0, 1, 2), out.map { it.lineIndex })
        assertEquals(listOf(CheckboxState.Open, CheckboxState.Done, CheckboxState.Open), out.map { it.state })
    }

    @Test
    fun `gray paper and lighter ink still work`() {
        val c = Canvas(paper = 200)
        c.box(100, 100, 30)
        for (y in 100..129) for (x in 100..129) {
            val i = y * c.w + x
            if ((c.px[i].toInt() and 0xFF) < 100) c.px[i] = 110.toByte()
        }
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(0, "water plants", 150, 100, 250, 30)), c.image())
        assertEquals(1, out.size)
        assertEquals(CheckboxState.Open, out[0].state)
    }

    // ─── Text glyph path ────────────────────────────────────────────

    @Test
    fun `text glyph prefixes are parsed without an image`() {
        val lines = listOf(
            OcrLine(0, "[ ] call mom", 0.1, 0.1, 0.5, 0.03),
            OcrLine(1, "- [x] pay rent", 0.1, 0.2, 0.5, 0.03),
            OcrLine(2, "☐ book flights", 0.1, 0.3, 0.5, 0.03),
            OcrLine(3, "☑ renew passport", 0.1, 0.4, 0.5, 0.03),
            OcrLine(4, "[] email Sam", 0.1, 0.5, 0.5, 0.03),
            OcrLine(5, "just a sentence", 0.1, 0.6, 0.5, 0.03),
            OcrLine(6, "[x]", 0.1, 0.7, 0.5, 0.03),
        )
        val out = CheckboxTodoDetector.detect(lines, image = null)
        assertEquals(listOf(0, 1, 2, 3, 4), out.map { it.lineIndex })
        assertEquals(
            listOf("call mom", "pay rent", "book flights", "renew passport", "email Sam"),
            out.map { it.text },
        )
        assertEquals(
            listOf(CheckboxState.Open, CheckboxState.Done, CheckboxState.Open, CheckboxState.Done, CheckboxState.Open),
            out.map { it.state },
        )
    }

    @Test
    fun `glyph wins over image when both are present`() {
        val c = Canvas()
        c.box(100, 100, 30) // empty box in pixels…
        val out = CheckboxTodoDetector.detect(listOf(c.lineAt(0, "[x] done already", 150, 100, 250, 30)), c.image())
        assertEquals(CheckboxState.Done, out.single().state) // …but OCR saw [x]
        assertEquals("done already", out.single().text)
    }
}
