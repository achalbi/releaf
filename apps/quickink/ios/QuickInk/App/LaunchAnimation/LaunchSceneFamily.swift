/*
 * LaunchSceneFamily.swift
 *
 * The four family members from the launch animation — mother (x=70,
 * holding a sapling in a terracotta pot), daughter (x=120, holding
 * a notebook with the QuickInk leaf glyph), son (x=165, pointing
 * up at the tree), and father (x=225, leaning forward to tip the
 * watering can right toward the tree).
 *
 * Direct port of the `Family` component in
 * `design_handoff_quickink_launch/source/scene.jsx`. We retain the
 * source's per-character SVG path geometry (clothing silhouettes,
 * heads, eye + smile markers) at moderate fidelity — fine enough to
 * read clearly at 400×870 viewBox-on-device but skipping a handful
 * of single-pixel decorations that wouldn't survive screen-density
 * downscaling anyway (e.g. eye-shine sub-pixel circles, hairline
 * pleat strokes). Those would land as wasted draw calls without
 * any visible payoff.
 *
 * Drawn by `drawFamily(ctx:p:t:)`, which `LaunchScene.body` calls
 * after the tree and before the water stream so the water reads as
 * pouring over the father's hand and into the soil. The watering
 * can itself is a child of the father group; the nozzle world-space
 * math used by the water stream lives in `LaunchScene.swift`'s
 * `nozzleAt(t:)` and must stay in sync with the transform stack
 * here (father pivot 225,720 → fLean rotation → can pivot 28,-65 →
 * tilt rotation → nozzle 25,-8).
 *
 * Counterpart: Android `LaunchSceneFamily.kt`.
 */

import SwiftUI

@MainActor
func drawFamily(ctx: inout GraphicsContext, p: LaunchPalette, t: Double) {
    let op = ease(LaunchEasing.easeOutCubic, between(t, 1.2, 1.9))
    if op <= 0.01 { return }

    // Beat-keyed motions reused across characters.
    let fLean = 4.0 * ease(LaunchEasing.easeOutCubic, between(t, 1.4, 1.85))
    let tilt = 28.0 * ease(LaunchEasing.easeOutBack, between(t, 1.4, 1.85))
    let lookUp = -10.0 * ease(LaunchEasing.easeOutCubic, between(t, 2.3, 3.6))
    let sonBob = -1.6 * abs(sin(t * 4))
                 * ease(LaunchEasing.easeOutCubic, between(t, 2.5, 3.8))
    let motherSway = sin(t * 1.4) * 1.2

    var family = ctx
    family.opacity = op

    drawMother  (ctx: &family, p: p, t: t, sway: motherSway)
    drawDaughter(ctx: &family, p: p, t: t, lookUp: lookUp, bob: sonBob * 0.5)
    drawSon     (ctx: &family, p: p, t: t, lookUp: lookUp, bob: sonBob)
    drawFather  (ctx: &family, p: p, t: t, fLean: fLean, tilt: tilt)
}

// MARK: - Shared head helper

