/*
 * SplashScreen.kt
 * Full-bleed launch splash. Matches the Releaf Branding Figma spec (live
 * DOM extraction, April 2026):
 *   - Linear gradient background, theme-primary → theme-deep, top-left
 *     to bottom-right.
 *   - 120dp leaf (canonical 24-viewport SVG, filled + cream outline +
 *     vein) with a subtle pulse.
 *   - "Releaf" wordmark, sans-serif 48sp medium, letter-spacing -0.025em
 *     in OnAccent cream.
 *   - "The notebook that grows back." tagline, sans-serif 18sp,
 *     OnAccent @ 90% opacity.
 *   - Three bouncing 8dp cream loading dots.
 * Every color flows from design-tokens.json via AppAccent / AppColors,
 * so the splash re-tints with the active leaf theme.
 */

package app.releaf.mobile.features.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.components.ReleafLogo
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.LocalFontWeight

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val start = AppAccent.primary
    val end = AppAccent.deep

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(start, end),
                    start = Offset(0f, 0f),
                    end   = Offset.Infinite,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = AppSpacing.s6),
        ) {
            // Leaf — filled gradient body with cream outline + vein.
            ReleafLogo(
                size = 120.dp,
                filled = true,
                outlineColor = AppColors.OnAccent,
                fillGradientStart = start,
                fillGradientEnd = end,
                strokeWidth = 2.dp,
            )

            Box(Modifier.height(AppSpacing.s8))

            Text(
                text = "Releaf",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = LocalFontWeight.current,
                    fontSize = 48.sp,
                    letterSpacing = (-0.025).em,
                ),
                color = AppColors.OnAccent,
            )

            Box(Modifier.height(AppSpacing.s3))

            Text(
                text = "The notebook that grows back.",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = LocalFontWeight.current,
                    fontSize = 18.sp,
                ),
                color = AppColors.OnAccent.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
            )

            Box(Modifier.height(64.dp))

            LoadingDots()
        }
    }
}

@Composable
private fun LoadingDots() {
    val transition = rememberInfiniteTransition(label = "splash-dots")
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 150),
                ),
                label = "splash-dot-$index",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(AppColors.OnAccent),
            )
        }
    }
}
