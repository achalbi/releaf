/*
 * PhotoCaptureSurface.kt
 *
 * Single-shot still-camera surface. Sibling to
 * [DocumentCaptureSurface] (ML Kit system scanner),
 * [BusinessCardCaptureSurface] (CameraX + quad detector), and
 * [VideoCaptureSurface] (CameraX VideoCapture, tap-to-toggle
 * recording). Reached from the Sundial radial menu's "Photo"
 * ray on the bottom-nav ⚡ FAB, which mounts [QuickCaptureScreen]
 * with `initialMode = CaptureMode.Photo`. The Sundial path uses
 * a no-op persist on the coordinator so this transient surface
 * doesn't overwrite the user's pill-selected last mode
 * (`quickink.capture.last_mode`).
 *
 * Shutter gesture (photo-only):
 *
 *   - Tap → still capture ([ImageCapture.takePicture]).
 *
 * Hold-to-record used to live on this surface too, but moved to
 * a dedicated [VideoCaptureSurface] once the Sundial menu gave
 * users a separate "Video" entry point. One verb per surface,
 * one unambiguous tap each.
 *
 * A small flip-camera button sits at the trailing edge of the
 * shutter row, next to the shutter itself (matches iOS Camera /
 * Instagram). Only shown in the idle Preview state — hidden
 * while a still capture is in flight since tearing down the
 * input mid-take would race the callback.
 *
 * A collapse-by-default chip strip sits to the left of the
 * shutter and lets the user pick one of four color filters
 * (None / B&W / Sepia / Cool). Collapsed → only the active chip
 * is visible. Tap → the column expands upward into the preview
 * area with all chips; each chip carries a live mini-preview
 * rendered from [TextureView.getBitmap] polled at 5fps and re-
 * tinted per chip via Compose [ColorFilter.colorMatrix]. Tap a
 * chip → it's selected and the strip collapses again.
 *
 * The selection drives the main live preview (via a Compose
 * [Canvas] overlay with an HSL [BlendMode] against the
 * COMPATIBLE-mode [PreviewView]'s TextureView pixels — no per-
 * frame Bitmap copy) AND the captured still (via
 * [android.graphics.ColorMatrix] applied to the Bitmap before
 * the JPEG re-encode). See [PhotoFilter] for the per-case
 * overlay / matrix mapping.
 *
 * After capture, the surface lands on the standard Retake / OK
 * screen. OK writes the JPEG via [buildImportArtifacts] and
 * fires `controller.onScanComplete(source="photo", paperSize=Custom)`.
 * Spec §6 calls out `paperSize=Custom` for photo mode — an
 * arbitrary phone-camera frame's aspect ratio is meaningless
 * against the A4 / Letter / card ratio bands.
 *
 * Mirror of iOS `PhotoCaptureSurface.swift`.
 */

package app.quickink.mobile.features.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One of four color filters the user can apply from the vertical
 * chip strip on the bottom-left of the live preview. Shared by
 * [PhotoCaptureSurface] (still capture) and
 * [VideoCaptureSurface] (post-process re-encode of the recorded
 * `.mp4`). Selection lives in each surface's `activeFilter`
 * state and is mirrored to:
 *
 *   - **Live preview** — via a Compose [Canvas] overlay that
 *     paints a single colored rect over the preview using one
 *     of the HSL blend modes ([BlendMode.Saturation] /
 *     [BlendMode.Color]). The [PreviewView] is forced into
 *     COMPATIBLE mode (TextureView under the hood) so the
 *     overlay can composite against it in the same render tree;
 *     PERFORMANCE mode's SurfaceView lives on its own surface
 *     and Compose blend modes wouldn't reach it.
 *
 *   - **Captured artifact** — via [android.graphics.ColorMatrix]
 *     applied to the saved [Bitmap] (still) or via a Media3
 *     [androidx.media3.effect.RgbMatrix] baked into every video
 *     frame (video). The matrix gives a precise, deterministic
 *     outcome; the live preview is a cheap approximation tuned
 *     to land in roughly the same color space.
 */
