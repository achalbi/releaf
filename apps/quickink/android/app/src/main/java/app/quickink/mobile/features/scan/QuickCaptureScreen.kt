/*
 * QuickCaptureScreen.kt
 *
 * Pre-capture surface — the dark, editorial scan UI from the
 * mockup brief. Per the brief:
 *
 *   - Dark camera UI
 *   - Animated detection corners on a tilted page preview
 *   - Mode selector (Single / Multi-page / Auto)
 *   - 78dp shutter with Zap icon and page count badge
 *
 * Architecturally this sits between Home (the Zap FAB tap) and
 * the system document scanner (`GmsDocumentScanning` via
 * `rememberDocumentScannerLauncher` from :shared:scan). It's a
 * mode-picker + visual hand-off, not a custom CameraX surface —
 * Google's document scanner already has mature edge detection and
 * rebuilding it here would be redundant.
 *
 * On Zap shutter tap, the system scanner launches and the result
 * flows back through the existing `ScanFlowController.onScanComplete`
 * pipeline.
 *
 * Mirror of iOS `QuickCaptureScreen.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.shared.scan.rememberDocumentScannerLauncher

private enum class CaptureMode(val label: String) {
    Single("Single"),
    MultiPage("Multi-page"),
    Auto("Auto"),
}

@Composable
fun QuickCaptureScreen(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var mode by remember { mutableStateOf(CaptureMode.Single) }
    var pageCount by remember { mutableIntStateOf(0) }

    val scannerLauncher = rememberDocumentScannerLauncher(
        onResult = { result ->
            controller.onScanComplete(result)
            onDismiss()
        },
        onError  = { /* TODO surface error */ },
    )

    val transition = rememberInfiniteTransition(label = "scan-sweep")
    val sweep by transition.animateFloat(
        initialValue = -50f,
        targetValue  = 130f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scan-sweep-y",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0E0D)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(icon = Icons.Filled.Close, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Text(text = "Capture", style = type.label, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.weight(1f))
                CircleIconButton(icon = Icons.Filled.FlashOff, onClick = { /* flash follow-up */ })
            }

            Spacer(Modifier.weight(1f))

            // Page preview with detection corners + scan line.
            Box(contentAlignment = Alignment.Center) {

                // Tilted lined-paper page mock.
                Box(
                    modifier = Modifier
                        .rotate(-4f)
                        .size(width = 240.dp, height = 320.dp)
                        .shadow(24.dp, RoundedCornerShape(QuickInkRadius.md))
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.surface),
                ) {
                    // Lined paper rule lines.
                    Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 14.dp)) {
                        val lineColor = colors.ink.copy(alpha = 0.10f)
                        val spacing = 18.dp.toPx()
                        var y = spacing
                        while (y < size.height) {
                            drawLine(
                                color       = lineColor,
                                start       = Offset(0f, y),
                                end         = Offset(size.width, y),
                                strokeWidth = 0.5f,
                            )
                            y += spacing
                        }
                    }
                    // Coral margin.
                    Box(
                        modifier = Modifier
                            .padding(start = 24.dp, top = 14.dp, bottom = 14.dp)
                            .width(1.5.dp)
                            .fillMaxSize()
                            .background(colors.accent.copy(alpha = 0.6f))
                    )
                    // Handwritten title peek.
                    Text(
                        text     = "Brainstorm — Q3\nGoals, opportunities,\nand notes…",
                        style    = type.handwritten.copy(fontSize = 20.sp),
                        color    = colors.ink.copy(alpha = 0.78f),
                        modifier = Modifier.padding(start = 36.dp, top = QuickInkSpacing.s5, end = QuickInkSpacing.s4),
                    )
                }

                // Scan line.
                Box(
                    modifier = Modifier
                        .offset(y = sweep.dp)
                        .size(width = 240.dp, height = 2.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    colors.accent.copy(alpha = 0f),
                                    colors.accent.copy(alpha = 0.7f),
                                    colors.accent,
                                    colors.accent.copy(alpha = 0.7f),
                                    colors.accent.copy(alpha = 0f),
                                )
                            )
                        ),
                )

                // Detection corners.
                CornerMark(rotation = 0f,   xOffset = (-120).dp, yOffset = (-160).dp)
                CornerMark(rotation = 90f,  xOffset =  120.dp,   yOffset = (-160).dp)
                CornerMark(rotation = 270f, xOffset = (-120).dp, yOffset =  160.dp)
                CornerMark(rotation = 180f, xOffset =  120.dp,   yOffset =  160.dp)
            }

            Spacer(Modifier.weight(1f))

            // Shutter row — page badge / shutter / done.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left slot — page count badge in multi mode.
                Box(modifier = Modifier.size(width = 64.dp, height = 64.dp), contentAlignment = Alignment.Center) {
                    if (mode == CaptureMode.MultiPage) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text  = "$pageCount",
                                style = type.heading,
                                color = Color.White,
                            )
                            Text(
                                text  = if (pageCount == 1) "page" else "pages",
                                style = type.caption,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // Shutter.
                ShutterButton(onClick = {
                    when (mode) {
                        CaptureMode.Single, CaptureMode.Auto -> scannerLauncher.launch()
                        CaptureMode.MultiPage -> {
                            pageCount += 1
                            if (pageCount == 1) scannerLauncher.launch()
                        }
                    }
                })

                // Right slot — Done in multi mode after first capture.
                Box(modifier = Modifier.size(width = 64.dp, height = 64.dp), contentAlignment = Alignment.Center) {
                    if (mode == CaptureMode.MultiPage && pageCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(width = 64.dp, height = 36.dp)
                                .clip(RoundedCornerShape(QuickInkRadius.pill))
                                .background(colors.accent)
                                .clickable { scannerLauncher.launch() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "Done", style = type.label, color = Color.White)
                        }
                    }
                }
            }

            // Mode selector.
            Row(
                modifier = Modifier
                    .padding(horizontal = QuickInkSpacing.s5)
                    .padding(bottom = QuickInkSpacing.s7)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(4.dp),
                ) {
                    CaptureMode.values().forEachIndexed { i, m ->
                        if (i > 0) Spacer(Modifier.size(4.dp))
                        val active = (m == mode)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(QuickInkRadius.pill))
                                .background(if (active) colors.accent else Color.Transparent)
                                .clickable { mode = m }
                                .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
                        ) {
                            Text(
                                text  = m.label,
                                style = type.label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = null,
            tint              = Color.White.copy(alpha = 0.85f),
            modifier          = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier         = Modifier
            .size(78.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Outer ring.
        Box(
            modifier = Modifier
                .size(78.dp)
                .border(3.dp, Color.White.copy(alpha = 0.6f), CircleShape),
        )
        // Filled coral inner.
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(16.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Bolt,
                contentDescription = "Capture",
                tint              = Color.White,
                modifier          = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun CornerMark(
    rotation: Float,
    xOffset: androidx.compose.ui.unit.Dp,
    yOffset: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalQuickInkColors.current
    Canvas(
        modifier = Modifier
            .offset(x = xOffset, y = yOffset)
            .rotate(rotation)
            .size(18.dp),
    ) {
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width, 0f)
        }
        drawPath(
            path  = path,
            color = colors.accent,
            style = Stroke(width = 3f.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
