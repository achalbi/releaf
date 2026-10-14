/*
 * CheckboxTodoDetector.swift
 *
 * Finds hand-drawn (or printed) checkboxes at the start of OCR lines
 * and classifies each as open or ticked. Unticked lines become open
 * to-dos; ticked lines become done items.
 *
 * Two signals, checked per line:
 *
 *   1. Text glyph — the OCR line itself starts with a box glyph
 *      (`☐`, `☑`, `☒`, `□`, …) or a Markdown-style `[ ]` / `[x]`.
 *      Wins when present because it is the most explicit signal.
 *   2. Image — crop the page around the line start (at and just left
 *      of the line's bounding box), binarise, trace contours, and
 *      look for a roughly square closed outline sized like the
 *      line's x-height. Ink density inside the box decides ticked vs
 *      empty.
 *
 * Pure function: input is the page's OCR lines (normalised 0..1
 * boxes, top-left origin) plus an optional grayscale buffer, so it
 * runs in plain XCTest with synthetic images. Binarisation and
 * contour tracing reuse `CardImageOps`.
 *
 * Mirror of Android `CheckboxTodoDetector.kt` — keep thresholds in
 * lockstep.
 */

import Foundation

/// One OCR line in normalised page coordinates (0..1, top-left origin).
public struct OcrLine: Equatable, Sendable {
    /// Position in the page's line list — kept as the to-do's source line index.
    public let index: Int
    public let text: String
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(index: Int, text: String, x: Double, y: Double, width: Double, height: Double) {
        self.index = index
        self.text = text
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    public var centerX: Double { x + width / 2 }
    public var centerY: Double { y + height / 2 }
}

/// 8-bit grayscale page buffer, row-major (`y * width + x`), 0 = black.
public struct GrayImage: Sendable {
    public let pixels: [UInt8]
    public let width: Int
    public let height: Int

    public init(pixels: [UInt8], width: Int, height: Int) {
        precondition(width > 0 && height > 0, "empty image")
        precondition(pixels.count >= width * height, "buffer smaller than width × height")
        self.pixels = pixels
        self.width = width
        self.height = height
    }
}

public enum CheckboxState: Equatable, Sendable {
    case open
    case done
}

public struct DetectedCheckboxLine: Equatable, Sendable {
    public let lineIndex: Int
    /// Line text with the box glyph / misread box character removed.
    public let text: String
    public let state: CheckboxState
}

public enum CheckboxTodoDetector {

    // MARK: - Tunables (mirror in CheckboxTodoDetector.kt)

    private static let minSideToLine = 0.45
    private static let maxSideToLine = 1.6
    private static let minAspect = 0.7
    private static let maxAspect = 1.4
    private static let searchLeft = 2.2
    private static let searchRight = 1.3
    private static let searchVertical = 0.45
    private static let sideCoverage = 0.85
    private static let tickDensity = 0.06
    private static let thresholdC = 12
    private static let minLineHeightPx = 8.0

    private static let openGlyphs: Set<Character> = ["☐", "□", "▢", "❏", "◻", "⬜", "❑", "❒"]
    private static let doneGlyphs: Set<Character> = ["☑", "☒", "✅", "⊠", "▣"]
    private static let doneMarks: Set<Character> = ["x", "X", "✓", "✔", "v", "V", "*"]
    private static let boxLookalikes: Set<String> = [
        "o", "0", "d", "q", "u", "a", "c", "[]", "[", "]", "|", "□", "口", "ㅁ",
        "0.", "o.", "d.",
    ]

    /// Detect checkbox lines. `lines` should be line-grained OCR
    /// blocks. Without `image` only the text-glyph signal runs.
    /// Output is ordered by `OcrLine.index`.
    public static func detect(lines: [OcrLine], image: GrayImage?) -> [DetectedCheckboxLine] {
        var out: [DetectedCheckboxLine] = []
        for line in lines.sorted(by: { $0.index < $1.index }) {
            if let glyph = parseGlyphPrefix(line.text) {
                if !glyph.text.trimmingCharacters(in: .whitespaces).isEmpty {
                    out.append(DetectedCheckboxLine(lineIndex: line.index, text: glyph.text, state: glyph.state))
                }
                continue
            }
            guard let image, let box = findBox(line: line, image: image) else { continue }
            let text = cleanText(line.text, line: line, box: box, image: image)
            guard !text.isEmpty else { continue }
            out.append(DetectedCheckboxLine(lineIndex: line.index, text: text, state: box.state))
        }
        return out
    }

    /// `[ ]` / `[x]` / box-glyph prefix → (state, remaining text).
    /// Tolerates a leading Markdown bullet (`- [ ] buy milk`).
    public static func parseGlyphPrefix(_ raw: String) -> (state: CheckboxState, text: String)? {
        var text = Substring(raw.trimmingCharacters(in: .whitespacesAndNewlines))
        if let first = text.first, "-*•·".contains(first),
           text.dropFirst().first?.isWhitespace == true {
            text = text.dropFirst().drop(while: { $0.isWhitespace })
        }
        guard let first = text.first else { return nil }
        if openGlyphs.contains(first) { return (.open, stripLead(String(text.dropFirst()))) }
        if doneGlyphs.contains(first) { return (.done, stripLead(String(text.dropFirst()))) }
        guard first == "[" else { return nil }
        // `[` + optional spaces + optional single mark + optional spaces + `]`
        var rest = text.dropFirst().drop(while: { $0 == " " })
        var state: CheckboxState = .open
        if let mark = rest.first, doneMarks.contains(mark) {
            state = .done
            rest = rest.dropFirst().drop(while: { $0 == " " })
        }
        guard rest.first == "]" else { return nil }
        return (state, stripLead(String(rest.dropFirst())))
    }

