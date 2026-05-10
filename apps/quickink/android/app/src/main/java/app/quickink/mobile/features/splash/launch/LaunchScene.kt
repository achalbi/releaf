/*
 * LaunchScene.kt
 *
 * The cinematic launch animation, drawn natively as a Compose Canvas
 * — direct port of `design_handoff_quickink_launch/source/scene.jsx`'s
 * SVG layers (sky, sun, clouds, mountains, birds, ground, pollen,
 * stumps, growing tree, family, water stream). The viewBox is 400×870
 * with `preserveAspectRatio="xMidYMid slice"`; we replicate that
 * scale-to-fill-and-crop math at the top of `LaunchScene` so the
 * cinematic fills any phone aspect without letterboxing.
 *
 * Each `draw…` extension on [DrawScope] takes the time and palette
 * and applies its own nested transforms via [withTransform]. Opacity
 * propagates through an explicit `parentAlpha` parameter (Compose's
 * DrawScope has no per-context alpha the way SwiftUI does), and per-
 * layer alphas multiply with parent for the same group-level fade
 * effect.
 *
 * The React-style overlay layers (Tree-points counter, logo lockup,
 * home-feed transition) live in `LaunchOverlays.kt` since they read
 * more naturally as Compose views than as Canvas drawing calls.
 *
 * Counterpart: iOS `LaunchScene.swift`. Layer-by-layer the two files
 * stay in lockstep — same parameter names, same magic numbers, same
 * easings — so a frame difference between platforms is always traced
 * to a single layer's port rather than a pile of independent drift.
 */

package app.quickink.mobile.features.splash.launch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Canvas-rendered scene. Time is supplied by the host
 * (`QuickInkLaunchAnimation`); palette is the resolved [LaunchPalette].
 */
@Composable
internal fun LaunchScene(time: Double, palette: LaunchPalette) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.skyBase)
    ) {
        // viewBox: 400×870, preserveAspectRatio: xMidYMid slice.
        val sx = size.width / 400f
        val sy = size.height / 870f
        val sc = max(sx, sy)
        val drawW = 400f * sc
        val drawH = 870f * sc
        val ox = (size.width  - drawW) / 2f
        val oy = (size.height - drawH) / 2f

        withTransform({
            translate(ox, oy)
            scale(sc, sc, pivot = Offset.Zero)
        }) {
            val p = palette
            val t = time

            drawSky          (p, t)
            drawSun          (p, t)
            drawClouds       (p, t)
            drawMountainBack (p, t)
            drawMountainMid  (p, t)
            drawHazeBand     (p, t)
            drawMountainFront(p, t)
            drawBirds        (p, t)
            drawGround       (p, t)
            drawStumps       (p, t)
            drawPollen       (p, t)
            drawGrowingTree  (p, t)
            drawFamily       (p, t)
            drawWaterStream  (p, t)
        }
    }
}

// MARK: ─── Sky ──────────────────────────────────────────────────────────

private fun DrawScope.drawSky(p: LaunchPalette, t: Double) {
    val op = ease(LaunchEasing::easeOutCubic, between(t, 0.0, 0.7))
    if (op <= 0.0) return
    drawRect(
        brush = Brush.linearGradient(
            0.00f to p.skyTop,
            0.55f to p.skyMid,
            0.85f to p.skyHorizon,
            1.00f to p.skyBase,
            start = Offset(200f, 0f),
            end   = Offset(200f, 600f)
        ),
        topLeft = Offset(0f, 0f),
        size = Size(400f, 600f),
        alpha = op.toFloat()
    )
}

// MARK: ─── Sun ──────────────────────────────────────────────────────────

