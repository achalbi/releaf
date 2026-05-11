/*
 * FullscreenPdfDialog.kt
 *
 * Edge-to-edge flipbook viewer launched from `ScanDetailScreen`'s
 * inline preview. Opens in a `Dialog` configured with
 * `usePlatformDefaultWidth = false` so the content fills the entire
 * screen, with a black backdrop that pushes focus onto the page.
 *
 * Re-uses the same flipbook UX as the inline view:
 *   - HorizontalPager paging mechanic with a 3D `rotationY`
 *     book-flip animation per page
 *   - Pinch-to-zoom + pan + double-tap to toggle 1× / 2×
 *   - 1-finger drag at 1× falls through to the pager (so the swipe
 *     actually flips pages)
 *
 * Differences from the inline view: no border / corner-clip chrome,
 * darker overlay pills (white-on-black instead of ink-on-cream), and
 * a close button in the top-end corner instead of a fullscreen
 * affordance. Pages render at the full viewport size so the user
 * gets the largest possible read surface.
 *
 * Bitmaps are re-rendered here instead of being passed in from the
 * inline view: PdfRenderer is fast on the typical 1–10-page scans
 * the app produces, the dialog is short-lived, and re-rendering
 * keeps the public API surface of `PageTurnPdfView` (URI in)
 * consistent.
 */

package app.quickink.mobile.features.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FullscreenPdfDialog(
    pdfUri: Uri,
    initialPage: Int = 0,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        FullscreenPdfContent(
            pdfUri      = pdfUri,
            initialPage = initialPage,
            onClose     = onDismiss,
        )
    }
}

