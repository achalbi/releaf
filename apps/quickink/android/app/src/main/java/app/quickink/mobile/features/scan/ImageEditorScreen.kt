/*
 * ImageEditorScreen.kt
 *
 * WhatsApp-style image editor that the "Share as Image" flow on
 * `ScanDetailScreen` routes through before the system share sheet.
 *
 * What the user can do per page:
 *   - Crop with a draggable 4-corner overlay.
 *   - Doodle freehand strokes on a Compose `Canvas`. 5-color palette
 *     + eraser (the eraser strokes blend with `BlendMode.Clear` so
 *     they punch through any earlier ink).
 *
 * Flow for multi-page captures:
 *   - Pages are presented one at a time. Bottom-right CTA says
 *     "Next" until the last page, then flips to "Share".
 *   - Back arrow steps back through edited pages preserving edits.
 *   - Tapping Share commits everything; `onDone` receives the
 *     final `[Bitmap]` list. The caller writes the JPEGs and opens
 *     the share intent.
 *
 * Crop coords are normalised [0..1] in image space so the per-page
 * state survives recomposition that changes the canvas size.
 * Strokes are tracked in normalised image space too so they
 * preserve their position when the screen rotates or the bitmap
 * layout reflows.
 *
 * Mirror of iOS `ImageEditorScreen.swift`.
 */

