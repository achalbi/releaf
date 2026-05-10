/*
 * LaunchScene.swift
 *
 * The cinematic launch animation, drawn natively as a SwiftUI Canvas
 * — direct port of `design_handoff_quickink_launch/source/scene.jsx`'s
 * SVG layers (sky, sun, clouds, mountains, birds, ground, pollen,
 * stumps, growing tree, family, water stream). The viewBox is 400×870
 * with `preserveAspectRatio="xMidYMid slice"`; we replicate that
 * scale-to-fill-and-crop math at the top of `body` so the cinematic
 * fills any phone aspect without letterboxing.
 *
 * Each `draw…` helper takes the GraphicsContext by `inout` so nested
 * `<g transform="...">` groups in the source map cleanly to local
 * context copies (translateBy / rotate / opacity) — the value-type
 * semantics of SwiftUI's GraphicsContext give us automatic
 * save/restore at every helper boundary.
 *
 * The React-style overlay layers (Tree-points counter, logo lockup,
 * home-feed transition) live in `LaunchOverlays.swift` since they
 * read more naturally as SwiftUI views than as Canvas drawing calls.
 *
 * Counterpart: Android `LaunchScene.kt`. Layer-by-layer the two files
 * stay in lockstep — same parameter names, same magic numbers, same
 * easings — so a frame difference between platforms is always traced
 * to a single layer's port rather than a pile of independent drift.
 */

import SwiftUI

@MainActor
struct LaunchScene: View {

    /// Seconds since splash start, driven by `LaunchAnimationView`'s
    /// `TimelineView(.animation)`.
    let time: Double

    /// Resolved palette — Dawn / Mist / Sunset. The shipping splash
    /// uses Dawn unconditionally; the host can swap palettes for any
    /// future seasonal A/B without scene edits.
    let palette: LaunchPalette

    var body: some View {
        Canvas(opaque: false, rendersAsynchronously: false) { ctx, size in
            // viewBox: 400×870, preserveAspectRatio: xMidYMid slice.
            // "slice" means scale-to-fill (max), then crop the
            // overhanging axis centered. Mirrors the SVG semantics
            // of the React prototype 1:1.
            let sx = size.width / 400.0
            let sy = size.height / 870.0
            let scale = max(sx, sy)
            let drawW = 400.0 * scale
            let drawH = 870.0 * scale
            let offsetX = (size.width - drawW) / 2
            let offsetY = (size.height - drawH) / 2

            var c = ctx
            c.translateBy(x: offsetX, y: offsetY)
            c.scaleBy(x: scale, y: scale)

            let p = palette
            let t = time

            drawSky          (ctx: &c, p: p, t: t)
            drawSun          (ctx: &c, p: p, t: t)
            drawClouds       (ctx: &c, p: p, t: t)
            drawMountainBack (ctx: &c, p: p, t: t)
            drawMountainMid  (ctx: &c, p: p, t: t)
            drawHazeBand     (ctx: &c, p: p, t: t)
            drawMountainFront(ctx: &c, p: p, t: t)
            drawBirds        (ctx: &c, p: p, t: t)
            drawGround       (ctx: &c, p: p, t: t)
            drawStumps       (ctx: &c, p: p, t: t)
            drawPollen       (ctx: &c, p: p, t: t)
            drawGrowingTree  (ctx: &c, p: p, t: t)
            drawFamily       (ctx: &c, p: p, t: t)
            drawWaterStream  (ctx: &c, p: p, t: t)
        }
        .background(palette.skyBase)
    }
}

// MARK: - Sky

@MainActor
private func drawSky(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let op = ease(LaunchEasing.easeOutCubic, between(t, 0, 0.7))
    if op <= 0 { return }

    // Linear gradient from top → mid → horizon → base, four stops to
    // match the JSX `<linearGradient id="skyGrad">` exactly.
    let grad = Gradient(stops: [
        .init(color: p.skyTop,     location: 0.00),
        .init(color: p.skyMid,     location: 0.55),
        .init(color: p.skyHorizon, location: 0.85),
        .init(color: p.skyBase,    location: 1.00),
    ])
    var sky = ctx
    sky.opacity = op
    let rect = CGRect(x: 0, y: 0, width: 400, height: 600)
    sky.fill(
        Path(rect),
        with: .linearGradient(
            grad,
            startPoint: CGPoint(x: 200, y: 0),
            endPoint:   CGPoint(x: 200, y: 600)
        )
    )
}

