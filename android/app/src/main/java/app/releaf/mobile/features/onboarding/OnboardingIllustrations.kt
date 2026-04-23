/*
 * OnboardingIllustrations.kt
 *
 * The 10 step-specific illustrations used by [OnboardingWizard]. All
 * visuals are built from Compose primitives (shapes, emoji, inline
 * vectors) — no raster assets — mirroring the web source at
 * docs/onboarding/source/_onboarding_wizard.html.erb.
 */

package app.releaf.mobile.features.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import app.releaf.mobile.ui.theme.AppAccent
import java.util.Calendar

private val IllustrationHeight = 140.dp

@Composable
internal fun IllustrationFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IllustrationHeight),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// Step 1 — Welcome: app icon + sparkles ───────────────────────────────
@Composable
internal fun WelcomeIllustration() = IllustrationFrame {
    val sparkle = AppAccent.primary.copy(alpha = 0.6f)
    Box(contentAlignment = Alignment.Center) {
        AppIconMark(size = 72.dp)
        // Sparkles scattered around the icon (same positions as the web).
        Text(
            "✦",
            color = sparkle,
            fontSize = 14.sp,
            modifier = Modifier.offset(x = 44.dp, y = (-44).dp),
        )
        Text(
            "✦",
            color = sparkle,
            fontSize = 17.sp,
            modifier = Modifier.offset(x = (-56).dp, y = (-8).dp),
        )
        Text(
            "✦",
            color = sparkle,
            fontSize = 12.sp,
            modifier = Modifier.offset(x = 52.dp, y = 40.dp),
        )
    }
}

