/*
 * CardImageOps.swift
 *
 * Pure-Swift image-processing primitives the iOS Business
 * Card detector + perspective warper compose. Mirror of
 * Android `CardImageOps.kt` — identical algorithms with
 * Swift-idiomatic signatures (UnsafeMutableBufferPointer
 * over `ByteArray`).
 *
 * Coordinate convention: 8-bit grayscale planar buffers
 * indexed as `y * width + x`, origin top-left, x right, y
 * down. Floating-point geometry — quad corners, the IoU
 * polygon — uses the same axes.
 *
 * Why no OpenCV: the OpenCV Swift Package weighs in at ~30
 * MB once linked. For "find a high-contrast 1.586:1
 * rectangle inside a fixed center ROI and warp it to
 * 1012×638", the per-frame ops are loose enough that
 * straight Swift is fast enough on a A12+ device. The
 * tradeoff is more code in this repo, but ~600 lines of
 * Swift against 30 MB of binary is the right call for a
 * single feature.
 */

import Foundation

public struct Point2f: Equatable, Hashable, Sendable {
    public let x: Float
    public let y: Float

    public init(x: Float, y: Float) {
        self.x = x
        self.y = y
    }

    public func distance(to other: Point2f) -> Float {
        let dx = x - other.x
        let dy = y - other.y
        return (dx * dx + dy * dy).squareRoot()
    }
}

public struct DetectedQuad: Equatable, Sendable {
    public let tl: Point2f
    public let tr: Point2f
    public let br: Point2f
    public let bl: Point2f

    public init(tl: Point2f, tr: Point2f, br: Point2f, bl: Point2f) {
        self.tl = tl
        self.tr = tr
        self.br = br
        self.bl = bl
    }

    public var asArray: [Point2f] { [tl, tr, br, bl] }

    public func scaled(sx: Float, sy: Float) -> DetectedQuad {
        DetectedQuad(
            tl: Point2f(x: tl.x * sx, y: tl.y * sy),
            tr: Point2f(x: tr.x * sx, y: tr.y * sy),
            br: Point2f(x: br.x * sx, y: br.y * sy),
            bl: Point2f(x: bl.x * sx, y: bl.y * sy),
        )
    }
}

public struct GuideRect: Equatable, Sendable {
    public let left: Float
    public let top: Float
    public let right: Float
    public let bottom: Float

    public var width: Float  { right - left }
    public var height: Float { bottom - top }
    public var centerX: Float { (left + right) * 0.5 }
    public var centerY: Float { (top + bottom) * 0.5 }

    public init(left: Float, top: Float, right: Float, bottom: Float) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public var asQuad: DetectedQuad {
        DetectedQuad(
            tl: Point2f(x: left,  y: top),
            tr: Point2f(x: right, y: top),
            br: Point2f(x: right, y: bottom),
            bl: Point2f(x: left,  y: bottom),
        )
    }
}

public enum CardImageOps {

    // ── Gaussian blur 5×5, σ ≈ 1.2 ─────────────────────────────
    // Separable; integer kernel [1,4,6,4,1] sum=16.

    private static let gauss5: [Int] = [1, 4, 6, 4, 1]

    public static func gaussianBlur5(
        src: inout [UInt8],
        width: Int,
        height: Int,
        scratch: inout [UInt8],
    ) {
        // Horizontal pass: src → scratch.
        for y in 0..<height {
            let rowOff = y * width
            for x in 0..<width {
                var acc = 0
                for k in -2...2 {
                    let xx = clamp(x + k, lo: 0, hi: width - 1)
                    acc += Int(src[rowOff + xx]) * gauss5[k + 2]
                }
                scratch[rowOff + x] = UInt8(acc / 16)
            }
        }
        // Vertical pass: scratch → src.
        for y in 0..<height {
            for x in 0..<width {
                var acc = 0
                for k in -2...2 {
                    let yy = clamp(y + k, lo: 0, hi: height - 1)
                    acc += Int(scratch[yy * width + x]) * gauss5[k + 2]
                }
                src[y * width + x] = UInt8(acc / 16)
            }
        }
    }

    // ── Adaptive mean threshold ────────────────────────────────

