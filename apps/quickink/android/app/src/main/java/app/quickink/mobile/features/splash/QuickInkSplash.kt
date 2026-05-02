/*
 * QuickInkSplash.kt
 *
 * Brief in-app splash composable that shows the QuickInk mark and
 * wordmark on the cream canvas, matching the brand prototype board's
 * "Minimal mark" splash variant. This is the visual the user actually
 * sees during launch — Android 12+'s system splash (icon-only, can't
 * include text) hands off to this immediately on first frame, and
 * this view holds for ~1.4 seconds before transitioning to
 * `QuickInkRoot`.
 *
 * Why a Compose splash and not a longer system splash:
 *   - The Android 12+ system splash window can't render text. Showing
 *     the wordmark requires rendering it inside the activity's own
 *     content tree.
 *   - `setKeepOnScreenCondition` on the system splash is for waiting
 *     on async work, not for branding moments. Using it for a fixed
 *     duration would block input, look static, and miss the chance
 *     to fade in from the system splash gracefully.
 *
 * Layout mirrors the iOS `SplashView.swift` and the `splash-*.png`
 * exports under `design/exports/`: mark at 28% short edge, wordmark
 * at 42% short edge, 18dp gap, vertically centered. The mark uses
 * `splash_mark` (bowl-anchored centering — bowl sits at the canvas
 * center column so it aligns with the wordmark below); the wordmark
 * uses `splash_wordmark` (Cormorant Garamond Medium, pre-rendered).
 *
 * Use from `QuickInkRoot` like:
 *
 *   if (showSplash) {
 *       QuickInkSplash(onFinished = { showSplash = false })
 *   } else {
 *       // real app
 *   }
 */

package app.quickink.mobile.features.splash

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.quickink.mobile.R
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import kotlinx.coroutines.delay

@Composable
fun QuickInkSplash(
    onFinished: () -> Unit,
    durationMillis: Long = 1400L,
) {
    val colors = LocalQuickInkColors.current

    // Hold the splash for the configured duration, then signal the
    // host to swap in `QuickInkRoot`. The fade-in below makes the
    // hand-off from the Android 12+ system splash feel continuous.
    LaunchedEffect(Unit) {
        delay(durationMillis)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            // Match the iOS SplashView sizing: 28% / 42% of short edge.
            val shorter = minOf(maxWidth, maxHeight)
            val markSize = shorter * 0.28f
            val wordmarkWidth = shorter * 0.42f

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter            = painterResource(id = R.drawable.splash_mark),
                    contentDescription = null,
                    modifier           = Modifier.size(markSize),
                    contentScale       = ContentScale.Fit,
                )
                Spacer(Modifier.height(18.dp))
                Image(
                    painter            = painterResource(id = R.drawable.splash_wordmark),
                    contentDescription = "QuickInk",
                    modifier           = Modifier.width(wordmarkWidth),
                    contentScale       = ContentScale.Fit,
                )
            }
        }
    }
}
