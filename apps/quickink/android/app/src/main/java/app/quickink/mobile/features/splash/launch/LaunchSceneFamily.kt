/*
 * LaunchSceneFamily.kt
 *
 * The four family members from the launch animation — mother (x=70,
 * holding a sapling in a terracotta pot), daughter (x=120, holding a
 * notebook with the QuickInk leaf glyph), son (x=165, pointing up at
 * the tree), and father (x=225, leaning forward to tip the watering
 * can right toward the tree).
 *
 * Direct port of the `Family` component in
 * `design_handoff_quickink_launch/source/scene.jsx`. We retain the
 * source's per-character SVG path geometry (clothing silhouettes,
 * heads, eye + smile markers) at moderate fidelity. A handful of
 * single-pixel decorations (eye-shine sub-pixel circles, hairline
 * pleat strokes) are dropped — they wouldn't survive screen-density
 * downscaling anyway and would land as wasted draw calls without any
 * visible payoff.
 *
 * Drawn by [drawFamily], called from `LaunchScene` after the tree and
 * before the water stream so the water reads as pouring over the
 * father's hand and into the soil. The watering can itself is drawn
 * inside `drawFather`; the nozzle world-space math used by the water
 * stream lives in `LaunchScene.kt`'s [nozzleAt] and must stay in sync
 * with the transform stack here (father pivot 225,720 → fLean
 * rotation → can pivot 28,-65 → tilt rotation → nozzle 25,-8).
 *
 * Counterpart: iOS `LaunchSceneFamily.swift`.
 */

package app.quickink.mobile.features.splash.launch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.sin

internal fun DrawScope.drawFamily(p: LaunchPalette, t: Double) {
    val op = ease(LaunchEasing::easeOutCubic, between(t, 1.2, 1.9)).toFloat()
    if (op <= 0.01f) return

    val fLean = (4.0  * ease(LaunchEasing::easeOutCubic, between(t, 1.4, 1.85))).toFloat()
    val tilt  = (28.0 * ease(LaunchEasing::easeOutBack,  between(t, 1.4, 1.85))).toFloat()
    val lookUp = (-10.0 * ease(LaunchEasing::easeOutCubic, between(t, 2.3, 3.6))).toFloat()
    val sonBob = (-1.6 * abs(sin(t * 4)) *
                  ease(LaunchEasing::easeOutCubic, between(t, 2.5, 3.8))).toFloat()
    val motherSway = (sin(t * 1.4) * 1.2).toFloat()

    drawMother  (p, motherSway, op, t)
    drawDaughter(p, lookUp, sonBob * 0.5f, op, t)
    drawSon     (p, lookUp, sonBob, op, t)
    drawFather  (p, fLean, tilt, op, t)
}

// MARK: ─── Shared head helpers ─────────────────────────────────────────

/**
 * Skin face circle. Drawn BEFORE the character's hair shape so the
 * hair sits on top of the face (matches the JSX SVG draw order —
 * face circle at z=0, hair on top, eyes / features above hair).
 * [eyeY] is the centre of the eyes; the face circle is centred on
 * eyeY so chin and forehead room are symmetrical.
 */
private fun DrawScope.drawFaceCircle(
    skin: Color,
    radius: Float,
    eyeY: Float,
    parentAlpha: Float,
) {
    drawCircle(
        color = skin,
        radius = radius,
        center = Offset(0f, eyeY),
        alpha = parentAlpha,
    )
}

/**
 * Face features (eyes, brows, smile, optional blush / bindi /
 * mustache hint) drawn on TOP of the hair so the eyes always read
 * clearly even where the hair shape would otherwise overlap. Pulled
 * out of the monolithic `drawHead` so each character can interleave
 * its own hair shape between [drawFaceCircle] and this call without
 * losing the eye / smile pass.
 */