    public static func adaptiveMeanThreshold(
        src: [UInt8],
        width: Int,
        height: Int,
        blockSize: Int,
        c: Int,
        dst: inout [UInt8],
    ) {
        precondition(blockSize % 2 == 1, "blockSize must be odd")
        let half = blockSize / 2
        var integral = [Int64](repeating: 0, count: (width + 1) * (height + 1))
        for y in 1...height {
            let rowPrev = (y - 1) * (width + 1)
            let rowThis = y * (width + 1)
            let srcRow  = (y - 1) * width
            var rowSum: Int64 = 0
            for x in 1...width {
                rowSum += Int64(src[srcRow + (x - 1)])
                integral[rowThis + x] = integral[rowPrev + x] + rowSum
            }
        }
        for y in 0..<height {
            let y0 = max(0, y - half)
            let y1 = min(height - 1, y + half)
            for x in 0..<width {
                let x0 = max(0, x - half)
                let x1 = min(width - 1, x + half)
                let area = Int64((x1 - x0 + 1) * (y1 - y0 + 1))
                let sum = integral[(y1 + 1) * (width + 1) + (x1 + 1)] -
                          integral[y0 * (width + 1) + (x1 + 1)] -
                          integral[(y1 + 1) * (width + 1) + x0] +
                          integral[y0 * (width + 1) + x0]
                let mean = Int(sum / area)
                let px = Int(src[y * width + x])
                dst[y * width + x] = (px < mean - c) ? 255 : 0
            }
        }
    }

    public static func sobelEdges(
        src: [UInt8],
        width: Int,
        height: Int,
        low: Int,
        high: Int,
        dst: inout [UInt8],
    ) {
        for y in 1..<(height - 1) {
            let ym1 = (y - 1) * width
            let y0  = y * width
            let yp1 = (y + 1) * width
            for x in 1..<(width - 1) {
                let tl = Int(src[ym1 + x - 1])
                let t  = Int(src[ym1 + x    ])
                let tr = Int(src[ym1 + x + 1])
                let l  = Int(src[y0  + x - 1])
                let r  = Int(src[y0  + x + 1])
                let bl = Int(src[yp1 + x - 1])
                let b  = Int(src[yp1 + x    ])
                let br = Int(src[yp1 + x + 1])
                let gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                let gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                let mag = abs(gx) + abs(gy)
                dst[y0 + x] = (mag >= low) ? 255 : 0
                _ = high  // hysteresis stage simplified to single threshold per Android
            }
        }
        for x in 0..<width {
            dst[x] = 0
            dst[(height - 1) * width + x] = 0
        }
        for y in 0..<height {
            dst[y * width] = 0
            dst[y * width + width - 1] = 0
        }
    }

    // ── External contour border-follow ─────────────────────────

    private static let mooreDx: [Int] = [ 1,  1,  0, -1, -1, -1,  0,  1]
    private static let mooreDy: [Int] = [ 0,  1,  1,  1,  0, -1, -1, -1]

    public static func findExternalContours(
        binary: [UInt8],
        width: Int,
        height: Int,
        minPixels: Int,
        maxContours: Int = 16,
    ) -> [[Int]] {
        var visited = [Bool](repeating: false, count: width * height)
        var contours: [[Int]] = []
        let maxSteps = width * height
        for y in 1..<(height - 1) {
            for x in 1..<(width - 1) {
                if visited[y * width + x] { continue }
                if binary[y * width + x] == 0 { continue }
                if binary[y * width + (x - 1)] != 0 { continue }
                if let border = traceContour(
                    binary: binary,
                    width: width, height: height,
                    startX: x, startY: y,
                    visited: &visited,
                    maxSteps: maxSteps,
                ) {
                    if border.count / 2 >= minPixels {
                        contours.append(border)
                        if contours.count >= maxContours { return contours }
                    }
                }
            }
        }
        return contours
    }

    private static func traceContour(
        binary: [UInt8],
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        visited: inout [Bool],
        maxSteps: Int,
    ) -> [Int]? {
        var out: [Int] = [startX, startY]
        visited[startY * width + startX] = true
        var cx = startX
        var cy = startY
        var prev = 6
        var steps = 0
        while steps < maxSteps {
            steps += 1
            var found = false
            for i in 0..<8 {
                let dir = (prev + 2 + i) & 7
                let nx = cx + mooreDx[dir]
                let ny = cy + mooreDy[dir]
                if nx < 0 || ny < 0 || nx >= width || ny >= height { continue }
                if binary[ny * width + nx] == 0 { continue }
                cx = nx; cy = ny
                prev = (dir + 4) & 7
                visited[ny * width + nx] = true
                if cx == startX && cy == startY {
                    found = false
                    break
                }
                out.append(cx); out.append(cy)
                found = true
                break
            }
            if !found { break }
        }
        return out.count / 2 < 4 ? nil : out
    }

    // ── Polygon approximation (Ramer–Douglas–Peucker) ──────────