package app.quickink.mobile.features.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ImageEditorScreen(
    pages: List<Bitmap>,
    onCancel: () -> Unit,
    onDone: (List<Bitmap>) -> Unit,
) {
    if (pages.isEmpty()) {
        // Defensive — caller should bail before opening the editor,
        // but if we somehow land here, dismiss instead of NPE.
        onCancel()
        return
    }

    var pageIndex by remember { mutableIntStateOf(0) }

    // Working copy of the source pages so a Crop → Done step can
    // permanently replace a page with its cropped version. The
    // original input list stays untouched for callers.
    val workingPages: SnapshotStateList<Bitmap> = remember(pages) {
        mutableStateListOf<Bitmap>().apply { addAll(pages) }
    }

    // Per-page normalised crop rect (origin + size in 0..1, image
    // space). Defaults to the full image; reset to (0,0,1,1) after
    // every commit so the next crop session starts fresh.
    val cropRects: SnapshotStateList<NormRect> = remember(pages) {
        mutableStateListOf<NormRect>().apply {
            repeat(pages.size) { add(NormRect(0f, 0f, 1f, 1f)) }
        }
    }

    // Per-page stroke list. Strokes are stored in normalised image
    // space so they survive layout changes. Cleared on crop commit
    // because the underlying image swap invalidates the coords.
    val strokesByPage: SnapshotStateList<SnapshotStateList<EditorStroke>> = remember(pages) {
        mutableStateListOf<SnapshotStateList<EditorStroke>>().apply {
            repeat(pages.size) { add(mutableStateListOf()) }
        }
    }

    var currentTool by remember { mutableStateOf(ImageEditorTool.Pencil) }
    var currentColor by remember { mutableStateOf(PaletteColor.Coral) }
    /// Brush size for both pencil and eraser. Three preset steps
    /// keep the picker visually compact while covering the typical
    /// detail-vs-broad-mark range.
    var brushSize by remember { mutableStateOf(BrushSize.Medium) }

    // `statusBars` returns 0 while the bar is hidden, but
    // `statusBarsIgnoringVisibility` keeps reporting the OS-known
    // status-bar height (i.e. the camera-cutout area on phones
    // with a notch / hole-punch). Plus a small extra margin so the
    // "Edit" title visually clears the cutout instead of sitting
    // right against it.
    val statusBarTop = WindowInsets.statusBarsIgnoringVisibility
        .asPaddingValues()
        .calculateTopPadding()

    // Edge-to-edge: paint behind the system status bar / nav bar
    // (the host Dialog already covers the activity). The status-bar
    // height is still padded into the top bar so the Close chip
    // doesn't sit under the notch / camera cut-out.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F10)),
    ) {
        EditorTopBar(
            pageLabel        = if (workingPages.size > 1) "Page ${pageIndex + 1} of ${workingPages.size}" else "Edit",
            ctaLabel         = if (pageIndex == workingPages.size - 1) "Share" else "Next",
            statusBarPadding = statusBarTop,
            onCancel         = onCancel,
            onAdvance        = {
                if (pageIndex < workingPages.size - 1) {
                    pageIndex += 1
                } else {
                    val edited = workingPages.indices.map { i ->
                        renderEdited(
                            source  = workingPages[i],
                            crop    = cropRects[i],
                            strokes = strokesByPage[i],
                        )
                    }
                    onDone(edited)
                }
            },
        )

        EditorCanvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            bitmap        = workingPages[pageIndex],
            strokes       = strokesByPage[pageIndex],
            cropRect      = cropRects[pageIndex],
            onCropChange  = { cropRects[pageIndex] = it },
            currentTool   = currentTool,
            currentColor  = currentColor,
            brushSize     = brushSize,
        )

        ImageEditorToolbar(
            currentTool   = currentTool,
            currentColor  = currentColor,
            brushSize     = brushSize,
            canStepBack   = pageIndex > 0,
            onSelectTool  = { currentTool = it },
            onSelectColor = {
                currentColor = it
                // Stick with whichever inking tool is active so the
                // user can recolour the highlighter without falling
                // back to pencil. Crop/Eraser jump back to pencil
                // on colour tap because they don't carry colour.
                if (currentTool != ImageEditorTool.Pencil &&
                    currentTool != ImageEditorTool.Highlighter) {
                    currentTool = ImageEditorTool.Pencil
                }
            },
            onSelectBrushSize = { brushSize = it },
            onSelectEraser = { currentTool = ImageEditorTool.Eraser },
            onStepBack    = { if (pageIndex > 0) pageIndex -= 1 },
            // Commits the in-flight crop rect: replace the working
            // bitmap with the cropped version, reset the rect to
            // full, drop any strokes that were drawn against the
            // pre-crop coords, and flip back to the pencil tool so
            // the user sees the freshly cropped preview.
            onCropDone     = {
                val src  = workingPages[pageIndex]
                val rect = cropRects[pageIndex]
                workingPages[pageIndex]   = cropBitmap(src, rect)
                cropRects[pageIndex]      = NormRect(0f, 0f, 1f, 1f)
                strokesByPage[pageIndex].clear()
                currentTool = ImageEditorTool.Pencil
            },
            // Reset — restore the current page to its original
            // input bitmap, drop the crop rect, and clear all
            // strokes. The `pages` parameter is the immutable
            // original list; `workingPages` is the editable copy
            // we keep replacing on crop commits.
            onReset        = {
                workingPages[pageIndex] = pages[pageIndex]
                cropRects[pageIndex]    = NormRect(0f, 0f, 1f, 1f)
                strokesByPage[pageIndex].clear()
                currentTool = ImageEditorTool.Pencil
            },
        )
    }
}

// ─── Sub-views ────────────────────────────────────────────────

@Composable
private fun EditorTopBar(
    pageLabel: String,
    ctaLabel: String,
    statusBarPadding: androidx.compose.ui.unit.Dp,
    onCancel: () -> Unit,
    onAdvance: () -> Unit,
) {
    // The Close icon chip (36dp circle) and the Share pill (wider,
    // with pill padding) are different widths, so the previous
    // Row + weighted Spacers pushed the title off the screen
    // midline. Layering the title as a Box-centred overlay pins
    // it to the centre regardless of chip widths.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = 16.dp,
                end    = 16.dp,
                top    = (statusBarPadding - 6.dp).coerceAtLeast(0.dp),
                bottom = 10.dp,
            ),
    ) {
        Text(
            text       = pageLabel,
            fontSize   = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            modifier   = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconChip(icon = Icons.Filled.Close, contentDesc = "Cancel", onClick = onCancel)
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFE07856))
                    .clickable(onClick = onAdvance)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text       = ctaLabel,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                )
            }
        }
    }
}