private fun DrawScope.drawSun(p: LaunchPalette, t: Double) {
    val opTotal = ease(LaunchEasing::easeOutCubic, between(t, 0.15, 1.0))
    if (opTotal <= 0.01) return
    val riseT  = ease(LaunchEasing::easeOutCubic, between(t, 0.15, 1.8))
    val cy     = (520.0 - 320.0 * riseT).toFloat()
    val breathe = (90.0 + 24.0 * sin(t * 1.05)).toFloat()
    val raysOp  = ease(LaunchEasing::easeOutCubic, between(t, 0.8, 2.6)) * 0.85

    if (raysOp > 0.01) {
        val angles = doubleArrayOf(-30.0, -18.0, -8.0, 4.0, 14.0, 26.0)
        for ((i, a) in angles.withIndex()) {
            val wobble = sin(t * 0.5 + i) * 1.5
            withTransform({
                translate(310f, cy)
                rotate(degrees = (a + wobble).toFloat(), pivot = Offset.Zero)
            }) {
                val ray = Path().apply {
                    moveTo(-8f, 0f)
                    lineTo((-2.0 - i * 0.3).toFloat(), 380f)
                    lineTo(( 2.0 + i * 0.3).toFloat(), 380f)
                    lineTo(8f, 0f)
                    close()
                }
                drawPath(
                    path = ray,
                    brush = Brush.linearGradient(
                        0f to p.rays.copy(alpha = p.rays.alpha * 0.8f),
                        1f to p.rays.copy(alpha = 0f),
                        start = Offset(0f, 0f),
                        end   = Offset(0f, 380f)
                    ),
                    alpha = (opTotal * raysOp).toFloat()
                )
            }
        }
    }

    withTransform({ translate(310f, cy) }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to p.sunGlow,
                1f to p.sunGlow.copy(alpha = 0f),
                center = Offset(0f, 0f),
                radius = breathe
            ),
            radius = breathe,
            center = Offset(0f, 0f),
            alpha = opTotal.toFloat()
        )
        drawCircle(p.sun, radius = 44f, center = Offset(0f, 0f), alpha = opTotal.toFloat())
        drawCircle(
            color = p.sun,
            radius = 14f,
            center = Offset(-12f, -10f),
            alpha = (opTotal * 0.6).toFloat()
        )
    }
}

// MARK: ─── Clouds ───────────────────────────────────────────────────────

private fun DrawScope.drawClouds(p: LaunchPalette, t: Double) {
    val op = ease(LaunchEasing::easeOutCubic, between(t, 0.9, 2.3)) * 0.85
    if (op <= 0.01) return
    val drift = (t * 6.0) % 80.0

    fun cloud(cx: Double, cy: Double, scl: Double) {
        withTransform({
            translate((cx + drift * scl).toFloat(), cy.toFloat())
            scale(scl.toFloat(), scl.toFloat(), pivot = Offset.Zero)
        }) {
            drawOval(p.cloud, topLeft = Offset(-22f, -6f), size = Size(44f, 12f),
                     alpha = op.toFloat())
            drawOval(p.cloud, topLeft = Offset(-26f, -7f), size = Size(24f, 10f),
                     alpha = op.toFloat())
            drawOval(p.cloud, topLeft = Offset(  4f, -5f), size = Size(20f, 8f),
                     alpha = op.toFloat())
            drawOval(p.cloud, topLeft = Offset(-12f, -8.5f), size = Size(16f, 7f),
                     alpha = (op * 0.9).toFloat())
        }
    }
    cloud( 60.0, 200.0, 1.00)
    cloud(180.0, 280.0, 0.70)
    cloud( 20.0, 340.0, 0.55)
}

// MARK: ─── Mountains ────────────────────────────────────────────────────

private fun DrawScope.drawMountainBack(p: LaunchPalette, t: Double) {
    val pr = ease(LaunchEasing::easeOutQuart, between(t, 0.45, 1.55))
    if (pr <= 0.01) return
    val ty = ((1 - pr) * 70.0).toFloat()
    withTransform({ translate(0f, ty) }) {
        val hill = Path().apply {
            moveTo(0f, 480f)
            lineTo(60f, 380f); lineTo(130f, 430f)
            lineTo(200f, 360f); lineTo(270f, 410f)
            lineTo(340f, 370f); lineTo(400f, 420f)
            lineTo(400f, 600f); lineTo(  0f, 600f)
            close()
        }
        drawPath(hill, p.mountainBack, alpha = pr.toFloat())
        val haze = Path().apply {
            moveTo(0f, 480f)
            lineTo(60f, 380f); lineTo(130f, 430f)
            lineTo(200f, 360f); lineTo(270f, 410f)
            lineTo(340f, 370f); lineTo(400f, 420f)
            lineTo(400f, 470f); lineTo(  0f, 470f)
            close()
        }
        drawPath(haze, p.mountainHaze, alpha = (pr * 0.35).toFloat())
    }
}