    public static func approxPolyDp(contour: [Int], epsilon: Float) -> [Float] {
        let n = contour.count / 2
        if n < 3 { return [] }
        var keep = [Bool](repeating: false, count: n)
        keep[0] = true; keep[n - 1] = true
        var stack: [(Int, Int)] = [(0, n - 1)]
        while let (lo, hi) = stack.popLast() {
            var maxDist: Float = 0
            var index = -1
            let x0 = Float(contour[lo * 2]);     let y0 = Float(contour[lo * 2 + 1])
            let x1 = Float(contour[hi * 2]);     let y1 = Float(contour[hi * 2 + 1])
            for i in (lo + 1)..<hi {
                let px = Float(contour[i * 2])
                let py = Float(contour[i * 2 + 1])
                let d = perpendicularDistance(px: px, py: py, ax: x0, ay: y0, bx: x1, by: y1)
                if d > maxDist {
                    maxDist = d
                    index = i
                }
            }
            if maxDist > epsilon && index > 0 {
                keep[index] = true
                stack.append((lo, index))
                stack.append((index, hi))
            }
        }
        var out: [Float] = []
        for i in 0..<n where keep[i] {
            out.append(Float(contour[i * 2]))
            out.append(Float(contour[i * 2 + 1]))
        }
        if out.count >= 4 {
            let sx = out[0]; let sy = out[1]
            let ex = out[out.count - 2]; let ey = out[out.count - 1]
            if sx == ex && sy == ey {
                out.removeLast(); out.removeLast()
            }
        }
        return out
    }