internal enum class PhotoFilter(
    val displayName: String,
    /** Color painted by the live-preview Canvas. `null` = no overlay. */
    val overlayColor: Color?,
    /** Blend mode the overlay uses against the preview underneath. */
    val overlayBlendMode: BlendMode,
    /**
     * 4x5 color matrix applied to the captured bitmap. `null`
     * leaves the bitmap untouched (the None case + the read site
     * skips the paint pass entirely).
     */
    val captureMatrix: FloatArray?,
) {
    None(
        displayName      = "None",
        overlayColor     = null,
        overlayBlendMode = BlendMode.SrcOver,
        captureMatrix    = null,
    ),
    BW(
        displayName      = "B&W",
        overlayColor     = Color.Gray,
        overlayBlendMode = BlendMode.Saturation,
        // Standard luminosity weights (Rec. 601). Produces a
        // gentle, naturally-toned grayscale rather than a flat
        // average-of-channels desaturation.
        captureMatrix    = floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f,
        ),
    ),
    Sepia(
        displayName      = "Sepia",
        overlayColor     = Color(0xFFD2A56F),
        overlayBlendMode = BlendMode.Color,
        // Classic Microsoft sepia matrix.
        captureMatrix    = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f,
        ),
    ),
    Cool(
        displayName      = "Cool",
        overlayColor     = Color(0xFF80A0FF),
        overlayBlendMode = BlendMode.Color,
        // Dim red, lift blue — matches the iOS
        // `CITemperatureAndTint` cool target.
        captureMatrix    = floatArrayOf(
            0.85f, 0f,    0f,    0f, 0f,
            0f,    0.95f, 0f,    0f, 0f,
            0f,    0f,    1.10f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f,
        ),
    ),
}

/**
 * Walk the view tree under [root] and return the first
 * [TextureView] found, or `null` if none exists. PreviewView in
 * COMPATIBLE mode hosts a TextureView as a child; we need a
 * reference to it so the filter-strip polling effect can call
 * `getBitmap(w, h)` for the live mini-previews. The TextureView
 * isn't part of PreviewView's public API, so walking the tree is
 * the supported way to reach it.
 *
 * Internal visibility so [VideoCaptureSurface] can reuse the
 * same walker for its own filter-strip thumbnail polling — the
 * shape of the PreviewView tree is identical between surfaces.
 */
internal fun findTextureView(root: View): TextureView? {
    if (root is TextureView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            val hit = findTextureView(root.getChildAt(i))
            if (hit != null) return hit
        }
    }
    return null
}

/**
 * Decode the JPEG at [file] with the long edge clamped to at
 * most [maxDimension] pixels. Uses the standard two-pass
 * BitmapFactory idiom — first pass reads only the header to
 * compute a power-of-2 `inSampleSize`, second pass decodes at
 * the sampled resolution (cheaper than a full decode followed
 * by a downscale, and avoids the OOM risk of decoding a 48MP
 * frame into a 192MB ARGB_8888 buffer just to throw most of it
 * away). A final exact-scale step rounds the result down to
 * [maxDimension] when the sample size leaves us slightly over.
 *
 * Returns null on a malformed / unreadable JPEG.
 */
private fun decodeAndDownscale(file: File, maxDimension: Int): Bitmap? {
    val sizeOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, sizeOptions)
    val srcW = sizeOptions.outWidth
    val srcH = sizeOptions.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    var sampleSize = 1
    while ((srcW / sampleSize) > maxDimension * 2 || (srcH / sampleSize) > maxDimension * 2) {
        sampleSize *= 2
    }
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

    val longEdge = maxOf(raw.width, raw.height)
    if (longEdge <= maxDimension) return raw
    val scale = maxDimension.toFloat() / longEdge
    val targetW = (raw.width  * scale).toInt().coerceAtLeast(1)
    val targetH = (raw.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
    if (scaled !== raw) raw.recycle()
    return scaled
}

/**
 * Apply [filter]'s `captureMatrix` to [source] and return a new
 * [Bitmap]. Returns [source] verbatim when filter is None
 * (caller doesn't need to special-case). Always returns an
 * ARGB_8888 bitmap regardless of the source config so the
 * downstream JPEG encoder has a known format.
 *
 * Internal visibility so [VideoCaptureSurface] can reuse it for
 * its first-frame filter pass.
 */
