/*
 * PdfPagesView.kt
 *
 * Composable that renders a multi-page PDF (`captures.pdf_uri`) as a
 * vertical column of bitmaps with pinch-to-zoom + pan per page.
 * Mirror of iOS's `PDFKitView` — both surface the canonical PDF the
 * scanner produced rather than just the first-page JPEG preview.
 *
 * Implementation notes:
 *  - `PdfRenderer` is the framework-native API (API 21+). Rendering
 *    is synchronous, so we kick the work onto an IO dispatcher and
 *    cache the resulting `Bitmap`s in a `remember`. Typical scans
 *    are 1–5 pages — fine to render eagerly. A virtualised LazyColumn
 *    + lazy bitmap fetch lands in a follow-up if multi-hundred-page
 *    docs surface.
 *  - Each page's `Bitmap` is created at 2× its `Page.width / height`
 *    for a sharper render on high-DPI displays. Cleanup is automatic
 *    when the bitmap state goes out of composition.
 *  - Pinch-to-zoom uses `Modifier.pointerInput(detectTransformGestures)`
 *    feeding `Modifier.graphicsLayer { scaleX/Y/translation }`. Tap
 *    to reset is handled by a double-tap detector below.
 */

package app.quickink.mobile.features.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PdfPagesView(
    pdfUri: Uri,
    modifier: Modifier = Modifier,
    onFullscreenClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    var pages by remember(pdfUri) { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember(pdfUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(pdfUri) {
        try {
            pages = withContext(Dispatchers.IO) { renderPdfPages(context, pdfUri) }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open PDF"
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier              = Modifier.fillMaxWidth(),
            verticalArrangement   = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            when {
                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp)
                            .clip(RoundedCornerShape(QuickInkRadius.md))
                            .background(colors.borderSoft)
                            .padding(QuickInkSpacing.s4),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = error!!,
                            style = type.meta,
                            color = colors.inkSoft,
                        )
                    }
                }
                pages == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
                else -> {
                    pages!!.forEachIndexed { index, bitmap ->
                        ZoomablePage(
                            bitmap = bitmap,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(QuickInkRadius.md))
                                .background(colors.surface),
                        )
                        if (pages!!.size > 1) {
                            Text(
                                text  = "Page ${index + 1} of ${pages!!.size}",
                                style = type.caption,
                                color = colors.muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = QuickInkSpacing.s1),
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen affordance — top-end pill button. Only renders
        // once pages are ready so the loader spinner isn't masked by
        // a button that won't do anything yet. Dark-on-light contrast
        // (ink @ 55% alpha) so the chip is readable on top of the
        // white page surface — see [PageTurnPdfView] for the same
        // rationale.
        if (onFullscreenClick != null && pages != null && error == null) {
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

@Composable
private fun ZoomablePage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .aspectRatio(aspectRatio.coerceIn(0.5f, 2.0f))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 4f)
                    // Clamp pan when zoomed out — at scale 1 there's
                    // no panning room, so always settle to (0, 0).
                    if (newScale == 1f) {
                        offset = Offset.Zero
                    } else {
                        offset += pan
                    }
                    scale = newScale
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2f
                    if (scale == 1f) offset = Offset.Zero
                })
            }
            .graphicsLayer {
                scaleX        = scale
                scaleY        = scale
                translationX  = offset.x
                translationY  = offset.y
            },
    )
}

internal fun renderPdfPages(context: Context, uri: Uri): List<Bitmap> {
    val pfd: ParcelFileDescriptor = context.contentResolver
        .openFileDescriptor(uri, "r")
        ?: throw IllegalStateException("Couldn't open PDF: $uri")

    return PdfRenderer(pfd).use { renderer ->
        (0 until renderer.pageCount).map { i ->
            renderer.openPage(i).use { page ->
                // 2× backing density so the rendering reads sharp on
                // typical phone displays without pushing memory too far.
                val w = page.width  * 2
                val h = page.height * 2
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }.also { pfd.close() }
}
