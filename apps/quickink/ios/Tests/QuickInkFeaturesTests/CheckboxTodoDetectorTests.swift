/*
 * CheckboxTodoDetectorTests.swift
 *
 * Pure-XCTest coverage for the checkbox → to-do detector, using
 * synthetic grayscale pages. Mirror of Android
 * `CheckboxTodoDetectorTest.kt` — same drawings, same expectations.
 */

import XCTest
@testable import QuickInkFeatures

final class CheckboxTodoDetectorTests: XCTestCase {

    // MARK: - Synthetic page helpers

    private struct Canvas {
        let w: Int
        let h: Int
        var px: [UInt8]

        init(w: Int = 800, h: Int = 600, paper: UInt8 = 245) {
            self.w = w
            self.h = h
            self.px = [UInt8](repeating: paper, count: w * h)
        }

        mutating func dot(_ x: Int, _ y: Int, ink: UInt8 = 30) {
            if x >= 0, x < w, y >= 0, y < h { px[y * w + x] = ink }
        }

        mutating func rect(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, ink: UInt8 = 30) {
            for y in y0...y1 { for x in x0...x1 { dot(x, y, ink: ink) } }
        }

        mutating func box(_ x: Int, _ y: Int, _ side: Int, t: Int = 3, gapRight: Bool = false) {
            rect(x, y, x + side - 1, y + t - 1)
            rect(x, y + side - t, x + side - 1, y + side - 1)
            rect(x, y, x + t - 1, y + side - 1)
            rect(x + side - t, y, x + side - 1, y + side - 1)
            if gapRight { rect(x + side - t, y + side / 4, x + side - 1, y + 3 * side / 4, ink: 245) }
        }

        mutating func line(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, t: Int = 3) {
            let len = hypot(Double(x1 - x0), Double(y1 - y0))
            let steps = max(1, Int((len * 2).rounded()))
            for i in 0...steps {
                let cx = Double(x0) + Double(x1 - x0) * Double(i) / Double(steps)
                let cy = Double(y0) + Double(y1 - y0) * Double(i) / Double(steps)
                for dy in (-t / 2)...(t / 2) {
                    for dx in (-t / 2)...(t / 2) {
                        dot(Int((cx + Double(dx)).rounded()), Int((cy + Double(dy)).rounded()))
                    }
                }
            }
        }

        mutating func ring(_ cx: Int, _ cy: Int, _ r: Int, t: Int = 3) {
            for y in (cy - r - t)...(cy + r + t) {
                for x in (cx - r - t)...(cx + r + t) {
                    let d = hypot(Double(x - cx), Double(y - cy))
                    if abs(d - Double(r)) <= Double(t) / 2 + 0.5 { dot(x, y) }
                }
            }
        }

        mutating func scribble(_ x0: Int, _ y0: Int, _ x1: Int, _ h: Int) {
            var x = x0
            while x < x1 { rect(x, y0 + 4, x + 2, y0 + h - 4); x += 9 }
        }

        func image() -> GrayImage { GrayImage(pixels: px, width: w, height: h) }

        func lineAt(_ index: Int, _ text: String, _ x: Int, _ y: Int, _ width: Int, _ height: Int) -> OcrLine {
            OcrLine(
                index: index, text: text,
                x: Double(x) / Double(w), y: Double(y) / Double(h),
                width: Double(width) / Double(w), height: Double(height) / Double(h)
            )
        }
    }

    // MARK: - Image path

    func testEmptyBoxBeforeLineBecomesOpenTodo() {
        var c = Canvas()
        c.box(100, 100, 30)
        c.scribble(150, 100, 400, 30)
        let out = CheckboxTodoDetector.detect(lines: [c.lineAt(0, "buy milk", 150, 100, 250, 30)], image: c.image())
        XCTAssertEqual(out.count, 1)
        XCTAssertEqual(out.first?.state, .open)
        XCTAssertEqual(out.first?.text, "buy milk")
        XCTAssertEqual(out.first?.lineIndex, 0)
    }

    func testWobblyRotatedHandDrawnBoxIsFound() {
        var c = Canvas()
        c.line(98, 102, 132, 99)
        c.line(130, 97, 133, 131)
        c.line(134, 129, 101, 132)
        c.line(103, 133, 100, 100)
        c.scribble(150, 100, 400, 30)
        let out = CheckboxTodoDetector.detect(lines: [c.lineAt(0, "post the letter", 150, 100, 250, 30)], image: c.image())
        XCTAssertEqual(out.map(\.state), [.open])
    }

    func testTickedBoxBecomesDone() {
        var c = Canvas()
        c.box(100, 100, 30)
        c.line(106, 115, 113, 123)
        c.line(113, 123, 124, 106)
        c.scribble(150, 100, 400, 30)
        let out = CheckboxTodoDetector.detect(lines: [c.lineAt(2, "call the bank", 150, 100, 250, 30)], image: c.image())
        XCTAssertEqual(out.map(\.state), [.done])
        XCTAssertEqual(out.first?.lineIndex, 2)
    }