internal fun applyFilterToBitmap(source: Bitmap, filter: PhotoFilter): Bitmap {
    val matrix = filter.captureMatrix ?: return source
    val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(AndroidColorMatrix(matrix))
    }
    AndroidCanvas(out).drawBitmap(source, 0f, 0f, paint)
    return out
}

/**
 * Max long-edge dimension we keep for a captured still before
 * persisting it. CameraX [ImageCapture] writes a full-resolution
 * JPEG (e.g. ~24MB on a 48MP sensor at default quality 100);
 * 2048px on the long edge keeps OCR + on-screen detail intact
 * while landing the file ~400KB-1.5MB.
 */
private const val CAPTURED_PHOTO_MAX_DIMENSION: Int = 2048

/**
 * Compression quality for the re-encoded JPEG. 88 is in the
 * "visually indistinguishable from the source" band for natural
 * photo content and keeps the per-file size budget tight.
 */
private const val CAPTURED_PHOTO_JPEG_QUALITY: Int = 88

@Composable
internal fun PhotoCaptureSurface(
    controller: ScanFlowController,
    /**
     * Owning user — threaded through every surface for symmetry,
     * even though the photo-only path no longer needs it (the
     * post-record voice-note write moved with the rest of the
     * video machinery to [VideoCaptureSurface]).
     */
    @Suppress("UNUSED_PARAMETER") userId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            ActivePhotoSurface(controller = controller, onDismiss = onDismiss)
        } else {
            PhotoPermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
    }
}

/** Holder so capture callbacks can pin the latest use-case instances. */
private class CameraXBindings {
    @Volatile var imageCapture: ImageCapture? = null
    /**
     * The provider itself — stashed so the surface's dispose
     * handler can call `unbindAll()` directly. [bindToLifecycle]
     * only auto-releases when the supplied [androidx.lifecycle.LifecycleOwner]
     * transitions to STOPPED; navigating back inside the same
     * Activity doesn't move the Activity's lifecycle, so without
     * an explicit unbind the camera stays hot in the background.
     */
    @Volatile var cameraProvider: ProcessCameraProvider? = null
}

/** Captured still returned to the host after the user taps OK. */
private data class CapturedPhoto(val image: Bitmap, val file: File)

/** Discriminated UI states for the active photo surface. */
private sealed interface PhotoUiState {
    data object Preview : PhotoUiState
    data object Capturing : PhotoUiState
    data object Processing : PhotoUiState
    data class Captured(val buffer: CapturedPhoto) : PhotoUiState
}