/// Generic cartoon head — circle face, eyes (white sclera + dark
/// pupil), brows, smile, optional cheek blush. Per-character
/// hair/clothing is drawn around it. Mirrors the head paint pass
/// shared by all four characters in the JSX (`<g transform=
/// "translate(0 -head)"> ... </g>`) without re-rolling four
/// near-identical inline blocks.
@MainActor
private func drawHead(
    ctx: inout GraphicsContext,
    p: LaunchPalette,
    skin: Color,
    radius: Double,
    eyeY: Double,
    smileY: Double,
    blush: Bool,
    bigSmile: Bool = false,
    bindi: Bool = false
) {
    // Face circle.
    ctx.fill(
        Path(ellipseIn: CGRect(x: -radius, y: eyeY - radius * 0.2 - radius,
                               width: radius * 2, height: radius * 2)),
        with: .color(skin)
    )
    // Eyes — sclera + pupil.
    let eyeRX = radius * 0.18, eyeRY = radius * 0.2
    ctx.fill(
        Path(ellipseIn: CGRect(x: -radius * 0.4 - eyeRX, y: eyeY - eyeRY,
                               width: eyeRX * 2, height: eyeRY * 2)),
        with: .color(.white)
    )
    ctx.fill(
        Path(ellipseIn: CGRect(x:  radius * 0.4 - eyeRX, y: eyeY - eyeRY,
                               width: eyeRX * 2, height: eyeRY * 2)),
        with: .color(.white)
    )
    let pupil = radius * 0.11
    ctx.fill(
        Path(ellipseIn: CGRect(x: -radius * 0.4 - pupil + pupil * 0.15,
                               y: eyeY - pupil + pupil * 0.5,
                               width: pupil * 2, height: pupil * 2)),
        with: .color(p.hairBrown)
    )
    ctx.fill(
        Path(ellipseIn: CGRect(x:  radius * 0.4 - pupil + pupil * 0.4,
                               y: eyeY - pupil + pupil * 0.5,
                               width: pupil * 2, height: pupil * 2)),
        with: .color(p.hairBrown)
    )
    // Brows.
    ctx.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: -radius * 0.7, y: eyeY - radius * 0.4))
            pth.addQuadCurve(
                to:      CGPoint(x: -radius * 0.15, y: eyeY - radius * 0.4),
                control: CGPoint(x: -radius * 0.4,  y: eyeY - radius * 0.5)
            )
        },
        with: .color(p.hairBrown),
        style: StrokeStyle(lineWidth: 0.9, lineCap: .round)
    )
    ctx.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: radius * 0.15, y: eyeY - radius * 0.4))
            pth.addQuadCurve(
                to:      CGPoint(x: radius * 0.7, y: eyeY - radius * 0.4),
                control: CGPoint(x: radius * 0.4,  y: eyeY - radius * 0.5)
            )
        },
        with: .color(p.hairBrown),
        style: StrokeStyle(lineWidth: 0.9, lineCap: .round)
    )

    // Smile.
    if bigSmile {
        ctx.fill(
            Path { pth in
                pth.move(to: CGPoint(x: -radius * 0.3, y: smileY))
                pth.addQuadCurve(
                    to:      CGPoint(x: radius * 0.3, y: smileY),
                    control: CGPoint(x: 0,            y: smileY + 3)
                )
                pth.addQuadCurve(
                    to:      CGPoint(x: -radius * 0.3, y: smileY),
                    control: CGPoint(x: 0,             y: smileY + 1)
                )
                pth.closeSubpath()
            },
            with: .color(Color(launchHex: 0x5a3020))
        )
    } else {
        ctx.stroke(
            Path { pth in
                pth.move(to: CGPoint(x: -radius * 0.3, y: smileY))
                pth.addQuadCurve(
                    to:      CGPoint(x: radius * 0.3, y: smileY),
                    control: CGPoint(x: 0,            y: smileY + 3)
                )
            },
            with: .color(Color(launchHex: 0x5a3020)),
            style: StrokeStyle(lineWidth: 0.9, lineCap: .round)
        )
    }

    // Bindi (mother only).
    if bindi {
        ctx.fill(
            Path(ellipseIn: CGRect(
                x: -1.4, y: eyeY - radius * 0.7 - 1.4,
                width: 2.8, height: 2.8
            )),
            with: .color(Color(launchHex: 0xc4283a))
        )
    }

    if blush {
        var bl = ctx
        bl.opacity *= 0.55
        bl.fill(
            Path(ellipseIn: CGRect(
                x: -radius * 0.85, y: eyeY + radius * 0.25,
                width: 4, height: 4
            )),
            with: .color(Color(launchHex: 0xf0a890))
        )
        bl.fill(
            Path(ellipseIn: CGRect(
                x: radius * 0.65, y: eyeY + radius * 0.25,
                width: 4, height: 4
            )),
            with: .color(Color(launchHex: 0xf0a890))
        )
    }
}

// MARK: - Mother