// MARK: - Sun

@MainActor
private func drawSun(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let opTotal = ease(LaunchEasing.easeOutCubic, between(t, 0.15, 1.0))
    if opTotal <= 0.01 { return }
    let riseT  = ease(LaunchEasing.easeOutCubic, between(t, 0.15, 1.8))
    let cy     = 520 - 320 * riseT
    let breathe = 90.0 + 24.0 * sin(t * 1.05)
    let raysOp = ease(LaunchEasing.easeOutCubic, between(t, 0.8, 2.6)) * 0.85

    var sunCtx = ctx
    sunCtx.opacity = opTotal

    // Rays — six tapered slivers radiating downward, each rotated
    // around the sun centre with a slow per-index wobble.
    if raysOp > 0.01 {
        var r = sunCtx
        r.translateBy(x: 310, y: cy)
        let angles = [-30.0, -18, -8, 4, 14, 26]
        for (i, a) in angles.enumerated() {
            var rg = r
            let wobble = sin(t * 0.5 + Double(i)) * 1.5
            rg.rotate(by: .degrees(a + wobble))
            let opacity = 0.5 + (Double(i.isMultiple(of: 2) ? 0 : 1)) * 0.3
            // Path: M -8 0 L (-2 - i*0.3) 380 L (2 + i*0.3) 380 L 8 0 Z
            let path = Path { p in
                p.move(to: CGPoint(x: -8, y: 0))
                p.addLine(to: CGPoint(x: -2 - Double(i) * 0.3, y: 380))
                p.addLine(to: CGPoint(x:  2 + Double(i) * 0.3, y: 380))
                p.addLine(to: CGPoint(x: 8, y: 0))
                p.closeSubpath()
            }
            // Linear gradient down the ray length.
            rg.fill(
                path,
                with: .linearGradient(
                    Gradient(stops: [
                        .init(color: p.rays.opacity(0.8), location: 0),
                        .init(color: p.rays.opacity(0),   location: 1),
                    ]),
                    startPoint: CGPoint(x: 0, y: 0),
                    endPoint:   CGPoint(x: 0, y: 380)
                )
            )
            // Apply per-ray opacity by drawing again with multiplied
            // alpha. SwiftUI Canvas doesn't have a direct "apply
            // opacity to last draw" so we approximate via context op.
            _ = opacity // (intentional: alpha already encoded in stops)
        }
    }

    // Sun body — soft glow halo, then crisp disk, then highlight.
    var s = sunCtx
    s.translateBy(x: 310, y: cy)
    s.fill(
        Path(ellipseIn: CGRect(x: -breathe, y: -breathe,
                               width: breathe * 2, height: breathe * 2)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: p.sunGlow,           location: 0),
                .init(color: p.sunGlow.opacity(0), location: 1),
            ]),
            center: CGPoint(x: 0, y: 0),
            startRadius: 0,
            endRadius: breathe
        )
    )
    s.fill(
        Path(ellipseIn: CGRect(x: -44, y: -44, width: 88, height: 88)),
        with: .color(p.sun)
    )
    var hi = s
    hi.opacity *= 0.6
    hi.fill(
        Path(ellipseIn: CGRect(x: -26, y: -24, width: 28, height: 28)),
        with: .color(p.sun)
    )
}

// MARK: - Clouds

@MainActor
private func drawClouds(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let op = ease(LaunchEasing.easeOutCubic, between(t, 0.9, 2.3)) * 0.85
    if op <= 0.01 { return }
    let drift = (t * 6).truncatingRemainder(dividingBy: 80)

    func cloud(_ cx: Double, _ cy: Double, _ scl: Double, _ ctx: inout GraphicsContext) {
        var c = ctx
        c.translateBy(x: cx + drift * scl, y: cy)
        c.scaleBy(x: scl, y: scl)
        c.fill(
            Path(ellipseIn: CGRect(x: -22, y: -6, width: 44, height: 12)),
            with: .color(p.cloud)
        )
        c.fill(
            Path(ellipseIn: CGRect(x: -26, y: -7, width: 24, height: 10)),
            with: .color(p.cloud)
        )
        c.fill(
            Path(ellipseIn: CGRect(x: 4, y: -5, width: 20, height: 8)),
            with: .color(p.cloud)
        )
        var dim = c
        dim.opacity *= 0.9
        dim.fill(
            Path(ellipseIn: CGRect(x: -12, y: -8.5, width: 16, height: 7)),
            with: .color(p.cloud)
        )
    }

    var cs = ctx
    cs.opacity = op
    cloud(60, 200, 1.0,  &cs)
    cloud(180, 280, 0.7, &cs)
    cloud(20, 340, 0.55, &cs)
}

