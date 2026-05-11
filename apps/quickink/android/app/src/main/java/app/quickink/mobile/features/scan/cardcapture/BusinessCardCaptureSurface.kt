/*
 * BusinessCardCaptureSurface.kt
 *
 * The in-app CameraX preview + custom detector for Business
 * Card capture. Mounted by [QuickCaptureScreen] when the user
 * toggles to [CaptureMode.BusinessCard]; the surface is torn
 * down when the user toggles back or dismisses the screen.
 *
 * Top-down structure:
 *
 *   1. Camera-permission gate — if CAMERA hasn't been granted,
 *      show a rationale + Grant button. The pill toggle is
 *      still on the parent so the user can switch back to
 *      Document mode without granting permission.
 *
 *   2. PreviewView mounted via AndroidView. CameraX is bound
 *      with three use cases (Preview, ImageAnalysis,
 *      ImageCapture) against the host's LifecycleOwner so the
 *      session releases automatically on Composable dispose.
 *
 *   3. ImageAnalysis analyzer runs [BusinessCardDetector] on
 *      each frame. Valid quads flow into [StabilityGate];
 *      Partial/None results refresh the overlay tint and
 *      reset the gate. When the gate fires, the surface
 *      kicks off [ImageCapture.takePicture] and routes the
 *      result through [BusinessCardPostProcessor].
 *
 *   4. [CardGuideOverlay] sits on top of the preview, driven
 *      by [OverlayState] derived from the latest detection.
 *      It publishes the canvas-space guide rect back via
 *      `onMetricsKnown` so the surface can map it to
 *      analyzer-frame coordinates for the detector + IoU
 *      gate.
 *
 *   5. Manual shutter — same coral disc as the Document
 *      surface. Tap fires `takePicture` immediately,
 *      regardless of detection state; the post-processor
 *      falls back to the guide rect as the quad when no
 *      valid detection is in flight.
 *
 * Threading: ImageAnalysis runs on a single-thread executor
 * (`STRATEGY_KEEP_ONLY_LATEST` backpressure), the detector
 * writes results to Compose state via `runOnUiThread`. Capture
 * runs on a separate executor; the post-processor itself is
 * called on a background coroutine since
 * `BitmapFactory.decodeByteArray` + `Bitmap.compress` are
 * CPU-bound enough to drop a preview frame if you do them on
 * the main thread.
 *
 * Mirror of iOS `BusinessCardCaptureSurface.swift`.
 */

package app.quickink.mobile.features.scan.cardcapture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import android.view.Surface
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.quickink.mobile.features.scan.CaptureAnalytics
import app.quickink.mobile.features.scan.CaptureMode
import app.quickink.mobile.features.scan.ScanFlowController
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Idle hint kicks in after this much wall time with no detection. */
private const val IDLE_HINT_THRESHOLD_MS = 8_000L

@Composable
internal fun BusinessCardCaptureSurface(
    controller: ScanFlowController,
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
    ) { granted -> hasCameraPermission = granted }

    // Auto-prompt on first mount when the permission isn't
    // already granted. If the user denied previously, the
    // launcher returns immediately without showing system UI
    // — the rationale block below covers that case.
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            ActiveBusinessCardSurface(
                controller = controller,
                onDismiss  = onDismiss,
            )
        } else {
            PermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
    }
}