@MainActor
private func drawMother(
    ctx: inout GraphicsContext,
    p: LaunchPalette,
    t: Double,
    sway: Double
) {
    var m = ctx
    m.translateBy(x: 70, y: 720)
    m.rotate(by: .degrees(sway * 0.3))

    // Shoes — two squat ellipses.
    m.fill(
        Path(ellipseIn: CGRect(x: -11, y: -3, width: 12, height: 6)),
        with: .color(p.hairDark)
    )
    m.fill(
        Path(ellipseIn: CGRect(x: -1, y: -3, width: 12, height: 6)),
        with: .color(p.hairDark)
    )

    // Sari skirt (A-line) with gold border at hem.
    m.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -16, y: -50))
            pth.addLine(to: CGPoint(x: -22, y: 0))
            pth.addLine(to: CGPoint(x:  22, y: 0))
            pth.addLine(to: CGPoint(x:  16, y: -50))
            pth.closeSubpath()
        },
        with: .color(p.motherSkirt)
    )
    m.fill(
        Path(CGRect(x: -22, y: -3, width: 44, height: 3)),
        with: .color(p.gold)
    )
    // Choli (blouse) torso block — covers waist seam.
    m.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -14, y: -50))
            pth.addLine(to: CGPoint(x: -16, y: -30))
            pth.addLine(to: CGPoint(x:  16, y: -30))
            pth.addLine(to: CGPoint(x:  14, y: -50))
            pth.closeSubpath()
        },
        with: .color(p.motherShirt)
    )
    // Body — choli over torso.
    m.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -14, y: -86))
            pth.addQuadCurve(
                to:      CGPoint(x: -15, y: -62),
                control: CGPoint(x: -16, y: -75)
            )
            pth.addLine(to: CGPoint(x: -14, y: -50))
            pth.addLine(to: CGPoint(x:  14, y: -50))
            pth.addLine(to: CGPoint(x:  15, y: -62))
            pth.addQuadCurve(
                to:      CGPoint(x:  14, y: -86),
                control: CGPoint(x:  16, y: -75)
            )
            pth.addQuadCurve(
                to:      CGPoint(x:   0, y: -90),
                control: CGPoint(x:  10, y: -90)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -14, y: -86),
                control: CGPoint(x: -10, y: -90)
            )
            pth.closeSubpath()
        },
        with: .color(p.motherShirt)
    )
    // Dupatta drape over left shoulder.
    var drape = m
    drape.opacity *= 0.85
    drape.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -14, y: -84))
            pth.addQuadCurve(
                to:      CGPoint(x: -20, y: -50),
                control: CGPoint(x: -22, y: -70)
            )
            pth.addLine(to: CGPoint(x: -16, y: -50))
            pth.addLine(to: CGPoint(x: -16, y: -68))
            pth.addQuadCurve(
                to:      CGPoint(x: -10, y: -84),
                control: CGPoint(x: -14, y: -78)
            )
            pth.closeSubpath()
        },
        with: .color(p.gold)
    )
    // Necklace — two arcs + pendant.
    m.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: -8, y: -86))
            pth.addQuadCurve(
                to:      CGPoint(x: 8, y: -86),
                control: CGPoint(x: 0, y: -82)
            )
        },
        with: .color(p.gold),
        style: StrokeStyle(lineWidth: 1)
    )
    m.fill(
        Path(ellipseIn: CGRect(x: -1.1, y: -79.1, width: 2.2, height: 2.2)),
        with: .color(p.gold)
    )

    // Arms — left holds sapling, right held to chest.
    m.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -14, y: -82))
            pth.addQuadCurve(
                to:      CGPoint(x: -16, y: -58),
                control: CGPoint(x: -19, y: -70)
            )
            pth.addLine(to: CGPoint(x: -10, y: -52))
            pth.addQuadCurve(
                to:      CGPoint(x:  -6, y: -56),
                control: CGPoint(x:  -7, y: -52)
            )
            pth.addLine(to: CGPoint(x: -10, y: -68))
            pth.closeSubpath()
        },
        with: .color(p.motherShirt)
    )
    m.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 14, y: -82))
            pth.addQuadCurve(
                to:      CGPoint(x: 16, y: -68),
                control: CGPoint(x: 18, y: -76)
            )
            pth.addLine(to: CGPoint(x: 12, y: -58))
            pth.addQuadCurve(
                to:      CGPoint(x:  6, y: -60),
                control: CGPoint(x:  8, y: -56)
            )
            pth.addLine(to: CGPoint(x:  8, y: -72))
            pth.closeSubpath()
        },
        with: .color(p.motherShirt)
    )

    // Sapling in terracotta pot held to chest at (0, -60).
    var pot = m
    pot.translateBy(x: 0, y: -60)
    // Pot body.
    pot.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -7, y: 0))
            pth.addLine(to: CGPoint(x: -5.5, y: 6))
            pth.addLine(to: CGPoint(x:  5.5, y: 6))
            pth.addLine(to: CGPoint(x:  7,   y: 0))
            pth.closeSubpath()
        },
        with: .color(Color(launchHex: 0xc46a3a))
    )
    pot.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -7.5, y: -1))
            pth.addLine(to: CGPoint(x:  7.5, y: -1))
            pth.addLine(to: CGPoint(x:  7,   y: 1))
            pth.addLine(to: CGPoint(x: -7,   y: 1))
            pth.closeSubpath()
        },
        with: .color(Color(launchHex: 0x9a4a22))
    )
    // Soil ellipse.
    pot.fill(
        Path(ellipseIn: CGRect(x: -6.5, y: -2, width: 13, height: 2)),
        with: .color(Color(launchHex: 0x3a2418))
    )
    // Trunk + leaf cluster (5 leaves).
    pot.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -0.6, y: -1))
            pth.addLine(to: CGPoint(x: -0.4, y: -10))
            pth.addLine(to: CGPoint(x:  0.4, y: -10))
            pth.addLine(to: CGPoint(x:  0.6, y: -1))
            pth.closeSubpath()
        },
        with: .color(p.bark)
    )
    pot.fill(
        Path(ellipseIn: CGRect(x: -2 - 2.4, y: -12.5 - 2.8, width: 4.8, height: 5.6)),
        with: .color(p.leaf)
    )
    pot.fill(
        Path(ellipseIn: CGRect(x:  2 - 2.4, y: -12.5 - 2.8, width: 4.8, height: 5.6)),
        with: .color(p.leaf)
    )
    pot.fill(
        Path(ellipseIn: CGRect(x: -2.3, y: -15 - 3, width: 4.6, height: 6)),
        with: .color(p.leafLight)
    )
    pot.fill(
        Path(ellipseIn: CGRect(x: -3.5 - 2.4, y: -9 - 3, width: 4.8, height: 6)),
        with: .color(p.leafDark)
    )
    pot.fill(
        Path(ellipseIn: CGRect(x:  3.5 - 2.4, y: -9 - 3, width: 4.8, height: 6)),
        with: .color(p.leafDark)
    )

    // Hands gripping pot.
    m.fill(
        Path(ellipseIn: CGRect(x: -11.6, y: -60.6, width: 5.2, height: 5.2)),
        with: .color(p.skin)
    )
    m.fill(
        Path(ellipseIn: CGRect(x:  6.4, y: -60.6, width: 5.2, height: 5.2)),
        with: .color(p.skin)
    )

    // Head.
    var head = m
    head.translateBy(x: 0, y: -90)
    // Long flowing hair behind face.
    head.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -13, y: -16))
            pth.addQuadCurve(
                to:      CGPoint(x: -9, y: -30),
                control: CGPoint(x: -16, y: -28)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 9, y: -30),
                control: CGPoint(x: 0, y: -32)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 13, y: -16),
                control: CGPoint(x: 16, y: -28)
            )
            pth.addLine(to: CGPoint(x: 16, y: 6))
            pth.addQuadCurve(
                to:      CGPoint(x: 8, y: 6),
                control: CGPoint(x: 12, y: 10)
            )
            pth.addLine(to: CGPoint(x: 8, y: -8))
            pth.addQuadCurve(
                to:      CGPoint(x: -8, y: -8),
                control: CGPoint(x: 0, y: -8)
            )
            pth.addLine(to: CGPoint(x: -8, y: 6))
            pth.addQuadCurve(
                to:      CGPoint(x: -16, y: 6),
                control: CGPoint(x: -12, y: 10)
            )
            pth.closeSubpath()
        },
        with: .color(p.hairBrown)
    )
    drawHead(
        ctx: &head, p: p,
        skin: p.skin,
        radius: 13.5,
        eyeY:   -15,
        smileY: -10,
        blush:  true,
        bindi:  true
    )
}