    private static func perpendicularDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ) -> Float {
        let dx = bx - ax; let dy = by - ay
        let len2 = dx * dx + dy * dy
        if len2 < 1e-6 {
            let ex = px - ax; let ey = py - ay
            return (ex * ex + ey * ey).squareRoot()
        }
        let cross = (px - ax) * dy - (py - ay) * dx
        return abs(cross) / len2.squareRoot()
    }

    // ── Corner ordering TL/TR/BR/BL ────────────────────────────

    public static func orderCornersClockwise(_ corners: [Point2f]) -> DetectedQuad {
        precondition(corners.count == 4, "orderCornersClockwise needs 4 corners")
        let cx = (corners[0].x + corners[1].x + corners[2].x + corners[3].x) * 0.25
        let cy = (corners[0].y + corners[1].y + corners[2].y + corners[3].y) * 0.25
        var tl = corners[0]; var tr = corners[0]
        var br = corners[0]; var bl = corners[0]
        for p in corners {
            let left = p.x < cx
            let top  = p.y < cy
            switch (left, top) {
            case (true,  true):  tl = p
            case (false, true):  tr = p
            case (false, false): br = p
            case (true,  false): bl = p
            }
        }
        return DetectedQuad(tl: tl, tr: tr, br: br, bl: bl)
    }

    // ── Convexity + area ───────────────────────────────────────

    public static func isConvex(poly: [Float]) -> Bool {
        guard poly.count == 8 else { return false }
        var sign = 0
        for i in 0..<4 {
            let x0 = poly[(i * 2)        ]; let y0 = poly[(i * 2)         + 1]
            let x1 = poly[((i + 1) % 4) * 2]; let y1 = poly[((i + 1) % 4) * 2 + 1]
            let x2 = poly[((i + 2) % 4) * 2]; let y2 = poly[((i + 2) % 4) * 2 + 1]
            let cross = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
            let s = cross > 0 ? 1 : (cross < 0 ? -1 : 0)
            if s == 0 { continue }
            if sign == 0 { sign = s }
            else if sign != s { return false }
        }
        return true
    }

    public static func shoelaceArea(poly: [Float]) -> Float {
        if poly.count < 6 { return 0 }
        var acc: Float = 0
        var j = poly.count / 2 - 1
        for i in 0..<(poly.count / 2) {
            let xi = poly[i * 2]; let yi = poly[i * 2 + 1]
            let xj = poly[j * 2]; let yj = poly[j * 2 + 1]
            acc += (xj + xi) * (yj - yi)
            j = i
        }
        return abs(acc) * 0.5
    }

    // ── Polygon IoU via Sutherland–Hodgman ─────────────────────

    public static func polygonIou(quad: DetectedQuad, guide: GuideRect) -> Float {
        let q: [Float] = [
            quad.tl.x, quad.tl.y,
            quad.tr.x, quad.tr.y,
            quad.br.x, quad.br.y,
            quad.bl.x, quad.bl.y,
        ]
        let gArea = guide.width * guide.height
        let qArea = shoelaceArea(poly: q)
        let clipped = clipPolygonAgainstRect(poly: q, rect: guide)
        let interArea = shoelaceArea(poly: clipped)
        let union = qArea + gArea - interArea
        if union <= 0 { return 0 }
        return interArea / union
    }

    private static func clipPolygonAgainstRect(poly: [Float], rect: GuideRect) -> [Float] {
        var output = poly
        output = clipPolygonAgainstEdge(
            poly: output,
            inside: { x, _ in x >= rect.left },
            intersect: { x0, y0, x1, y1 in interpX(x0: x0, y0: y0, x1: x1, y1: y1, x: rect.left) },
        )
        output = clipPolygonAgainstEdge(
            poly: output,
            inside: { x, _ in x <= rect.right },
            intersect: { x0, y0, x1, y1 in interpX(x0: x0, y0: y0, x1: x1, y1: y1, x: rect.right) },
        )
        output = clipPolygonAgainstEdge(
            poly: output,
            inside: { _, y in y >= rect.top },
            intersect: { x0, y0, x1, y1 in interpY(x0: x0, y0: y0, x1: x1, y1: y1, y: rect.top) },
        )
        output = clipPolygonAgainstEdge(
            poly: output,
            inside: { _, y in y <= rect.bottom },
            intersect: { x0, y0, x1, y1 in interpY(x0: x0, y0: y0, x1: x1, y1: y1, y: rect.bottom) },
        )
        return output
    }

    private static func clipPolygonAgainstEdge(
        poly: [Float],
        inside: (Float, Float) -> Bool,
        intersect: (Float, Float, Float, Float) -> (Float, Float),
    ) -> [Float] {
        if poly.isEmpty { return poly }
        var out: [Float] = []
        let n = poly.count / 2
        for i in 0..<n {
            let cx = poly[i * 2]; let cy = poly[i * 2 + 1]
            let px = poly[((i - 1 + n) % n) * 2]
            let py = poly[((i - 1 + n) % n) * 2 + 1]
            let curIn  = inside(cx, cy)
            let prevIn = inside(px, py)
            if curIn {
                if !prevIn {
                    let ip = intersect(px, py, cx, cy)
                    out.append(ip.0); out.append(ip.1)
                }
                out.append(cx); out.append(cy)
            } else if prevIn {
                let ip = intersect(px, py, cx, cy)
                out.append(ip.0); out.append(ip.1)
            }
        }
        return out
    }

    private static func interpX(
        x0: Float, y0: Float, x1: Float, y1: Float, x: Float,
    ) -> (Float, Float) {
        let t = x1 == x0 ? 0 : (x - x0) / (x1 - x0)
        return (x, y0 + t * (y1 - y0))
    }

    private static func interpY(
        x0: Float, y0: Float, x1: Float, y1: Float, y: Float,
    ) -> (Float, Float) {
        let t = y1 == y0 ? 0 : (y - y0) / (y1 - y0)
        return (x0 + t * (x1 - x0), y)
    }

    // ── Perspective transform ──────────────────────────────────

    public static func getPerspectiveTransform(src: [Point2f], dst: [Point2f]) -> [Float] {
        precondition(src.count == 4 && dst.count == 4)
        var a = Array(repeating: [Float](repeating: 0, count: 8), count: 8)
        var b = [Float](repeating: 0, count: 8)
        for i in 0..<4 {
            let sx = src[i].x; let sy = src[i].y
            let dx = dst[i].x; let dy = dst[i].y
            a[i * 2]     = [sx, sy, 1, 0, 0, 0, -sx * dx, -sy * dx]
            a[i * 2 + 1] = [0, 0, 0, sx, sy, 1, -sx * dy, -sy * dy]
            b[i * 2]     = dx
            b[i * 2 + 1] = dy
        }
        let h = solveLinearSystem8(a: &a, b: &b)
        return [
            h[0], h[1], h[2],
            h[3], h[4], h[5],
            h[6], h[7], 1,
        ]
    }

    private static func solveLinearSystem8(a: inout [[Float]], b: inout [Float]) -> [Float] {
        let n = 8
        for i in 0..<n {
            var pivot = i
            var pivotVal = abs(a[i][i])
            for r in (i + 1)..<n {
                if abs(a[r][i]) > pivotVal {
                    pivot = r
                    pivotVal = abs(a[r][i])
                }
            }
            if pivot != i {
                a.swapAt(i, pivot)
                let tb = b[i]; b[i] = b[pivot]; b[pivot] = tb
            }
            let diag = a[i][i]
            if abs(diag) < 1e-8 {
                var r = [Float](repeating: 0, count: 8)
                r[0] = 1; r[4] = 1
                return r
            }
            for c in i..<n { a[i][c] /= diag }
            b[i] /= diag
            for r in 0..<n {
                if r == i { continue }
                let factor = a[r][i]
                if factor == 0 { continue }
                for c in i..<n { a[r][c] -= factor * a[i][c] }
                b[r] -= factor * b[i]
            }
        }
        return b
    }

    public static func meanLuminance(src: [UInt8]) -> Int {
        if src.isEmpty { return 0 }
        var sum: Int64 = 0
        for v in src { sum += Int64(v) }
        return Int(sum / Int64(src.count))
    }

    private static func clamp(_ v: Int, lo: Int, hi: Int) -> Int {
        v < lo ? lo : (v > hi ? hi : v)
    }
}