@Composable
private fun ActivePhotoSurface(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val uiScope = rememberCoroutineScope()

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val bindings = remember { CameraXBindings() }

    var uiState by remember { mutableStateOf<PhotoUiState>(PhotoUiState.Preview) }
    var commitError by remember { mutableStateOf<String?>(null) }
    var isCommitting by remember { mutableStateOf(false) }

    // Active camera. Hoisted out of `bindCameraX` so the
    // LaunchedEffect below can re-bind the use case against a
    // different selector when the user taps the flip button.
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    // Selected color filter. Resets to None on every fresh mount
    // — we don't persist across sessions since a filter that
    // silently survived to the next launch could surprise the
    // user.
    var activeFilter by remember { mutableStateOf(PhotoFilter.None) }
    var isFilterStripExpanded by remember { mutableStateOf(false) }
    // Latest 96x96 RGBA bitmap from the live PreviewView's
    // TextureView, polled while the strip is expanded.
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // Release the hardware camera. Explicit unbind frees
            // the surface immediately even when the host activity
            // stays in RESUMED.
            bindings.cameraProvider?.unbindAll()
            captureExecutor.shutdown()
            processingScope.cancel()
            (uiState as? PhotoUiState.Captured)?.buffer?.file?.delete()
        }
    }

    LaunchedEffect(previewViewRef, cameraSelector) {
        val pv = previewViewRef ?: return@LaunchedEffect
        bindCameraX(
            context        = context,
            previewView    = pv,
            lifecycleOwner = lifecycleOwner,
            bindings       = bindings,
            cameraSelector = cameraSelector,
        )
    }

    LaunchedEffect(isFilterStripExpanded, previewViewRef) {
        if (!isFilterStripExpanded) {
            thumbnailBitmap = null
            return@LaunchedEffect
        }
        val pv = previewViewRef ?: return@LaunchedEffect
        while (isActive && isFilterStripExpanded) {
            val tv = findTextureView(pv)
            if (tv != null) {
                val frame = runCatching { tv.getBitmap(96, 96) }.getOrNull()
                if (frame != null) {
                    thumbnailBitmap = frame
                }
            }
            delay(200)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val state = uiState
            when (state) {
                is PhotoUiState.Captured -> {
                    Image(
                        bitmap             = state.buffer.image.asImageBitmap(),
                        contentDescription = "Captured photo",
                        modifier           = Modifier.fillMaxSize().background(Color.Black),
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val pv = PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                }
                                previewViewRef = pv
                                pv
                            },
                            onRelease = { previewViewRef = null },
                        )
                        val showFilter = activeFilter != PhotoFilter.None
                        if (showFilter) {
                            val color = activeFilter.overlayColor
                            val blend = activeFilter.overlayBlendMode
                            if (color != null) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawRect(color = color, blendMode = blend)
                                }
                            }
                        }
                    }
                    if (state is PhotoUiState.Capturing || state is PhotoUiState.Processing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }

        val captured = uiState as? PhotoUiState.Captured
        if (captured == null) {
            ShutterRow(
                uiState = uiState,
                onTap = onTap@{
                    if (uiState != PhotoUiState.Preview) return@onTap
                    uiState = PhotoUiState.Capturing
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    CaptureAnalytics.manualFired(CaptureMode.Photo)
                    val filterAtCapture = activeFilter
                    triggerPhotoCapture(
                        context         = context,
                        imageCapture    = bindings.imageCapture,
                        captureExecutor = captureExecutor,
                        processingScope = processingScope,
                        onResult = { file, bitmap ->
                            if (filterAtCapture == PhotoFilter.None) {
                                uiState = PhotoUiState.Captured(CapturedPhoto(bitmap, file))
                            } else {
                                uiState = PhotoUiState.Processing
                                processingScope.launch {
                                    val filtered = withContext(Dispatchers.Default) {
                                        applyFilterToBitmap(bitmap, filterAtCapture)
                                    }
                                    withContext(Dispatchers.IO) {
                                        file.outputStream().use { out ->
                                            filtered.compress(
                                                Bitmap.CompressFormat.JPEG,
                                                CAPTURED_PHOTO_JPEG_QUALITY,
                                                out,
                                            )
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        uiState = PhotoUiState.Captured(
                                            CapturedPhoto(filtered, file),
                                        )
                                    }
                                }
                            }
                        },
                        onError = {
                            android.util.Log.w("PhotoCapture", "takePicture failed", it)
                            uiState = PhotoUiState.Preview
                        },
                    )
                },
                onFlipCamera = {
                    cameraSelector =
                        if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else
                            CameraSelector.DEFAULT_BACK_CAMERA
                },
            )
        } else {
            CommitRow(
                commitError = commitError,
                isCommitting = isCommitting,
                onRetake = {
                    commitError = null
                    captured.buffer.file.delete()
                    uiState = PhotoUiState.Preview
                },
                onUse = onUse@{
                    isCommitting = true
                    uiScope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            commitPhotoCapture(
                                context    = context,
                                controller = controller,
                                buffer     = captured.buffer,
                            )
                        }
                        if (outcome) {
                            onDismiss()
                        } else {
                            commitError = "Couldn't save photo — try again"
                            isCommitting = false
                        }
                    }
                },
            )
        }
    }
        if (uiState is PhotoUiState.Preview) {
            FilterStrip(
                isExpanded      = isFilterStripExpanded,
                activeFilter    = activeFilter,
                thumbnailBitmap = thumbnailBitmap,
                onToggleExpand  = { isFilterStripExpanded = !isFilterStripExpanded },
                onSelect        = { picked ->
                    activeFilter = picked
                    isFilterStripExpanded = false
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = QuickInkSpacing.s4, bottom = 86.dp),
            )
        }
    }
}