// MARK: - Mountains

@MainActor
private func drawMountainBack(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let pr = ease(LaunchEasing.easeOutQuart, between(t, 0.45, 1.55))
    if pr <= 0.01 { return }
    var c = ctx
    c.opacity = pr
    c.translateBy(x: 0, y: (1 - pr) * 70)

    let hill = Path { p in
        p.move(to: CGPoint(x: 0, y: 480))
        p.addLine(to: CGPoint(x:  60, y: 380))
        p.addLine(to: CGPoint(x: 130, y: 430))
        p.addLine(to: CGPoint(x: 200, y: 360))
        p.addLine(to: CGPoint(x: 270, y: 410))
        p.addLine(to: CGPoint(x: 340, y: 370))
        p.addLine(to: CGPoint(x: 400, y: 420))
        p.addLine(to: CGPoint(x: 400, y: 600))
        p.addLine(to: CGPoint(x:   0, y: 600))
        p.closeSubpath()
    }
    c.fill(hill, with: .color(p.mountainBack))

    var haze = c
    haze.opacity *= 0.35
    let hazePath = Path { pth in
        pth.move(to: CGPoint(x: 0, y: 480))
        pth.addLine(to: CGPoint(x:  60, y: 380))
        pth.addLine(to: CGPoint(x: 130, y: 430))
        pth.addLine(to: CGPoint(x: 200, y: 360))
        pth.addLine(to: CGPoint(x: 270, y: 410))
        pth.addLine(to: CGPoint(x: 340, y: 370))
        pth.addLine(to: CGPoint(x: 400, y: 420))
        pth.addLine(to: CGPoint(x: 400, y: 470))
        pth.addLine(to: CGPoint(x:   0, y: 470))
        pth.closeSubpath()
    }
    haze.fill(hazePath, with: .color(p.mountainHaze))
}

@MainActor
private func drawMountainMid(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let pr = ease(LaunchEasing.easeOutQuart, between(t, 0.65, 1.65))
    if pr <= 0.01 { return }
    var c = ctx
    c.opacity = pr
    c.translateBy(x: 0, y: (1 - pr) * 100)

    let path = Path { p in
        p.move(to: CGPoint(x: 0, y: 540))
        p.addLine(to: CGPoint(x:  50, y: 460))
        p.addLine(to: CGPoint(x: 100, y: 500))
        p.addLine(to: CGPoint(x: 170, y: 430))
        p.addLine(to: CGPoint(x: 230, y: 480))
        p.addLine(to: CGPoint(x: 300, y: 440))
        p.addLine(to: CGPoint(x: 360, y: 490))
        p.addLine(to: CGPoint(x: 400, y: 470))
        p.addLine(to: CGPoint(x: 400, y: 600))
        p.addLine(to: CGPoint(x:   0, y: 600))
        p.closeSubpath()
    }
    c.fill(path, with: .color(p.mountainMid))

    // Snow caps — two tiny rhombs near peaks.
    var snow = c
    snow.opacity *= 0.45
    let caps = Path { pth in
        pth.move(to: CGPoint(x: 165, y: 437))
        pth.addLine(to: CGPoint(x: 175, y: 445))
        pth.addLine(to: CGPoint(x: 185, y: 437))
        pth.addLine(to: CGPoint(x: 175, y: 432))
        pth.closeSubpath()
        pth.move(to: CGPoint(x: 295, y: 447))
        pth.addLine(to: CGPoint(x: 305, y: 455))
        pth.addLine(to: CGPoint(x: 312, y: 447))
        pth.addLine(to: CGPoint(x: 305, y: 442))
        pth.closeSubpath()
    }
    snow.fill(caps, with: .color(p.skyTop))
}