// MARK: - Daughter

@MainActor
private func drawDaughter(
    ctx: inout GraphicsContext,
    p: LaunchPalette,
    t: Double,
    lookUp: Double,
    bob: Double
) {
    var d = ctx
    d.translateBy(x: 120, y: 720 + bob)

    // Shoes.
    d.fill(
        Path(ellipseIn: CGRect(x: -9, y: -2.5, width: 10, height: 5)),
        with: .color(p.hairDark)
    )
    d.fill(
        Path(ellipseIn: CGRect(x: -1, y: -2.5, width: 10, height: 5)),
        with: .color(p.hairDark)
    )
    // Lehenga skirt.
    d.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -10, y: -50))
            pth.addLine(to: CGPoint(x: -16, y:   0))
            pth.addLine(to: CGPoint(x:  16, y:   0))
            pth.addLine(to: CGPoint(x:  10, y: -50))
            pth.closeSubpath()
        },
        with: .color(p.daughterSkirt)
    )
    d.fill(
        Path(CGRect(x: -16, y: -2, width: 32, height: 2)),
        with: .color(p.gold)
    )
    // Mirror-work dots on lehenga.
    let mirrorPts: [(Double, Double)] = [
        (-7, -30), (0, -25), (7, -30), (-4, -15), (4, -15)
    ]
    for (mx, my) in mirrorPts {
        d.fill(
            Path(ellipseIn: CGRect(x: mx - 0.8, y: my - 0.8, width: 1.6, height: 1.6)),
            with: .color(p.gold)
        )
    }
    // Choli (cropped top).
    d.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -11, y: -75))
            pth.addQuadCurve(
                to:      CGPoint(x: -11, y: -55),
                control: CGPoint(x: -12, y: -65)
            )
            pth.addLine(to: CGPoint(x: -10, y: -50))
            pth.addLine(to: CGPoint(x:  10, y: -50))
            pth.addLine(to: CGPoint(x:  11, y: -55))
            pth.addQuadCurve(
                to:      CGPoint(x:  11, y: -75),
                control: CGPoint(x:  12, y: -65)
            )
            pth.addQuadCurve(
                to:      CGPoint(x:   0, y: -78),
                control: CGPoint(x:   8, y: -78)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -11, y: -75),
                control: CGPoint(x:  -8, y: -78)
            )
            pth.closeSubpath()
        },
        with: .color(p.daughterShirt)
    )
    // Arms holding notebook.
    d.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -11, y: -72))
            pth.addQuadCurve(
                to:      CGPoint(x: -12, y: -52),
                control: CGPoint(x: -15, y: -62)
            )
            pth.addLine(to: CGPoint(x: -8, y: -52))
            pth.addLine(to: CGPoint(x: -8, y: -62))
            pth.addLine(to: CGPoint(x: -7, y: -70))
            pth.closeSubpath()
        },
        with: .color(p.daughterShirt)
    )
    d.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 11, y: -72))
            pth.addQuadCurve(
                to:      CGPoint(x: 12, y: -52),
                control: CGPoint(x: 15, y: -62)
            )
            pth.addLine(to: CGPoint(x: 8, y: -52))
            pth.addLine(to: CGPoint(x: 8, y: -62))
            pth.addLine(to: CGPoint(x: 7, y: -70))
            pth.closeSubpath()
        },
        with: .color(p.daughterShirt)
    )
    // Notebook with leaf glyph.
    var nb = d
    nb.translateBy(x: -9, y: -62)
    nb.fill(
        Path(roundedRect: CGRect(x: 0, y: 0, width: 18, height: 13), cornerRadius: 2),
        with: .color(p.leafDark)
    )
    var nbStroke = nb
    nbStroke.opacity *= 0.8
    nbStroke.stroke(
        Path(roundedRect: CGRect(x: 2, y: 2, width: 14, height: 9), cornerRadius: 1),
        with: .color(p.leafHi),
        style: StrokeStyle(lineWidth: 0.6)
    )
    // Leaf glyph.
    nb.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 9, y: 6))
            pth.addQuadCurve(
                to:      CGPoint(x: 12.6, y: 6),
                control: CGPoint(x: 10.8, y: 4.4)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 9, y: 6),
                control: CGPoint(x: 10.8, y: 7.6)
            )
            pth.closeSubpath()
        },
        with: .color(p.leafHi)
    )

    // Head with look-up rotation.
    var head = d
    head.translateBy(x: 0, y: -78)
    head.rotate(by: .degrees(lookUp * 0.7))
    // Pigtail backdrop hair.
    head.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -11, y: -13))
            pth.addQuadCurve(
                to:      CGPoint(x: -7, y: -25),
                control: CGPoint(x: -13, y: -22)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 7, y: -25),
                control: CGPoint(x: 0, y: -26)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 11, y: -13),
                control: CGPoint(x: 13, y: -22)
            )
            pth.addLine(to: CGPoint(x: 14, y: -10))
            pth.addLine(to: CGPoint(x: 9,  y: -7))
            pth.addQuadCurve(
                to:      CGPoint(x: 6, y: -16),
                control: CGPoint(x: 6, y: -10)
            )
            pth.addLine(to: CGPoint(x: -6, y: -16))
            pth.addQuadCurve(
                to:      CGPoint(x: -9, y: -7),
                control: CGPoint(x: -6, y: -10)
            )
            pth.addLine(to: CGPoint(x: -14, y: -10))
            pth.closeSubpath()
        },
        with: .color(p.hairBrown)
    )
    head.fill(
        Path(ellipseIn: CGRect(x: -16, y: -13, width: 6, height: 8)),
        with: .color(p.hairBrown)
    )
    head.fill(
        Path(ellipseIn: CGRect(x:  10, y: -13, width: 6, height: 8)),
        with: .color(p.hairBrown)
    )
    // Ribbons (small diamonds).
    head.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -13, y: -13))
            pth.addLine(to: CGPoint(x: -16, y: -12))
            pth.addLine(to: CGPoint(x: -13, y: -10))
            pth.addLine(to: CGPoint(x: -10, y: -12))
            pth.closeSubpath()
        },
        with: .color(p.daughterSkirt)
    )
    head.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 13, y: -13))
            pth.addLine(to: CGPoint(x: 16, y: -12))
            pth.addLine(to: CGPoint(x: 13, y: -10))
            pth.addLine(to: CGPoint(x: 10, y: -12))
            pth.closeSubpath()
        },
        with: .color(p.daughterSkirt)
    )
    drawHead(
        ctx: &head, p: p,
        skin: p.skin,
        radius: 11,
        eyeY:   -12,
        smileY:  -8,
        blush:  true
    )
}