private fun DrawScope.drawMountainMid(p: LaunchPalette, t: Double) {
    val pr = ease(LaunchEasing::easeOutQuart, between(t, 0.65, 1.65))
    if (pr <= 0.01) return
    val ty = ((1 - pr) * 100.0).toFloat()
    withTransform({ translate(0f, ty) }) {
        val body = Path().apply {
            moveTo(0f, 540f)
            lineTo(50f, 460f); lineTo(100f, 500f)
            lineTo(170f, 430f); lineTo(230f, 480f)
            lineTo(300f, 440f); lineTo(360f, 490f)
            lineTo(400f, 470f); lineTo(400f, 600f); lineTo(0f, 600f)
            close()
        }
        drawPath(body, p.mountainMid, alpha = pr.toFloat())

        val caps = Path().apply {
            moveTo(165f, 437f); lineTo(175f, 445f); lineTo(185f, 437f); lineTo(175f, 432f); close()
            moveTo(295f, 447f); lineTo(305f, 455f); lineTo(312f, 447f); lineTo(305f, 442f); close()
        }
        drawPath(caps, p.skyTop, alpha = (pr * 0.45).toFloat())
    }
}

private fun DrawScope.drawMountainFront(p: LaunchPalette, t: Double) {
    val pr = ease(LaunchEasing::easeOutQuart, between(t, 0.85, 1.75))
    if (pr <= 0.01) return
    val ty = ((1 - pr) * 130.0).toFloat()
    withTransform({ translate(0f, ty) }) {
        val body = Path().apply {
            moveTo(0f, 600f)
            lineTo(40f, 540f); lineTo(90f, 565f)
            lineTo(140f, 510f); lineTo(200f, 555f)
            lineTo(260f, 525f); lineTo(320f, 560f)
            lineTo(380f, 530f); lineTo(400f, 555f)
            lineTo(400f, 600f); lineTo(0f, 600f)
            close()
        }
        drawPath(body, p.mountainFront, alpha = pr.toFloat())
        val spikes = Path().apply {
            for ((x, y) in listOf(60f to 552f, 110f to 550f, 180f to 530f,
                                  250f to 545f, 295f to 540f, 340f to 550f)) {
                moveTo(x - 3f, y); lineTo(x, y - 14f); lineTo(x + 3f, y); close()
            }
        }
        drawPath(spikes, p.mountainFront, alpha = pr.toFloat())
    }
}

// MARK: ─── Haze band between mid/front mountains ───────────────────────

private fun DrawScope.drawHazeBand(p: LaunchPalette, t: Double) {
    val op = ease(LaunchEasing::easeOutCubic, between(t, 0.9, 2.0)) * 0.6
    if (op <= 0.01) return
    drawRect(p.haze, topLeft = Offset(0f, 540f), size = Size(400f, 80f),
             alpha = op.toFloat())
}

// MARK: ─── Birds ────────────────────────────────────────────────────────

private fun DrawScope.drawBirds(p: LaunchPalette, t: Double) {
    val appear = ease(LaunchEasing::easeOutCubic, between(t, 1.3, 1.9))
    val depart = ease(LaunchEasing::easeInCubic,  between(t, 4.0, 4.5))
    val op = appear * (1 - depart)
    if (op <= 0.01) return

    fun flap(k: Double) = 1.2 + 0.4 * sin(t * 6 + k)
    val prog = (t - 1.3) / 2.5
    val x1 = 60.0 + prog * 220
    val y1 = 280.0 + sin(t * 1.2) * 4
    val x2 = 30.0 + prog * 240
    val y2 = 320.0 + sin(t * 1.2 + 1) * 4

    fun bird(cx: Double, cy: Double, k: Double) {
        withTransform({ translate(cx.toFloat(), cy.toFloat()) }) {
            val path = Path().apply {
                moveTo(0f, 0f)
                quadraticTo(-5f, (-flap(k) * 2).toFloat(), -10f, 0f)
                moveTo(0f, 0f)
                quadraticTo( 5f, (-flap(k) * 2).toFloat(),  10f, 0f)
            }
            drawPath(
                path  = path,
                color = p.bird,
                alpha = op.toFloat(),
                style = Stroke(width = 1.4f, cap = StrokeCap.Round)
            )
        }
    }
    bird(x1, y1, 0.0)
    bird(x2, y2, 1.0)
}

