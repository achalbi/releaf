/*
 * LaunchEasing.kt
 *
 * Time / easing primitives shared by every layer of the cinematic
 * launch animation. Direct ports of the helpers in the React
 * prototype (`design_handoff_quickink_launch/source/animations.jsx`):
 *
 *   - [between] clamps + maps a global second offset into a local
 *     [0,1] over the layer's appearance window. Most layers pipe
 *     this through one of the easings below to get a non-linear
 *     entry / exit curve.
 *   - [lerp] is the unsurprising linear interpolation. Used
 *     everywhere a layer needs to slide a numeric attribute (radius,
 *     y offset, opacity ramp).
 *   - [LaunchEasing] mirrors Popmotion-style easings 1:1 with the
 *     JSX prototype so the cinematic timing matches frame-for-frame
 *     on iOS, Android, and the web preview.
 *
 * Counterpart: iOS `LaunchEasing.swift`. Function bodies must stay
 * identical so the reveal hits the same beat on both platforms.
 */

package app.quickink.mobile.features.splash.launch

import kotlin.math.pow
import kotlin.math.sin

/**
 * Map global time [t] (seconds since splash start) into [0,1]
 * covering the window [a..b]. Clamped at the edges. Mirrors the
 * React prototype's `between(t, a, b)` exactly.
 */
internal fun between(t: Double, a: Double, b: Double): Double {
    val span = b - a
    if (span <= 0.0) return if (t >= b) 1.0 else 0.0
    val v = (t - a) / span
    return if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
}

/** Linear interpolation. */
internal fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Tiny double clamp — companion to JSX `clamp(v, min, max)`. */
internal fun clamp(v: Double, lo: Double, hi: Double): Double =
    if (v < lo) lo else if (v > hi) hi else v

/**
 * Easing functions, all taking `t` ∈ [0,1] and returning eased
 * `t` ∈ [0,1] (back / elastic curves may overshoot, by design).
 * Names + bodies mirror Popmotion conventions and the React
 * prototype's `Easing` namespace; do not reorder or rewrite the
 * formulas without updating iOS in lockstep.
 */
internal object LaunchEasing {
    fun linear(t: Double): Double = t

    // Quad
    fun easeInQuad(t: Double): Double = t * t
    fun easeOutQuad(t: Double): Double = t * (2 - t)

    // Cubic
    fun easeInCubic(t: Double): Double = t * t * t
    fun easeOutCubic(t: Double): Double {
        val p = t - 1
        return p * p * p + 1
    }

    // Quart
    fun easeInQuart(t: Double): Double = t * t * t * t
    fun easeOutQuart(t: Double): Double {
        val p = t - 1
        return 1 - p * p * p * p
    }

    // Sine
    fun easeOutSine(t: Double): Double = sin(t * Math.PI / 2)

    // Back (overshoots past 1.0 then settles)
    fun easeOutBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = c1 + 1
        val p = t - 1
        return 1 + c3 * p * p * p + c1 * p * p
    }
}

/**
 * Shorthand: `ease(LaunchEasing::easeOutCubic, between(time, 0.0, 0.7))`
 * reads the same as the JSX `ease(Easing.easeOutCubic, ...)`. Just
 * clamps the input and forwards.
 */
internal inline fun ease(fn: (Double) -> Double, t: Double): Double =
    fn(clamp(t, 0.0, 1.0))

/** Tiny convenience — `pow(x, y)` against doubles, matching JSX. */
internal fun powd(base: Double, exp: Double): Double = base.pow(exp)