// MARK: - Son

@MainActor
private func drawSon(
    ctx: inout GraphicsContext,
    p: LaunchPalette,
    t: Double,
    lookUp: Double,
    bob: Double
) {
    var s = ctx
    s.translateBy(x: 165, y: 720 + bob)

    // Feet.
    s.fill(
        Path(ellipseIn: CGRect(x: -9, y: -2.5, width: 10, height: 5)),
        with: .color(p.hairDark)
    )
    s.fill(
        Path(ellipseIn: CGRect(x: -1, y: -2.5, width: 10, height: 5)),
        with: .color(p.hairDark)
    )
    // White pyjama legs.
    s.fill(
        Path(roundedRect: CGRect(x: -7, y: -22, width: 6, height: 22), cornerRadius: 2),
        with: .color(p.sonPants)
    )
    s.fill(
        Path(roundedRect: CGRect(x:  1, y: -22, width: 6, height: 22), cornerRadius: 2),
        with: .color(p.sonPants)
    )
    // Kurta tunic.
    s.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -12, y: -55))
            pth.addQuadCurve(
                to:      CGPoint(x: -12, y: -35),
                control: CGPoint(x: -13, y: -45)
            )
            pth.addLine(to: CGPoint(x: -11, y: -22))
            pth.addLine(to: CGPoint(x:  11, y: -22))
            pth.addLine(to: CGPoint(x:  12, y: -35))
            pth.addQuadCurve(
                to:      CGPoint(x:  12, y: -55),
                control: CGPoint(x:  13, y: -45)
            )
            pth.addQuadCurve(
                to:      CGPoint(x:   0, y: -58),
                control: CGPoint(x:   9, y: -58)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -12, y: -55),
                control: CGPoint(x:  -9, y: -58)
            )
            pth.closeSubpath()
        },
        with: .color(p.sonShirt)
    )
    // Gold trim.
    s.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: -11, y: -22))
            pth.addLine(to: CGPoint(x:  11, y: -22))
        },
        with: .color(p.gold),
        style: StrokeStyle(lineWidth: 0.8)
    )

    // Pointing arm — extends up and right toward the tree.
    s.fill(
        Path { pth in
            let lift = lookUp * 0.4
            pth.move(to: CGPoint(x: 12, y: -55))
            pth.addQuadCurve(
                to:      CGPoint(x: 24 + lift, y: -72 + lift),
                control: CGPoint(x: 18 + lift * 0.3, y: -65 + lift * 0.4)
            )
            pth.addLine(to: CGPoint(x: 27, y: -69))
            pth.addQuadCurve(
                to:      CGPoint(x: 12, y: -50),
                control: CGPoint(x: 21, y: -65)
            )
            pth.closeSubpath()
        },
        with: .color(p.sonShirt)
    )
    s.fill(
        Path(ellipseIn: CGRect(x: 23.8, y: -74.2, width: 4.4, height: 4.4)),
        with: .color(p.skin)
    )
    // Other arm — neutral down.
    s.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -12, y: -52))
            pth.addQuadCurve(
                to:      CGPoint(x: -13, y: -32),
                control: CGPoint(x: -16, y: -42)
            )
            pth.addLine(to: CGPoint(x: -10, y: -32))
            pth.addLine(to: CGPoint(x: -10, y: -42))
            pth.addLine(to: CGPoint(x:  -8, y: -50))
            pth.closeSubpath()
        },
        with: .color(p.sonShirt)
    )

    // Head — bowl-cut hair, look-up rotation.
    var head = s
    head.translateBy(x: 0, y: -58)
    head.rotate(by: .degrees(lookUp * 0.9))
    head.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -12, y: -14))
            pth.addQuadCurve(
                to:      CGPoint(x: -7, y: -27),
                control: CGPoint(x: -13, y: -25)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 7, y: -27),
                control: CGPoint(x: 0, y: -28)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 12, y: -14),
                control: CGPoint(x: 13, y: -25)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 7, y: -18),
                control: CGPoint(x: 11, y: -17)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 0, y: -19),
                control: CGPoint(x: 4, y: -20)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -7, y: -18),
                control: CGPoint(x: -4, y: -20)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -12, y: -14),
                control: CGPoint(x: -11, y: -17)
            )
            pth.closeSubpath()
        },
        with: .color(p.hairDark)
    )
    drawHead(
        ctx: &head, p: p,
        skin: p.skin,
        radius: 12,
        eyeY:   -13,
        smileY:  -8,
        blush:  true,
        bigSmile: true
    )
}