// MARK: ─── Pollen ───────────────────────────────────────────────────────

private fun DrawScope.drawPollen(p: LaunchPalette, t: Double) {
    val appear = ease(LaunchEasing::easeOutCubic, between(t, 1.5, 2.4))
    val depart = ease(LaunchEasing::easeInCubic,  between(t, 4.0, 4.6))
    val op = appear * (1 - depart)
    if (op <= 0.01) return

    for (i in 0 until 14) {
        val seed = i * 1.7
        val baseX = 80.0 + ((i * 23) % 280).toDouble()
        val x = baseX + sin(t * 0.6 + seed) * 6
        val baseY = 720.0 - ((i * 17) % 200).toDouble()
        val scrollY = ((t - 1.5) * 14) % 100
        val y = baseY - scrollY
        val r = 1.0 + (i % 3) * 0.6
        val localOp = 0.4 + 0.4 * sin(t * 1.5 + seed)
        val a = max(0.0, localOp * op).toFloat()
        drawCircle(
            color  = p.pollen,
            radius = r.toFloat(),
            center = Offset(x.toFloat(), y.toFloat()),
            alpha  = a
        )
    }
}

// MARK: ─── Ground ───────────────────────────────────────────────────────

private fun DrawScope.drawGround(p: LaunchPalette, t: Double) {
    val pr = ease(LaunchEasing::easeOutCubic, between(t, 1.0, 1.7))
    if (pr <= 0.01) return
    val wind = sin(t * 1.65) * 1.5

    drawRect(
        brush = Brush.linearGradient(
            0.0f to p.grassLight,
            0.4f to p.grassDark,
            1.0f to p.ground,
            start = Offset(200f, 600f),
            end   = Offset(200f, 870f)
        ),
        topLeft = Offset(0f, 600f),
        size    = Size(400f, 270f),
        alpha   = pr.toFloat()
    )

    val xs = doubleArrayOf(20.0, 55.0, 95.0, 125.0, 155.0, 200.0, 240.0, 270.0, 340.0, 375.0)
    for ((i, x) in xs.withIndex()) {
        val sway = wind * (0.6 + (i % 3) * 0.3)
        val path = Path().apply {
            moveTo(x.toFloat(), 622f)
            quadraticTo(
                (x + sway).toFloat(),         (622 - 8).toFloat(),
                (x + sway * 1.1).toFloat(),   (622 - 16).toFloat()
            )
        }
        drawPath(
            path  = path,
            color = p.grassLight,
            alpha = (pr * 0.75).toFloat(),
            style = Stroke(width = 2f, cap = StrokeCap.Round)
        )
    }
}

// MARK: ─── Stumps (deforestation tone) ─────────────────────────────────

