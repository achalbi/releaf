/*
 * QuickInkLaunchAnimation.kt
 *
 * Splash composable that plays the cinematic Lottie launch animation
 * handed off by design (`design_handoff_quickink_launch/README.md`)
 * when the corresponding After Effects → Lottie export is bundled,
 * and falls through to the minimal-mark [QuickInkSplash] when it
 * isn't.
 *
 * Asset location: drop the JSON export at
 *   `android/app/src/main/assets/quickink_launch.json`
 * The asset is detected at runtime via `AssetManager.list("")`, so a
 * missing file degrades gracefully — the build doesn't depend on it
 * being present, and the user sees the existing minimal-mark splash
 * until design hands the JSON over. The moment the file lands at
 * that path, the cinematic starts playing on next launch.
 *
 * Called from `MainActivity` in place of the bare `QuickInkSplash`.
 * On animation completion (or when the safety timeout expires, or
 * when the fallback splash's hold completes) `onFinished` fires and
 * the host swaps in `QuickInkRoot`. The safety timeout (README's
 * stated 5.0s + 500ms slack) covers the rare case where Lottie's
 * progress callback never reaches `1f` — e.g. a malformed JSON that
 * loads as a non-null composition but stalls mid-render — so we
 * never strand the user on the splash.
 *
 * Counterpart: iOS `LaunchAnimationView.swift`. Both load the same
 * JSON file (the README recommends Lottie precisely because one
 * `.json` ships to both platforms unchanged) and both fall back to
 * the minimal-mark splash when the asset is missing.
 *
 * Wiring `target` (the user's lifetime tree-points balance, ticked
 * up by the in-comp counter): TODO. The README documents `target`
 * as one of the animation's three input props. To wire it in
 * Compose-Lottie, override the text layer named in the AE comp via
 * `rememberLottieDynamicProperties` keyed on the layer's name (e.g.
 * `KeyPath("TreePointsCounter", "**")`). Held for a follow-up
 * because (a) the AE layer name isn't fixed yet, and (b) reading
 * the lifetime page count synchronously at splash-time would push
 * the database open before Compose's first frame — needs its own
 * design pass on whether to read it sync, async-with-restart, or
 * just let the comp's baked-in default play through.
 */

package app.quickink.mobile.features.splash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay

/// Path inside the APK assets folder where the AE → Lottie JSON
/// export is expected to live. Detected at runtime; absent → fallback.
private const val LAUNCH_ANIMATION_ASSET = "quickink_launch.json"

/// Safety ceiling on the animation hold. Matches the README's stated
/// 5.0s duration + 500ms slack so a stalled progress callback can't
/// strand the user on the splash forever.
private const val SAFETY_TIMEOUT_MS = 5_500L

@Composable
fun QuickInkLaunchAnimation(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val hasAsset = remember(context) { context.hasAsset(LAUNCH_ANIMATION_ASSET) }

    // Asset not bundled yet (or removed) — fall through to the
    // existing minimal-mark splash so the launch path is unchanged
    // for the user. Once the AE export lands at the documented path
    // this branch goes silent and the cinematic plays.
    if (!hasAsset) {
        QuickInkSplash(onFinished = onFinished)
        return
    }

    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset(LAUNCH_ANIMATION_ASSET),
    )

    val progress by animateLottieCompositionAsState(
        composition = composition,
        // Animation plays once on app launch; loops only as a
        // last-resort failure mode (see README §"Interactions &
        // Behavior" — "Loops silently if launch network/init takes
        // longer than 5s"). The host already gates dismissal on
        // `onFinished`, so a single iteration is what we want here.
        iterations  = 1,
    )

    // Belt-and-braces dismissal — fire `onFinished` whichever path
    // completes first:
    //   1. Composition load fails OR animation stalls → safety
    //      timeout (5.5s) catches it.
    //   2. Animation plays cleanly to its end frame → the progress
    //      effect below catches it the moment progress reaches 1f.
    LaunchedEffect(Unit) {
        delay(SAFETY_TIMEOUT_MS)
        onFinished()
    }
    LaunchedEffect(progress, composition) {
        if (composition != null && progress >= 1f) {
            onFinished()
        }
    }

    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Cream paint so the first frame load + any aspect-ratio
            // letterbox edges read as the brand canvas, not black.
            // Same token the minimal-mark splash uses — see
            // QuickInkSplash for the rationale.
            .background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Cheap presence check for an asset by name. `AssetManager.list("")`
 * reads the root directory listing without opening the file, so this
 * is essentially free at startup. Try/catch covers the "no assets
 * folder at all" case (returns false rather than crashing) — that's
 * the state when this file lands before the assets folder is created.
 */
private fun Context.hasAsset(name: String): Boolean = try {
    assets.list("")?.contains(name) == true
} catch (_: Throwable) {
    false
}
