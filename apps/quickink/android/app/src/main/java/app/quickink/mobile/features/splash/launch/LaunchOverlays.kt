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

    val slideIn  = ease(LaunchEasing::easeOutBack, between(time, 0.5, 1.4))
    val slideOut = 1 - ease(LaunchEasing::easeInCubic, between(time, 4.45, 5.0))
    val op = (slideIn * slideOut).coerceIn(0.0, 1.0).toFloat()
    val tx = ((1 - slideIn) * -120).toFloat()
    val cT = ease(LaunchEasing::easeOutCubic, between(time, 1.85, 4.1))
    val value = (target * cT).toInt()
    val ticking = cT > 0 && cT < 0.99
    val pulse = (if (ticking) 1 + 0.025 * abs(sin(time * 13.5)) else 1.0).toFloat()
    val celebrate = ease(LaunchEasing::easeOutBack, between(time, 4.0, 4.3))
    val celeFade  = ease(LaunchEasing::easeInCubic, between(time, 4.2, 4.45))
    val celeScale = (1 + celebrate * 0.08 * (1 - celeFade)).toFloat()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 18.dp, top = 64.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .alpha(op)
                .scale(pulse * celeScale)
                .offset(x = tx.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(palette.badgeBg)
                .border(
                    width = 1.dp,
                    color = palette.badgeAccent.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(50)
                )
                .padding(start = 11.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawLeafGlyph(palette.badgeAccent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "$value",
                    color = palette.badgeText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "TREE POINTS",
                    color = palette.badgeAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
            }
        }
        if (ticking) {
            Text(
                text  = "+1",
                color = palette.badgeAccent.copy(
                    alpha = (0.5f + 0.5f * abs(sin(time * 13)).toFloat())
                ),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = (110 + tx).dp,
                        y = (-10 - abs(sin(time * 13)) * 4).toFloat().dp
                    )
                    .alpha(op)
            )
        }
    }
}

// MARK: ─── Logo lockup ─────────────────────────────────────────────────

@Composable
internal fun LaunchLogoLockup(time: Double, palette: LaunchPalette) {
    val inOp  = ease(LaunchEasing::easeOutCubic, between(time, 2.0, 2.6))
    val outOp = 1 - ease(LaunchEasing::easeInCubic, between(time, 4.5, 4.85))
    val op = (inOp * outOp).toFloat()
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

// MARK: ─── Home-feed transition ────────────────────────────────────────

@Composable
internal fun LaunchHomeFeedTransition(
    time: Double,
    palette: LaunchPalette,
    target: Int,
) {
    val op = ease(LaunchEasing::easeOutCubic, between(time, 4.55, 5.0)).toFloat()
    if (op <= 0f) return
    val ty = ((1 - op) * 24 * 0.4).toFloat()

    val isDark  = palette.feedIsDark
    val surface = if (isDark) Color(0xFF26161E) else Color.White
    val text    = if (isDark) Color(0xFFFFF5E3) else Color(0xFF0E1F15)
    val muted   = if (isDark) Color(0xFFFFF5E3).copy(alpha = 0.55f)
                  else Color(0xFF0E1F15).copy(alpha = 0.55f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(op)
            .offset(y = ty.dp)
            .background(palette.feedBg)
            .padding(start = 18.dp, end = 18.dp, top = 64.dp),
    ) {
        // Header.
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GOOD MORNING",
                    color = muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "My QuickInk",
                    color = text,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(palette.feedAccent)
                    .padding(start = 11.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Canvas(modifier = Modifier.size(11.dp)) {
                    drawLeafGlyph(Color(0xFF9ADE7A))
                }
                Text(
                    text = "$target",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Hero card.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(palette.feedAccent)
                .padding(18.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF9ADE7A).copy(alpha = 0.22f)),
            ) {
                Canvas(modifier = Modifier.size(26.dp)) {
                    drawLeafGlyph(Color(0xFF9ADE7A))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YOU'VE HELPED PLANT",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$target trees",
                    color = Color(0xFFF3FBE6),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
            }
            Text(
                text = "›",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "RECENT NOTEBOOKS",
            color = muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )

        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeedRow("note", "Daily journal",   "24 pages · today",
                    surface, text, muted, palette.feedAccent)
            FeedRow("idea", "Sketch ideas",    "18 pages · 2d ago",
                    surface, text, muted, palette.feedAccent)
            FeedRow("todo", "Garden to-do",    "12 pages · 3d ago",
                    surface, text, muted, palette.feedAccent)
            FeedRow("mtg",  "Family meetings", "9 pages · 1w ago",
                    surface, text, muted, palette.feedAccent)
        }
    }
}

@Composable
private fun FeedRow(
    kind: String,
    title: String,
    meta: String,
    surface: Color,
    text: Color,
    muted: Color,
    feedAccent: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(feedAccent.copy(alpha = 18f / 255f)),
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                drawFeedIcon(kind, feedAccent)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = meta, color = muted, fontSize = 11.sp)
        }
        Text(text = "›", color = muted, fontSize = 18.sp)
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