private fun DrawScope.drawFaceFeatures(
    p: LaunchPalette,
    radius: Float,
    eyeY: Float,
    smileY: Float,
    blush: Boolean,
    bigSmile: Boolean = false,
    bindi: Boolean = false,
    time: Double,
    blinkAt: Double,
    parentAlpha: Float,
) {
    // Eye height squeezed by a per-character blink so the four
    // family members don't all blink in unison.
    val blink = blinkScale(time, at = blinkAt).toFloat()
    val eyeRX = radius * 0.18f
    val eyeRY = radius * 0.2f * blink
    drawOval(
        color = Color.White,
        topLeft = Offset(-radius * 0.4f - eyeRX, eyeY - eyeRY),
        size = Size(eyeRX * 2f, eyeRY * 2f),
        alpha = parentAlpha,
    )
    drawOval(
        color = Color.White,
        topLeft = Offset(radius * 0.4f - eyeRX, eyeY - eyeRY),
        size = Size(eyeRX * 2f, eyeRY * 2f),
        alpha = parentAlpha,
    )
    val pupil = radius * 0.11f * kotlin.math.max(blink, 0.1f)
    drawCircle(
        color = p.hairBrown,
        radius = pupil,
        center = Offset(-radius * 0.4f + pupil * 0.15f, eyeY + pupil * 0.5f),
        alpha = parentAlpha,
    )
    drawCircle(
        color = p.hairBrown,
        radius = pupil,
        center = Offset(radius * 0.4f + pupil * 0.4f, eyeY + pupil * 0.5f),
        alpha = parentAlpha,
    )
    // Eye shine — small white highlight off-centre on each pupil.
    if (blink > 0.5f) {
        val shineR = pupil * 0.32f
        drawCircle(
            color = Color.White,
            radius = shineR,
            center = Offset(-radius * 0.4f - pupil * 0.2f, eyeY - pupil * 0.2f),
            alpha = parentAlpha,
        )
        drawCircle(
            color = Color.White,
            radius = shineR,
            center = Offset(radius * 0.4f + pupil * 0.05f, eyeY - pupil * 0.2f),
            alpha = parentAlpha,
        )
    }

    val browL = Path().apply {
        moveTo(-radius * 0.7f, eyeY - radius * 0.4f)
        quadraticTo(
            -radius * 0.4f, eyeY - radius * 0.5f,
            -radius * 0.15f, eyeY - radius * 0.4f
        )
    }
    val browR = Path().apply {
        moveTo(radius * 0.15f, eyeY - radius * 0.4f)
        quadraticTo(
            radius * 0.4f,  eyeY - radius * 0.5f,
            radius * 0.7f,  eyeY - radius * 0.4f
        )
    }
    drawPath(browL, p.hairBrown, alpha = parentAlpha,
             style = Stroke(width = 0.9f, cap = StrokeCap.Round))
    drawPath(browR, p.hairBrown, alpha = parentAlpha,
             style = Stroke(width = 0.9f, cap = StrokeCap.Round))

    if (bigSmile) {
        val smile = Path().apply {
            moveTo(-radius * 0.3f, smileY)
            quadraticTo(0f, smileY + 3f, radius * 0.3f, smileY)
            quadraticTo(0f, smileY + 1f, -radius * 0.3f, smileY)
            close()
        }
        drawPath(smile, Color(0xFF5A3020), alpha = parentAlpha)
    } else {
        val smile = Path().apply {
            moveTo(-radius * 0.3f, smileY)
            quadraticTo(0f, smileY + 3f, radius * 0.3f, smileY)
        }
        drawPath(smile, Color(0xFF5A3020),
                 alpha = parentAlpha,
                 style = Stroke(width = 0.9f, cap = StrokeCap.Round))
        // Lip line — thinner secondary arc just under the smile.
        val lip = Path().apply {
            moveTo(-radius * 0.2f, smileY + 1f)
            quadraticTo(0f, smileY + 2f, radius * 0.2f, smileY + 1f)
        }
        drawPath(lip, Color(0xFFA04050),
                 alpha = parentAlpha,
                 style = Stroke(width = 0.7f, cap = StrokeCap.Round))
    }

    if (bindi) {
        drawCircle(
            color = Color(0xFFC4283A),
            radius = 1.4f,
            center = Offset(0f, eyeY - radius * 0.7f),
            alpha = parentAlpha,
        )
    }

    if (blush) {
        val a = parentAlpha * 0.55f
        drawOval(
            color = Color(0xFFF0A890),
            topLeft = Offset(-radius * 0.85f, eyeY + radius * 0.25f),
            size = Size(4f, 4f),
            alpha = a,
        )
        drawOval(
            color = Color(0xFFF0A890),
            topLeft = Offset(radius * 0.65f, eyeY + radius * 0.25f),
            size = Size(4f, 4f),
            alpha = a,
        )
    }
}

// MARK: ─── Mother ──────────────────────────────────────────────────────