@Composable
private fun FullscreenPdfContent(
    pdfUri: Uri,
    initialPage: Int,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val type = LocalQuickInkTypography.current
    val scope = rememberCoroutineScope()

    var pages by remember(pdfUri) { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember(pdfUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(pdfUri) {
        try {
            pages = withContext(Dispatchers.IO) { renderPdfPages(context, pdfUri) }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open PDF"
        }
    }

    // Swipe-down-to-dismiss state. Tracks downward motion (px) and
    // animates back to 0 when released below the threshold, or fires
    // `onClose` past it. `detectVerticalDragGestures` only consumes
    // events after vertical pointer slop is exceeded, so horizontal
    // page-swipes inside `HorizontalPager` still flow through.
    var dismissDragOffset by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val dismissDistanceThresholdPx = with(density) { 150.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dismissDragOffset = 0f },
                    onDragCancel = {
                        scope.launch {
                            animate(
                                initialValue = dismissDragOffset,
                                targetValue  = 0f,
                                animationSpec = tween(durationMillis = 220),
                            ) { value, _ -> dismissDragOffset = value }
                        }
                    },
                    onDragEnd = {
                        if (dismissDragOffset > dismissDistanceThresholdPx) {
                            onClose()
                        } else {
                            scope.launch {
                                animate(
                                    initialValue = dismissDragOffset,
                                    targetValue  = 0f,
                                    animationSpec = tween(durationMillis = 220),
                                ) { value, _ -> dismissDragOffset = value }
                            }
                        }
                    },
                    onVerticalDrag = { _, dragAmount ->
                        // Only follow downward motion — clamp to 0 so
                        // an upward overshoot doesn't pull the page
                        // above its natural position.
                        dismissDragOffset = (dismissDragOffset + dragAmount)
                            .coerceAtLeast(0f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, dismissDragOffset.toInt()) },
            contentAlignment = Alignment.Center,
        ) {
            when {
                error != null -> {
                    Text(
                        text  = error!!,
                        style = type.meta,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(QuickInkSpacing.s4),
                    )
                }
                pages == null -> {
                    CircularProgressIndicator(color = Color.White)
                }
                else -> {
                    FullscreenFlipbook(
                        pages       = pages!!,
                        initialPage = initialPage.coerceIn(0, (pages!!.size - 1).coerceAtLeast(0)),
                    )
                }
            }
        }

        // Top bar overlays. Sit above the content so the close hit
        // target stays reachable even while a page is zoomed. Stays
        // in place during the swipe-down drag too — the close button
        // is always a tap away.
        val topInsets = WindowInsets.statusBars.asPaddingValues()
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(topInsets)
                .padding(QuickInkSpacing.s3)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Close,
                contentDescription = "Close fullscreen",
                tint               = Color.White,
                modifier           = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun FullscreenFlipbook(
    pages: List<Bitmap>,
    initialPage: Int,
) {
    val type = LocalQuickInkTypography.current
    val density = LocalDensity.current

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount   = { pages.size },
    )

    var zoomScale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        zoomScale = 1f
        panOffset = Offset.Zero
    }
    val isZoomed = zoomScale > 1.01f

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state             = pagerState,
            userScrollEnabled = !isZoomed,
            modifier          = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val pageOffset = (
                (pagerState.currentPage - pageIndex)
                + pagerState.currentPageOffsetFraction
            ).let { -it }

            val bitmap   = pages[pageIndex]
            val ratio    = (bitmap.width.toFloat()
                / bitmap.height.toFloat().coerceAtLeast(1f))
                .coerceIn(0.4f, 2.5f)
            val isActive = (pageIndex == pagerState.currentPage)
            val pageScale = if (isActive) zoomScale else 1f
            val pagePan   = if (isActive) panOffset else Offset.Zero

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap             = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale       = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(ratio)
                        .pointerInput(pageIndex) {
                            // Mirror of the inline view's gesture
                            // detector — only consume pointer events
                            // on real pinch (2+ fingers) or while
                            // zoomed (pan). Lets the pager handle
                            // 1-finger swipe-to-flip.
                            if (!isActive) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pointerCount = event.changes.count { it.pressed }
                                    val shouldHandle = pointerCount >= 2 || zoomScale > 1.01f
                                    if (shouldHandle) {
                                        val zoomDelta = event.calculateZoom()
                                        val panDelta = event.calculatePan()
                                        if (zoomDelta != 1f || panDelta != Offset.Zero) {
                                            zoomScale = (zoomScale * zoomDelta).coerceIn(1f, 5f)
                                            panOffset = if (zoomScale <= 1.01f) {
                                                Offset.Zero
                                            } else {
                                                clampFullscreenPan(
                                                    pan        = panOffset + panDelta,
                                                    scale      = zoomScale,
                                                    layoutSize = size,
                                                )
                                            }
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(pageIndex) {
                            if (!isActive) return@pointerInput
                            detectTapGestures(
                                onDoubleTap = {
                                    if (zoomScale > 1f) {
                                        zoomScale = 1f
                                        panOffset = Offset.Zero
                                    } else {
                                        zoomScale = 2.5f
                                    }
                                }
                            )
                        }
                        .graphicsLayer {
                            scaleX        = pageScale
                            scaleY        = pageScale
                            translationX  = pagePan.x
                            translationY  = pagePan.y

                            if (!(isActive && isZoomed)) {
                                cameraDistance = 12f * density.density
                                val clamped = pageOffset.coerceIn(-1f, 1f)
                                rotationY = clamped * 75f
                                transformOrigin = TransformOrigin(
                                    pivotFractionX = if (clamped > 0) 0f else 1f,
                                    pivotFractionY = 0.5f,
                                )
                            }
                            alpha = if (pageOffset.absoluteValue > 1f) 0f else 1f
                        },
                )
            }
        }

        if (pages.size > 1) {
            // Page count pill — dark variant for the black backdrop.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = QuickInkSpacing.s5)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            ) {
                Text(
                    text  = "${pagerState.currentPage + 1} / ${pages.size}",
                    style = type.caption,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Same envelope clamp as the inline `PageTurnPdfView.clampPan`, kept
 * locally to avoid leaking a `private` symbol across files. See the
 * inline view's docstring for the geometry rationale.
 */
private fun clampFullscreenPan(
    pan: Offset,
    scale: Float,
    layoutSize: IntSize,
): Offset {
    val overflow = (scale - 1f).coerceAtLeast(0f)
    val maxX = (layoutSize.width  * overflow) / 2f
    val maxY = (layoutSize.height * overflow) / 2f
    return Offset(
        x = pan.x.coerceIn(-maxX, maxX),
        y = pan.y.coerceIn(-maxY, maxY),
    )
}