private fun DrawScope.drawFeedIcon(kind: String, color: Color) {
    val s = size.minDimension / 18f
    drawScale(s, s, pivot = Offset.Zero) {
        when (kind) {
            "note" -> {
                drawPath(
                    Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = 3f, top = 2.5f, right = 15f, bottom = 15.5f,
                                radiusX = 2f, radiusY = 2f
                            )
                        )
                    },
                    color = color,
                    style = Stroke(width = 1.4f)
                )
                for (y in listOf(6f, 9f, 12f)) {
                    drawLine(
                        color = color,
                        start = Offset(6f, y),
                        end   = Offset(if (y == 12f) 10f else 12f, y),
                        strokeWidth = 1.4f,
                        cap = StrokeCap.Round
                    )
                }
            }
            "idea" -> {
                val path = Path().apply {
                    moveTo(9f, 2f)
                    cubicTo(5.5f, 2f, 4f, 4.5f, 4f, 6.8f)
                    cubicTo(4f, 8.5f, 5f, 9.5f, 6f, 10.8f)
                    lineTo(6f, 12f); lineTo(12f, 12f); lineTo(12f, 10.8f)
                    cubicTo(13f, 9.5f, 14f, 8.5f, 14f, 6.8f)
                    cubicTo(14f, 4.5f, 12.5f, 2f, 9f, 2f)
                    close()
                }
                drawPath(path, color, style = Stroke(width = 1.4f))
            }
            "todo" -> {
                drawPath(
                    Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(
                            2.5f, 2.5f, 8.5f, 8.5f, 1.4f, 1.4f
                        ))
                    },
                    color = color,
                    style = Stroke(width = 1.4f)
                )
                drawPath(
                    Path().apply {
                        moveTo(4f, 5.5f); lineTo(5f, 6.5f); lineTo(7f, 4.5f)
                    },
                    color = color,
                    style = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(
                            2.5f, 9.5f, 8.5f, 15.5f, 1.4f, 1.4f
                        ))
                    },
                    color = color,
                    style = Stroke(width = 1.4f)
                )
                for (y in listOf(5.5f, 12.5f)) {
                    drawLine(
                        color = color,
                        start = Offset(10.5f, y), end = Offset(15.5f, y),
                        strokeWidth = 1.4f, cap = StrokeCap.Round
                    )
                }
            }
            else -> {
                drawCircle(
                    color = color,
                    radius = 2.2f,
                    center = Offset(6f, 6.5f),
                    style = Stroke(width = 1.4f)
                )
                drawCircle(
                    color = color,
                    radius = 1.8f,
                    center = Offset(12f, 7.5f),
                    style = Stroke(width = 1.4f)
                )
                val torsoA = Path().apply {
                    moveTo(2.5f, 14f)
                    cubicTo(2.5f, 11.8f, 4f, 10.5f, 9.5f, 14f)
                }
                drawPath(torsoA, color,
                         style = Stroke(width = 1.4f, cap = StrokeCap.Round))
                val torsoB = Path().apply {
                    moveTo(9f, 14f)
                    cubicTo(9f, 12.4f, 10.5f, 11.5f, 15f, 14f)
                }
                drawPath(torsoB, color,
                         style = Stroke(width = 1.4f, cap = StrokeCap.Round))
            }
        }
    }
}