@Composable
private fun ActiveBusinessCardSurface(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    // Detection state — driven by the analyzer callback,
    // observed by the overlay + the shutter row.
    var overlayState by remember { mutableStateOf(OverlayState.Neutral) }
    var lastDetectionAtMs by remember { mutableStateOf(System.currentTimeMillis()) }
    // Guide rect in canvas-space pixels, published by the
    // overlay's BoxWithConstraints. Held in a mutable state so
    // recompositions don't recreate the analyzer's mapping.
    var guideCanvas by remember { mutableStateOf<GuideMetrics?>(null) }

    // Single-thread executor for ImageAnalysis. Created once,
    // shut down when the surface disposes.
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // The post-processing pipeline can outlive its triggering
    // frame; we hold a strong reference to the imageCapture use
    // case here so the shutter callback + the gate-fired
    // callback both go through the same instance.
    val imageCaptureHolder = remember { ImageCaptureHolder() }

    // Stability gate. Recreated per surface mount; the debounce
    // window keeps it from re-firing after the post-processor
    // hands control back to the controller.
    val stabilityGate = remember { StabilityGate() }

    // The detector. Created lazily on first analyzer callback —
    // the actual analyzer-frame size depends on what CameraX
    // negotiates with the camera HAL; we don't know it before
    // the first frame.
    var detectorHolder = remember { DetectorHolder() }

    // Idle-hint ticker. Wakes every 500 ms and bumps overlay
    // state to Idle when the no-detection window crosses
    // [IDLE_HINT_THRESHOLD_MS]. Keeps a single coroutine
    // alive for the lifetime of the surface; cancels on
    // dispose via DisposableEffect below.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            if (overlayState == OverlayState.Neutral &&
                System.currentTimeMillis() - lastDetectionAtMs > IDLE_HINT_THRESHOLD_MS
            ) {
                overlayState = OverlayState.Idle
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            captureExecutor.shutdown()
            processingScope.cancel()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Live preview + overlay. Both share the same
        // BoxWithConstraints-driven canvas size, so the guide
        // rect drawn over the preview lines up pixel-for-pixel
        // with the rect we hand to the detector.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        // FIT_CENTER would letterbox; FILL_CENTER
                        // crops to fill the surface, which is what
                        // a typical camera UX looks like. The
                        // detector still sees the full analyzer
                        // frame either way.
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    }
                    bindCameraX(
                        context           = ctx,
                        previewView       = previewView,
                        lifecycleOwner    = lifecycleOwner,
                        analyzerExecutor  = analyzerExecutor,
                        imageCaptureHolder = imageCaptureHolder,
                        onAnalyzerFrame    = { proxy ->
                            handleAnalyzerFrame(
                                proxy            = proxy,
                                guideCanvas      = guideCanvas,
                                detectorHolder   = detectorHolder,
                                stabilityGate    = stabilityGate,
                                onState          = { state ->
                                    previewView.post {
                                        overlayState = state
                                        if (state != OverlayState.Neutral &&
                                            state != OverlayState.Idle
                                        ) {
                                            lastDetectionAtMs = System.currentTimeMillis()
                                        }
                                    }
                                },
                                onAutoFire       = { quadInStill, guideInStill, elapsedMs ->
                                    previewView.post {
                                        // Medium-tap haptic on auto-fire per spec.
                                        view.performHapticFeedback(
                                            HapticFeedbackConstants.CONTEXT_CLICK,
                                        )
                                    }
                                    CaptureAnalytics.autoFired(
                                        mode         = CaptureMode.BusinessCard,
                                        timeToLockMs = elapsedMs,
                                    )
                                    // analyzerSize may briefly be null between
                                    // surface mount and the first analyzer frame
                                    // — we don't have a meaningful frame to
                                    // capture against in that window, so skip
                                    // this fire and rely on the next stable
                                    // streak. Implicit-label local returns
                                    // don't work from a non-trailing lambda
                                    // arg, hence the if-else rather than a
                                    // `?: return@onAutoFire` early-out.
                                    val ah = detectorHolder.analyzerSize
                                    if (ah != null) {
                                        triggerCapture(
                                            context             = ctx,
                                            imageCapture        = imageCaptureHolder.value,
                                            captureExecutor     = captureExecutor,
                                            processingScope     = processingScope,
                                            controller          = controller,
                                            quadInAnalyzer      = quadInStill,
                                            guideInAnalyzer     = guideInStill,
                                            analyzerSize        = ah,
                                            onComplete          = { previewView.post { onDismiss() } },
                                        )
                                    }
                                },
                            )
                        },
                    )
                    previewView
                },
            )

            CardGuideOverlay(
                state          = overlayState,
                onMetricsKnown = { metrics ->
                    // BoxWithConstraints recomputes on every
                    // layout pass; only re-publish when the rect
                    // actually changed so the detector's mapping
                    // doesn't churn on identity-equal callbacks.
                    val current = guideCanvas
                    if (current == null || current.rect != metrics.rect) {
                        guideCanvas = metrics
                    }
                },
            )
        }

        // Shutter row. Manual fallback — taps fire immediately
        // even when stability isn't met (post-processor falls
        // back to the guide rect as the quad).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            BusinessCardShutterButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    CaptureAnalytics.manualFired(CaptureMode.BusinessCard)
                    // analyzerSize is null until the camera has
                    // delivered its first frame. Tap before then
                    // is a no-op — no haptic regret, the
                    // CaptureAnalytics event still records the
                    // user's intent.
                    val ah = detectorHolder.analyzerSize
                    if (ah != null) {
                        val guideInAnalyzer = computeAnalyzerGuide(ah.width, ah.height)
                        triggerCapture(
                            context           = context,
                            imageCapture      = imageCaptureHolder.value,
                            captureExecutor   = captureExecutor,
                            processingScope   = processingScope,
                            controller        = controller,
                            quadInAnalyzer    = null,
                            guideInAnalyzer   = guideInAnalyzer,
                            analyzerSize      = ah,
                            onComplete        = { onDismiss() },
                        )
                    }
                },
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s7))
    }
}