    /// Pixel-space box found next to a line, plus its classification.
    public struct BoxHit: Equatable, Sendable {
        public let left: Int
        public let top: Int
        public let right: Int
        public let bottom: Int
        public let inkDensity: Double
        public let state: CheckboxState

        public var side: Int { max(right - left + 1, bottom - top + 1) }
    }

    /// Look for a closed, roughly square outline at the start of
    /// `line`. Returns the best (largest) passing candidate or nil.
    public static func findBox(line: OcrLine, image: GrayImage) -> BoxHit? {
        let w = image.width
        let h = image.height
        let lx = line.x * Double(w)
        let ly = line.y * Double(h)
        let lh = line.height * Double(h)
        guard lh >= minLineHeightPx else { return nil }

        let x0 = clamp(Int((lx - searchLeft * lh).rounded()), 0, w - 1)
        let x1 = clamp(Int((lx + searchRight * lh).rounded()), 0, w - 1)
        let y0 = clamp(Int((ly - searchVertical * lh).rounded()), 0, h - 1)
        let y1 = clamp(Int((ly + lh + searchVertical * lh).rounded()), 0, h - 1)
        let rw = x1 - x0 + 1
        let rh = y1 - y0 + 1
        guard rw >= 6, rh >= 6 else { return nil }

        var roi = [UInt8](repeating: 0, count: rw * rh)
        for yy in 0..<rh {
            let src = (y0 + yy) * w + x0
            roi.replaceSubrange((yy * rw)..<(yy * rw + rw), with: image.pixels[src..<(src + rw)])
        }
        let block = oddAtLeast(max(15, Int((lh * 3).rounded())))
        var binary = [UInt8](repeating: 0, count: rw * rh)
        CardImageOps.adaptiveMeanThreshold(src: roi, width: rw, height: rh, blockSize: block, c: thresholdC, dst: &binary)
        // 1px background pad so contours touching the crop edge still
        // start on a background→ink transition.
        let pw = rw + 2
        let ph = rh + 2
        var padded = [UInt8](repeating: 0, count: pw * ph)
        for yy in 0..<rh {
            let dst = (yy + 1) * pw + 1
            padded.replaceSubrange(dst..<(dst + rw), with: binary[(yy * rw)..<(yy * rw + rw)])
        }

        let minSide = minSideToLine * lh
        let maxSide = maxSideToLine * lh
        let contours = CardImageOps.findExternalContours(
            binary: padded,
            width: pw,
            height: ph,
            minPixels: max(8, Int((2 * minSide).rounded())),
            maxContours: 64
        )

        var best: BoxHit?
        for c in contours {
            var minX = Int.max, minY = Int.max, maxX = Int.min, maxY = Int.min
            var i = 0
            while i < c.count {
                let cx = c[i], cy = c[i + 1]
                minX = min(minX, cx); maxX = max(maxX, cx)
                minY = min(minY, cy); maxY = max(maxY, cy)
                i += 2
            }
            let bw = Double(maxX - minX + 1)
            let bh = Double(maxY - minY + 1)
            if bw < minSide || bh < minSide || bw > maxSide || bh > maxSide { continue }
            let aspect = bw / bh
            if aspect < minAspect || aspect > maxAspect { continue }
            let left = minX - 1 + x0
            let top = minY - 1 + y0
            let right = maxX - 1 + x0
            let bottom = maxY - 1 + y0
            let centerY = Double(top + bottom) / 2
            if centerY < ly - 0.25 * lh || centerY > ly + 1.25 * lh { continue }
            if Double(right) > lx + searchRight * lh { continue }
            if isHoleBoundary(padded, stride: pw, height: ph, minX, minY, maxX, maxY) { continue }
            if !isClosedSquare(padded, stride: pw, minX, minY, maxX, maxY) { continue }
            let density = interiorInk(padded, stride: pw, minX, minY, maxX, maxY)
            let hit = BoxHit(
                left: left, top: top, right: right, bottom: bottom,
                inkDensity: density,
                state: density >= tickDensity ? .done : .open
            )
            if best == nil || hit.side > best!.side { best = hit }
        }
        return best
    }

    // MARK: - Shape tests

