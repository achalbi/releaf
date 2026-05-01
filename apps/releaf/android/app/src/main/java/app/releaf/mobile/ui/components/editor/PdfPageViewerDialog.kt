/*
 * PdfPageViewerDialog.kt
 *
 * In-house PDF page viewer. The ScansSection opens this when the user
 * taps a scanned document — the dialog paginates through the PDF's
 * pages with `android.graphics.pdf.PdfRenderer` (no third-party
 * dependency) and lets the user import any one page into the
 * sub-page pager as a new drawable surface.
 *
 * Page bitmaps are cached in a `SnapshotStateMap` keyed by page index
 * so swipes through a 10-page PDF don't re-render pages the user has
 * already seen. `PdfRenderer` must be closed when we're done — we own
 * the session in a `remember(pdfUri)` slot and release it in
 * `DisposableEffect.onDispose`.
 *
 * Thread-safety: PdfRenderer + Page are documented as non-thread-safe
 * and only one `Page` may be open per renderer. Renders run on
 * `Dispatchers.IO` but serialize through the session's `Mutex` so two
 * pager neighbours being prefetched in parallel can't collide.
 *
 * Import flow (per-page):
 *   1. Re-render the selected page if not already cached.
 *   2. Save that bitmap as a JPG into `AttachmentStorage.directory()`.
 *   3. Hand the resulting `file://` URI to `onImport`, which the
 *      caller wires to `viewModel.addSubPageFromImage(uri)`.
 */

package app.releaf.mobile.ui.components.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Pixels-per-point multiplier used when rendering pages to bitmaps.
 *  PdfRenderer pages are measured in PostScript points (1/72"). 2x
 *  gives ~144 DPI — sharp enough to annotate, small enough that the
 *  bitmap cache doesn't balloon on longer documents. */
private const val RENDER_SCALE = 2

/** In-memory snapshot of an open PDF. Held behind a `Mutex` so all
 *  `openPage(…)` + `render(…)` calls serialize — PdfRenderer + Page
 *  are explicitly single-threaded and only one Page may be open per
 *  renderer at a time. */
private class PdfSession(
    val renderer: PdfRenderer,
    val fileDescriptor: ParcelFileDescriptor,
    val pageCount: Int,
) {
    val mutex: Mutex = Mutex()

    /** Render one page into an ARGB_8888 bitmap with a white backing.
     *  Callers must hold [mutex] — we don't re-lock internally to
     *  keep the API honest about the contract. */
    fun renderPageLocked(index: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val bmp = Bitmap.createBitmap(
                page.width * RENDER_SCALE,
                page.height * RENDER_SCALE,
                Bitmap.Config.ARGB_8888,
            )
            bmp.eraseColor(AndroidColor.WHITE)
            page.render(
                bmp,
                /* destClip   = */ null,
                /* transform  = */ null,
                /* renderMode = */ PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
            )
            return bmp
        }
    }

    fun close() {
        runCatching { renderer.close() }
        runCatching { fileDescriptor.close() }
    }
}