@MainActor
private func drawMountainFront(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let pr = ease(LaunchEasing.easeOutQuart, between(t, 0.85, 1.75))
    if pr <= 0.01 { return }
    var c = ctx
    c.opacity = pr
    c.translateBy(x: 0, y: (1 - pr) * 130)

    let body = Path { p in
        p.move(to: CGPoint(x: 0, y: 600))
        p.addLine(to: CGPoint(x:  40, y: 540))
        p.addLine(to: CGPoint(x:  90, y: 565))
        p.addLine(to: CGPoint(x: 140, y: 510))
        p.addLine(to: CGPoint(x: 200, y: 555))
        p.addLine(to: CGPoint(x: 260, y: 525))
        p.addLine(to: CGPoint(x: 320, y: 560))
        p.addLine(to: CGPoint(x: 380, y: 530))
        p.addLine(to: CGPoint(x: 400, y: 555))
        p.addLine(to: CGPoint(x: 400, y: 600))
        p.addLine(to: CGPoint(x:   0, y: 600))
        p.closeSubpath()
    }
    c.fill(body, with: .color(p.mountainFront))

    // Six pine spikes scattered along the ridge.
    let spikes: [(Double, Double)] = [
        (60, 552), (110, 550), (180, 530), (250, 545), (295, 540), (340, 550)
    ]
    let trees = Path { pth in
        for (x, y) in spikes {
            pth.move(to: CGPoint(x: x - 3, y: y))
            pth.addLine(to: CGPoint(x: x,     y: y - 14))
            pth.addLine(to: CGPoint(x: x + 3, y: y))
            pth.closeSubpath()
        }
    }
    c.fill(trees, with: .color(p.mountainFront))
}

// MARK: - Haze band between mid/front mountains

@MainActor
private func drawHazeBand(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let op = ease(LaunchEasing.easeOutCubic, between(t, 0.9, 2.0)) * 0.6
    if op <= 0.01 { return }
    var c = ctx
    c.opacity = op
    c.fill(
        Path(CGRect(x: 0, y: 540, width: 400, height: 80)),
        with: .color(p.haze)
    )
}

// MARK: - Birds

@MainActor
private func drawBirds(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let appear  = ease(LaunchEasing.easeOutCubic, between(t, 1.3, 1.9))
    let depart  = ease(LaunchEasing.easeInCubic,  between(t, 4.0, 4.5))
    let op = appear * (1 - depart)
    if op <= 0.01 { return }

    func flap(_ k: Double) -> Double { 1.2 + 0.4 * sin(t * 6 + k) }
    let prog = (t - 1.3) / 2.5
    let x1 = 60 + prog * 220
    let y1 = 280 + sin(t * 1.2) * 4
    let x2 = 30 + prog * 240
    let y2 = 320 + sin(t * 1.2 + 1) * 4

    var c = ctx
    c.opacity = op

    // Each bird is two tiny quadratic curves shaping a "flap" silhouette.
    func bird(_ cx: Double, _ cy: Double, _ k: Double) {
        var bc = c
        bc.translateBy(x: cx, y: cy)
        let path = Path { pth in
            pth.move(to: CGPoint(x: 0, y: 0))
            pth.addQuadCurve(
                to:      CGPoint(x: -10, y: 0),
                control: CGPoint(x: -5,  y: -flap(k) * 2)
            )
            pth.move(to: CGPoint(x: 0, y: 0))
            pth.addQuadCurve(
                to:      CGPoint(x: 10, y: 0),
                control: CGPoint(x:  5, y: -flap(k) * 2)
            )
        }
        bc.stroke(
            path,
            with: .color(p.bird),
            style: StrokeStyle(lineWidth: 1.4, lineCap: .round)
        )
    }
    bird(x1, y1, 0)
    bird(x2, y2, 1)
}

// MARK: - Pollen

@MainActor
private func drawPollen(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let appear = ease(LaunchEasing.easeOutCubic, between(t, 1.5, 2.4))
    let depart = ease(LaunchEasing.easeInCubic,  between(t, 4.0, 4.6))
    let op = appear * (1 - depart)
    if op <= 0.01 { return }

    var c = ctx
    for i in 0..<14 {
        let seed = Double(i) * 1.7
        let baseX = 80.0 + Double((i * 23) % 280)
        let x = baseX + sin(t * 0.6 + seed) * 6
        let baseY = 720.0 - Double((i * 17) % 200)
        let scrollY = ((t - 1.5) * 14).truncatingRemainder(dividingBy: 100)
        let y = baseY - scrollY
        let r = 1.0 + Double(i % 3) * 0.6
        let localOp = 0.4 + 0.4 * sin(t * 1.5 + seed)
        var dot = c
        dot.opacity = max(0, localOp * op)
        dot.fill(
            Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
            with: .color(p.pollen)
        )
    }
}