/** Allocated outside the surface so capture callbacks can pin the latest instance. */
private class ImageCaptureHolder {
    @Volatile var value: ImageCapture? = null
}

/** Created lazily on the first analyzer frame; carries the detector + the analyzer-frame size. */
private class DetectorHolder {
    @Volatile var detector: BusinessCardDetector? = null
    @Volatile var analyzerSize: Size? = null
}

private fun bindCameraX(
    context: android.content.Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    analyzerExecutor: java.util.concurrent.ExecutorService,
    imageCaptureHolder: ImageCaptureHolder,
    onAnalyzerFrame: (ImageProxy) -> Unit,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        // Resolution preference — favor 1280×720 for analysis;
        // the spec's "1280×720 for detection" callout. Still
        // capture uses the camera's highest available JPEG
        // resolution (CameraX picks by default).
        val analyzerResolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    /* boundSize = */ Size(1280, 720),
                    /* fallbackRule = */ ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analyzer = ImageAnalysis.Builder()
            .setResolutionSelector(analyzerResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also {
                it.setAnalyzer(analyzerExecutor) { proxy ->
                    try { onAnalyzerFrame(proxy) } finally { proxy.close() }
                }
            }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        imageCaptureHolder.value = imageCapture

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analyzer,
            imageCapture,
        )
    }, ContextCompat.getMainExecutor(context))
}

private fun handleAnalyzerFrame(
    proxy: ImageProxy,
    guideCanvas: GuideMetrics?,
    detectorHolder: DetectorHolder,
    stabilityGate: StabilityGate,
    onState: (OverlayState) -> Unit,
    onAutoFire: (quadInAnalyzer: DetectedQuad, guideInAnalyzer: GuideRect, elapsedMs: Long) -> Unit,
) {
    // Width/height after CameraX's setTargetRotation(0) — these
    // are the dimensions the luma plane uses, not the raw
    // sensor's. The detector needs the same numbers for ROI
    // arithmetic.
    val w = proxy.width
    val h = proxy.height

    if (detectorHolder.detector == null || detectorHolder.analyzerSize?.width != w || detectorHolder.analyzerSize?.height != h) {
        detectorHolder.detector = BusinessCardDetector(w, h)
        detectorHolder.analyzerSize = Size(w, h)
    }
    if (guideCanvas == null) {
        // Canvas hasn't published its guide rect yet — first
        // frames before the overlay lays out. Treat as no
        // detection and let the next frame run.
        onState(OverlayState.Neutral)
        return
    }
    // The overlay draws the guide as a fixed fraction of its
    // canvas (70% width, 1.586:1, centered at 50%/45%). The
    // CameraX preview is pinned to the same aspect ratio (4:3,
    // FILL_CENTER), so the analyzer's guide rect is the same
    // fraction of the analyzer frame — compute it from the
    // analyzer dimensions directly rather than mapping pixels.
    val guideAnalyzer = computeAnalyzerGuide(w, h)
    val detector = detectorHolder.detector!!
    val result = detector.detect(proxy, guideAnalyzer)

    when (result) {
        is DetectionResult.None -> {
            stabilityGate.reset()
            onState(OverlayState.Neutral)
        }
        is DetectionResult.Partial -> {
            stabilityGate.reset()
            onState(OverlayState.Partial)
        }
        is DetectionResult.Valid -> {
            onState(OverlayState.Valid)
            val fired = stabilityGate.vote(result.quad)
            if (fired) {
                val elapsed = stabilityGate.streakElapsedMs()
                onAutoFire(result.quad, guideAnalyzer, elapsed)
            }
        }
    }
}

/**
 * Compute the guide rect in analyzer-frame coordinates. Same
 * fractional layout as [GuideMetrics.compute] — 70% width,
 * 1.586:1, centered horizontally at 50% and vertically at 45%
 * — applied to analyzer dimensions instead of canvas
 * dimensions. The Preview/Analyzer/ImageCapture chain shares
 * a single aspect ratio (4:3, FILL_CENTER), so the same
 * fractions land on matching pixel regions in every space.
 */