@Composable
fun PdfPageViewerDialog(
    pdfUri: String,
    onImport: (pageImageUri: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Reactive holders so Compose actually recomposes when the load
    // finishes. Keyed on `pdfUri` so swapping documents in the same
    // viewer resets state cleanly.
    var session by remember(pdfUri) { mutableStateOf<PdfSession?>(null) }
    var loadError by remember(pdfUri) { mutableStateOf<Throwable?>(null) }

    LaunchedEffect(pdfUri) {
        val result = withContext(Dispatchers.IO) {
            runCatching { openPdf(context, pdfUri) }
        }
        result.onSuccess { session = it }
        result.onFailure { loadError = it }
    }

    DisposableEffect(pdfUri) {
        onDispose {
            session?.close()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val activeSession = session
            val topInsets = WindowInsets.statusBars.asPaddingValues()

            when {
                activeSession != null -> {
                    val pagerState = rememberPagerState(
                        initialPage = 0,
                        pageCount   = { activeSession.pageCount },
                    )
                    val pageCache = remember(activeSession) {
                        mutableStateMapOf<Int, Bitmap>()
                    }

                    HorizontalPager(
                        state       = pagerState,
                        modifier    = Modifier.fillMaxSize(),
                        pageSpacing = AppSpacing.s2,
                    ) { index ->
                        val bitmap = pageCache[index]
                        if (bitmap == null) {
                            // Lazy render — swipe to a page, render it,
                            // cache it. Mutex keeps prefetched neighbours
                            // from colliding with the active page's render.
                            LaunchedEffect(index, activeSession) {
                                val rendered = withContext(Dispatchers.IO) {
                                    activeSession.mutex.withLock {
                                        runCatching {
                                            activeSession.renderPageLocked(index)
                                        }.getOrNull()
                                    }
                                }
                                if (rendered != null) pageCache[index] = rendered
                            }
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        } else {
                            Image(
                                bitmap              = bitmap.asImageBitmap(),
                                contentDescription  = null,
                                contentScale        = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier            = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    // Top bar: close + page counter.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(topInsets)
                            .padding(AppSpacing.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillIcon(
                            icon    = Icons.Filled.Close,
                            label   = "Close",
                            onClick = onDismiss,
                        )
                        Spacer(Modifier.weight(1f))
                        PillText(text = "${pagerState.currentPage + 1} / ${activeSession.pageCount}")
                    }

                    // Bottom action: import current page.
                    val bottomInsets = WindowInsets.navigationBars.asPaddingValues()
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottomInsets)
                            .padding(AppSpacing.s4),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ImportPagePill(
                            onClick = {
                                val target = pagerState.currentPage
                                scope.launch {
                                    val bmp = pageCache[target] ?: withContext(Dispatchers.IO) {
                                        activeSession.mutex.withLock {
                                            runCatching {
                                                activeSession.renderPageLocked(target)
                                            }.getOrNull()
                                        }
                                    }
                                    val uri = bmp?.let {
                                        withContext(Dispatchers.IO) {
                                            writeBitmapToAttachments(context, it)
                                        }
                                    }
                                    if (uri != null) {
                                        onImport(uri.toString())
                                        onDismiss()
                                    }
                                }
                            },
                        )
                    }
                }

                loadError != null -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            text  = "Couldn't open this document.",
                            style = AppTypography.Body,
                            color = Color.White,
                        )
                    }
                    // Still offer a Close button in the error branch so
                    // the user isn't stuck — back press would also work
                    // but a visible affordance reads clearer.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(topInsets)
                            .padding(AppSpacing.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillIcon(
                            icon    = Icons.Filled.Close,
                            label   = "Close",
                            onClick = onDismiss,
                        )
                    }
                }

                else -> {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }
}

private fun openPdf(context: Context, rawUri: String): PdfSession {
    val uri = rawUri.toUri()
    val pfd = when (uri.scheme) {
        "file" -> {
            val path = uri.path ?: error("file URI missing path: $uri")
            ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
        else   -> context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open $uri")
    }
    val renderer = PdfRenderer(pfd)
    return PdfSession(
        renderer       = renderer,
        fileDescriptor = pfd,
        pageCount      = renderer.pageCount,
    )
}

@Composable
private fun PillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = Color.White,
            modifier           = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PillText(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
    ) {
        Text(text = text, style = AppTypography.Meta, color = Color.White)
    }
}

@Composable
private fun ImportPagePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.s3))
            .background(AppAccent.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.NoteAdd,
            contentDescription = null,
            tint               = Color.White,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            text  = "Import this page to notes",
            style = AppTypography.Button,
            color = Color.White,
        )
    }
}

/** Save [bitmap] into the app's attachments dir as a JPG. Returns the
 *  `file://` URI or null on failure. Runs synchronously — callers wrap
 *  in `withContext(Dispatchers.IO)`. */
private fun writeBitmapToAttachments(context: Context, bitmap: Bitmap): Uri? {
    val dir = AttachmentStorage.directory(context)
    val file = File(dir, "${Uuidv7.generate()}.jpg")
    return runCatching {
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        Uri.fromFile(file)
    }.getOrNull()
}