@Composable
private fun EditorCanvas(
    modifier: Modifier = Modifier,
    bitmap: Bitmap,
    strokes: SnapshotStateList<EditorStroke>,
    cropRect: NormRect,
    onCropChange: (NormRect) -> Unit,
    currentTool: ImageEditorTool,
    currentColor: PaletteColor,
    brushSize: BrushSize,
) {
    val density = LocalDensity.current
    // Visible image rect inside this Box. Updated by
    // `onGloballyPositioned` on the Image so all overlays know the
    // aspect-fitted box to translate normalised coords through.
    var imageBoxSize by remember { mutableStateOf(ComposeSize.Zero) }
    var imageBoxOffset by remember { mutableStateOf(Offset.Zero) }
    // In-flight pencil stroke — committed to `strokes` on release.
    var inFlight by remember { mutableStateOf<EditorStroke?>(null) }

    Box(modifier = modifier) {
        // Centered image — we use BoxWithConstraints semantics via
        // a fixed aspect contain. The Image itself stretches to the
        // intrinsic ratio; we ride `onGloballyPositioned` to learn
        // the actual rendered rect.
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    imageBoxSize   = coords.size.let { ComposeSize(it.width.toFloat(), it.height.toFloat()) }
                    imageBoxOffset = coords.boundsInParent().topLeft
                },
        )

        // Drawing layer — overlays the entire Box; we clip strokes
        // to the image rect by translating from normalised coords.
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(currentTool, currentColor, brushSize) {
                    if (currentTool == ImageEditorTool.Pencil ||
                        currentTool == ImageEditorTool.Highlighter ||
                        currentTool == ImageEditorTool.Eraser) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val rect = imageRectInParent(imageBoxSize, bitmap)
                                val norm = denormalise(start, rect) ?: return@detectDragGestures
                                val isEraser      = currentTool == ImageEditorTool.Eraser
                                val isHighlighter = currentTool == ImageEditorTool.Highlighter
                                val width = when {
                                    isEraser      -> brushSize.eraserPx
                                    isHighlighter -> brushSize.highlighterPx
                                    else          -> brushSize.pencilPx
                                }
                                inFlight = EditorStroke(
                                    color         = currentColor,
                                    width         = width,
                                    isEraser      = isEraser,
                                    isHighlighter = isHighlighter,
                                    points        = mutableListOf(norm),
                                )
                            },
                            onDrag = { change, _ ->
                                val rect = imageRectInParent(imageBoxSize, bitmap)
                                val norm = denormalise(change.position, rect) ?: return@detectDragGestures
                                inFlight = inFlight?.copy(
                                    points = (inFlight!!.points + norm).toMutableList(),
                                )
                            },
                            onDragEnd = {
                                inFlight?.let { strokes.add(it) }
                                inFlight = null
                            },
                            onDragCancel = { inFlight = null },
                        )
                    }
                },
        ) {
            val rect = imageRectInParent(imageBoxSize, bitmap)
            // Render persisted strokes first, then the in-flight one
            // so the active stroke renders on top.
            (strokes + listOfNotNull(inFlight)).forEach { stroke ->
                if (stroke.points.size < 2) return@forEach
                val path = ComposePath()
                val first = denormToScreen(stroke.points.first(), rect)
                path.moveTo(first.x, first.y)
                for (i in 1 until stroke.points.size) {
                    val pt = denormToScreen(stroke.points[i], rect)
                    path.lineTo(pt.x, pt.y)
                }
                // Highlighter renders with reduced alpha so the
                // underlying scan still reads through. Eraser uses
                // Clear to punch transparency through earlier ink.
                // Pencil is opaque SrcOver.
                val strokeAlpha = if (stroke.isHighlighter) 0.38f else 1f
                drawPath(
                    path  = path,
                    color = if (stroke.isEraser) Color.Transparent
                            else stroke.color.composeColor.copy(alpha = strokeAlpha),
                    style = DrawStroke(
                        width = stroke.width,
                        cap   = StrokeCap.Round,
                        join  = StrokeJoin.Round,
                    ),
                    blendMode = if (stroke.isEraser) BlendMode.Clear else BlendMode.SrcOver,
                )
            }
        }

        // Crop overlay — dim outside + 4 corner handles. Interactive
        // only when the Crop tool is selected; pointerInput on the
        // handles updates the normalised crop rect.
        CropOverlay(
            normRect      = cropRect,
            onChange      = onCropChange,
            enabled       = currentTool == ImageEditorTool.Crop,
            imageBoxSize  = imageBoxSize,
            bitmap        = bitmap,
            modifier      = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CropOverlay(
    normRect: NormRect,
    onChange: (NormRect) -> Unit,
    enabled: Boolean,
    imageBoxSize: ComposeSize,
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val grabRadiusPx = with(density) { 56.dp.toPx() }

    val rect = remember(imageBoxSize, bitmap) { imageRectInParent(imageBoxSize, bitmap) }
    val screenRect = ScreenRect(
        x = rect.x + normRect.x * rect.w,
        y = rect.y + normRect.y * rect.h,
        w = normRect.w * rect.w,
        h = normRect.h * rect.h,
    )

    // Single drag-detector on the whole overlay. At drag-start we
    // pick whichever corner is closest to the touch and within a
    // generous grab radius; subsequent drag events update that
    // corner.
    //
    // CRITICAL: `pointerInput` only re-keys on `rect` + `enabled`.
    // We deliberately DO NOT include `normRect` in the keys —
    // otherwise every drag-induced update would re-launch the
    // gesture coroutine mid-drag and the rect would feel sticky.
    // Fresh `normRect` and `onChange` reads inside the suspend
    // block come from `rememberUpdatedState` snapshots.
    val currentNormRect by androidx.compose.runtime.rememberUpdatedState(normRect)
    val currentOnChange by androidx.compose.runtime.rememberUpdatedState(onChange)
    val currentScreenRect by androidx.compose.runtime.rememberUpdatedState(screenRect)
    var grabbed by remember { mutableStateOf<Corner?>(null) }
    val dragModifier = if (enabled) {
        Modifier.pointerInput(rect) {
            detectDragGestures(
                onDragStart = { startPos ->
                    grabbed = nearestCornerWithinRadius(
                        startPos,
                        currentScreenRect,
                        grabRadiusPx,
                    )
                },
                onDrag = { change, drag ->
                    val corner = grabbed ?: return@detectDragGestures
                    val deltaXNorm = drag.x / max(rect.w, 1f)
                    val deltaYNorm = drag.y / max(rect.h, 1f)
                    val minSize = 0.1f
                    var r = currentNormRect
                    when (corner) {
                        Corner.TopLeading -> {
                            val newX = (r.x + deltaXNorm).coerceIn(0f, r.x + r.w - minSize)
                            val newY = (r.y + deltaYNorm).coerceIn(0f, r.y + r.h - minSize)
                            r = NormRect(newX, newY, r.x + r.w - newX, r.y + r.h - newY)
                        }
                        Corner.TopTrailing -> {
                            val newW = (r.w + deltaXNorm).coerceAtLeast(minSize)
                                .coerceAtMost(1f - r.x)
                            val newY = (r.y + deltaYNorm).coerceIn(0f, r.y + r.h - minSize)
                            r = NormRect(r.x, newY, newW, r.y + r.h - newY)
                        }
                        Corner.BottomLeading -> {
                            val newX = (r.x + deltaXNorm).coerceIn(0f, r.x + r.w - minSize)
                            val newH = (r.h + deltaYNorm).coerceAtLeast(minSize)
                                .coerceAtMost(1f - r.y)
                            r = NormRect(newX, r.y, r.x + r.w - newX, newH)
                        }
                        Corner.BottomTrailing -> {
                            val newW = (r.w + deltaXNorm).coerceAtLeast(minSize)
                                .coerceAtMost(1f - r.x)
                            val newH = (r.h + deltaYNorm).coerceAtLeast(minSize)
                                .coerceAtMost(1f - r.y)
                            r = NormRect(r.x, r.y, newW, newH)
                        }
                    }
                    currentOnChange(r)
                    change.consume()
                },
                onDragEnd = { grabbed = null },
                onDragCancel = { grabbed = null },
            )
        }
    } else Modifier

    Box(modifier = modifier.then(dragModifier)) {
        // Four-rectangle dim — top / bottom / left / right around
        // the crop window. Sticking to rectangles dodges the iOS-
        // 17-only path-subtract dance. Visible handles render last
        // so the dot sits on top of the dim.
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val sz = size
            val dim = Color.Black.copy(alpha = 0.45f)
            // Top
            drawRect(color = dim,
                topLeft = Offset(0f, 0f),
                size = ComposeSize(sz.width, screenRect.y))
            // Bottom
            drawRect(color = dim,
                topLeft = Offset(0f, screenRect.y + screenRect.h),
                size = ComposeSize(sz.width, sz.height - (screenRect.y + screenRect.h)))
            // Left
            drawRect(color = dim,
                topLeft = Offset(0f, screenRect.y),
                size = ComposeSize(screenRect.x, screenRect.h))
            // Right
            drawRect(color = dim,
                topLeft = Offset(screenRect.x + screenRect.w, screenRect.y),
                size = ComposeSize(sz.width - (screenRect.x + screenRect.w), screenRect.h))
            // Inner border
            drawRect(
                color   = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(screenRect.x, screenRect.y),
                size    = ComposeSize(screenRect.w, screenRect.h),
                style   = DrawStroke(width = 1f),
            )

            // Corner dots — purely visual; the single drag-detector
            // above owns the gestures.
            if (enabled) {
                val dotRadius = with(density) { 9.dp.toPx() }
                val cornerPoints = listOf(
                    Offset(screenRect.x, screenRect.y),
                    Offset(screenRect.x + screenRect.w, screenRect.y),
                    Offset(screenRect.x, screenRect.y + screenRect.h),
                    Offset(screenRect.x + screenRect.w, screenRect.y + screenRect.h),
                )
                cornerPoints.forEach { p ->
                    drawCircle(color = Color.White, radius = dotRadius, center = p)
                }
            }
        }
    }
}