@Composable
private fun ShutterRow(
    uiState: PhotoUiState,
    onTap: () -> Unit,
    onFlipCamera: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier         = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PhotoShutterButton(
                enabled = uiState !is PhotoUiState.Capturing && uiState !is PhotoUiState.Processing,
                onTap   = onTap,
            )
            if (uiState is PhotoUiState.Preview) {
                CameraFlipButton(
                    onClick  = onFlipCamera,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Tap for photo",
            style = LocalQuickInkTypography.current.caption.copy(fontSize = 11.sp),
            color = Color.White.copy(alpha = 0.55f),
        )
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

@Composable
private fun CommitRow(
    commitError: String?,
    isCommitting: Boolean,
    onRetake: () -> Unit,
    onUse: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (commitError != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            ) {
                Text(text = commitError, style = type.label, color = Color.White)
            }
            Spacer(Modifier.size(QuickInkSpacing.s3))
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(enabled = !isCommitting, onClick = onRetake)
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Close,
                    contentDescription = null,
                    tint              = Color.White,
                    modifier          = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(text = "Retake", style = type.label, color = Color.White)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .shadow(12.dp, RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accent)
                    .clickable(enabled = !isCommitting, onClick = onUse)
                    .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isCommitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        imageVector       = Icons.Filled.Check,
                        contentDescription = null,
                        tint              = Color.White,
                        modifier          = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(text = "OK", style = type.label, color = Color.White)
            }
        }
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

/**
 * 78dp coral disc with a camera icon. Single-tap dispatches to
 * [onTap]; no recording state, no hold gesture.
 */
@Composable
private fun PhotoShutterButton(
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .size(78.dp)
            .clickable(enabled = enabled, onClick = onTap),
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
                imageVector        = Icons.Filled.PhotoCamera,
                contentDescription = "Take photo",
                tint               = Color.White,
                modifier           = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * Bare 40dp black disc that toggles between the back and front
 * cameras. Only rendered in [PhotoUiState.Preview] — hidden
 * mid-capture since flipping mid-take would race the callback.
 */
@Composable
private fun CameraFlipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Filled.Cameraswitch,
            contentDescription = "Switch camera",
            tint               = Color.White,
            modifier           = Modifier.size(20.dp),
        )
    }
}

/**
 * Collapse-aware filter strip — anchored to the bottom-left of
 * the screen by the caller. When [isExpanded] is false (default
 * on every fresh mount), only the active chip renders. Tapping
 * it flips [isExpanded] true via [onToggleExpand] and the column
 * reveals additional chips above. Tapping any chip during
 * expanded state selects via [onSelect].
 */
@Composable
private fun FilterStrip(
    isExpanded: Boolean,
    activeFilter: PhotoFilter,
    thumbnailBitmap: Bitmap?,
    onToggleExpand: () -> Unit,
    onSelect: (PhotoFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier              = modifier,
        verticalArrangement   = Arrangement.spacedBy(6.dp),
        horizontalAlignment   = Alignment.Start,
    ) {
        if (isExpanded) {
            PhotoFilter.entries.filter { it != activeFilter }.forEach { filter ->
                FilterChip(
                    filter    = filter,
                    isActive  = false,
                    thumbnail = thumbnailBitmap,
                    onClick   = { onSelect(filter) },
                )
            }
        }
        FilterChip(
            filter    = activeFilter,
            isActive  = true,
            thumbnail = thumbnailBitmap,
            onClick   = onToggleExpand,
        )
    }
}

@Composable
private fun FilterChip(
    filter: PhotoFilter,
    isActive: Boolean,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.sm))
            .background(
                if (isActive) colors.accent.copy(alpha = 0.85f)
                else Color.Black.copy(alpha = 0.55f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(6.dp),
                ),
        ) {
            if (thumbnail != null) {
                val colorFilter = filter.captureMatrix?.let {
                    ColorFilter.colorMatrix(ComposeColorMatrix(it))
                }
                Image(
                    bitmap             = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    colorFilter        = colorFilter,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text  = filter.displayName,
            style = type.caption.copy(fontSize = 11.sp),
            color = Color.White,
        )
    }
}