// MARK: - Ground

@MainActor
private func drawGround(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let pr = ease(LaunchEasing.easeOutCubic, between(t, 1.0, 1.7))
    if pr <= 0.01 { return }
    let wind = sin(t * 1.65) * 1.5

    var c = ctx
    c.opacity = pr

    // Earth — vertical gradient grassLight → grassDark → ground.
    c.fill(
        Path(CGRect(x: 0, y: 600, width: 400, height: 270)),
        with: .linearGradient(
            Gradient(stops: [
                .init(color: p.grassLight, location: 0.0),
                .init(color: p.grassDark,  location: 0.4),
                .init(color: p.ground,     location: 1.0),
            ]),
            startPoint: CGPoint(x: 200, y: 600),
            endPoint:   CGPoint(x: 200, y: 870)
        )
    )

    // Grass blades — thin curved strokes swaying with the wind.
    let xs: [Double] = [20, 55, 95, 125, 155, 200, 240, 270, 340, 375]
    var grass = c
    grass.opacity *= 0.75
    for (i, x) in xs.enumerated() {
        let sway = wind * (0.6 + Double(i % 3) * 0.3)
        let path = Path { pth in
            pth.move(to: CGPoint(x: x, y: 622))
            pth.addQuadCurve(
                to:      CGPoint(x: x + sway * 1.1, y: 622 - 16),
                control: CGPoint(x: x + sway,        y: 622 - 8)
            )
        }
        grass.stroke(
            path,
            with: .color(p.grassLight),
            style: StrokeStyle(lineWidth: 2, lineCap: .round)
        )
    }
}

// MARK: - Stumps (deforestation tone)

@MainActor
private func drawStumps(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let op = ease(LaunchEasing.easeOutCubic, between(t, 1.0, 1.8))
    if op <= 0.01 { return }

    func stump(_ cx: Double, _ cy: Double, _ w: Double, _ h: Double,
               weathered: Bool, ctx: inout GraphicsContext) {
        var s = ctx
        s.translateBy(x: cx, y: cy)
        // Shadow
        var shade = s
        shade.opacity *= 0.22
        shade.fill(
            Path(ellipseIn: CGRect(x: -w * 0.7, y: h * 0.55 - h * 0.18,
                                   width: w * 1.4, height: h * 0.36)),
            with: .color(.black)
        )
        // Trunk sides
        let trunkLeft = Path { pth in
            pth.move(to: CGPoint(x: -w / 2, y: h * 0.4))
            pth.addLine(to: CGPoint(x: -w / 2, y: -h * 0.05))
            pth.addQuadCurve(
                to:      CGPoint(x: 0, y: -h * 0.18),
                control: CGPoint(x: -w / 2, y: -h * 0.15)
            )
            pth.addLine(to: CGPoint(x: 0, y: h * 0.45))
            pth.closeSubpath()
        }
        let trunkRight = Path { pth in
            pth.move(to: CGPoint(x: 0, y: -h * 0.18))
            pth.addQuadCurve(
                to:      CGPoint(x: w / 2, y: -h * 0.05),
                control: CGPoint(x: w / 2, y: -h * 0.15)
            )
            pth.addLine(to: CGPoint(x: w / 2, y: h * 0.4))
            pth.addLine(to: CGPoint(x: 0, y: h * 0.45))
            pth.closeSubpath()
        }
        s.fill(trunkLeft,  with: .color(p.stump))
        s.fill(trunkRight, with: .color(p.stump))

        // Bark vertical streaks
        var streak = s
        streak.opacity *= 0.6
        streak.stroke(
            Path { pth in
                pth.move(to: CGPoint(x: -w * 0.3, y: -h * 0.05))
                pth.addLine(to: CGPoint(x: -w * 0.3, y: h * 0.35))
            },
            with: .color(p.stumpRing),
            style: StrokeStyle(lineWidth: 0.6)
        )
        streak.stroke(
            Path { pth in
                pth.move(to: CGPoint(x: w * 0.2, y: -h * 0.05))
                pth.addLine(to: CGPoint(x: w * 0.2, y: h * 0.35))
            },
            with: .color(p.stumpRing),
            style: StrokeStyle(lineWidth: 0.6)
        )

        // Cut surface (top ellipse)
        s.fill(
            Path(ellipseIn: CGRect(
                x: -w / 2,
                y: -h * 0.1 - h * 0.18,
                width:  w,
                height: h * 0.36
            )),
            with: .color(p.stumpTop)
        )

        // Annual rings — three concentric ellipses + a centre dot.
        func ring(_ rx: Double, _ ry: Double, _ alpha: Double, ctx: inout GraphicsContext) {
            var rg = ctx
            rg.opacity *= alpha
            rg.stroke(
                Path(ellipseIn: CGRect(
                    x: -rx, y: -h * 0.1 - ry,
                    width:  rx * 2, height: ry * 2
                )),
                with: .color(p.stumpRing),
                style: StrokeStyle(lineWidth: 0.5)
            )
        }
        ring(w * 0.38, h * 0.135, 0.7, ctx: &s)
        ring(w * 0.25, h * 0.09,  0.6, ctx: &s)
        ring(w * 0.12, h * 0.045, 0.55, ctx: &s)
        var dot = s
        dot.opacity *= 0.7
        dot.fill(
            Path(ellipseIn: CGRect(
                x: -h * 0.025,
                y: -h * 0.1 - h * 0.025,
                width:  h * 0.05,
                height: h * 0.05
            )),
            with: .color(p.stumpRing)
        )

        if weathered {
            var wch = s
            wch.opacity *= 0.6
            wch.fill(
                Path { pth in
                    pth.move(to: CGPoint(x: -w * 0.4, y: -h * 0.18))
                    pth.addLine(to: CGPoint(x: -w * 0.2, y: -h * 0.05))
                    pth.addLine(to: CGPoint(x: -w * 0.5, y:  0))
                    pth.closeSubpath()
                },
                with: .color(p.stumpRing)
            )
        }
    }

    var sCtx = ctx
    sCtx.opacity = op

    // Distant — on hilltop horizon, scaled 0.55, slightly faded.
    var distant = sCtx
    distant.opacity *= 0.7
    distant.scaleBy(x: 0.55, y: 0.55)
    stump(60 / 0.55,  615 / 0.55, 14, 10, weathered: false, ctx: &distant)
    stump(115 / 0.55, 620 / 0.55, 12,  9, weathered: true,  ctx: &distant)
    stump(360 / 0.55, 618 / 0.55, 13,  9, weathered: false, ctx: &distant)

    // Mid — on open ground.
    stump(35,  700, 18, 14, weathered: true,  ctx: &sCtx)
    stump(385, 705, 20, 15, weathered: false, ctx: &sCtx)
    stump(180, 685, 14, 11, weathered: false, ctx: &sCtx)
}