private fun DrawScope.drawStumps(p: LaunchPalette, t: Double) {
    val op = ease(LaunchEasing::easeOutCubic, between(t, 1.0, 1.8))
    if (op <= 0.01) return

    fun stump(cx: Float, cy: Float, w: Float, h: Float, weathered: Boolean,
              parentAlpha: Float) {
        withTransform({ translate(cx, cy) }) {
            // Shadow
            drawOval(
                color   = Color.Black,
                topLeft = Offset(-w * 0.7f, h * 0.55f - h * 0.18f),
                size    = Size(w * 1.4f, h * 0.36f),
                alpha   = parentAlpha * 0.22f
            )
            // Trunk halves
            val left = Path().apply {
                moveTo(-w / 2f, h * 0.4f)
                lineTo(-w / 2f, -h * 0.05f)
                quadraticTo(-w / 2f, -h * 0.15f, 0f, -h * 0.18f)
                lineTo(0f, h * 0.45f)
                close()
            }
            val right = Path().apply {
                moveTo(0f, -h * 0.18f)
                quadraticTo(w / 2f, -h * 0.15f, w / 2f, -h * 0.05f)
                lineTo(w / 2f, h * 0.4f)
                lineTo(0f, h * 0.45f)
                close()
            }
            drawPath(left,  p.stump, alpha = parentAlpha)
            drawPath(right, p.stump, alpha = parentAlpha)

            // Bark vertical streaks.
            drawLine(
                color = p.stumpRing,
                start = Offset(-w * 0.3f, -h * 0.05f),
                end   = Offset(-w * 0.3f,  h * 0.35f),
                strokeWidth = 0.6f,
                alpha = parentAlpha * 0.6f
            )
            drawLine(
                color = p.stumpRing,
                start = Offset(w * 0.2f, -h * 0.05f),
                end   = Offset(w * 0.2f,  h * 0.35f),
                strokeWidth = 0.6f,
                alpha = parentAlpha * 0.6f
            )
            // Cut surface.
            drawOval(
                color   = p.stumpTop,
                topLeft = Offset(-w / 2f, -h * 0.1f - h * 0.18f),
                size    = Size(w, h * 0.36f),
                alpha   = parentAlpha
            )
            // Annual rings — three concentric strokes + centre dot.
            fun ring(rx: Float, ry: Float, alpha: Float) {
                drawOval(
                    color   = p.stumpRing,
                    topLeft = Offset(-rx, -h * 0.1f - ry),
                    size    = Size(rx * 2f, ry * 2f),
                    alpha   = parentAlpha * alpha,
                    style   = Stroke(width = 0.5f)
                )
            }
            ring(w * 0.38f, h * 0.135f, 0.7f)
            ring(w * 0.25f, h * 0.09f,  0.6f)
            ring(w * 0.12f, h * 0.045f, 0.55f)
            drawOval(
                color   = p.stumpRing,
                topLeft = Offset(-h * 0.025f, -h * 0.1f - h * 0.025f),
                size    = Size(h * 0.05f, h * 0.05f),
                alpha   = parentAlpha * 0.7f
            )
            if (weathered) {
                val notch = Path().apply {
                    moveTo(-w * 0.4f, -h * 0.18f)
                    lineTo(-w * 0.2f, -h * 0.05f)
                    lineTo(-w * 0.5f,  0f)
                    close()
                }
                drawPath(notch, p.stumpRing, alpha = parentAlpha * 0.6f)
            }
        }
    }

    // Distant — on hilltop horizon, scaled 0.55, 0.7 alpha.
    val distantA = (op * 0.7f).toFloat()
    withTransform({ scale(0.55f, 0.55f, pivot = Offset.Zero) }) {
        stump((60f / 0.55f),  (615f / 0.55f), 14f, 10f, weathered = false, distantA)
        stump((115f / 0.55f), (620f / 0.55f), 12f,  9f, weathered = true,  distantA)
        stump((360f / 0.55f), (618f / 0.55f), 13f,  9f, weathered = false, distantA)
    }
    // Mid — on open ground.
    stump( 35f, 700f, 18f, 14f, weathered = true,  op.toFloat())
    stump(385f, 705f, 20f, 15f, weathered = false, op.toFloat())
    stump(180f, 685f, 14f, 11f, weathered = false, op.toFloat())
}

// MARK: ─── Growing tree ────────────────────────────────────────────────

