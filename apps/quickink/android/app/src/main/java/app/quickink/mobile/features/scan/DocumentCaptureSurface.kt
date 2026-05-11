/*
 * DocumentCaptureSurface.kt
 *
 * The Document branch of the capture screen — preserves the
 * existing ML Kit `GmsDocumentScanning` flow verbatim. Owned by
 * [QuickCaptureScreen], which mounts this composable when
 * [CaptureMode.Document] is active and tears it down on a flip
 * to [CaptureMode.BusinessCard].
 *
 * Behavior is the same as the previous all-in-one QuickCaptureScreen
 * body: page-mode pill (Single/Multi-page/Auto) + tilted lined-
 * paper page mock + shutter that launches the system scanner +
 * Import button that opens the system photo picker. The scanner
 * result flows through `ScanFlowController.onScanComplete` exactly
 * as before — no detector swap, no overlay, no in-app camera.
 *
 * Renamed from the original private `CaptureMode` (Single /
 * MultiPage / Auto) to [ScanPageMode] so the new top-level
 * [CaptureMode] can take the canonical name. The pill labels and
 * `pageLimit` plumbing are unchanged.
 *
 * Mirror of iOS `DocumentCaptureSurface.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.shared.scan.rememberDocumentScannerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page-count intent passed to ML Kit's `setPageLimit`. Was named
 * `CaptureMode` historically; renamed to make room for the top-
 * level [CaptureMode] enum that picks between Document and
 * Business Card surfaces.
 */
internal enum class ScanPageMode(val label: String) {
    /** Single-shot capture — `pageLimit = 1` hides Add-page. */
    Single("Single"),
    /** Multi-page — no `pageLimit`, in-scanner Add-page visible. */
    MultiPage("Multi-page"),
    /** Auto — same scanner config as Multi-page today. */
    Auto("Auto"),
}

@Composable
internal fun DocumentCaptureSurface(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var pageMode by remember { mutableStateOf(ScanPageMode.Single) }
    // Page-count badge in Multi-page — reflects the LAST scanner
    // session's page count, reset on mode change.
    var pageCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(pageMode) {
        pageCount = 0
    }

    val scannerLauncher = rememberDocumentScannerLauncher(
        onResult = { result ->
            pageCount = result.pageUris.size
            // Document surface fires a manual capture event whenever
            // the shutter actually returns a result. Auto-capture
            // doesn't apply here — that's a Business Card-only
            // behavior — so every Document path is "manual".
            CaptureAnalytics.manualFired(CaptureMode.Document)
            controller.onScanComplete(result)
            onDismiss()
        },
        onError = { /* TODO surface error */ },
        pageLimit = if (pageMode == ScanPageMode.Single) 1 else null,
        galleryImportAllowed = false,
    )

    val context = LocalContext.current
    val importScope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importScope.launch {
            val result = withContext(Dispatchers.IO) {
                buildImportArtifacts(context, uris)
            } ?: return@launch
            pageCount = result.pageUris.size
            controller.onScanComplete(result, source = "import")
            onDismiss()
        }
    }

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

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .rotate(-4f)
                    .size(width = 240.dp, height = 320.dp)
                    .shadow(24.dp, RoundedCornerShape(QuickInkRadius.md))
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.surface),
            ) {
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
                Box(
                    modifier = Modifier
                        .padding(start = 24.dp, top = 14.dp, bottom = 14.dp)
                        .width(1.5.dp)
                        .fillMaxSize()
                        .background(colors.accent.copy(alpha = 0.6f))
                )
                Text(
                    text     = "Brainstorm — Q3\nGoals, opportunities,\nand notes…",
                    style    = type.handwritten.copy(fontSize = 20.sp),
                    color    = colors.ink.copy(alpha = 0.78f),
                    modifier = Modifier.padding(start = 36.dp, top = QuickInkSpacing.s5, end = QuickInkSpacing.s4),
                )
            }

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

            DocumentCornerMark(rotation = 0f,   xOffset = (-120).dp, yOffset = (-160).dp)
            DocumentCornerMark(rotation = 90f,  xOffset =  120.dp,   yOffset = (-160).dp)
            DocumentCornerMark(rotation = 270f, xOffset = (-120).dp, yOffset =  160.dp)
            DocumentCornerMark(rotation = 180f, xOffset =  120.dp,   yOffset =  160.dp)
        }

        Spacer(Modifier.weight(1f))

        // Shutter row — page badge / shutter / import.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(modifier = Modifier.size(width = 64.dp, height = 64.dp), contentAlignment = Alignment.Center) {
                if (pageMode == ScanPageMode.MultiPage) {
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

            DocumentShutterButton(onClick = scannerLauncher::launch)

            Box(
                modifier = Modifier.size(width = 64.dp, height = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                DocumentImportButton(
                    onClick = {
                        importLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                )
            }
        }

        // Page-mode pill (Single / Multi-page / Auto). Lives below
        // the shutter row, unchanged from the pre-refactor layout.
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
                ScanPageMode.values().forEachIndexed { i, m ->
                    if (i > 0) Spacer(Modifier.size(4.dp))
                    val active = (m == pageMode)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(if (active) colors.accent else Color.Transparent)
                            .clickable { pageMode = m }
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

@Composable
private fun DocumentShutterButton(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier         = Modifier
            .size(78.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .border(3.dp, Color.White.copy(alpha = 0.6f), CircleShape),
        )
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
private fun DocumentImportButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector       = Icons.Filled.Image,
            contentDescription = "Import photo",
            tint              = Color.White.copy(alpha = 0.85f),
            modifier          = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun DocumentCornerMark(
    rotation: Float,
    xOffset: Dp,
    yOffset: Dp,
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