@Composable
internal fun AppIconMark(size: Dp) {
    val accent = AppAccent.primary
    Box(
        modifier = Modifier
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(size / 4),
                ambientColor = accent,
                spotColor = accent,
            )
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(
                Brush.linearGradient(
                    listOf(OnboardTokens.IconGradientStart, OnboardTokens.IconGradientEnd),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Interior card
        Canvas(
            modifier = Modifier.size(size * 0.63f, size * 0.66f),
        ) {
            val corner = size.toPx() / 18f
            drawRoundRect(
                color = OnboardTokens.IconSurface,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )

            // Three horizontal lines
            val lineStroke = size.toPx() / 13f
            val lineColor = OnboardTokens.IconLine
            val w = this.size.width
            val h = this.size.height
            val lineInset = w * 0.13f
            val y1 = h * 0.26f
            val y2 = h * 0.50f
            val y3 = h * 0.74f
            drawLine(
                color = lineColor,
                start = Offset(lineInset, y1),
                end   = Offset(w - lineInset, y1),
                strokeWidth = lineStroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = lineColor,
                start = Offset(lineInset, y2),
                end   = Offset(w - lineInset, y2),
                strokeWidth = lineStroke,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = lineColor,
                start = Offset(lineInset, y3),
                end   = Offset(w * 0.70f, y3),
                strokeWidth = lineStroke,
                cap = StrokeCap.Round,
            )

            // Orange check dot bottom-right
            val dotRadius = w * 0.145f
            val dotCenter = Offset(w * 0.775f, h * 0.76f)
            drawCircle(
                color = OnboardTokens.IconDotFill,
                radius = dotRadius,
                center = dotCenter,
            )
            val checkStroke = size.toPx() / 24f
            val path = Path().apply {
                moveTo(dotCenter.x - dotRadius * 0.45f, dotCenter.y)
                lineTo(dotCenter.x - dotRadius * 0.1f, dotCenter.y + dotRadius * 0.38f)
                lineTo(dotCenter.x + dotRadius * 0.5f, dotCenter.y - dotRadius * 0.5f)
            }
            drawPath(
                path = path,
                color = OnboardTokens.IconSurface,
                style = Stroke(width = checkStroke, cap = StrokeCap.Round),
            )
        }
    }
}

// Step 2 — Notebooks: stacked cards ─────────────────────────────────
@Composable
internal fun NotebooksIllustration() = IllustrationFrame {
    Box(modifier = Modifier.size(80.dp, 100.dp), contentAlignment = Alignment.Center) {
        CardMock(emoji = "📑", rotation = -8f, offsetX = (-6).dp, offsetY = 6.dp, alpha = 0.5f)
        CardMock(emoji = "📄", rotation = -3f, offsetX = (-2).dp, offsetY = 2.dp, alpha = 0.75f)
        CardMock(emoji = "📓", rotation = 0f,  offsetX = 0.dp,    offsetY = 0.dp, alpha = 1f)
    }
}

@Composable
private fun CardMock(
    emoji: String,
    rotation: Float,
    offsetX: Dp,
    offsetY: Dp,
    alpha: Float,
    width: Dp = 72.dp,
    height: Dp = 88.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 32.sp,
) {
    Box(
        modifier = Modifier
            .offset(offsetX, offsetY)
            .rotate(rotation)
            .size(width, height)
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(OnboardTokens.CardBg.copy(alpha = alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = fontSize)
    }
}

// Step 3 — Notepad: calendar card ───────────────────────────────────
@Composable
internal fun NotepadIllustration() = IllustrationFrame {
    val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    Column(
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(OnboardTokens.CardBg)
            .width(100.dp)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Text(
            "APRIL",
            style = OnboardTokens.CalendarHeader,
            color = AppAccent.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            day.toString(),
            style = OnboardTokens.CalendarNumber,
            color = OnboardTokens.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(OnboardTokens.LineFill),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(OnboardTokens.LineFill),
        )
    }
}

// Step 4 — Photos: frame + settings pill ────────────────────────────
@Composable
internal fun PhotosIllustration() = IllustrationFrame {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(16.dp))
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(OnboardTokens.PhotoFrameBg)
                .border(2.dp, OnboardTokens.PhotoFrameBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("📷", fontSize = 34.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(AppAccent.soft)
                .border(1.dp, AppAccent.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                "Settings → Quality",
                style = OnboardTokens.PhotoBadge,
                color = AppAccent.primary,
            )
        }
    }
}

// Step 5 — Voice notes: mic + waveform ─────────────────────────────
@Composable
internal fun VoiceIllustration() = IllustrationFrame {
    val barColor = AppAccent.primary.copy(alpha = 0.7f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🎙️", fontSize = 38.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(32.dp),
        ) {
            listOf(10, 20, 28, 18, 30, 22, 14, 24, 10).forEach { h ->
                Box(
                    Modifier
                        .size(width = 4.dp, height = h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
        }
    }
}

// Step 6 — To-do: three rows ────────────────────────────────────────
@Composable
internal fun TodoIllustration() = IllustrationFrame {
    Column(
        modifier = Modifier
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(OnboardTokens.CardBg)
            .width(220.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TodoRow(checked = true,  label = "Buy groceries", strike = true)
        TodoRow(checked = false, label = "Call dentist",  trailing = { Text("⏰", fontSize = 13.sp) })
        TodoRow(checked = false, label = "Finish report", trailing = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppAccent.soft)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text(
                    "Task",
                    style = OnboardTokens.ScanPill.copy(fontWeight = FontWeight.SemiBold),
                    color = AppAccent.deep,
                )
            }
        })
    }
}

@Composable
private fun TodoRow(
    checked: Boolean,
    label: String,
    strike: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val accent = AppAccent.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) accent else Color.Transparent)
                .border(
                    width = 2.dp,
                    color = if (checked) accent else OnboardTokens.BorderRest,
                    shape = RoundedCornerShape(4.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            label,
            style = OnboardTokens.TodoItem,
            color = if (strike) Color(0xFF999999) else Color(0xFF3D3A35),
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// Step 7 — Scan: document + pills ──────────────────────────────────
@Composable
internal fun ScanIllustration() = IllustrationFrame {
    val accent = AppAccent.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(6.dp))
                .size(width = 80.dp, height = 100.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(OnboardTokens.CardBg),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                val corner = 12.dp.toPx()
                val thickness = 2.dp.toPx()
                val inset = 6.dp.toPx()
                val color = accent
                // Top-left
                drawLine(color, Offset(inset, inset), Offset(inset + corner, inset), thickness)
                drawLine(color, Offset(inset, inset), Offset(inset, inset + corner), thickness)
                // Top-right
                drawLine(color, Offset(size.width - inset - corner, inset), Offset(size.width - inset, inset), thickness)
                drawLine(color, Offset(size.width - inset, inset), Offset(size.width - inset, inset + corner), thickness)
                // Bottom-left
                drawLine(color, Offset(inset, size.height - inset - corner), Offset(inset, size.height - inset), thickness)
                drawLine(color, Offset(inset, size.height - inset), Offset(inset + corner, size.height - inset), thickness)
                // Bottom-right
                drawLine(color, Offset(size.width - inset, size.height - inset - corner), Offset(size.width - inset, size.height - inset), thickness)
                drawLine(color, Offset(size.width - inset - corner, size.height - inset), Offset(size.width - inset, size.height - inset), thickness)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(52.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(4) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(OnboardTokens.LineFill),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(accent)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text("PDF ✓", color = Color.White, style = OnboardTokens.Badge)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ScanPill("Capture", done = true)
            ScanPill("Detect",  done = true)
            ScanPill("Enhance", done = true)
            ScanPill("Save",    done = false, active = true)
        }
    }
}

@Composable
private fun ScanPill(label: String, done: Boolean, active: Boolean = false) {
    val (bg, fg) = when {
        active -> AppAccent.primary      to Color.White
        done   -> AppAccent.soft         to AppAccent.deep
        else   -> OnboardTokens.LineFill to OnboardTokens.TextSubtle
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = OnboardTokens.ScanPill, color = fg)
    }
}