private fun DrawScope.drawGrowingTree(p: LaunchPalette, t: Double) {
    val g = ease(LaunchEasing::easeOutCubic, between(t, 1.85, 3.95))
    if (g <= 0.0) return

    val trunkH = (95.0 * g).toFloat()
    val trunkW = (6.0 + 4.0 * g).toFloat()
    val canopyR = (max(0.0, g - 0.12) * 72.0).toFloat()
    val canopyY = (-trunkH - canopyR * 0.55).toFloat()
    val sway = (sin(t * 2.2) * 1.2 * g).toFloat()
    val soilOp = ease(LaunchEasing::easeOutCubic, between(t, 1.85, 2.1))

    withTransform({
        translate(305f, 720f)
        rotate(degrees = sway, pivot = Offset.Zero)
    }) {
        drawOval(
            color   = p.soil,
            topLeft = Offset(-22f, 3f - 3.5f),
            size    = Size(44f, 7f),
            alpha   = (soilOp * 0.85).toFloat()
        )
        drawOval(
            color   = p.soilWet,
            topLeft = Offset(-14f, 2f - 2f),
            size    = Size(28f, 4f),
            alpha   = (soilOp * 0.6).toFloat()
        )
        val shadeRx = 20f + 30f * g.toFloat()
        val shadeRy =  3f +  2f * g.toFloat()
        drawOval(
            color   = Color.Black,
            topLeft = Offset(-shadeRx, 2f - shadeRy),
            size    = Size(shadeRx * 2f, shadeRy * 2f),
            alpha   = (0.18 * g).toFloat()
        )

        val trunk = Path().apply {
            moveTo(-trunkW / 2f - 1f, 0f)
            quadraticTo(
                -trunkW / 2f - 0.5f, -trunkH * 0.5f,
                -trunkW / 2f + 1f,   -trunkH
            )
            lineTo(trunkW / 2f - 1f, -trunkH)
            quadraticTo(
                trunkW / 2f + 0.5f, -trunkH * 0.5f,
                trunkW / 2f + 1f,    0f
            )
            close()
        }
        drawPath(trunk, p.bark)

        drawLine(
            color = p.barkLight,
            start = Offset(-trunkW / 4f, -2f),
            end   = Offset(-trunkW / 4f, -trunkH + 2f),
            strokeWidth = 1f,
            alpha = 0.55f
        )

        if (canopyR > 0f) {
            fun disk(cx: Float, cy: Float, r: Float, col: Color) {
                drawCircle(col, radius = r, center = Offset(cx, cy))
            }
            disk(-canopyR * 0.5f,  canopyY + canopyR * 0.4f, canopyR * 0.65f, p.leafDark)
            disk( canopyR * 0.5f,  canopyY + canopyR * 0.4f, canopyR * 0.65f, p.leafDark)
            disk( 0f,              canopyY + canopyR * 0.55f, canopyR * 0.7f, p.leafDark)
            disk(-canopyR * 0.35f, canopyY + canopyR * 0.1f,  canopyR * 0.6f, p.leaf)
            disk( canopyR * 0.35f, canopyY + canopyR * 0.1f,  canopyR * 0.6f, p.leaf)
            disk( 0f,              canopyY - canopyR * 0.05f, canopyR * 0.55f, p.leaf)
            disk(-canopyR * 0.2f,  canopyY - canopyR * 0.25f, canopyR * 0.4f,  p.leafLight)
            disk( canopyR * 0.25f, canopyY - canopyR * 0.2f,  canopyR * 0.35f, p.leafLight)
            disk( canopyR * 0.05f, canopyY - canopyR * 0.4f,  canopyR * 0.22f, p.leafHi)
        }

        if (g > 0.3 && g < 0.95) {
            val off = ((1 - between(g, 0.75, 0.95)) * 0.7)
            val positions = listOf(
                -30f to -75f, 25f to -60f, 0f to -90f, -12f to -45f, 35f to -82f
            )
            for ((i, pos) in positions.withIndex()) {
                val localT = (g * 3 + i * 0.5) % 1.0
                val opp = (1 - localT) * 0.9
                val r = (localT * 3).toFloat()
                withTransform({ translate(pos.first, pos.second) }) {
                    drawCircle(
                        color = p.leafHi,
                        radius = r,
                        center = Offset.Zero,
                        alpha = (off * opp).toFloat(),
                        style  = Stroke(width = 0.8f)
                    )
                    drawCircle(
                        color = p.leafHi,
                        radius = 0.8f,
                        center = Offset.Zero,
                        alpha  = (off * opp).toFloat()
                    )
                }
            }
        }
    }
}

// MARK: ─── Water stream ─────────────────────────────────────────────────

/**
 * Compute the watering-can nozzle's world-space position at [t],
 * matching the SVG transform stack: father pivot (225, 720) → father
 * lean rotation → can pivot (28, -65) in father-local → can tilt
 * rotation → nozzle local (25, -8).
 */
internal fun nozzleAt(t: Double): Offset {
    val fLeanDeg = 4.0  * ease(LaunchEasing::easeOutCubic, between(t, 1.4, 1.85))
    val tiltDeg  = 28.0 * ease(LaunchEasing::easeOutBack,  between(t, 1.4, 1.85))
    val fLean = fLeanDeg * Math.PI / 180.0
    val tilt  = tiltDeg  * Math.PI / 180.0

    var rx = 25.0 * cos(tilt) - (-8.0) * sin(tilt)
    var ry = 25.0 * sin(tilt) + (-8.0) * cos(tilt)
    rx += 28
    ry += -65
    val lrx = rx * cos(fLean) - ry * sin(fLean)
    val lry = rx * sin(fLean) + ry * cos(fLean)
    return Offset((225 + lrx).toFloat(), (720 + lry).toFloat())
}

