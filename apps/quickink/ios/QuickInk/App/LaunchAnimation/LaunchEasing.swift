/*
 * LaunchEasing.swift
 *
 * Time / easing primitives shared by every layer of the cinematic
 * launch animation. Direct ports of the helpers in the React
 * prototype (`design_handoff_quickink_launch/source/animations.jsx`):
 *
 *   - `between(t, a, b)` clamps + maps a global second offset into a
 *     local [0,1] over the layer's appearance window. Most layers
 *     pipe this through one of the easings below to get a non-linear
 *     entry / exit curve.
 *   - `lerp(a, b, t)` is the unsurprising linear interpolation. Used
 *     everywhere a layer needs to slide a numeric attribute (radius,
 *     y offset, opacity ramp).
 *   - `Easing.*` mirrors Popmotion-style easings 1:1 with the JSX
 *     prototype so the cinematic timing matches frame-for-frame on
 *     iOS, Android, and the web preview.
 *
 * Counterpart: Android `LaunchEasing.kt`. Function bodies must stay
 * identical so the reveal hits the same beat on both platforms.
 */

import Foundation

/// Map global time `t` (seconds since splash start) into [0,1]
/// covering the window [a,b]. Clamped at the edges. Mirrors the
/// React prototype's `between(t, a, b)` exactly.
@inlinable
func between(_ t: Double, _ a: Double, _ b: Double) -> Double {
    let span = b - a
    if span <= 0 { return t >= b ? 1 : 0 }
    let v = (t - a) / span
    return min(max(v, 0), 1)
}

/// Linear interpolation. Used everywhere a layer scrubs a numeric
/// attribute against eased progress.
@inlinable
func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double {
    a + (b - a) * t
}

/// Tiny double clamp — the JSX `clamp(v, min, max)` companion. Hoist
/// here so call sites don't have to guard each interpolation.
@inlinable
func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double {
    min(max(v, lo), hi)
}

/// Easing functions, all taking a `t` ∈ [0,1] and returning eased
/// `t` ∈ [0,1] (back / elastic curves may overshoot, by design).
/// Names + bodies mirror Popmotion conventions and the React
/// prototype's `Easing` namespace; do not reorder or rewrite the
/// formulas without updating Android in lockstep.
enum LaunchEasing {

    @inlinable static func linear(_ t: Double) -> Double { t }

    // Quad
    @inlinable static func easeInQuad(_ t: Double) -> Double { t * t }
    @inlinable static func easeOutQuad(_ t: Double) -> Double { t * (2 - t) }

    // Cubic
    @inlinable static func easeInCubic(_ t: Double) -> Double { t * t * t }
    @inlinable static func easeOutCubic(_ t: Double) -> Double {
        let p = t - 1
        return p * p * p + 1
    }

    // Quart
    @inlinable static func easeInQuart(_ t: Double) -> Double { t * t * t * t }
    @inlinable static func easeOutQuart(_ t: Double) -> Double {
        let p = t - 1
        return 1 - p * p * p * p
    }

    // Sine
    @inlinable static func easeOutSine(_ t: Double) -> Double {
        sin(t * .pi / 2)
    }

    // Back (overshoots past 1.0 then settles)
    @inlinable static func easeOutBack(_ t: Double) -> Double {
        let c1 = 1.70158
        let c3 = c1 + 1
        let p = t - 1
        return 1 + c3 * p * p * p + c1 * p * p
    }
}

/// Shorthand: `ease(LaunchEasing.easeOutCubic, between(time, 0, 0.7))`
/// reads the same as the prototype's `ease(Easing.easeOutCubic, ...)`.
/// Just clamps the input and forwards.
@inlinable
func ease(_ fn: (Double) -> Double, _ t: Double) -> Double {
    fn(clamp(t, 0, 1))
}

/// Multiplier used to squeeze the family characters' eye height during
/// a blink. Returns 1.0 outside the ±120 ms window around `at`, and
/// dips to ~0.08 at the centre of the blink. Different characters
/// pass different `at` values so the blinks don't all fire on the
/// same frame — that's what makes the family read as alive rather
/// than as four mannequins synchronized on one timer.
///
/// Curve: cosine ramp from 1 → 0.08 → 1 across the blink window. Not
/// linear because the eyelid closes faster than it opens (matches
/// real-world blink mechanics; a triangular ramp looks robotic).
@inlinable
func blinkScale(_ time: Double, at: Double, halfWindow: Double = 0.12) -> Double {
    let dt = abs(time - at)
    if dt > halfWindow { return 1.0 }
    let phase = dt / halfWindow                 // 0 at centre, 1 at edge
    let dip = 0.08                              // residual eye-slit height
    return dip + (1.0 - dip) * (0.5 - 0.5 * cos(phase * .pi))
}
