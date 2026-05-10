/*
 * QuickInkLaunchAnimation.kt
 *
 * Splash composable that plays the native cinematic launch animation
 * — the Compose port of the React/SVG prototype handed off by design
 * (`design_handoff_quickink_launch/`). Composes:
 *
 *   - [LaunchScene] — Canvas-rendered SVG scene (sky, sun, mountains,
 *     family, growing tree, water stream, etc.).
 *   - [LaunchPointsCounter] / [LaunchLogoLockup] /
 *     [LaunchHomeFeedTransition] — React-style overlays that ride
 *     above the Canvas as native Compose composables.
 *
 * Time is driven by `withFrameNanos`, which fires once per display
 * refresh; the scene's per-layer easings derive their `t` from the
 * seconds elapsed since the composable entered composition. After
 * the prototype's 5.0 s timeline plus a 250 ms tail (matches the JSX
 * preview's fade-to-feed buffer), [onFinished] fires and the host
 * (`MainActivity`) swaps in `QuickInkRoot`.
 *
 * Reduced motion: when `Settings.Global.TRANSITION_ANIMATION_SCALE`
 * is 0 (or the OS reports reduced motion), we pin `time` at 2.5 s so
 * the user sees a single representative frame rather than 5 s of
 * motion — and we shorten the dismissal so the launch still feels
 * prompt. This addresses the "prefers-reduced-motion fallback" item
 * from `design/SPLASH_INTEGRATION.md`'s deferred list.
 *
 * [target] is the user's lifetime Tree-points balance — fed into the
 * counter pill and the home-feed hero. The host passes the resolved
 * value (default [defaultTarget] while we settle the sync/async
 * page-count read on the database open path; that's the second item
 * on the deferred list).
 *
 * Counterpart: iOS `LaunchAnimationView.swift`.
 */

package app.quickink.mobile.features.splash

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.features.splash.launch.LaunchLogoLockup
import app.quickink.mobile.features.splash.launch.LaunchPalettes
import app.quickink.mobile.features.splash.launch.LaunchPointsCounter
import app.quickink.mobile.features.splash.launch.LaunchScene
import app.quickink.mobile.ui.theme.LocalQuickInkColors

/**
 * Default Tree-points target shown while the host hasn't yet wired
 * the live page count — mirrors the prototype's preview value (247).
 * Replace with the actual lifetime page total when the database
 * read path is finalized (see the launch animation deferred TODO in
 * `design/SPLASH_INTEGRATION.md`).
 */
internal const val defaultTarget = 247

/** End of the cinematic timeline (5.0 s) plus 500 ms safety tail. */
private const val TOTAL_DURATION_S = 5.5

/** Reduced-motion frame to hold (mid-bloom). */
private const val REDUCED_MOTION_FROZEN_T = 2.5

/** Shortened reduced-motion dismissal — same as the legacy splash. */
private const val REDUCED_MOTION_HOLD_S = 1.4

@Composable
fun QuickInkLaunchAnimation(
    onFinished: () -> Unit,
    target: Int = defaultTarget,
) {
    val context = LocalContext.current
    val colors = LocalQuickInkColors.current
    val palette = LaunchPalettes.Dawn

    val reduceMotion = remember {
        // Android reads "reduced motion" via the transition animation
        // scale system setting. 0.0 means "off"; treat that as the
        // reduced-motion preference. Scope: app-wide preference, not
        // per-component, mirroring the JSX prototype's behavior.
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE
            ) == 0f
        } catch (_: Throwable) {
            false
        }
    }

    var elapsedS by remember { mutableStateOf(0.0) }
    var didFinish by remember { mutableStateOf(false) }

    // Drive time via `withFrameNanos` so the scene re-renders every
    // display refresh. Stops once the dismissal target is reached.
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            elapsedS = (now - startNanos) / 1_000_000_000.0
            val limit = if (reduceMotion) REDUCED_MOTION_HOLD_S else TOTAL_DURATION_S
            if (!didFinish && elapsedS >= limit) {
                didFinish = true
                onFinished()
                break
            }
        }
    }

    val t = if (reduceMotion) REDUCED_MOTION_FROZEN_T else elapsedS

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Cream behind the Canvas — covers any first-frame gap
            // before the sky gradient ramps in (op = 0 at t=0).
            .background(colors.bg),
    ) {
        LaunchScene(time = t, palette = palette)
        LaunchPointsCounter(
            target  = target,
            time    = t,
            palette = palette,
            show    = true,
        )
        LaunchLogoLockup(time = t, palette = palette)
        // (No home-feed transition — the cinematic dismisses straight
        // to the real Home screen, so a baked-in preview of it inside
        // the splash would just play immediately on top of itself.)
    }
}
