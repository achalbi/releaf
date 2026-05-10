/*
 * LaunchOverlays.kt
 *
 * The three React-style overlay layers that ride above the Compose
 * Canvas in the launch animation: the Tree-points counter pill
 * (top-left), the centered QuickInk logo lockup, and the home-feed
 * transition that wipes in over the last ~450 ms before the splash
 * dismisses.
 *
 * Direct ports of the same-named components in
 * `design_handoff_quickink_launch/source/scene.jsx`. We render them
 * as native Compose views (not Canvas draw calls) because each one
 * is dominated by typography + layout rather than shapes — Compose's
 * built-in text rendering, rounded surfaces, and shadow modifiers
 * are a more honest match for the JSX `<div>` markup than re-rolling
 * them inside a Canvas.
 *
 * Counterpart: iOS `LaunchOverlays.swift`. Layout values must stay
 * in lockstep so the counter slides in at the same beat on both
 * platforms.
 */

package app.quickink.mobile.features.splash.launch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale as drawScale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.R
import kotlin.math.abs
import kotlin.math.sin

// MARK: ─── Tree-points counter ─────────────────────────────────────────

@Composable
internal fun LaunchPointsCounter(
    target: Int,
    time: Double,
    palette: LaunchPalette,
    show: Boolean,
) {
    if (!show) return

    val slideIn = ease(LaunchEasing::easeOutBack, between(time, 0.5, 1.4))
    // Counter holds at full opacity through the 2 s tail — the host
    // dismisses the splash before any fade-out is needed.
    val op = slideIn.coerceIn(0.0, 1.0).toFloat()
    val ty = ((1 - slideIn) * -30).toFloat()                  // drop in from above
    val cT = ease(LaunchEasing::easeOutCubic, between(time, 1.85, 4.1))
    val value = (target * cT).toInt()
    val ticking = cT > 0 && cT < 0.99
    val pulse = (if (ticking) 1 + 0.02 * abs(sin(time * 13.5)) else 1.0).toFloat()
    val celebrate = ease(LaunchEasing::easeOutBack, between(time, 4.0, 4.3))
    val celeFade  = ease(LaunchEasing::easeInCubic, between(time, 4.2, 4.45))
    val celeScale = (1 + celebrate * 0.10 * (1 - celeFade)).toFloat()

    // Use the deep-green feed accent for ink-on-cream contrast — the
    // badgeAccent (light green) doesn't read on the cream sky.
    val ink = palette.feedAccent

    // Number + caption only — no leaf glyph. The Column's
    // CenterHorizontally alignment plants both texts dead-centre.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxSize()
            .alpha(op)
            .scale(pulse * celeScale)
            .offset(y = ty.dp)
            .padding(top = 60.dp),
    ) {
        Text(
            text = "$value",
            color = ink,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "TREE POINTS",
            color = ink.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.5.sp,
        )
    }
}

// MARK: ─── Logo lockup ─────────────────────────────────────────────────

@Composable
internal fun LaunchLogoLockup(time: Double, palette: LaunchPalette) {
    // Logo holds at full opacity through the 2 s tail — pairs with
    // the centred Tree-points counter so the final frame of the
    // splash is "QuickInk + your impact", visible long enough for
    // the user to take it in.
    val inOp = ease(LaunchEasing::easeOutCubic, between(time, 2.0, 2.6))
    val op = inOp.toFloat()
    val ty = ((1 - inOp) * 14).toFloat()
    val logoScale = (0.9 + 0.1 * inOp).toFloat()

    val isDark = palette.feedIsDark
    val titleColor = if (isDark) Color(0xFFFFF5E3) else Color(0xFF0E1F15)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 0.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 0.dp)
                .offset(y = (160 + ty).dp)
                .alpha(op),
        ) {
            Image(
                painter = painterResource(id = R.drawable.splash_mark),
                contentDescription = null,
                modifier = Modifier
                    .size(110.dp)
                    .scale(logoScale),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = "QuickInk",
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.5).sp,
                color = titleColor,
                modifier = Modifier.offset(y = (-6).dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "IDEAS  ·  THAT  ·  GROW",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.5.sp,
                color = palette.logoTagline,
                textAlign = TextAlign.Center,
            )
        }
    }
}


// MARK: ─── Glyph helpers ───────────────────────────────────────────────

/**
 * Leaf glyph used in both the points pill and the feed accent — the
 * same path data lifted from the JSX so the two surfaces can't drift
 * in shape. Drawn into the Canvas at its native 18×18 viewBox.
 */
private fun DrawScope.drawLeafGlyph(color: Color) {
    val s = size.minDimension / 18f
    drawScale(s, s, pivot = Offset.Zero) {
        val path = Path().apply {
            moveTo(9f, 1.5f)
            cubicTo(4f, 3f, 1.5f, 7f, 2.5f, 12f)
            cubicTo(4f, 12.3f, 5.5f, 12f, 6.8f, 11.2f)
            lineTo(6.8f, 8f)
            lineTo(7.6f, 8f)
            lineTo(7.6f, 10.7f)
            cubicTo(8.7f, 9.9f, 9.7f, 8.7f, 10.5f, 7.2f)
            lineTo(9f, 7f)
            lineTo(10.8f, 6.5f)
            cubicTo(11.3f, 5.5f, 11.7f, 4.5f, 12f, 3.4f)
            cubicTo(11f, 2.4f, 10f, 1.8f, 9f, 1.5f)
            close()
        }
        drawPath(path, color)
    }
}