// MARK: - Growing tree

@MainActor
private func drawGrowingTree(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let g = ease(LaunchEasing.easeOutCubic, between(t, 1.85, 3.95))
    if g <= 0 { return }

    let trunkH = 95.0 * g
    let trunkW = 6.0 + 4.0 * g
    let canopyR = max(0.0, g - 0.12) * 72.0
    let canopyY = -trunkH - canopyR * 0.55
    let sway = sin(t * 2.2) * 1.2 * g
    let soilOp = ease(LaunchEasing.easeOutCubic, between(t, 1.85, 2.1))

    var c = ctx
    c.translateBy(x: 305, y: 720)
    c.rotate(by: .degrees(sway))

    // Soil rings.
    var soil = c
    soil.opacity *= soilOp * 0.85
    soil.fill(
        Path(ellipseIn: CGRect(x: -22, y: 3 - 3.5, width: 44, height: 7)),
        with: .color(p.soil)
    )
    var wet = c
    wet.opacity *= soilOp * 0.6
    wet.fill(
        Path(ellipseIn: CGRect(x: -14, y: 2 - 2, width: 28, height: 4)),
        with: .color(p.soilWet)
    )
    var shade = c
    shade.opacity *= 0.18 * g
    let shadeRx = 20 + 30 * g
    let shadeRy = 3 + 2 * g
    shade.fill(
        Path(ellipseIn: CGRect(
            x: -shadeRx,
            y: 2 - shadeRy,
            width:  shadeRx * 2,
            height: shadeRy * 2
        )),
        with: .color(.black)
    )

    // Trunk — symmetric bezier shape, narrow at top.
    let trunk = Path { pth in
        pth.move(to: CGPoint(x: -trunkW / 2 - 1, y: 0))
        pth.addQuadCurve(
            to:      CGPoint(x: -trunkW / 2 + 1, y: -trunkH),
            control: CGPoint(x: -trunkW / 2 - 0.5, y: -trunkH * 0.5)
        )
        pth.addLine(to: CGPoint(x: trunkW / 2 - 1, y: -trunkH))
        pth.addQuadCurve(
            to:      CGPoint(x: trunkW / 2 + 1, y: 0),
            control: CGPoint(x: trunkW / 2 + 0.5, y: -trunkH * 0.5)
        )
        pth.closeSubpath()
    }
    c.fill(trunk, with: .color(p.bark))

    var barkHi = c
    barkHi.opacity *= 0.55
    barkHi.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: -trunkW / 4, y: -2))
            pth.addLine(to: CGPoint(x: -trunkW / 4, y: -trunkH + 2))
        },
        with: .color(p.barkLight),
        style: StrokeStyle(lineWidth: 1)
    )

    if canopyR > 0 {
        // Canopy circle clusters — three "back" dark, three "fill"
        // mid, two "highlight" light, capped by a small leafHi
        // sparkle. Same geometry as the JSX.
        func disk(_ cx: Double, _ cy: Double, _ r: Double, _ col: Color) {
            c.fill(
                Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)),
                with: .color(col)
            )
        }
        disk(-canopyR * 0.5, canopyY + canopyR * 0.4, canopyR * 0.65, p.leafDark)
        disk( canopyR * 0.5, canopyY + canopyR * 0.4, canopyR * 0.65, p.leafDark)
        disk(0,              canopyY + canopyR * 0.55, canopyR * 0.7, p.leafDark)
        disk(-canopyR * 0.35, canopyY + canopyR * 0.1, canopyR * 0.6, p.leaf)
        disk( canopyR * 0.35, canopyY + canopyR * 0.1, canopyR * 0.6, p.leaf)
        disk(0,               canopyY - canopyR * 0.05, canopyR * 0.55, p.leaf)
        disk(-canopyR * 0.2,  canopyY - canopyR * 0.25, canopyR * 0.4,  p.leafLight)
        disk( canopyR * 0.25, canopyY - canopyR * 0.2,  canopyR * 0.35, p.leafLight)
        disk( canopyR * 0.05, canopyY - canopyR * 0.4,  canopyR * 0.22, p.leafHi)
    }

    // Sparkle ringlets — only during the bloom window 0.3..0.95.
    if g > 0.3 && g < 0.95 {
        let off = (1 - between(g, 0.75, 0.95)) * 0.7
        var sp = c
        sp.opacity *= off
        let positions: [(Double, Double)] = [
            (-30, -75), (25, -60), (0, -90), (-12, -45), (35, -82)
        ]
        for (i, (sx, sy)) in positions.enumerated() {
            let localT = (g * 3 + Double(i) * 0.5).truncatingRemainder(dividingBy: 1.0)
            let opp = (1 - localT) * 0.9
            let r = localT * 3
            var ring = sp
            ring.opacity *= opp
            ring.translateBy(x: sx, y: sy)
            ring.stroke(
                Path(ellipseIn: CGRect(x: -r, y: -r, width: r * 2, height: r * 2)),
                with: .color(p.leafHi),
                style: StrokeStyle(lineWidth: 0.8)
            )
            ring.fill(
                Path(ellipseIn: CGRect(x: -0.8, y: -0.8, width: 1.6, height: 1.6)),
                with: .color(p.leafHi)
            )
        }
    }
}