// MARK: - Father (with watering can)

@MainActor
private func drawFather(
    ctx: inout GraphicsContext,
    p: LaunchPalette,
    t: Double,
    fLean: Double,
    tilt: Double
) {
    var f = ctx
    f.translateBy(x: 225, y: 720)
    f.rotate(by: .degrees(fLean))

    // Feet.
    f.fill(
        Path(ellipseIn: CGRect(x: -14, y: -3.5, width: 14, height: 7)),
        with: .color(p.hairDark)
    )
    f.fill(
        Path(ellipseIn: CGRect(x:  0, y: -3.5, width: 14, height: 7)),
        with: .color(p.hairDark)
    )
    // Trousers.
    f.fill(
        Path(roundedRect: CGRect(x: -9, y: -32, width: 8, height: 32), cornerRadius: 2),
        with: .color(p.fatherPants)
    )
    f.fill(
        Path(roundedRect: CGRect(x:  1, y: -32, width: 8, height: 32), cornerRadius: 2),
        with: .color(p.fatherPants)
    )
    // Long saffron kurta.
    f.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -16, y: -88))
            pth.addQuadCurve(
                to:      CGPoint(x: -16, y: -60),
                control: CGPoint(x: -18, y: -75)
            )
            pth.addLine(to: CGPoint(x: -13, y: -45))
            pth.addQuadCurve(
                to:      CGPoint(x: -10, y: -32),
                control: CGPoint(x: -11, y: -36)
            )
            pth.addLine(to: CGPoint(x:  10, y: -32))
            pth.addQuadCurve(
                to:      CGPoint(x:  13, y: -45),
                control: CGPoint(x:  11, y: -36)
            )
            pth.addLine(to: CGPoint(x:  16, y: -60))
            pth.addQuadCurve(
                to:      CGPoint(x:  16, y: -88),
                control: CGPoint(x:  18, y: -75)
            )
            pth.addQuadCurve(
                to:      CGPoint(x:   0, y: -92),
                control: CGPoint(x:  12, y: -92)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -16, y: -88),
                control: CGPoint(x: -12, y: -92)
            )
            pth.closeSubpath()
        },
        with: .color(p.fatherShirt)
    )
    // Hem trim.
    f.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: -10, y: -32))
            pth.addLine(to: CGPoint(x:  10, y: -32))
        },
        with: .color(p.gold),
        style: StrokeStyle(lineWidth: 1)
    )
    // Center placket buttons.
    f.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: 0, y: -86))
            pth.addLine(to: CGPoint(x: 0, y: -50))
        },
        with: .color(p.gold),
        style: StrokeStyle(lineWidth: 0.6)
    )
    for by in [-78.0, -72, -66, -60] {
        f.fill(
            Path(ellipseIn: CGRect(x: -0.7, y: by - 0.7, width: 1.4, height: 1.4)),
            with: .color(p.gold)
        )
    }
    // Mandarin collar.
    var collar = f
    collar.opacity *= 0.85
    collar.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -5, y: -88))
            pth.addQuadCurve(
                to:      CGPoint(x:  0, y: -84),
                control: CGPoint(x: -3, y: -84)
            )
            pth.addQuadCurve(
                to:      CGPoint(x:  5, y: -88),
                control: CGPoint(x:  3, y: -84)
            )
            pth.addLine(to: CGPoint(x: 0, y: -90))
            pth.closeSubpath()
        },
        with: .color(p.gold)
    )
    // Back arm — reaching forward to grip can.
    f.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -16, y: -84))
            pth.addQuadCurve(
                to:      CGPoint(x: 8, y: -72),
                control: CGPoint(x: -8, y: -78)
            )
            pth.addLine(to: CGPoint(x: 18, y: -68))
            pth.addQuadCurve(
                to:      CGPoint(x: 22, y: -62),
                control: CGPoint(x: 22, y: -66)
            )
            pth.addLine(to: CGPoint(x: 14, y: -60))
            pth.addQuadCurve(
                to:      CGPoint(x: -10, y: -72),
                control: CGPoint(x: 0,    y: -64)
            )
            pth.closeSubpath()
        },
        with: .color(p.fatherShirt)
    )
    f.fill(
        Path(ellipseIn: CGRect(x: 17, y: -69, width: 6, height: 6)),
        with: .color(p.skin)
    )

    // Head.
    var head = f
    head.translateBy(x: 0, y: -92)
    head.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -14, y: -17))
            pth.addQuadCurve(
                to:      CGPoint(x: -8, y: -31),
                control: CGPoint(x: -15, y: -29)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 8, y: -31),
                control: CGPoint(x: 0, y: -32)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 14, y: -17),
                control: CGPoint(x: 15, y: -29)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: 8, y: -21),
                control: CGPoint(x: 14, y: -20)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -8, y: -21),
                control: CGPoint(x: 0, y: -22)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -14, y: -17),
                control: CGPoint(x: -14, y: -20)
            )
            pth.closeSubpath()
        },
        with: .color(p.hairDark)
    )
    drawHead(
        ctx: &head, p: p,
        skin: p.skin,
        radius: 14.5,
        eyeY:   -16,
        smileY: -10,
        blush:  false
    )

    // Front arm — stretches right, holds can handle.
    f.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 14, y: -86))
            pth.addQuadCurve(
                to:      CGPoint(x: 28, y: -68),
                control: CGPoint(x: 22, y: -80)
            )
            pth.addLine(to: CGPoint(x: 30, y: -62))
            pth.addQuadCurve(
                to:      CGPoint(x: 14, y: -78),
                control: CGPoint(x: 22, y: -74)
            )
            pth.closeSubpath()
        },
        with: .color(p.fatherShirt)
    )
    f.fill(
        Path(ellipseIn: CGRect(x: 25, y: -68, width: 6, height: 6)),
        with: .color(p.skin)
    )

    // WATERING CAN — pivot at hand (28, -65), tilt rotation.
    var can = f
    can.translateBy(x: 28, y: -65)
    can.rotate(by: .degrees(tilt))
    // Body — handle on left, spout on right.
    can.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -18, y: -10))
            pth.addQuadCurve(
                to:      CGPoint(x: -20, y: -8),
                control: CGPoint(x: -20, y: -10)
            )
            pth.addLine(to: CGPoint(x: -20, y: 8))
            pth.addQuadCurve(
                to:      CGPoint(x: -18, y: 10),
                control: CGPoint(x: -20, y: 10)
            )
            pth.addLine(to: CGPoint(x: 4, y: 10))
            pth.addQuadCurve(
                to:      CGPoint(x: 6, y: 8),
                control: CGPoint(x: 6, y: 10)
            )
            pth.addLine(to: CGPoint(x: 6, y: -8))
            pth.addQuadCurve(
                to:      CGPoint(x: 4, y: -10),
                control: CGPoint(x: 6, y: -10)
            )
            pth.closeSubpath()
        },
        with: .color(p.watercan)
    )
    var hi = can
    hi.opacity *= 0.65
    hi.fill(
        Path(CGRect(x: -17, y: -9, width: 2.5, height: 18)),
        with: .color(p.watercanHi)
    )
    var sh = can
    sh.opacity *= 0.6
    sh.fill(
        Path(CGRect(x: 2, y: -9, width: 3, height: 18)),
        with: .color(p.watercanShade)
    )
    var rim = can
    rim.opacity *= 0.7
    rim.fill(
        Path(ellipseIn: CGRect(x: -20, y: -12, width: 26, height: 4)),
        with: .color(p.watercanShade)
    )
    // Handle — arching up-left.
    can.stroke(
        Path { pth in
            pth.move(to: CGPoint(x: -18, y: -8))
            pth.addQuadCurve(
                to:      CGPoint(x: -26, y: 6),
                control: CGPoint(x: -28, y: -4)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -19, y: 10),
                control: CGPoint(x: -24, y: 10)
            )
        },
        with: .color(p.watercan),
        style: StrokeStyle(lineWidth: 3, lineCap: .round)
    )
    // Spout.
    can.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 6, y: -2))
            pth.addLine(to: CGPoint(x: 22, y: -10))
            pth.addQuadCurve(
                to:      CGPoint(x: 25, y: -7),
                control: CGPoint(x: 25, y: -10)
            )
            pth.addLine(to: CGPoint(x: 9, y: 5))
            pth.closeSubpath()
        },
        with: .color(p.watercan)
    )
    var spoutHi = can
    spoutHi.opacity *= 0.5
    spoutHi.fill(
        Path { pth in
            pth.move(to: CGPoint(x: 6, y: -2))
            pth.addLine(to: CGPoint(x: 22, y: -10))
            pth.addLine(to: CGPoint(x: 22, y: -8))
            pth.addLine(to: CGPoint(x: 9, y: 0))
            pth.closeSubpath()
        },
        with: .color(p.watercanHi)
    )
    // Shower head.
    can.fill(
        Path(ellipseIn: CGRect(x: 21.5, y: -11, width: 7, height: 6)),
        with: .color(p.watercan)
    )
    var headShade = can
    headShade.opacity *= 0.5
    headShade.fill(
        Path(ellipseIn: CGRect(x: 21.5, y: -11, width: 7, height: 6)),
        with: .color(p.watercanShade)
    )
    for (sx, sy) in [(27.0, -9.0), (27, -7), (26, -8.5), (26, -6.8)] {
        can.fill(
            Path(ellipseIn: CGRect(x: sx - 0.5, y: sy - 0.5, width: 1, height: 1)),
            with: .color(p.watercanShade)
        )
    }
    var leafDeco = can
    leafDeco.opacity *= 0.7
    leafDeco.fill(
        Path { pth in
            pth.move(to: CGPoint(x: -11, y: -3))
            pth.addQuadCurve(
                to:      CGPoint(x: -5, y: -3),
                control: CGPoint(x: -7, y: -5)
            )
            pth.addQuadCurve(
                to:      CGPoint(x: -11, y: -3),
                control: CGPoint(x: -8, y: 0)
            )
            pth.closeSubpath()
        },
        with: .color(p.leafLight)
    )
}