private fun DrawScope.drawMother(
    p: LaunchPalette, sway: Float, parentAlpha: Float, time: Double,
) {
    withTransform({
        translate(70f, 720f)
        rotate(degrees = sway * 0.3f, pivot = Offset.Zero)
    }) {
        drawOval(p.hairDark, topLeft = Offset(-11f, -3f),
                 size = Size(12f, 6f), alpha = parentAlpha)
        drawOval(p.hairDark, topLeft = Offset(-1f, -3f),
                 size = Size(12f, 6f), alpha = parentAlpha)

        val skirt = Path().apply {
            moveTo(-16f, -50f); lineTo(-22f, 0f)
            lineTo( 22f,   0f); lineTo( 16f, -50f); close()
        }
        drawPath(skirt, p.motherSkirt, alpha = parentAlpha)
        drawRect(p.gold,
                 topLeft = Offset(-22f, -3f),
                 size    = Size(44f, 3f),
                 alpha   = parentAlpha)
        val choliBlock = Path().apply {
            moveTo(-14f, -50f); lineTo(-16f, -30f)
            lineTo( 16f, -30f); lineTo( 14f, -50f); close()
        }
        drawPath(choliBlock, p.motherShirt, alpha = parentAlpha)
        val torso = Path().apply {
            moveTo(-14f, -86f)
            quadraticTo(-16f, -75f, -15f, -62f)
            lineTo(-14f, -50f); lineTo( 14f, -50f); lineTo( 15f, -62f)
            quadraticTo(16f, -75f, 14f, -86f)
            quadraticTo(10f, -90f,  0f, -90f)
            quadraticTo(-10f, -90f, -14f, -86f); close()
        }
        drawPath(torso, p.motherShirt, alpha = parentAlpha)

        val drape = Path().apply {
            moveTo(-14f, -84f)
            quadraticTo(-22f, -70f, -20f, -50f)
            lineTo(-16f, -50f); lineTo(-16f, -68f)
            quadraticTo(-14f, -78f, -10f, -84f); close()
        }
        drawPath(drape, p.gold, alpha = parentAlpha * 0.85f)

        val necklace = Path().apply {
            moveTo(-8f, -86f); quadraticTo(0f, -82f, 8f, -86f)
        }
        drawPath(necklace, p.gold,
                 alpha = parentAlpha,
                 style = Stroke(width = 1f))
        drawCircle(p.gold, radius = 1.1f,
                   center = Offset(0f, -78f), alpha = parentAlpha)

        // Arms.
        val leftArm = Path().apply {
            moveTo(-14f, -82f)
            quadraticTo(-19f, -70f, -16f, -58f)
            lineTo(-10f, -52f)
            quadraticTo(-7f, -52f, -6f, -56f)
            lineTo(-10f, -68f); close()
        }
        val rightArm = Path().apply {
            moveTo(14f, -82f)
            quadraticTo(18f, -76f, 16f, -68f)
            lineTo(12f, -58f)
            quadraticTo(8f, -56f, 6f, -60f)
            lineTo(8f, -72f); close()
        }
        drawPath(leftArm,  p.motherShirt, alpha = parentAlpha)
        drawPath(rightArm, p.motherShirt, alpha = parentAlpha)

        // Sapling in pot.
        withTransform({ translate(0f, -60f) }) {
            val pot = Path().apply {
                moveTo(-7f, 0f); lineTo(-5.5f, 6f)
                lineTo( 5.5f, 6f); lineTo( 7f, 0f); close()
            }
            drawPath(pot, Color(0xFFC46A3A), alpha = parentAlpha)
            val rim = Path().apply {
                moveTo(-7.5f, -1f); lineTo( 7.5f, -1f)
                lineTo( 7f,    1f); lineTo(-7f,   1f); close()
            }
            drawPath(rim, Color(0xFF9A4A22), alpha = parentAlpha)
            drawOval(
                color = Color(0xFF3A2418),
                topLeft = Offset(-6.5f, -2f),
                size = Size(13f, 2f),
                alpha = parentAlpha
            )
            val trunk = Path().apply {
                moveTo(-0.6f, -1f); lineTo(-0.4f, -10f)
                lineTo( 0.4f,-10f); lineTo( 0.6f, -1f); close()
            }
            drawPath(trunk, p.bark, alpha = parentAlpha)
            drawOval(p.leaf, topLeft = Offset(-2f - 2.4f, -12.5f - 2.8f),
                     size = Size(4.8f, 5.6f), alpha = parentAlpha)
            drawOval(p.leaf, topLeft = Offset( 2f - 2.4f, -12.5f - 2.8f),
                     size = Size(4.8f, 5.6f), alpha = parentAlpha)
            drawOval(p.leafLight, topLeft = Offset(-2.3f, -15f - 3f),
                     size = Size(4.6f, 6f), alpha = parentAlpha)
            drawOval(p.leafDark, topLeft = Offset(-3.5f - 2.4f, -9f - 3f),
                     size = Size(4.8f, 6f), alpha = parentAlpha)
            drawOval(p.leafDark, topLeft = Offset( 3.5f - 2.4f, -9f - 3f),
                     size = Size(4.8f, 6f), alpha = parentAlpha)
        }

        // Hands gripping pot.
        drawCircle(p.skin, radius = 2.6f, center = Offset(-9f, -58f), alpha = parentAlpha)
        drawCircle(p.skin, radius = 2.6f, center = Offset( 9f, -58f), alpha = parentAlpha)

        // Head.
        withTransform({ translate(0f, -90f) }) {
            // Skin face circle first — hair frames the face on top.
            drawFaceCircle(p.skin, radius = 13.5f, eyeY = -15f,
                           parentAlpha = parentAlpha)
            val hair = Path().apply {
                moveTo(-13f, -16f)
                quadraticTo(-16f, -28f, -9f, -30f)
                quadraticTo(0f, -32f, 9f, -30f)
                quadraticTo(16f, -28f, 13f, -16f)
                lineTo(16f, 6f)
                quadraticTo(12f, 10f, 8f, 6f)
                lineTo(8f, -8f)
                quadraticTo(0f, -8f, -8f, -8f)
                lineTo(-8f, 6f)
                quadraticTo(-12f, 10f, -16f, 6f)
                close()
            }
            drawPath(hair, p.hairBrown, alpha = parentAlpha)
            // Hair flow strands — thin lighter-tone strokes give the
            // hair texture rather than reading as a flat brown helmet.
            val hi = p.hairLight
            val flow1 = Path().apply {
                moveTo(-10f, -25f); quadraticTo(-14f, -10f, -13f, 0f)
            }
            drawPath(flow1, hi, alpha = parentAlpha,
                     style = Stroke(width = 0.7f, cap = StrokeCap.Round))
            val flow2 = Path().apply {
                moveTo(-6f, -28f); quadraticTo(-10f, -10f, -10f, 4f)
            }
            drawPath(flow2, hi, alpha = parentAlpha,
                     style = Stroke(width = 0.6f, cap = StrokeCap.Round))
            val flow3 = Path().apply {
                moveTo(10f, -25f); quadraticTo(14f, -10f, 13f, 0f)
            }
            drawPath(flow3, hi, alpha = parentAlpha,
                     style = Stroke(width = 0.7f, cap = StrokeCap.Round))
            val flow4 = Path().apply {
                moveTo(6f, -28f); quadraticTo(10f, -10f, 10f, 4f)
            }
            drawPath(flow4, hi, alpha = parentAlpha,
                     style = Stroke(width = 0.6f, cap = StrokeCap.Round))
            // Centre parting.
            drawLine(
                color = hi,
                start = Offset(0f, -29f),
                end   = Offset(0f, -22f),
                strokeWidth = 0.5f,
                cap = StrokeCap.Round,
                alpha = parentAlpha,
            )
            drawFaceFeatures(
                p, radius = 13.5f, eyeY = -15f, smileY = -10f,
                blush = true, bindi = true,
                time = time, blinkAt = 1.6,
                parentAlpha = parentAlpha,
            )
        }
    }
}