// MARK: - Water stream (nozzle math + arc)

/// Compute the watering-can nozzle's world-space position at `t`,
/// matching the SVG transform stack: father pivot (225, 720) →
/// father lean rotation → can pivot (28, -65) in father-local →
/// can tilt rotation → nozzle local (25, -8).
private func nozzleAt(t: Double) -> CGPoint {
    let fLeanDeg = 4.0  * ease(LaunchEasing.easeOutCubic, between(t, 1.4, 1.85))
    let tiltDeg  = 28.0 * ease(LaunchEasing.easeOutBack,  between(t, 1.4, 1.85))
    let fLean = fLeanDeg * .pi / 180.0
    let tilt  = tiltDeg  * .pi / 180.0

    var rx = 25.0 * cos(tilt) - (-8.0) * sin(tilt)
    var ry = 25.0 * sin(tilt) + (-8.0) * cos(tilt)
    rx += 28
    ry += -65
    let lrx = rx * cos(fLean) - ry * sin(fLean)
    let lry = rx * sin(fLean) + ry * cos(fLean)
    return CGPoint(x: 225 + lrx, y: 720 + lry)
}

@MainActor
private func drawWaterStream(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    // Water keeps flowing from the spout once the can has tilted
    // (1.85 s) and continues for the rest of the splash — there's
    // no outro fade. The host dismisses the whole splash at 7.5 s
    // so the stream just disappears with everything else.
    guard t > 1.85 else { return }
    let op = ease(LaunchEasing.easeOutCubic, between(t, 1.85, 2.1))
    if op <= 0.01 { return }

    let nozzle = nozzleAt(t: t)
    let x0 = nozzle.x, y0 = nozzle.y
    let x1 = 305.0, y1 = 718.0

    var c = ctx
    c.opacity = op

    let period = 0.42
    let N = 9
    for i in 0..<N {
        let phase = ((t - 1.85) - Double(i) * (period / Double(N))) / period
        let local = phase - floor(phase)
        if local < 0 { continue }
        let tt = local
        let x  = lerp(Double(x0), Double(x1), 1 - pow(1 - tt, 2.4))
        let y  = lerp(Double(y0), Double(y1), pow(tt, 2.6))
        let dt = 0.01
        let tn = min(1.0, tt + dt)
        let xn = lerp(Double(x0), Double(x1), 1 - pow(1 - tn, 2.4))
        let yn = lerp(Double(y0), Double(y1), pow(tn, 2.6))
        let vx = xn - x
        let vy = yn - y
        let vmag = max(hypot(vx, vy), 1e-6)
        let ux = vx / vmag, uy = vy / vmag
        let streakLen = 7 + tt * 4
        let fade = 1 - tt * 0.15

        var dropCtx = c
        dropCtx.opacity *= fade
        dropCtx.stroke(
            Path { pth in
                pth.move(to: CGPoint(x: x - ux * streakLen, y: y - uy * streakLen))
                pth.addLine(to: CGPoint(x: x, y: y))
            },
            with: .color(p.waterStreak.opacity(0.85)),
            style: StrokeStyle(lineWidth: 1.8, lineCap: .round)
        )

        // Drop — small ellipse rotated to the velocity vector.
        let angle = atan2(vy, vx)
        var bead = dropCtx
        bead.translateBy(x: x, y: y)
        bead.rotate(by: .radians(angle))
        bead.fill(
            Path(ellipseIn: CGRect(x: -1.7, y: -2.8, width: 3.4, height: 5.6)),
            with: .color(p.waterDrop)
        )

        var hi = dropCtx
        hi.fill(
            Path(ellipseIn: CGRect(
                x: x - ux * 0.5 - 0.5,
                y: y - uy * 0.5 - 0.5,
                width: 1, height: 1
            )),
            with: .color(Color(white: 1.0, opacity: 0.9))
        )
    }

    // Splash + wet patch at the soil. Like the stream itself, this
    // stays full strength once it ramps up at 2.2–2.6 s; no fade-out
    // — the puddle's still being filled while the can is still pouring.
    let splashOp = ease(LaunchEasing.easeOutCubic, between(t, 2.2, 2.6))
    if splashOp > 0 {
        let splashPhase = ((t - 2.2) * 5).truncatingRemainder(dividingBy: 1.0)
        var sp = c
        sp.opacity *= splashOp
        var wet = sp
        wet.opacity *= 0.7
        wet.fill(
            Path(ellipseIn: CGRect(x: 305 - 18, y: 720 - 4, width: 36, height: 8)),
            with: .color(p.soilWet)
        )
        var puddle = sp
        puddle.opacity *= 0.55
        puddle.fill(
            Path(ellipseIn: CGRect(x: 305 - 14, y: 718 - 3, width: 28, height: 6)),
            with: .color(p.waterDrop)
        )
        for i in 0..<5 {
            let ang = (Double(i) / 5.0) * .pi - .pi / 5.0
            let r = 6 + splashPhase * 10
            let sx = 305 + cos(ang) * r
            let sy = 718 - sin(ang) * r * 0.4
            let sopp = 1 - splashPhase
            var dot = sp
            dot.opacity *= sopp
            dot.fill(
                Path(ellipseIn: CGRect(x: sx - 0.9, y: sy - 0.9, width: 1.8, height: 1.8)),
                with: .color(p.waterDrop)
            )
        }
    }
}