private fun DrawScope.drawWaterStream(p: LaunchPalette, t: Double) {
    // Water keeps flowing from the spout once the can has tilted
    // (1.85 s) and continues for the rest of the splash — there's
    // no outro fade. The host dismisses the whole splash at 7.5 s
    // so the stream just disappears with everything else.
    if (t <= 1.85) return
    val op = ease(LaunchEasing::easeOutCubic, between(t, 1.85, 2.1))
    if (op <= 0.01) return

    val nozzle = nozzleAt(t)
    val x0 = nozzle.x.toDouble()
    val y0 = nozzle.y.toDouble()
    val x1 = 305.0
    val y1 = 718.0

    val period = 0.42
    val n = 9
    for (i in 0 until n) {
        val phase = ((t - 1.85) - i * (period / n)) / period
        val local = phase - floor(phase)
        if (local < 0.0) continue
        val tt = local
        val x  = lerp(x0, x1, 1 - powd(1 - tt, 2.4))
        val y  = lerp(y0, y1, powd(tt, 2.6))
        val dt = 0.01
        val tn = min(1.0, tt + dt)
        val xn = lerp(x0, x1, 1 - powd(1 - tn, 2.4))
        val yn = lerp(y0, y1, powd(tn, 2.6))
        val vx = xn - x
        val vy = yn - y
        val vmag = max(hypot(vx, vy), 1e-6)
        val ux = vx / vmag; val uy = vy / vmag
        val streakLen = 7 + tt * 4
        val fade = 1 - tt * 0.15

        drawLine(
            color = p.waterStreak.copy(alpha = p.waterStreak.alpha * 0.85f),
            start = Offset((x - ux * streakLen).toFloat(), (y - uy * streakLen).toFloat()),
            end   = Offset(x.toFloat(), y.toFloat()),
            strokeWidth = 1.8f,
            alpha = (op * fade).toFloat(),
            cap = StrokeCap.Round
        )

        val angle = atan2(vy, vx)
        withTransform({
            translate(x.toFloat(), y.toFloat())
            rotate(degrees = (angle * 180.0 / Math.PI).toFloat(), pivot = Offset.Zero)
        }) {
            drawOval(
                color   = p.waterDrop,
                topLeft = Offset(-1.7f, -2.8f),
                size    = Size(3.4f, 5.6f),
                alpha   = (op * fade).toFloat()
            )
        }
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 0.5f,
            center = Offset(
                (x - ux * 0.5).toFloat(),
                (y - uy * 0.5).toFloat()
            ),
            alpha = (op * fade).toFloat()
        )
    }

    // Splash + wet patch. Like the stream itself, this stays full
    // strength once it ramps up at 2.2–2.6 s; no fade-out — the
    // puddle's still being filled while the can is still pouring.
    val splashOp = ease(LaunchEasing::easeOutCubic, between(t, 2.2, 2.6))
    if (splashOp > 0) {
        val splashPhase = ((t - 2.2) * 5) % 1.0
        drawOval(
            color = p.soilWet,
            topLeft = Offset(305f - 18f, 720f - 4f),
            size    = Size(36f, 8f),
            alpha   = (op * splashOp * 0.7).toFloat()
        )
        drawOval(
            color = p.waterDrop,
            topLeft = Offset(305f - 14f, 718f - 3f),
            size    = Size(28f, 6f),
            alpha   = (op * splashOp * 0.55).toFloat()
        )
        for (i in 0 until 5) {
            val ang = (i / 5.0) * Math.PI - Math.PI / 5
            val r = 6 + splashPhase * 10
            val sx = 305 + cos(ang) * r
            val sy = 718 - sin(ang) * r * 0.4
            val sopp = (1 - splashPhase).toFloat()
            drawOval(
                color = p.waterDrop,
                topLeft = Offset((sx - 0.9).toFloat(), (sy - 0.9).toFloat()),
                size    = Size(1.8f, 1.8f),
                alpha   = (op * splashOp * sopp).toFloat()
            )
        }
    }
}