@Composable
private fun PhotoPermissionRationale(onRequest: () -> Unit) {
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(QuickInkSpacing.s5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector       = Icons.Filled.PhotoCamera,
            contentDescription = null,
            tint              = Color.White.copy(alpha = 0.85f),
            modifier          = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s4))
        Text(text = "Allow camera to take photos", style = type.heading, color = Color.White)
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Photo mode uses your camera to capture a still image. You can still scan documents and import from your library without it.",
            style = type.body,
            color = Color.White.copy(alpha = 0.70f),
        )
        Spacer(Modifier.size(QuickInkSpacing.s5))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(LocalQuickInkColors.current.accent)
                .clickable(onClick = onRequest)
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
        ) {
            Text(text = "Grant access", style = type.label, color = Color.White)
        }
    }
}

/**
 * Bind CameraX with two use cases — Preview + ImageCapture.
 * Video recording lives on [VideoCaptureSurface] with its own
 * binding helper. Lifecycle release happens automatically via
 * [lifecycleOwner].
 *
 * Re-callable: pass a different [cameraSelector] (e.g. FRONT
 * instead of BACK) and the function `unbindAll`s and rebinds.
 * That's how the flip button works.
 */
private fun bindCameraX(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    bindings: CameraXBindings,
    cameraSelector: CameraSelector,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        bindings.cameraProvider = cameraProvider

        val preview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        bindings.imageCapture = imageCapture

        cameraProvider.unbindAll()
        runCatching {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
            )
        }.onFailure {
            android.util.Log.w(
                "PhotoCapture",
                "bindToLifecycle failed for selector=$cameraSelector",
                it,
            )
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * Take a single still using [ImageCapture.OutputFileOptions] →
 * the file lives in the app's cache directory under
 * `photo_capture/buffer-<ts>.jpg`. The ImageCapture-written
 * JPEG is full-sensor-resolution at quality 100 (∼15-25MB on
 * 48MP-class sensors), so we immediately decode-and-downscale
 * to [CAPTURED_PHOTO_MAX_DIMENSION] and re-encode at
 * [CAPTURED_PHOTO_JPEG_QUALITY], overwriting the same file.
 * The downscaled [Bitmap] is also handed to [onResult] for the
 * captured-preview render.
 */
private fun triggerPhotoCapture(
    context: Context,
    imageCapture: ImageCapture?,
    captureExecutor: ExecutorService,
    processingScope: CoroutineScope,
    onResult: (File, Bitmap) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val ic = imageCapture ?: run {
        onError(IllegalStateException("camera not bound"))
        return
    }
    val cacheDir = File(context.cacheDir, "photo_capture").apply { mkdirs() }
    val outFile = File(cacheDir, "buffer-${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
    ic.takePicture(
        outputOptions,
        captureExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                processingScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        decodeAndDownscale(outFile, CAPTURED_PHOTO_MAX_DIMENSION)
                    }
                    if (bitmap == null) {
                        outFile.delete()
                        withContext(Dispatchers.Main) {
                            onError(IllegalStateException("decode failed"))
                        }
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        outFile.outputStream().use { out ->
                            bitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                CAPTURED_PHOTO_JPEG_QUALITY,
                                out,
                            )
                        }
                    }
                    withContext(Dispatchers.Main) { onResult(outFile, bitmap) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                outFile.delete()
                processingScope.launch {
                    withContext(Dispatchers.Main) { onError(exception) }
                }
            }
        },
    )
}

/**
 * Use Photo handler — promotes the captured still into the scan
 * pipeline. Builds the JPEG/PDF artifacts via
 * [buildImportArtifacts] and fires `controller.onScanComplete`
 * with `source="photo"` / `paperSize=Custom`. Returns true on
 * success; false surfaces an inline retry toast.
 */
private suspend fun commitPhotoCapture(
    context: Context,
    controller: ScanFlowController,
    buffer: CapturedPhoto,
): Boolean {
    val frameUri = Uri.fromFile(buffer.file)
    val result = buildImportArtifacts(context, listOf(frameUri)) ?: return false

    withContext(Dispatchers.Main) {
        controller.onScanComplete(
            result    = result,
            source    = "photo",
            paperSize = PaperSize.Custom,
        )
    }
    buffer.file.delete()
    return true
}