// Step 8 — Migrate: calendar → notebook ────────────────────────────
@Composable
internal fun MigrateIllustration() = IllustrationFrame {
    val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(OnboardTokens.CardBg)
                .size(width = 64.dp, height = 72.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                "APR",
                style = OnboardTokens.CalendarHeader,
                color = AppAccent.primary,
            )
            Text(
                day.toString(),
                style = OnboardTokens.CalendarNumber.copy(fontSize = 22.sp, lineHeight = 24.sp),
                color = OnboardTokens.TextPrimary,
            )
        }
        Text(
            "→",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = AppAccent.primary,
        )
        Box(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(OnboardTokens.CardBg)
                .size(width = 64.dp, height = 72.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("📓", fontSize = 32.sp)
        }
    }
}

// Step 9 — Backup: app icon → Google Drive ─────────────────────────
@Composable
internal fun BackupIllustration() = IllustrationFrame {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIconMark(size = 56.dp)
        Text(
            "→",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = AppAccent.primary,
        )
        Box(
            modifier = Modifier
                .shadow(4.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(OnboardTokens.CardBg)
                .size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            GoogleDriveLogo(size = 36.dp)
        }
    }
}

@Composable
private fun GoogleDriveLogo(size: Dp) {
    // Approximation of the multicolour Drive triangle from the ERB SVG.
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun tri(points: List<Offset>, color: Color) {
            val p = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(p, color)
        }
        val topX = w * 0.5f
        val topY = h * 0.08f
        val leftMid = Offset(w * 0.08f, h * 0.64f)
        val rightMid = Offset(w * 0.92f, h * 0.64f)
        val leftBot = Offset(w * 0.30f, h * 0.92f)
        val rightBot = Offset(w * 0.70f, h * 0.92f)
        val centerLeft = Offset(w * 0.36f, h * 0.62f)
        val centerRight = Offset(w * 0.64f, h * 0.62f)

        // Green left wing
        tri(listOf(Offset(topX, topY), centerLeft, leftMid), Color(0xFF00AC47))
        // Blue bottom-left
        tri(listOf(leftMid, leftBot, Offset(w * 0.5f, h * 0.66f)), Color(0xFF0066DA))
        // Yellow right wing
        tri(listOf(Offset(topX, topY), centerRight, rightMid), Color(0xFFFFBA00))
        // Red bottom-right
        tri(listOf(rightMid, rightBot, Offset(w * 0.5f, h * 0.66f)), Color(0xFFEA4335))
        // Base blue strip
        tri(listOf(leftBot, rightBot, Offset(w * 0.5f, h * 0.66f)), Color(0xFF2684FC))
    }
}

// Step 10 — Done: checkmark ─────────────────────────────────────────
@Composable
internal fun DoneIllustration() = IllustrationFrame {
    val accent = AppAccent.primary
    val wash   = AppAccent.soft
    Canvas(modifier = Modifier.size(72.dp)) {
        val stroke = 3.dp.toPx()
        val checkStroke = 3.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = (w / 2f) - stroke
        drawCircle(
            color = wash,
            radius = radius,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = accent,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.50f)
            lineTo(w * 0.44f, h * 0.66f)
            lineTo(w * 0.72f, h * 0.36f)
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = checkStroke, cap = StrokeCap.Round),
        )
    }
}