// MARK: ─── Daughter ────────────────────────────────────────────────────

private fun DrawScope.drawDaughter(
    p: LaunchPalette, lookUp: Float, bob: Float, parentAlpha: Float, time: Double,
) {
    withTransform({ translate(120f, 720f + bob) }) {
        drawOval(p.hairDark, topLeft = Offset(-9f, -2.5f),
                 size = Size(10f, 5f), alpha = parentAlpha)
        drawOval(p.hairDark, topLeft = Offset(-1f, -2.5f),
                 size = Size(10f, 5f), alpha = parentAlpha)

        val skirt = Path().apply {
            moveTo(-10f, -50f); lineTo(-16f,  0f)
            lineTo( 16f,   0f); lineTo( 10f, -50f); close()
        }
        drawPath(skirt, p.daughterSkirt, alpha = parentAlpha)
        drawRect(p.gold,
                 topLeft = Offset(-16f, -2f),
                 size    = Size(32f, 2f),
                 alpha   = parentAlpha)
        for ((mx, my) in listOf(-7f to -30f, 0f to -25f, 7f to -30f,
                                -4f to -15f, 4f to -15f)) {
            drawCircle(p.gold, radius = 0.8f,
                       center = Offset(mx, my), alpha = parentAlpha)
        }
        val choli = Path().apply {
            moveTo(-11f, -75f)
            quadraticTo(-12f, -65f, -11f, -55f)
            lineTo(-10f, -50f); lineTo(10f, -50f); lineTo(11f, -55f)
            quadraticTo(12f, -65f, 11f, -75f)
            quadraticTo(8f, -78f, 0f, -78f)
            quadraticTo(-8f, -78f, -11f, -75f); close()
        }
        drawPath(choli, p.daughterShirt, alpha = parentAlpha)

        val leftArm = Path().apply {
            moveTo(-11f, -72f)
            quadraticTo(-15f, -62f, -12f, -52f)
            lineTo(-8f, -52f); lineTo(-8f, -62f); lineTo(-7f, -70f); close()
        }
        val rightArm = Path().apply {
            moveTo(11f, -72f)
            quadraticTo(15f, -62f, 12f, -52f)
            lineTo(8f, -52f); lineTo(8f, -62f); lineTo(7f, -70f); close()
        }
        drawPath(leftArm,  p.daughterShirt, alpha = parentAlpha)
        drawPath(rightArm, p.daughterShirt, alpha = parentAlpha)

        // Notebook.
        withTransform({ translate(-9f, -62f) }) {
            drawRoundRect(
                color = p.leafDark,
                topLeft = Offset(0f, 0f),
                size = Size(18f, 13f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
                alpha = parentAlpha
            )
            drawRoundRect(
                color = p.leafHi,
                topLeft = Offset(2f, 2f),
                size = Size(14f, 9f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f),
                alpha = parentAlpha * 0.8f,
                style = Stroke(width = 0.6f)
            )
            val leaf = Path().apply {
                moveTo(9f, 6f)
                quadraticTo(10.8f, 4.4f, 12.6f, 6f)
                quadraticTo(10.8f, 7.6f, 9f, 6f)
                close()
            }
            drawPath(leaf, p.leafHi, alpha = parentAlpha)
        }

        // Head.
        withTransform({
            translate(0f, -78f)
            rotate(degrees = lookUp * 0.7f, pivot = Offset.Zero)
        }) {
            // Skin face circle first — pigtails sit on top.
            drawFaceCircle(p.skin, radius = 11f, eyeY = -12f,
                           parentAlpha = parentAlpha)
            val hair = Path().apply {
                moveTo(-11f, -13f)
                quadraticTo(-13f, -22f, -7f, -25f)
                quadraticTo(0f, -26f, 7f, -25f)
                quadraticTo(13f, -22f, 11f, -13f)
                lineTo(14f, -10f); lineTo(9f, -7f)
                quadraticTo(6f, -10f, 6f, -16f)
                lineTo(-6f, -16f)
                quadraticTo(-6f, -10f, -9f, -7f)
                lineTo(-14f, -10f); close()
            }
            drawPath(hair, p.hairBrown, alpha = parentAlpha)
            drawOval(p.hairBrown, topLeft = Offset(-16f, -13f),
                     size = Size(6f, 8f), alpha = parentAlpha)
            drawOval(p.hairBrown, topLeft = Offset( 10f, -13f),
                     size = Size(6f, 8f), alpha = parentAlpha)
            // Pigtail braid texture — three short stripes per
            // pigtail bun give the kid-style "twisted bunch" feel.
            val dHi = p.hairLight
            for (offsetY in listOf(-11f, -9f, -7f)) {
                val l = Path().apply {
                    moveTo(-16f, offsetY)
                    quadraticTo(-13f, offsetY - 0.6f, -10f, offsetY)
                }
                drawPath(l, dHi, alpha = parentAlpha,
                         style = Stroke(width = 0.5f, cap = StrokeCap.Round))
                val r = Path().apply {
                    moveTo(10f, offsetY)
                    quadraticTo(13f, offsetY - 0.6f, 16f, offsetY)
                }
                drawPath(r, dHi, alpha = parentAlpha,
                         style = Stroke(width = 0.5f, cap = StrokeCap.Round))
            }
            // Top bangs across the forehead.
            for (x in listOf(-5f, -2f, 1f, 4f)) {
                drawLine(
                    color = p.hairBrown,
                    start = Offset(x, -22f),
                    end   = Offset(x + 0.5f, -16f),
                    strokeWidth = 0.6f,
                    cap = StrokeCap.Round,
                    alpha = parentAlpha,
                )
            }
            // Ribbons.
            val ribL = Path().apply {
                moveTo(-13f, -13f); lineTo(-16f, -12f)
                lineTo(-13f, -10f); lineTo(-10f, -12f); close()
            }
            val ribR = Path().apply {
                moveTo(13f, -13f); lineTo(16f, -12f)
                lineTo(13f, -10f); lineTo(10f, -12f); close()
            }
            drawPath(ribL, p.daughterSkirt, alpha = parentAlpha)
            drawPath(ribR, p.daughterSkirt, alpha = parentAlpha)
            drawFaceFeatures(
                p, radius = 11f, eyeY = -12f, smileY = -8f,
                blush = true,
                time = time, blinkAt = 2.4,
                parentAlpha = parentAlpha,
            )
        }
    }
}

// MARK: ─── Son ─────────────────────────────────────────────────────────

private fun DrawScope.drawSon(
    p: LaunchPalette, lookUp: Float, bob: Float, parentAlpha: Float, time: Double,
) {
    withTransform({ translate(165f, 720f + bob) }) {
        drawOval(p.hairDark, topLeft = Offset(-9f, -2.5f),
                 size = Size(10f, 5f), alpha = parentAlpha)
        drawOval(p.hairDark, topLeft = Offset(-1f, -2.5f),
                 size = Size(10f, 5f), alpha = parentAlpha)
        drawRoundRect(
            color = p.sonPants,
            topLeft = Offset(-7f, -22f),
            size = Size(6f, 22f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            alpha = parentAlpha
        )
        drawRoundRect(
            color = p.sonPants,
            topLeft = Offset(1f, -22f),
            size = Size(6f, 22f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            alpha = parentAlpha
        )
        val kurta = Path().apply {
            moveTo(-12f, -55f)
            quadraticTo(-13f, -45f, -12f, -35f)
            lineTo(-11f, -22f); lineTo(11f, -22f); lineTo(12f, -35f)
            quadraticTo(13f, -45f, 12f, -55f)
            quadraticTo(9f, -58f, 0f, -58f)
            quadraticTo(-9f, -58f, -12f, -55f); close()
        }
        drawPath(kurta, p.sonShirt, alpha = parentAlpha)
        drawLine(
            color = p.gold,
            start = Offset(-11f, -22f), end = Offset(11f, -22f),
            strokeWidth = 0.8f, alpha = parentAlpha
        )

        // Pointing arm — extends up and right.
        val lift = lookUp * 0.4f
        val pointing = Path().apply {
            moveTo(12f, -55f)
            quadraticTo(
                18f + lift * 0.3f, -65f + lift * 0.4f,
                24f + lift,        -72f + lift
            )
            lineTo(27f, -69f)
            quadraticTo(21f, -65f, 12f, -50f); close()
        }
        drawPath(pointing, p.sonShirt, alpha = parentAlpha)
        drawCircle(p.skin, radius = 2.2f,
                   center = Offset(26f, -72f), alpha = parentAlpha)

        // Other arm.
        val other = Path().apply {
            moveTo(-12f, -52f)
            quadraticTo(-16f, -42f, -13f, -32f)
            lineTo(-10f, -32f); lineTo(-10f, -42f); lineTo(-8f, -50f); close()
        }
        drawPath(other, p.sonShirt, alpha = parentAlpha)

        withTransform({
            translate(0f, -58f)
            rotate(degrees = lookUp * 0.9f, pivot = Offset.Zero)
        }) {
            // Skin face circle first — bowl-cut sits on top.
            drawFaceCircle(p.skin, radius = 12f, eyeY = -13f,
                           parentAlpha = parentAlpha)
            val hair = Path().apply {
                moveTo(-12f, -14f)
                quadraticTo(-13f, -25f, -7f, -27f)
                quadraticTo(0f, -28f, 7f, -27f)
                quadraticTo(13f, -25f, 12f, -14f)
                quadraticTo(11f, -17f, 7f, -18f)
                quadraticTo(4f, -20f, 0f, -19f)
                quadraticTo(-4f, -20f, -7f, -18f)
                quadraticTo(-11f, -17f, -12f, -14f)
                close()
            }
            drawPath(hair, p.hairDark, alpha = parentAlpha)
            // Spiky tufts on top — three short upward triangles give
            // the bowl-cut a "messy boy" feel.
            val tuft1 = Path().apply {
                moveTo(-3f, -25f); lineTo(-1f, -30f); lineTo(1f, -27f); close()
            }
            val tuft2 = Path().apply {
                moveTo(1f, -27f); lineTo(3f, -31f); lineTo(5f, -27f); close()
            }
            val tuft3 = Path().apply {
                moveTo(-6f, -26f); lineTo(-4f, -29f); lineTo(-2f, -26f); close()
            }
            drawPath(tuft1, p.hairDark, alpha = parentAlpha)
            drawPath(tuft2, p.hairDark, alpha = parentAlpha)
            drawPath(tuft3, p.hairDark, alpha = parentAlpha)
            drawFaceFeatures(
                p, radius = 12f, eyeY = -13f, smileY = -8f,
                blush = true, bigSmile = true,
                time = time, blinkAt = 3.0,
                parentAlpha = parentAlpha,
            )
        }
    }
}

// MARK: ─── Father (with watering can) ──────────────────────────────────

private fun DrawScope.drawFather(
    p: LaunchPalette, fLean: Float, tilt: Float, parentAlpha: Float, time: Double,
) {
    withTransform({
        translate(225f, 720f)
        rotate(degrees = fLean, pivot = Offset.Zero)
    }) {
        drawOval(p.hairDark, topLeft = Offset(-14f, -3.5f),
                 size = Size(14f, 7f), alpha = parentAlpha)
        drawOval(p.hairDark, topLeft = Offset(0f, -3.5f),
                 size = Size(14f, 7f), alpha = parentAlpha)
        drawRoundRect(
            color = p.fatherPants,
            topLeft = Offset(-9f, -32f), size = Size(8f, 32f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            alpha = parentAlpha
        )
        drawRoundRect(
            color = p.fatherPants,
            topLeft = Offset(1f, -32f), size = Size(8f, 32f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
            alpha = parentAlpha
        )

        val kurta = Path().apply {
            moveTo(-16f, -88f)
            quadraticTo(-18f, -75f, -16f, -60f)
            lineTo(-13f, -45f)
            quadraticTo(-11f, -36f, -10f, -32f)
            lineTo(10f, -32f)
            quadraticTo(11f, -36f, 13f, -45f)
            lineTo(16f, -60f)
            quadraticTo(18f, -75f, 16f, -88f)
            quadraticTo(12f, -92f, 0f, -92f)
            quadraticTo(-12f, -92f, -16f, -88f)
            close()
        }
        drawPath(kurta, p.fatherShirt, alpha = parentAlpha)
        drawLine(
            color = p.gold,
            start = Offset(-10f, -32f), end = Offset(10f, -32f),
            strokeWidth = 1f, alpha = parentAlpha
        )
        drawLine(
            color = p.gold,
            start = Offset(0f, -86f), end = Offset(0f, -50f),
            strokeWidth = 0.6f, alpha = parentAlpha
        )
        for (by in listOf(-78f, -72f, -66f, -60f)) {
            drawCircle(p.gold, radius = 0.7f,
                       center = Offset(0f, by), alpha = parentAlpha)
        }
        val collar = Path().apply {
            moveTo(-5f, -88f)
            quadraticTo(-3f, -84f, 0f, -84f)
            quadraticTo(3f, -84f, 5f, -88f)
            lineTo(0f, -90f); close()
        }
        drawPath(collar, p.gold, alpha = parentAlpha * 0.85f)

        // Back arm — reaches forward.
        val backArm = Path().apply {
            moveTo(-16f, -84f)
            quadraticTo(-8f, -78f, 8f, -72f)
            lineTo(18f, -68f)
            quadraticTo(22f, -66f, 22f, -62f)
            lineTo(14f, -60f)
            quadraticTo(0f, -64f, -10f, -72f); close()
        }
        drawPath(backArm, p.fatherShirt, alpha = parentAlpha)
        drawCircle(p.skin, radius = 3f,
                   center = Offset(20f, -66f), alpha = parentAlpha)

        // Head.
        withTransform({ translate(0f, -92f) }) {
            // Skin face circle first — hair cap sits on top.
            drawFaceCircle(p.skin, radius = 14.5f, eyeY = -16f,
                           parentAlpha = parentAlpha)
            val hair = Path().apply {
                moveTo(-14f, -17f)
                quadraticTo(-15f, -29f, -8f, -31f)
                quadraticTo(0f, -32f, 8f, -31f)
                quadraticTo(15f, -29f, 14f, -17f)
                quadraticTo(14f, -20f, 8f, -21f)
                quadraticTo(0f, -22f, -8f, -21f)
                quadraticTo(-14f, -20f, -14f, -17f)
                close()
            }
            drawPath(hair, p.hairDark, alpha = parentAlpha)
            // Forehead hairline + side wisps for texture.
            for (x in listOf(-10f, -7f, -4f, -1f, 2f, 5f, 8f, 11f)) {
                drawLine(
                    color = p.hairBrown,
                    start = Offset(x, -27f),
                    end   = Offset(x + 0.4f, -22f),
                    strokeWidth = 0.5f,
                    cap = StrokeCap.Round,
                    alpha = parentAlpha,
                )
            }
            drawLine(
                color = p.hairDark,
                start = Offset(-13f, -19f),
                end   = Offset(-10f, -16f),
                strokeWidth = 0.6f,
                cap = StrokeCap.Round,
                alpha = parentAlpha,
            )
            drawLine(
                color = p.hairDark,
                start = Offset(13f, -19f),
                end   = Offset(10f, -16f),
                strokeWidth = 0.6f,
                cap = StrokeCap.Round,
                alpha = parentAlpha,
            )
            drawFaceFeatures(
                p, radius = 14.5f, eyeY = -16f, smileY = -10f,
                blush = false,
                time = time, blinkAt = 2.0,
                parentAlpha = parentAlpha,
            )
            // Father bushy brows + mustache hint — drawn AFTER face
            // features so they sit on top of the standard brows the
            // helper drew.
            val browL = Path().apply {
                moveTo(-8f, -20f)
                quadraticTo(-5f, -21.5f, -2f, -20f)
            }
            val browR = Path().apply {
                moveTo(2f, -20f)
                quadraticTo(5f, -21.5f, 8f, -20f)
            }
            drawPath(browL, p.hairDark, alpha = parentAlpha,
                     style = Stroke(width = 1.3f, cap = StrokeCap.Round))
            drawPath(browR, p.hairDark, alpha = parentAlpha,
                     style = Stroke(width = 1.3f, cap = StrokeCap.Round))
            // Mustache — thin shape under the nose.
            val mustache = Path().apply {
                moveTo(-4f, -11f)
                quadraticTo(-2f, -10f, 0f, -11f)
                quadraticTo(2f, -10f, 4f, -11f)
                quadraticTo(2f, -9.5f, 0f, -10f)
                quadraticTo(-2f, -9.5f, -4f, -11f)
                close()
            }
            drawPath(mustache, p.hairDark, alpha = parentAlpha)
        }

        // Front arm + can.
        val frontArm = Path().apply {
            moveTo(14f, -86f)
            quadraticTo(22f, -80f, 28f, -68f)
            lineTo(30f, -62f)
            quadraticTo(22f, -74f, 14f, -78f); close()
        }
        drawPath(frontArm, p.fatherShirt, alpha = parentAlpha)
        drawCircle(p.skin, radius = 3f,
                   center = Offset(28f, -65f), alpha = parentAlpha)

        // Watering can.
        withTransform({
            translate(28f, -65f)
            rotate(degrees = tilt, pivot = Offset.Zero)
        }) {
            val body = Path().apply {
                moveTo(-18f, -10f)
                quadraticTo(-20f, -10f, -20f, -8f)
                lineTo(-20f, 8f)
                quadraticTo(-20f, 10f, -18f, 10f)
                lineTo(4f, 10f)
                quadraticTo(6f, 10f, 6f, 8f)
                lineTo(6f, -8f)
                quadraticTo(6f, -10f, 4f, -10f)
                close()
            }
            drawPath(body, p.watercan, alpha = parentAlpha)
            drawRect(p.watercanHi,
                     topLeft = Offset(-17f, -9f), size = Size(2.5f, 18f),
                     alpha = parentAlpha * 0.65f)
            drawRect(p.watercanShade,
                     topLeft = Offset(2f, -9f), size = Size(3f, 18f),
                     alpha = parentAlpha * 0.6f)
            drawOval(p.watercanShade,
                     topLeft = Offset(-20f, -12f), size = Size(26f, 4f),
                     alpha = parentAlpha * 0.7f)
            // Handle.
            val handle = Path().apply {
                moveTo(-18f, -8f)
                quadraticTo(-28f, -4f, -26f, 6f)
                quadraticTo(-24f, 10f, -19f, 10f)
            }
            drawPath(handle, p.watercan,
                     alpha = parentAlpha,
                     style = Stroke(width = 3f, cap = StrokeCap.Round))
            // Spout.
            val spout = Path().apply {
                moveTo(6f, -2f); lineTo(22f, -10f)
                quadraticTo(25f, -10f, 25f, -7f)
                lineTo(9f, 5f); close()
            }
            drawPath(spout, p.watercan, alpha = parentAlpha)
            val spoutHi = Path().apply {
                moveTo(6f, -2f); lineTo(22f, -10f)
                lineTo(22f, -8f); lineTo(9f, 0f); close()
            }
            drawPath(spoutHi, p.watercanHi, alpha = parentAlpha * 0.5f)
            // Shower head.
            drawOval(
                color = p.watercan,
                topLeft = Offset(21.5f, -11f),
                size = Size(7f, 6f),
                alpha = parentAlpha
            )
            drawOval(
                color = p.watercanShade,
                topLeft = Offset(21.5f, -11f),
                size = Size(7f, 6f),
                alpha = parentAlpha * 0.5f
            )
            for ((sx, sy) in listOf(27f to -9f, 27f to -7f,
                                    26f to -8.5f, 26f to -6.8f)) {
                drawCircle(p.watercanShade, radius = 0.5f,
                           center = Offset(sx, sy), alpha = parentAlpha)
            }
            val deco = Path().apply {
                moveTo(-11f, -3f)
                quadraticTo(-7f, -5f, -5f, -3f)
                quadraticTo(-8f, 0f, -11f, -3f)
                close()
            }
            drawPath(deco, p.leafLight, alpha = parentAlpha * 0.7f)
        }
    }
}
