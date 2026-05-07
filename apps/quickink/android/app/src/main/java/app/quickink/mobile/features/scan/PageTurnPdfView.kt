/*
 * PageTurnPdfView.kt
 *
 * Multi-page PDF viewer with one-page-at-a-time layout + a 3D
 * page-turn animation on swipe. Mirror of iOS's `PageTurnPdfView`.
 * Used by `ScanDetailScreen` whenever the capture has more than one
 * page; single-page captures keep the scrollable `PdfPagesView` so
 * users get pinch-to-zoom for free.
 *
 * Rendering: `PdfRenderer` rasterises each page to a `Bitmap` once
 * on first appear via the existing `renderPdfPages` helper from
 * `PdfPagesView.kt`. Cache lives in a `remember` for the lifetime
 * of the screen.
 *
 * Animation: HorizontalPager handles the swipe + paging mechanics;
 * `Modifier.graphicsLayer` per page applies a `rotationY` driven by
 * the page's offset from the active page. The rotation pivots
 * around the page's leading or trailing edge depending on which
 * direction it's exiting — simple book-flip cue without splitting
 * the page geometry. `cameraDistance` adds depth so the rotation
 * reads as 3D, not flat.
 *
 * Gesture handling: a custom `awaitEachGesture` detector only claims
 * the pointer stream when there are 2+ fingers down (pinch) OR the
 * page is already zoomed (pan). Single-finger drags at 1× fall
 * through to the HorizontalPager so swipe-to-flip works as expected
 * — without this, `detectTransformGestures` ate the slop and the
 * pager never saw the swipe.
 */

package app.quickink.mobile.features.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlin.math.absoluteValue
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clamp [pan] so the scaled page can't drift past the page-frame
 * edges. The maximum allowed offset on each axis is half the
 * scaled-vs-natural overflow: at scale 1× there's no overflow, so
 * the envelope collapses to (0, 0); at 4× the page is 4× wider /
 * taller, so the envelope is `(layoutSize.width × 3) / 2` on each
 * side. Pixel-coordinates throughout — [layoutSize] comes from the
 * pointerInput scope's `size`, which is in the same units as the
 * graphicsLayer's `translationX/Y`.
 */
private fun clampPan(pan: Offset, scale: Float, layoutSize: IntSize): Offset {
    val overflow = (scale - 1f).coerceAtLeast(0f)
    val maxX = (layoutSize.width  * overflow) / 2f
    val maxY = (layoutSize.height * overflow) / 2f
    return Offset(
        x = pan.x.coerceIn(-maxX, maxX),
        y = pan.y.coerceIn(-maxY, maxY),
    )
}