/// Distance² between two points — cheaper than `sqrt` for ranking.
private fun distSq(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

/// Closest corner to `pos` from the supplied rect's four corners,
/// or null if all four are farther than `radius` away. Lets the
/// user grab a corner by tapping anywhere reasonably near it.
private fun nearestCornerWithinRadius(
    pos: Offset,
    rect: ScreenRect,
    radius: Float,
): Corner? {
    val candidates = listOf(
        Corner.TopLeading      to Offset(rect.x, rect.y),
        Corner.TopTrailing     to Offset(rect.x + rect.w, rect.y),
        Corner.BottomLeading   to Offset(rect.x, rect.y + rect.h),
        Corner.BottomTrailing  to Offset(rect.x + rect.w, rect.y + rect.h),
    )
    val r2 = radius * radius
    val (corner, d2) = candidates
        .map { it.first to distSq(pos, it.second) }
        .minByOrNull { it.second } ?: return null
    return if (d2 <= r2) corner else null
}

private enum class Corner { TopLeading, TopTrailing, BottomLeading, BottomTrailing }

@Composable
private fun ImageEditorToolbar(
    currentTool: ImageEditorTool,
    currentColor: PaletteColor,
    brushSize: BrushSize,
    canStepBack: Boolean,
    onSelectTool: (ImageEditorTool) -> Unit,
    onSelectColor: (PaletteColor) -> Unit,
    onSelectBrushSize: (BrushSize) -> Unit,
    onSelectEraser: () -> Unit,
    onStepBack: () -> Unit,
    onCropDone: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.spacedBy(10.dp),
    ) {
        // Color palette + brush size — only visible while drawing
        // tools are active. Eraser has moved into the tool row
        // below so the palette stays colours + size only.
        if (currentTool == ImageEditorTool.Pencil ||
            currentTool == ImageEditorTool.Highlighter ||
            currentTool == ImageEditorTool.Eraser) {
            // Brush size picker — 3 dots whose visual radii match
            // the stroke they apply to. Affects both pencil and
            // eraser; the stroke-creation site picks the right
            // px value via `BrushSize.pencilPx` / `.eraserPx`.
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                BrushSize.values().forEach { size ->
                    BrushSizeDot(
                        size    = size,
                        active  = size == brushSize,
                        onClick = { onSelectBrushSize(size) },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                PaletteColor.values().forEach { swatch ->
                    val isInkingTool = currentTool == ImageEditorTool.Pencil ||
                        currentTool == ImageEditorTool.Highlighter
                    ColorDot(
                        color   = swatch.composeColor,
                        active  = isInkingTool && swatch == currentColor,
                        onClick = { onSelectColor(swatch) },
                    )
                }
            }
        }
        // Crop confirm CTA — only visible while the Crop tool is
        // active. Tap commits the rect into the working image and
        // flips the editor back to the pencil tool so the user
        // sees the cropped preview.
        if (currentTool == ImageEditorTool.Crop) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFE07856))
                    .clickable(onClick = onCropDone)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    text       = "Done",
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            ToolButton(
                icon    = Icons.Outlined.Refresh,
                active  = false,
                onClick = onReset,
                contentDesc = "Reset edits",
            )
            ToolButton(
                icon    = Icons.Outlined.Crop,
                active  = currentTool == ImageEditorTool.Crop,
                onClick = { onSelectTool(ImageEditorTool.Crop) },
                contentDesc = "Crop",
            )
            ToolButton(
                icon    = Icons.Outlined.Edit,
                active  = currentTool == ImageEditorTool.Pencil,
                onClick = { onSelectTool(ImageEditorTool.Pencil) },
                contentDesc = "Pencil",
            )
            ToolButton(
                icon    = Icons.Outlined.BorderColor,
                active  = currentTool == ImageEditorTool.Highlighter,
                onClick = { onSelectTool(ImageEditorTool.Highlighter) },
                contentDesc = "Highlighter",
            )
            ToolButton(
                icon    = Icons.Outlined.FormatColorReset,
                active  = currentTool == ImageEditorTool.Eraser,
                onClick = onSelectEraser,
                contentDesc = "Eraser",
            )
            if (canStepBack) {
                IconChip(
                    icon       = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDesc = "Previous page",
                    onClick    = onStepBack,
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun BrushSizeDot(size: BrushSize, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(
                if (active) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Visual dot scales with brush size so the picker reads as
        // "small/medium/large" without a label.
        Box(
            modifier = Modifier
                .size(size.dotDp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    contentDesc: String,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = contentDesc,
            tint              = Color.White,
            modifier          = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = contentDesc,
            tint              = Color.White,
            modifier          = Modifier.size(16.dp),
        )
    }
}

// ─── Geometry helpers ─────────────────────────────────────────

private data class ImageRect(val x: Float, val y: Float, val w: Float, val h: Float)
private data class ScreenRect(val x: Float, val y: Float, val w: Float, val h: Float)

/** Aspect-fit `bitmap` into `box`, returning the displayed rect. */
private fun imageRectInParent(box: ComposeSize, bitmap: Bitmap): ImageRect {
    if (box.width <= 0f || box.height <= 0f) return ImageRect(0f, 0f, 0f, 0f)
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    if (bw <= 0f || bh <= 0f) return ImageRect(0f, 0f, 0f, 0f)
    val scale = min(box.width / bw, box.height / bh)
    val w = bw * scale
    val h = bh * scale
    return ImageRect(
        x = (box.width  - w) / 2f,
        y = (box.height - h) / 2f,
        w = w,
        h = h,
    )
}

/** Screen point → normalised image-space [0..1], or null if outside. */
private fun denormalise(point: Offset, rect: ImageRect): Offset? {
    if (rect.w <= 0f || rect.h <= 0f) return null
    val nx = (point.x - rect.x) / rect.w
    val ny = (point.y - rect.y) / rect.h
    if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
    return Offset(nx, ny)
}

private fun denormToScreen(point: Offset, rect: ImageRect): Offset {
    return Offset(rect.x + point.x * rect.w, rect.y + point.y * rect.h)
}

// ─── State types ──────────────────────────────────────────────

internal data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float)

internal data class EditorStroke(
    val color: PaletteColor,
    val width: Float,
    val isEraser: Boolean,
    val isHighlighter: Boolean = false,
    val points: MutableList<Offset>,
)

internal enum class ImageEditorTool { Crop, Pencil, Highlighter, Eraser }

/**
 * Brush-size preset. Pencil and eraser share a 3-step picker; each
 * tool reads its own pixel width from `pencilPx` / `eraserPx`. The
 * `dotDp` value drives the picker dot's visual radius so the UI
 * reads as small / medium / large without needing labels.
 */
internal enum class BrushSize(
    val pencilPx: Float,
    val highlighterPx: Float,
    val eraserPx: Float,
    val dotDp: androidx.compose.ui.unit.Dp,
) {
    Small (pencilPx = 3f,  highlighterPx = 18f, eraserPx = 12f, dotDp = 6.dp),
    Medium(pencilPx = 6f,  highlighterPx = 30f, eraserPx = 24f, dotDp = 12.dp),
    Large (pencilPx = 12f, highlighterPx = 48f, eraserPx = 40f, dotDp = 20.dp),
}

internal enum class PaletteColor(val composeColor: Color) {
    Coral(Color(0xFFE07856)),
    Charcoal(Color(0xFF272220)),
    White(Color.White),
    Yellow(Color(0xFFFFCB33)),
    Blue(Color(0xFF3373D9)),
}

// ─── Render output ────────────────────────────────────────────

/**
 * Composite the source bitmap with its crop rect + strokes into a
 * single output Bitmap. The cropped image is the base; strokes are
 * drawn on top in the cropped coordinate space.
 */
/**
 * Crop `source` to `crop` (normalised [0..1] image-space rect) and
 * return a fresh Bitmap. Used by the Crop tool's Done action so the
 * editor's working bitmap reflects the committed crop and downstream
 * tools (pencil) run against the cropped canvas.
 */
private fun cropBitmap(source: Bitmap, crop: NormRect): Bitmap {
    val sw = source.width
    val sh = source.height
    val left   = (crop.x * sw).toInt().coerceIn(0, sw)
    val top    = (crop.y * sh).toInt().coerceIn(0, sh)
    val right  = ((crop.x + crop.w) * sw).toInt().coerceIn(0, sw)
    val bottom = ((crop.y + crop.h) * sh).toInt().coerceIn(0, sh)
    val cw = (right  - left).coerceAtLeast(1)
    val ch = (bottom - top ).coerceAtLeast(1)
    return Bitmap.createBitmap(source, left, top, cw, ch)
}

private fun renderEdited(
    source: Bitmap,
    crop: NormRect,
    strokes: List<EditorStroke>,
): Bitmap {
    // 1. Crop region in source pixel space.
    val sw = source.width
    val sh = source.height
    val cropPx = Rect(
        (crop.x * sw).toInt().coerceIn(0, sw),
        (crop.y * sh).toInt().coerceIn(0, sh),
        ((crop.x + crop.w) * sw).toInt().coerceIn(0, sw),
        ((crop.y + crop.h) * sh).toInt().coerceIn(0, sh),
    )
    val cw = (cropPx.right  - cropPx.left).coerceAtLeast(1)
    val ch = (cropPx.bottom - cropPx.top ).coerceAtLeast(1)
    val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(source, cropPx, Rect(0, 0, cw, ch), null)

    // 2. Strokes — points are normalised to the full source. Map
    //    them into the cropped pixel rect.
    if (strokes.isEmpty()) return out
    val pencilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap  = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode  = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val paint = if (stroke.isEraser) eraserPaint else pencilPaint
        paint.strokeWidth = stroke.width
        val baseArgb = stroke.color.composeColor.toArgb()
        paint.color = if (stroke.isHighlighter) {
            // 38% alpha to match the live-canvas render.
            (baseArgb and 0x00FFFFFF) or (0x61 shl 24)
        } else baseArgb
        val path = android.graphics.Path()
        val first = stroke.points.first()
        val firstX = (first.x * sw) - cropPx.left
        val firstY = (first.y * sh) - cropPx.top
        path.moveTo(firstX, firstY)
        for (i in 1 until stroke.points.size) {
            val pt = stroke.points[i]
            path.lineTo((pt.x * sw) - cropPx.left, (pt.y * sh) - cropPx.top)
        }
        canvas.drawPath(path, paint)
    }
    return out
}