    /// True when the contour traces the inside of a hole (e.g. the
    /// inner edge of a ring): the 1px frame just outside it is mostly
    /// stroke, not paper. Keeps the hole inside an "O" from passing
    /// the corner test.
    private static func isHoleBoundary(
        _ bin: [UInt8], stride: Int, height: Int,
        _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int
    ) -> Bool {
        let fx0 = x0 - 1, fx1 = x1 + 1, fy0 = y0 - 1, fy1 = y1 + 1
        if fx0 < 0 || fy0 < 0 || fx1 >= stride || fy1 >= height { return false }
        var ink = 0, total = 0
        func count(_ x: Int, _ y: Int) { total += 1; if bin[y * stride + x] != 0 { ink += 1 } }
        for x in fx0...fx1 { count(x, fy0); count(x, fy1) }
        for y in y0...y1 { count(fx0, y); count(fx1, y) }
        return Double(ink) / Double(total) > 0.5
    }

    /// Each side must carry ink along ≥ `sideCoverage` of its length
    /// (5%–95%, thin band) and each corner patch must carry ink. The
    /// corner check separates a box from an "O" / "D".
    private static func isClosedSquare(
        _ bin: [UInt8], stride: Int,
        _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int
    ) -> Bool {
        let bw = x1 - x0 + 1
        let bh = y1 - y0 + 1
        let band = max(2, Int((Double(min(bw, bh)) * 0.14).rounded()))
        func ink(_ x: Int, _ y: Int) -> Bool { bin[y * stride + x] != 0 }

        func horizontalCoverage(_ yStart: Int, _ yEnd: Int) -> Double {
            let from = x0 + Int((Double(bw) * 0.05).rounded())
            let to = x1 - Int((Double(bw) * 0.05).rounded())
            guard from <= to else { return 0 }
            var hits = 0
            for x in from...to where (yStart...yEnd).contains(where: { ink(x, $0) }) { hits += 1 }
            return Double(hits) / Double(to - from + 1)
        }
        func verticalCoverage(_ xStart: Int, _ xEnd: Int) -> Double {
            let from = y0 + Int((Double(bh) * 0.05).rounded())
            let to = y1 - Int((Double(bh) * 0.05).rounded())
            guard from <= to else { return 0 }
            var hits = 0
            for y in from...to where (xStart...xEnd).contains(where: { ink($0, y) }) { hits += 1 }
            return Double(hits) / Double(to - from + 1)
        }
        if horizontalCoverage(y0, y0 + band - 1) < sideCoverage { return false }
        if horizontalCoverage(y1 - band + 1, y1) < sideCoverage { return false }
        if verticalCoverage(x0, x0 + band - 1) < sideCoverage { return false }
        if verticalCoverage(x1 - band + 1, x1) < sideCoverage { return false }

        let patch = max(2, Int((Double(min(bw, bh)) * 0.12).rounded()))
        func cornerInk(_ cx0: Int, _ cy0: Int) -> Bool {
            for y in cy0..<(cy0 + patch) {
                for x in cx0..<(cx0 + patch) where ink(x, y) { return true }
            }
            return false
        }
        return cornerInk(x0, y0) && cornerInk(x1 - patch + 1, y0) &&
            cornerInk(x0, y1 - patch + 1) && cornerInk(x1 - patch + 1, y1 - patch + 1)
    }

    /// Ink fraction of the box interior, shrunk ~22% per side so the
    /// outline strokes don't count.
    private static func interiorInk(
        _ bin: [UInt8], stride: Int,
        _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int
    ) -> Double {
        let mx = max(2, Int((Double(x1 - x0 + 1) * 0.22).rounded()))
        let my = max(2, Int((Double(y1 - y0 + 1) * 0.22).rounded()))
        let ix0 = x0 + mx, ix1 = x1 - mx, iy0 = y0 + my, iy1 = y1 - my
        guard ix1 >= ix0, iy1 >= iy0 else { return 0 }
        var ink = 0, total = 0
        for y in iy0...iy1 {
            for x in ix0...ix1 {
                total += 1
                if bin[y * stride + x] != 0 { ink += 1 }
            }
        }
        return Double(ink) / Double(total)
    }

    // MARK: - Text cleanup

    /// When the box sits inside the OCR line's box, the recogniser
    /// usually read it as a character ("O", "D", "口", "[]"). Drop
    /// that leading token.
    private static func cleanText(_ raw: String, line: OcrLine, box: BoxHit, image: GrayImage) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lineLeftPx = line.x * Double(image.width)
        let lineHeightPx = line.height * Double(image.height)
        if Double(box.right) > lineLeftPx + 0.15 * lineHeightPx {
            let firstSpace = text.firstIndex(where: { $0.isWhitespace })
            let firstToken = firstSpace.map { String(text[..<$0]) } ?? text
            if boxLookalikes.contains(firstToken.lowercased()) ||
                (firstToken.count <= 2 && !firstToken.contains(where: { $0.isLetter || $0.isNumber })) {
                text = firstSpace.map { String(text[$0...]) } ?? ""
            }
        }
        return stripLead(text)
    }

    private static func stripLead(_ s: String) -> String {
        let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
        let dropped = trimmed.drop(while: { $0 == "-" || $0 == ":" || $0 == "." || $0 == ")" || $0.isWhitespace })
        return String(dropped).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func oddAtLeast(_ v: Int) -> Int { v % 2 == 1 ? v : v + 1 }

    private static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { v < lo ? lo : (v > hi ? hi : v) }
}