    func testCrossedAndShadedBoxesReadAsDone() {
        var c = Canvas()
        c.box(100, 100, 30)
        c.line(104, 104, 125, 125)
        c.line(125, 104, 104, 125)
        c.rect(100, 200, 129, 229)
        let lines = [
            c.lineAt(0, "crossed", 150, 100, 250, 30),
            c.lineAt(1, "shaded", 150, 200, 250, 30),
        ]
        XCTAssertEqual(CheckboxTodoDetector.detect(lines: lines, image: c.image()).map(\.state), [.done, .done])
    }

    func testSwallowedBoxCharacterIsStripped() {
        var c = Canvas()
        c.box(100, 100, 30)
        c.scribble(140, 100, 400, 30)
        let out = CheckboxTodoDetector.detect(lines: [c.lineAt(0, "O pay rent", 100, 100, 300, 30)], image: c.image())
        XCTAssertEqual(out.map(\.text), ["pay rent"])
        XCTAssertEqual(out.map(\.state), [.open])
    }

    func testCircleIsNotACheckbox() {
        var c = Canvas()
        c.ring(115, 115, 14)
        c.scribble(150, 100, 400, 30)
        XCTAssertTrue(CheckboxTodoDetector.detect(lines: [c.lineAt(0, "Oranges", 150, 100, 250, 30)], image: c.image()).isEmpty)
    }

    func testOpenShapeWithGapIsNotACheckbox() {
        var c = Canvas()
        c.box(100, 100, 30, gapRight: true)
        c.scribble(150, 100, 400, 30)
        XCTAssertTrue(CheckboxTodoDetector.detect(lines: [c.lineAt(0, "notes", 150, 100, 250, 30)], image: c.image()).isEmpty)
    }

    func testPlainLinesAndFarBoxesAreIgnored() {
        var c = Canvas()
        c.scribble(150, 100, 400, 30)
        c.box(20, 300, 30)
        XCTAssertTrue(CheckboxTodoDetector.detect(lines: [c.lineAt(0, "Meeting notes", 150, 100, 250, 30)], image: c.image()).isEmpty)
    }

    func testBoxesFarOffLineHeightAreRejected() {
        var c = Canvas()
        c.box(60, 90, 70)
        c.box(130, 112, 6)
        c.scribble(150, 100, 400, 30)
        XCTAssertNil(CheckboxTodoDetector.findBox(line: c.lineAt(0, "x", 150, 100, 250, 30), image: c.image()))
    }

    func testMixedChecklistKeepsOrderAndStates() {
        var c = Canvas()
        for (i, y) in [80, 140, 200].enumerated() {
            c.box(100, y, 28)
            c.scribble(150, y, 380, 28)
            if i == 1 { c.line(105, y + 14, 112, y + 22); c.line(112, y + 22, 123, y + 5) }
        }
        let lines = [
            c.lineAt(0, "first", 150, 80, 230, 28),
            c.lineAt(1, "second", 150, 140, 230, 28),
            c.lineAt(2, "third", 150, 200, 230, 28),
            c.lineAt(3, "no box here", 150, 260, 230, 28),
        ]
        let out = CheckboxTodoDetector.detect(lines: lines, image: c.image())
        XCTAssertEqual(out.map(\.lineIndex), [0, 1, 2])
        XCTAssertEqual(out.map(\.state), [.open, .done, .open])
    }

    func testGrayPaperAndLighterInk() {
        var c = Canvas(paper: 200)
        c.box(100, 100, 30)
        for y in 100...129 {
            for x in 100...129 where c.px[y * c.w + x] < 100 { c.px[y * c.w + x] = 110 }
        }
        let out = CheckboxTodoDetector.detect(lines: [c.lineAt(0, "water plants", 150, 100, 250, 30)], image: c.image())
        XCTAssertEqual(out.map(\.state), [.open])
    }

    // MARK: - Text glyph path

    func testTextGlyphPrefixesWithoutImage() {
        let texts = ["[ ] call mom", "- [x] pay rent", "☐ book flights", "☑ renew passport",
                     "[] email Sam", "just a sentence", "[x]"]
        let lines = texts.enumerated().map { i, t in
            OcrLine(index: i, text: t, x: 0.1, y: 0.1 * Double(i + 1), width: 0.5, height: 0.03)
        }
        let out = CheckboxTodoDetector.detect(lines: lines, image: nil)
        XCTAssertEqual(out.map(\.lineIndex), [0, 1, 2, 3, 4])
        XCTAssertEqual(out.map(\.text), ["call mom", "pay rent", "book flights", "renew passport", "email Sam"])
        XCTAssertEqual(out.map(\.state), [.open, .done, .open, .done, .open])
    }

    func testGlyphWinsOverImage() {
        var c = Canvas()
        c.box(100, 100, 30)
        let out = CheckboxTodoDetector.detect(lines: [c.lineAt(0, "[x] done already", 150, 100, 250, 30)], image: c.image())
        XCTAssertEqual(out.map(\.state), [.done])
        XCTAssertEqual(out.map(\.text), ["done already"])
    }
}
