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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private enum class CaptureMode(val label: String) {
    /**
     * Single-page intent. Launches the ML Kit scanner with
     * `setPageLimit(1)` so the in-scanner Add-page affordance is
     * suppressed and the capture returns after one page.
     */
    Single("Single"),
    /**
     * Multi-page intent. No page limit set — the in-scanner UI
     * lets the user keep adding pages until they tap Done.
     */
    MultiPage("Multi-page"),
    /**
     * Auto mode placeholder — same scanner config as Multi-page
     * today. Reserved for a future BASE / BASE_WITH_FILTER
     * auto-capture-on-edge-detect path; for now it behaves the
     * same as Multi-page.
     */
    Auto("Auto"),
}

// Flash control was removed — Google's GmsDocumentScanning runs
// its own activity with its own flash UI in-scanner, and there's
// no public option on `GmsDocumentScannerOptions` to seed flash
// state from outside. Adding a button here that visually cycles
// without actually driving the camera ended up looking broken to
// users (icon flipped, but nothing happened on the actual capture).
//
// If we ever move to a custom CameraX pre-capture surface, flash
// can come back here and drive the camera directly. Until then
// users use the in-scanner flash button.

@Composable
fun QuickCaptureScreen(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var mode by remember { mutableStateOf(CaptureMode.Single) }
    // pageCount kept for the visible "N pages" badge in Multi-page
    // mode — tracks how many pages the LAST scanner session
    // returned. Reset when the user switches modes so the badge
    // doesn't stick around with stale numbers from a prior run.
    var pageCount by remember { mutableIntStateOf(0) }

    // Mode change → reset the page counter so the Multi-page badge
    // doesn't display stale numbers from a previous mode's session.
    androidx.compose.runtime.LaunchedEffect(mode) {
        pageCount = 0
    }

    val scannerLauncher = rememberDocumentScannerLauncher(
        onResult = { result ->
            pageCount = result.pageUris.size
            controller.onScanComplete(result)
            onDismiss()
        },
        onError = { /* TODO surface error */ },
        pageLimit = if (mode == CaptureMode.Single) 1 else null,
        galleryImportAllowed = false,
    )

    // System photo picker — replaces the old in-scanner gallery
    // tab (`galleryImportAllowed = true`) we used to lean on. Tap
    // the right-slot Import button → PickMultipleVisualMedia returns
    // a list of content:// URIs (selection order preserved) →
    // `buildImportArtifacts` writes each JPEG and a single multi-
    // page PDF into AttachmentStorage on a worker thread → the
    // controller takes the artifacts with `source = "import"` so
    // Library cards render the "Import" pill on the resulting
    // capture row. Mirror of iOS PhotosPicker (multi-select).
    //
    // No `maxItems` — falls back to the system picker's own cap
    // (around 100 on current builds), which is well past any
    // realistic single-import session. Capping it lower here would
    // be arbitrary product policy without an underlying constraint.
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

    // System status-bar inset — without this, the close button sits
    // under the notch on edge-to-edge devices (target SDK 35+). Added
    // to the top bar's top padding so the page mock + shutter row stay
    // where they were.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0E0D)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            // Centers the pagePreview Box (240dp wide) inside the
            // full-width Column. Spacing rows above + below already
            // use fillMaxWidth, so this only affects the page mock.
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = QuickInkSpacing.s5,
                        end    = QuickInkSpacing.s5,
                        top    = statusBarTop + QuickInkSpacing.s6,
                        bottom = QuickInkSpacing.s4,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(icon = Icons.Filled.Close, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                Text(text = "Capture", style = type.label, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.weight(1f))
                // Right-slot spacer to keep the title centred — the
                // flash button used to live here. Removed because
                // Google's scanner owns flash internally and we
                // can't drive it from outside (see header note).
                // Reserve the same 36dp footprint a CircleIconButton
                // claims so the layout doesn't shift when comparing
                // against older builds.
                Spacer(Modifier.size(36.dp))
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

                // Shutter — always launches the system scanner. The
                // scanner runs its own UI from there: edge
                // detection, capture, optional Add-page, Done. The
                // current `mode` selection feeds the launcher's
                // `pageLimit` (above): Single → 1, others → no
                // limit, so the in-scanner Add-page affordance is
                // hidden in Single and visible in Multi/Auto. The
                // earlier "increment pageCount, only launch on
                // first tap" code was a leftover from a custom
                // multi-page state machine that never shipped — it
                // made every shutter tap after the first a no-op.
                ShutterButton(onClick = scannerLauncher::launch)

                // Right slot — Import button (system photo picker).
                // Replaces ML Kit's in-scanner gallery tab so the
                // resulting capture can be tagged `source = "import"`
                // and the Library cards can render an "Import" pill.
                // Mirrors the slot the page badge claims on the left,
                // so the layout stays balanced around the shutter.
                Box(
                    modifier = Modifier.size(width = 64.dp, height = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ImportButton(
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
private fun ImportButton(onClick: () -> Unit) {
    // Visually quieter than the shutter — neutral white-on-translucent
    // disc, ~48dp, sized to fit inside the 64dp right slot without
    // dominating the row. Same surface treatment as the close button
    // in the top bar so it reads as a secondary affordance.
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