@Composable
fun PageTurnPdfView(
    pdfUri: Uri,
    modifier: Modifier = Modifier,
    onFullscreenClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val density = LocalDensity.current

    var pages by remember(pdfUri) { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember(pdfUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(pdfUri) {
        try {
            pages = withContext(Dispatchers.IO) { renderPdfPages(context, pdfUri) }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open PDF"
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            error != null -> {
                Text(
                    text  = error!!,
                    style = type.meta,
                    color = colors.inkSoft,
                    modifier = Modifier.padding(QuickInkSpacing.s4),
                )
            }
            pages == null -> {
                CircularProgressIndicator(color = colors.accent)
            }
            else -> {
                PageTurnPager(
                    pages             = pages!!,
                    onFullscreenClick = onFullscreenClick,
                )
            }
        }
    }
}

@Composable
private fun PageTurnPager(
    pages: List<Bitmap>,
    onFullscreenClick: (() -> Unit)? = null,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val density = LocalDensity.current

    val pagerState = rememberPagerState(pageCount = { pages.size })

    // Pinch-to-zoom + pan state for the ACTIVE page only. Reset to
    // defaults whenever the active page changes so each page starts
    // at fit-to-frame. Without the reset, a user who zoomed page 3
    // would land on page 4 already zoomed.
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
            // Lock the pager while the active page is zoomed — pinch
            // + pan get exclusive ownership of touch in that state,
            // so a horizontal pan doesn't accidentally flip pages.
            userScrollEnabled = !isZoomed,
            modifier          = Modifier.fillMaxSize(),
        ) { pageIndex ->
            // Distance of THIS page from the currently centred page.
            // Negative when the page sits to the left of the active
            // one; positive when right; 0 means dead-centre.
            val pageOffset = (
                (pagerState.currentPage - pageIndex)
                + pagerState.currentPageOffsetFraction
            ).let { -it } // flip sign so right-of-centre is positive

            val bitmap   = pages[pageIndex]
            val ratio    = (bitmap.width.toFloat()
                / bitmap.height.toFloat().coerceAtLeast(1f))
                .coerceIn(0.5f, 2f)
            val isActive = (pageIndex == pagerState.currentPage)
            val pageScale = if (isActive) zoomScale else 1f
            val pagePan   = if (isActive) panOffset else Offset.Zero

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(ratio)
                    .pointerInput(pageIndex) {
                        // Pinch + pan handler. Only the active page
                        // accepts these — non-active pages are mid-
                        // swipe so we'd rather they finish exiting
                        // before any zoom kicks in.
                        //
                        // Custom `awaitEachGesture` so single-finger
                        // drags at 1× fall through to the
                        // HorizontalPager. We only consume events
                        // when the user has 2+ fingers down (real
                        // pinch) or the page is already zoomed
                        // (pan-while-zoomed). `detectTransformGestures`
                        // would have eaten the slop for any drag,
                        // including the horizontal swipes the pager
                        // needs to flip pages.
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
                                        zoomScale = (zoomScale * zoomDelta).coerceIn(1f, 4f)
                                        panOffset = if (zoomScale <= 1.01f) {
                                            Offset.Zero
                                        } else {
                                            clampPan(
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
                        // Double-tap toggles between fit (1×) and 2×.
                        if (!isActive) return@pointerInput
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoomScale > 1f) {
                                    zoomScale = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    zoomScale = 2f
                                }
                            }
                        )
                    }
                    .graphicsLayer {
                        // Apply zoom + pan FIRST so they happen
                        // relative to the page's natural bounds.
                        scaleX        = pageScale
                        scaleY        = pageScale
                        translationX  = pagePan.x
                        translationY  = pagePan.y

                        // Page-turn rotation — only when this page
                        // is NOT the actively-zoomed one. Rotating a
                        // scaled image looks like a smear; the
                        // pager's userScrollEnabled lock ensures
                        // we're not actively swiping in that case
                        // anyway, so this is a safety net.
                        if (!(isActive && isZoomed)) {
                            cameraDistance = 12f * density.density
                            val clamped = pageOffset.coerceIn(-1f, 1f)
                            rotationY = clamped * 75f
                            transformOrigin = TransformOrigin(
                                pivotFractionX = if (clamped > 0) 0f else 1f,
                                pivotFractionY = 0.5f,
                            )
                        }
                        // Fade pages that are fully off-screen.
                        alpha = if (pageOffset.absoluteValue > 1f) 0f else 1f
                    },
            )
        }

        if (pages.size > 1) {
            Text(
                text  = "${pagerState.currentPage + 1} / ${pages.size}",
                style = type.caption,
                color = colors.inkSoft,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = QuickInkSpacing.s3)
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.surface.copy(alpha = 0.85f))
                    .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s1),
            )
        }

        // Fullscreen affordance — top-end pill button. Shown only
        // when the parent passed an [onFullscreenClick]; previews
        // and other call sites that don't want a fullscreen path
        // can omit the callback and the button drops out.
        //
        // Dark-on-light contrast deliberately: pages render as a
        // white surface, so a translucent-white pill (like the
        // page-count chip at the bottom) disappears entirely. The
        // dark `ink` fill at ~80% alpha keeps the page readable
        // through the chip while the icon stays unmistakeably
        // tappable.
        if (onFullscreenClick != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(QuickInkSpacing.s3)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.ink.copy(alpha = 0.55f))
                    .clickable(onClick = onFullscreenClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Fullscreen,
                    contentDescription = "View fullscreen",
                    tint               = colors.textOnAccent,
                    modifier           = Modifier.size(22.dp),
                )
            }
        }
    }
}