internal fun computeAnalyzerGuide(analyzerWidth: Int, analyzerHeight: Int): GuideRect {
    val aw = analyzerWidth.toFloat()
    val ah = analyzerHeight.toFloat()
    val targetW = aw * GuideMetrics.GUIDE_WIDTH_FRACTION
    val targetH = targetW / GuideMetrics.CARD_ASPECT_RATIO
    val cx = aw * 0.5f
    val cy = ah * 0.45f
    val left = cx - targetW * 0.5f
    val top  = cy - targetH * 0.5f
    return GuideRect(
        left   = left.coerceAtLeast(0f),
        top    = top.coerceAtLeast(0f),
        right  = (left + targetW).coerceAtMost(aw),
        bottom = (top + targetH).coerceAtMost(ah),
    )
}

/**
 * Kick a still capture, decode it, scale the analyzer-frame
 * quad into the still's resolution, warp, save, and hand the
 * result to the controller. Most of the work happens on a
 * background coroutine — the call from the analyzer thread or
 * the UI thread is fire-and-forget.
 */
private fun triggerCapture(
    context: android.content.Context,
    imageCapture: ImageCapture?,
    captureExecutor: java.util.concurrent.ExecutorService,
    processingScope: CoroutineScope,
    controller: ScanFlowController,
    quadInAnalyzer: DetectedQuad?,
    guideInAnalyzer: GuideRect,
    analyzerSize: Size,
    onComplete: () -> Unit,
) {
    val ic = imageCapture ?: return
    ic.takePicture(
        captureExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                processingScope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.Default) {
                            decodeProxyToBitmap(image)
                        }
                        image.close()
                        if (bitmap == null) {
                            withContext(Dispatchers.Main) { onComplete() }
                            return@launch
                        }
                        val sx = bitmap.width  / analyzerSize.width.toFloat()
                        val sy = bitmap.height / analyzerSize.height.toFloat()
                        val quadInBitmap = quadInAnalyzer?.scaled(sx, sy)
                        val guideInBitmap = GuideRect(
                            left   = guideInAnalyzer.left   * sx,
                            top    = guideInAnalyzer.top    * sy,
                            right  = guideInAnalyzer.right  * sx,
                            bottom = guideInAnalyzer.bottom * sy,
                        )
                        withContext(Dispatchers.Default) {
                            BusinessCardPostProcessor.process(
                                context       = context,
                                source        = bitmap,
                                quadInBitmap  = quadInBitmap,
                                guideInBitmap = guideInBitmap,
                                controller    = controller,
                            )
                        }
                        withContext(Dispatchers.Main) { onComplete() }
                    } catch (t: Throwable) {
                        // Best-effort — surface to logcat and
                        // dismiss. A failed warp on a near-miss
                        // shouldn't strand the user inside the
                        // camera with no exit.
                        android.util.Log.w("BusinessCardCapture", "post-process failed", t)
                        withContext(Dispatchers.Main) { onComplete() }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                android.util.Log.w("BusinessCardCapture", "takePicture failed: ${exception.message}")
            }
        },
    )
}

/**
 * Decode an ImageCapture proxy (JPEG bytes inside a single
 * plane) into a [Bitmap]. CameraX hands us the JPEG already
 * oriented; no further `Matrix.postRotate` is needed under
 * our `setTargetRotation(Surface.ROTATION_0)` configuration.
 */
private fun decodeProxyToBitmap(proxy: ImageProxy): Bitmap? {
    val buffer = proxy.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = proxy.imageInfo.rotationDegrees
    if (rotation == 0) return bitmap
    val rotMatrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, rotMatrix, true,
    )
    if (rotated != bitmap) bitmap.recycle()
    return rotated
}

private fun DetectedQuad.scaled(sx: Float, sy: Float): DetectedQuad = DetectedQuad(
    tl = Point2f(tl.x * sx, tl.y * sy),
    tr = Point2f(tr.x * sx, tr.y * sy),
    br = Point2f(br.x * sx, br.y * sy),
    bl = Point2f(bl.x * sx, bl.y * sy),
)

@Composable
private fun BusinessCardShutterButton(onClick: () -> Unit) {
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
                contentDescription = "Capture business card",
                tint              = Color.White,
                modifier          = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit) {
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
        Text(
            text  = "Allow camera to scan cards",
            style = type.heading,
            color = Color.White,
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Business Card mode uses your camera to detect and capture cards in-frame. Document mode keeps working without it.",
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
            Text(
                text  = "Grant access",
                style = type.label,
                color = Color.White,
            )
        }
    }
}
