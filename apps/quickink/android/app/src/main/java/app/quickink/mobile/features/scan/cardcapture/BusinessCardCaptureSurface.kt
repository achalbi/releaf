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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
                                onAutoFire       = { quadInStill, elapsedMs ->
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
                                    // streak. Same for the canvas metrics: if
                                    // the overlay hasn't laid out yet, the
                                    // post-processor has no view-aspect to
                                    // crop the bitmap with.
                                    val ah = detectorHolder.analyzerSize
                                    val gc = guideCanvas
                                    if (ah != null && gc != null) {
                                        triggerCapture(
                                            context         = ctx,
                                            imageCapture    = imageCaptureHolder.value,
                                            captureExecutor = captureExecutor,
                                            processingScope = processingScope,
                                            controller      = controller,
                                            quadInAnalyzer  = quadInStill,
                                            analyzerSize    = ah,
                                            viewWidth       = gc.canvasWidth,
                                            viewHeight      = gc.canvasHeight,
                                            onComplete      = { previewView.post { onDismiss() } },
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
                    // delivered its first frame; guideCanvas is
                    // null until the overlay has laid out. Tap
                    // before either is a no-op — the haptic +
                    // analytics still fire to record the user's
                    // intent.
                    val ah = detectorHolder.analyzerSize
                    val gc = guideCanvas
                    if (ah != null && gc != null) {
                        triggerCapture(
                            context         = context,
                            imageCapture    = imageCaptureHolder.value,
                            captureExecutor = captureExecutor,
                            processingScope = processingScope,
                            controller      = controller,
                            quadInAnalyzer  = null,
                            analyzerSize    = ah,
                            viewWidth       = gc.canvasWidth,
                            viewHeight      = gc.canvasHeight,
                            onComplete      = { onDismiss() },
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

        // All three use cases share the SAME 4:3 aspect ratio so
        // the detector's analyzer frame and the still capture
        // bitmap line up pixel-for-pixel under a single uniform
        // scale factor. Mismatch here (previously the analyzer
        // was pinned at 1280×720 = 16:9 while ImageCapture used
        // setTargetAspectRatio(RATIO_4_3)) caused sx != sy when
        // mapping the analyzer quad / guide to the bitmap, which
        // vertically squashed the warped output.
        //
        // The shared strategy is 4:3 with auto fallback — every
        // back camera advertises a 4:3 output, and the
        // RATIO_4_3_FALLBACK_AUTO_STRATEGY tolerates the rare
        // device that doesn't.
        val sharedAspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        val analyzerSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(sharedAspect)
            // 720×960 (4:3 portrait) keeps the detector fast on
            // mid-range Android — under a 40 ms per-frame budget
            // at 24 fps on a Pixel 6a-class device. CameraX
            // picks the closest available 4:3 resolution.
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(720, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(sharedAspect)
            .build()

        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(sharedAspect)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analyzer = ImageAnalysis.Builder()
            .setResolutionSelector(analyzerSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also {
                it.setAnalyzer(analyzerExecutor) { proxy ->
                    try { onAnalyzerFrame(proxy) } finally { proxy.close() }
                }
            }

        val imageCapture = ImageCapture.Builder()
            .setResolutionSelector(captureSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
    onAutoFire: (quadInAnalyzer: DetectedQuad, elapsedMs: Long) -> Unit,
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
    if (guideCanvas == null || guideCanvas.canvasWidth <= 0f || guideCanvas.canvasHeight <= 0f) {
        // Canvas hasn't published its guide rect yet — first
        // frames before the overlay lays out. Treat as no
        // detection and let the next frame run.
        onState(OverlayState.Neutral)
        return
    }
    // The PreviewView uses FILL_CENTER, so the user sees the
    // center sub-rect of the analyzer frame whose aspect
    // matches the on-screen canvas. The analyzer guide is the
    // 70%-of-width / 1.586:1 / 45%-vertical rect inside THAT
    // sub-rect — not inside the full analyzer frame — so the
    // IoU check matches what the user actually sees behind the
    // on-screen overlay.
    val guideAnalyzer = computeAnalyzerGuide(
        analyzerWidth  = w,
        analyzerHeight = h,
        viewWidth      = guideCanvas.canvasWidth,
        viewHeight     = guideCanvas.canvasHeight,
    )
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
                onAutoFire(result.quad, elapsed)
            }
        }
    }
}

/**
 * Compute the guide rect in analyzer-frame coordinates, taking
 * the on-screen canvas dimensions into account so the rect
 * lines up with what the user sees behind the FILL_CENTER
 * preview. We map the on-screen 70% / 1.586:1 / 45%-vertical
 * guide back to the analyzer pixel grid via
 * [CardImageOps.visibleRectForViewAspect] (the same
 * center-crop the PreviewView applies) and
 * [CardImageOps.guideRectInside].
 *
 * When the canvas aspect ≠ analyzer aspect (the typical
 * 19.5:9 view ÷ 4:3 sensor case), the analyzer-space rect is
 * NOT 70% of the analyzer's width — it's 70% of the visible
 * center crop's width. Without this correction the detector's
 * IoU gate fires against a region larger than the user's
 * overlay box, and the manual shutter crops a region wider
 * than the on-screen rect.
 */
internal fun computeAnalyzerGuide(
    analyzerWidth: Int,
    analyzerHeight: Int,
    viewWidth: Float,
    viewHeight: Float,
): GuideRect {
    val visible = CardImageOps.visibleRectForViewAspect(
        imageWidth  = analyzerWidth,
        imageHeight = analyzerHeight,
        viewWidth   = viewWidth,
        viewHeight  = viewHeight,
    )
    return CardImageOps.guideRectInside(visible)
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
    analyzerSize: Size,
    viewWidth: Float,
    viewHeight: Float,
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
                        // The analyzer + still capture now share
                        // a single aspect ratio (4:3, configured
                        // via shared AspectRatioStrategy), so sx
                        // ≈ sy and the detected quad scales
                        // uniformly into bitmap pixels without
                        // aspect distortion.
                        val sx = bitmap.width  / analyzerSize.width.toFloat()
                        val sy = bitmap.height / analyzerSize.height.toFloat()
                        val quadInBitmap = quadInAnalyzer?.scaled(sx, sy)
                        withContext(Dispatchers.Default) {
                            BusinessCardPostProcessor.process(
                                context      = context,
                                source       = bitmap,
                                quadInBitmap = quadInBitmap,
                                viewWidth    = viewWidth,
                                viewHeight   = viewHeight,
                                controller   = controller,
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
