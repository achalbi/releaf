/*
 * SplashScreen.kt
 * Full-bleed launch splash derived from the April 2026 Releaf marketing
 * material: deep green, cream-filled diagonal leaf with deep-green
 * vein, lowercase serif wordmark, and the "WRITE. ERASE. REPEAT." loop
 * tagline.
 */

package app.releaf.mobile.features.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.components.ReleafLogoSolid
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val start = Color(0xFF0F5A35)
    val mid = Color(0xFF0B4328)
    val end = Color(0xFF062F1D)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(start, mid, end),
                    start = Offset(0f, 0f),
                    end   = Offset.Infinite,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        SplashDotGrid()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = AppSpacing.s6),
        ) {
            ReleafLogoSolid(
                size = 136.dp,
                leafColor = AppColors.OnAccent,
                veinColor = end,
                veinWidth = 4.dp,
            )

            Box(Modifier.height(28.dp))

            Text(
                text = "releaf",
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 64.sp,
                    letterSpacing = (-0.015).em,
                ),
                color = AppColors.OnAccent,
            )

            Box(Modifier.height(AppSpacing.s2))

            Text(
                text = "WRITE. ERASE. REPEAT.",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 18.sp,
                    letterSpacing = 0.13.em,
                ),
                color = AppColors.OnAccent,
                textAlign = TextAlign.Center,
            )

            Box(Modifier.height(AppSpacing.s3))

            Text(
                text = "Reusable notebook + smart app companion.",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 17.sp,
                ),
                color = AppColors.OnAccent.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
            )

            Box(Modifier.height(72.dp))

            LoadingDots()
        }
    }
}

@Composable
private fun SplashDotGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val step = 18.dp.toPx()
        val radius = 1.dp.toPx()
        var y = 0f
        while (y <= size.height) {
            var x = 0f
            while (x <= size.width) {
                drawCircle(
                    color = Color(0x12F5EEDF),
                    radius = radius,
                    center = Offset(x, y),
                )
                x += step
            }
            y += step
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
